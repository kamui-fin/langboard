#include "engine.h"

#include <algorithm>
#include <cmath>

#include "ggml.h"

namespace lb {

int64_t now_us() { return ggml_time_us(); }

float token_logprob(const float * logits, int n_vocab, llama_token tok) {
    float max = logits[0];
    for (int i = 1; i < n_vocab; ++i) max = std::max(max, logits[i]);
    double sum = 0.0;
    for (int i = 0; i < n_vocab; ++i) sum += std::exp((double) (logits[i] - max));
    return logits[tok] - (max + (float) std::log(sum));
}

size_t complete_utf8_prefix(const std::string & s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        const auto c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {  // lead byte or ASCII
            size_t need = c < 0x80 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
            return back + 1 >= need ? s.size() : i - 1;
        }
        --i;
        ++back;
    }
    return s.size();
}

static bool abort_callback(void * data) {
    return static_cast<Engine *>(data)->cancel.load(std::memory_order_relaxed);
}

Engine * Engine::load(const char * path, int n_ctx, int n_threads, int max_beams) {
    llama_model_params mp = llama_model_default_params();
    // File-backed pages: Android can reclaim them under pressure instead of killing the keyboard.
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model * model = llama_model_load_from_file(path, mp);
    if (!model) return nullptr;

    auto * e = new Engine();
    e->model = model;
    e->vocab = llama_model_get_vocab(model);
    e->max_beams = std::max(0, max_beams);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = n_ctx;  // the whole prompt in one decode call
    cp.n_ubatch = 512;
    // Sequence 0 holds the prompt; each beam step writes into one of two banks of max_beams.
    cp.n_seq_max = 1 + 2 * e->max_beams;
    // Beams share the prompt's cells instead of each holding a copy.
    cp.kv_unified = true;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    cp.abort_callback = abort_callback;
    cp.abort_callback_data = e;
    e->ctx = llama_init_from_model(model, cp);
    if (!e->ctx) {
        e->ctx = nullptr;
        delete e;
        return nullptr;
    }
    return e;
}

Engine::~Engine() {
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);
}

std::string Engine::piece(llama_token tok) const {
    char buf[256];
    int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special*/ false);
    return len > 0 ? std::string(buf, len) : std::string();
}

const std::vector<llama_token> & Engine::latin_tokens() {
    if (!is_latin.empty()) return latin;
    const int n_vocab = llama_vocab_n_tokens(vocab);
    is_latin.assign(n_vocab, 0);
    for (llama_token tok = 0; tok < n_vocab; ++tok) {
        const std::string pc = piece(tok);
        if (std::any_of(pc.begin(), pc.end(), [](char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); })) {
            is_latin[tok] = 1;
            latin.push_back(tok);
        }
    }
    return latin;
}

Status Engine::decode_prompt(const std::string & prompt, int extra, Timings & t, int & n_prompt) {
    cancel.store(false);
    const int64_t t0 = now_us();
    // The prompt carries its own BOS and chat markers.
    std::vector<llama_token> tokens(prompt.size() + 8);
    int n = llama_tokenize(vocab, prompt.data(), (int32_t) prompt.size(), tokens.data(),
                           (int32_t) tokens.size(), /*add_special*/ false, /*parse_special*/ true);
    if (n <= 0) return FAILED;
    tokens.resize(n);
    t.prompt_tokens = n;
    n_prompt = n;
    if (n + extra > (int) llama_n_ctx(ctx)) return TOO_LONG;

    // Keep the shared start of the cache; at least one token is decoded so there are logits.
    llama_memory_t mem = llama_get_memory(ctx);
    size_t common = 0;
    while (common < cached.size() && common < tokens.size() && cached[common] == tokens[common]) ++common;
    if (common == tokens.size()) --common;
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    cached.clear();
    t.reused_tokens = (int64_t) common;

    llama_batch batch = llama_batch_get_one(tokens.data() + common, (int32_t) (tokens.size() - common));
    int rc = llama_decode(ctx, batch);
    if (rc != 0 || cancel.load()) {
        llama_memory_clear(mem, true);  // partly written; don't trust it next time
        return rc == 2 || cancel.load() ? CANCELLED : FAILED;
    }
    cached = tokens;
    t.prompt_us = now_us() - t0;
    return OK;
}

namespace {

struct Beam {
    std::vector<llama_token> toks;
    std::string text;
    float logprob = 0.0f;
    llama_seq_id seq = 0;
};

struct Expansion {
    int parent;  // index into the alive beams, or -1 for the prompt
    llama_token tok;
    float logprob;  // parent + this token
};

float length_norm(int len, float alpha) {
    return alpha <= 0.0f ? 1.0f : std::pow((5.0f + (float) len) / 6.0f, alpha);
}

// The k most likely next tokens after these logits, as log-probabilities under the full
// distribution; tokens marked in penalized (when not null) lose `penalty` (infinity bans them).
void top_k_logprobs(const float * logits, int n_vocab, int k, const std::vector<char> * penalized, float penalty,
                    std::vector<std::pair<float, llama_token>> & out) {
    float max = logits[0];
    for (int i = 1; i < n_vocab; ++i) max = std::max(max, logits[i]);
    double sum = 0.0;
    for (int i = 0; i < n_vocab; ++i) sum += std::exp((double) (logits[i] - max));
    const float log_z = max + (float) std::log(sum);

    out.clear();
    out.reserve(n_vocab);
    for (int i = 0; i < n_vocab; ++i) out.emplace_back(penalized && (*penalized)[i] ? logits[i] - penalty : logits[i], i);
    k = std::min(k, n_vocab);
    std::partial_sort(out.begin(), out.begin() + k, out.end(), [](auto & a, auto & b) { return a.first > b.first; });
    out.resize(k);
    for (auto & p : out) p.first -= log_z;
}

}  // namespace

Status Engine::beams(const std::string & prompt, const BeamParams & p, Timings & t, std::vector<Hypothesis> & out) {
    out.clear();
    const int64_t t0 = now_us();
    const int B = std::max(1, std::min(p.beams, max_beams));
    int n_prompt = 0;
    // Each beam adds up to max_tokens cells; pruned beams give theirs back.
    Status st = decode_prompt(prompt, B * p.max_tokens, t, n_prompt);
    if (st != OK) return st;

    llama_memory_t mem = llama_get_memory(ctx);
    const int n_vocab = llama_vocab_n_tokens(vocab);
    const std::vector<char> * penalized = nullptr;
    const float penalty = p.ban_latin ? INFINITY : p.latin_penalty;
    if (penalty > 0.0f) {
        latin_tokens();
        penalized = &is_latin;
    }
    std::vector<Beam> alive;
    std::vector<Hypothesis> finished;
    std::vector<std::pair<float, llama_token>> top;
    std::vector<Expansion> expansions;
    llama_batch batch = llama_batch_init(B, 0, 1);
    // Beams alternate between two banks of sequence ids, so a child can copy its parent's cells.
    int bank = 0;
    auto bank_seq = [&](int b, int j) { return (llama_seq_id) (1 + b * B + j); };
    auto clear_bank = [&](int b) {
        for (int j = 0; j < B; ++j) llama_memory_seq_rm(mem, bank_seq(b, j), -1, -1);
    };
    auto finish = [&](const std::vector<llama_token> & toks, float lp) {
        if (toks.empty()) return;
        Hypothesis h;
        for (llama_token tk : toks) h.text += piece(tk);
        h.logprob = lp;
        h.tokens = (int) toks.size();
        h.score = lp / length_norm(h.tokens, p.length_alpha);
        finished.push_back(std::move(h));
    };
    auto kth_best_finished = [&]() {
        std::vector<float> s;
        for (auto & h : finished) s.push_back(h.score);
        std::nth_element(s.begin(), s.begin() + (B - 1), s.end(), std::greater<float>());
        return s[B - 1];
    };

    Status status = OK;
    for (int step = 0; step <= p.max_tokens; ++step) {
        if (cancel.load()) { status = CANCELLED; break; }

        // Every way to extend every beam by one token, from the logits of its last decode.
        expansions.clear();
        const int parents = step == 0 ? 1 : (int) alive.size();
        for (int i = 0; i < parents; ++i) {
            const float * logits = llama_get_logits_ith(ctx, step == 0 ? -1 : i);
            const float base = step == 0 ? 0.0f : alive[i].logprob;
            top_k_logprobs(logits, n_vocab, B, penalized, penalty, top);
            for (auto & [lp, tok] : top) expansions.push_back({step == 0 ? -1 : i, tok, base + lp});
        }
        std::sort(expansions.begin(), expansions.end(), [](auto & a, auto & b) { return a.logprob > b.logprob; });

        std::vector<Beam> next;
        for (auto & x : expansions) {
            if ((int) next.size() >= B) break;
            static const std::vector<llama_token> none;
            const auto & ptoks = x.parent < 0 ? none : alive[x.parent].toks;
            if (llama_vocab_is_eog(vocab, x.tok)) { finish(ptoks, x.logprob); continue; }
            const std::string pc = piece(x.tok);
            if (p.stop_at_newline && pc.find('\n') != std::string::npos) {
                // Text before the newline still belongs to the answer.
                auto toks = ptoks;
                if (pc.find_first_not_of(" \n") < pc.find('\n')) toks.push_back(x.tok);
                finish(toks, x.logprob);
                continue;
            }
            if (std::any_of(p.stops.begin(), p.stops.end(), [&](auto & st) { return !st.empty() && pc.find(st) != std::string::npos; })) {
                // A one-line answer that ends its sentence (or its phrase) is complete.
                auto toks = ptoks;
                toks.push_back(x.tok);
                finish(toks, x.logprob);
                continue;
            }
            static const std::string empty;
            std::string text = (x.parent < 0 ? empty : alive[x.parent].text) + pc;
            if (!p.stop_text.empty() && text.find(p.stop_text, 1) != std::string::npos) {
                auto toks = ptoks;
                toks.push_back(x.tok);
                finish(toks, x.logprob);
                continue;
            }
            if (step == p.max_tokens) {
                // Out of room: a cut-off answer isn't an answer, unless the caller trims it anyway.
                if (p.keep_unfinished) {
                    auto toks = ptoks;
                    toks.push_back(x.tok);
                    finish(toks, x.logprob);
                }
                continue;
            }
            Beam b;
            b.toks = ptoks;
            b.toks.push_back(x.tok);
            b.text = std::move(text);
            b.logprob = x.logprob;
            b.seq = bank_seq(bank, (int) next.size());
            llama_memory_seq_rm(mem, b.seq, -1, -1);
            llama_memory_seq_cp(mem, x.parent < 0 ? 0 : alive[x.parent].seq, b.seq, -1, -1);
            next.push_back(std::move(b));
        }
        clear_bank(1 - bank);
        alive = std::move(next);
        bank = 1 - bank;
        if (alive.empty()) break;

        // Done when no beam can still reach the top B, even at its current length.
        if ((int) finished.size() >= B) {
            const float best_alive = alive.front().logprob / length_norm((int) alive.front().toks.size(), p.length_alpha);
            if (best_alive < kth_best_finished()) break;
        }

        batch.n_tokens = 0;
        for (auto & b : alive) {
            const int j = batch.n_tokens++;
            batch.token[j] = b.toks.back();
            batch.pos[j] = n_prompt + (llama_pos) b.toks.size() - 1;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = b.seq;
            batch.logits[j] = true;
        }
        const int rc = llama_decode(ctx, batch);
        if (rc == 2 || cancel.load()) { status = CANCELLED; break; }
        if (rc != 0) { status = FAILED; break; }
        t.generated_tokens += batch.n_tokens;
        if (step == 0) t.first_token_us = now_us() - t0;
    }
    llama_batch_free(batch);
    clear_bank(0);
    clear_bank(1);

    std::sort(finished.begin(), finished.end(), [](auto & a, auto & b) { return a.score > b.score; });
    // Different token paths can spell the same text; keep the best of each.
    for (auto & h : finished) {
        if (std::none_of(out.begin(), out.end(), [&](auto & o) { return o.text == h.text; })) out.push_back(std::move(h));
    }
    if ((int) out.size() > 3 * B) out.resize(3 * B);
    t.total_us = now_us() - t0;
    return status;
}

Status Engine::score(const std::string & prompt, const std::vector<std::string> & continuations, Timings & t,
                     std::vector<float> & out) {
    out.assign(continuations.size(), -INFINITY);
    const int64_t t0 = now_us();
    std::vector<std::vector<llama_token>> full;
    size_t tail = 0;
    for (auto & c : continuations) {
        const std::string text = prompt + c;
        std::vector<llama_token> toks(text.size() + 8);
        int n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(), false, true);
        if (n <= 0) return FAILED;
        toks.resize(n);
        full.push_back(std::move(toks));
    }
    int n_prompt = 0;
    Status st = decode_prompt(prompt, 0, t, n_prompt);
    if (st != OK) return st;
    if (continuations.empty()) return OK;

    // Score every continuation from the same token: one before the first that any of them changes.
    size_t shared = (size_t) n_prompt;
    for (auto & f : full) {
        size_t c = 0;
        while (c < f.size() && c < cached.size() && f[c] == cached[c]) ++c;
        shared = std::min(shared, c);
    }
    const size_t from = shared > 0 ? shared - 1 : 0;
    for (auto & f : full) tail += f.size() - from;
    if (n_prompt + (int) tail > (int) llama_n_ctx(ctx)) return TOO_LONG;

    llama_memory_t mem = llama_get_memory(ctx);
    const int n_vocab = llama_vocab_n_tokens(vocab);
    const int max_seqs = std::max(1, 2 * max_beams);
    Status status = OK;
    for (size_t start = 0; start < full.size() && status == OK; start += max_seqs) {
        const size_t end = std::min(full.size(), start + max_seqs);
        size_t n_tokens = 0;
        for (size_t k = start; k < end; ++k) n_tokens += full[k].size() - from;
        llama_batch batch = llama_batch_init((int32_t) n_tokens, 0, 1);
        std::vector<std::pair<size_t, int>> first_row;  // continuation, its first row in the batch
        for (size_t k = start; k < end; ++k) {
            const llama_seq_id seq = (llama_seq_id) (1 + k - start);
            llama_memory_seq_rm(mem, seq, -1, -1);
            llama_memory_seq_cp(mem, 0, seq, 0, (llama_pos) from);
            first_row.emplace_back(k, batch.n_tokens);
            for (size_t j = from; j < full[k].size(); ++j) {
                const int r = batch.n_tokens++;
                batch.token[r] = full[k][j];
                batch.pos[r] = (llama_pos) j;
                batch.n_seq_id[r] = 1;
                batch.seq_id[r][0] = seq;
                batch.logits[r] = j + 1 < full[k].size();
            }
        }
        const int rc = llama_decode(ctx, batch);
        if (rc == 2 || cancel.load()) status = CANCELLED;
        else if (rc != 0) status = FAILED;
        else {
            for (auto & [k, row] : first_row) {
                double lp = 0.0;
                for (size_t j = from + 1; j < full[k].size(); ++j) {
                    const float * logits = llama_get_logits_ith(ctx, row + (int) (j - 1 - from));
                    lp += token_logprob(logits, n_vocab, full[k][j]);
                }
                out[k] = (float) lp;
            }
            t.generated_tokens += (int64_t) n_tokens;
        }
        llama_batch_free(batch);
        for (size_t k = start; k < end; ++k) llama_memory_seq_rm(mem, (llama_seq_id) (1 + k - start), -1, -1);
    }
    t.total_us = now_us() - t0;
    return status;
}

}  // namespace lb
