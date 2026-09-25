// One loaded model with its context, shared by the JNI bridge (llama_jni.cpp) and the host eval
// tool (tools/hymt_eval). Knows nothing about prompts, Chinese or Android.
//
// Nothing here logs prompt or output text.

#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "llama.h"

namespace lb {

enum Status : int64_t { OK = 0, CANCELLED = 1, TOO_LONG = 2, FAILED = 3 };

struct Timings {
    int64_t prompt_tokens = 0;
    int64_t generated_tokens = 0;
    int64_t prompt_us = 0;
    int64_t first_token_us = 0;
    int64_t total_us = 0;
    int64_t reused_tokens = 0;
    // Log-probability of what was generated, under the model's raw distribution.
    double logprob = 0.0;
};

struct Sampling {
    int max_tokens = 64;
    float temperature = 0.0f;  // <= 0 is greedy
    int top_k = 20;
    float top_p = 0.6f;
    float repeat_penalty = 1.05f;
    uint32_t seed = 1;
    // Never pick a token with an ASCII letter in it (a Chinese answer shouldn't copy the English).
    bool ban_latin = false;
};

struct BeamParams {
    int beams = 10;
    int max_tokens = 24;
    // GNMT length penalty ((5 + len) / 6)^alpha; 0 ranks by total log-probability.
    float length_alpha = 0.6f;
    // A newline in the output ends a hypothesis (answers are one line).
    bool stop_at_newline = true;
    // A token whose text contains one of these ends a hypothesis, with the token kept.
    std::vector<std::string> stops = {"。", "！", "？"};
    // As Sampling::ban_latin.
    bool ban_latin = false;
    // A hypothesis whose text contains this after its first byte ends there, with its last token kept
    // (the text after the gap, reached).
    std::string stop_text;
    // Hypotheses still going at max_tokens are returned rather than dropped.
    bool keep_unfinished = false;
};

struct Hypothesis {
    std::string text;
    float logprob = 0.0f;  // sum over tokens
    float score = 0.0f;    // length-normalised, what results are sorted by
    int tokens = 0;
};

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> cancel{false};
    // Prompt tokens in sequence 0 of the cache, for reuse by the next prompt.
    std::vector<llama_token> cached;
    int max_beams = 0;

    // Null on failure. max_beams sets how many sequences the context holds (2 per beam, plus the prompt).
    static Engine * load(const char * path, int n_ctx, int n_threads, int max_beams);
    ~Engine();

    // One stateless completion; on_piece gets each piece of output on a UTF-8 boundary and returns
    // false once it has enough.
    template <typename F>
    Status generate(const std::string & prompt, const Sampling & s, Timings & t, F && on_piece);

    // Beam search: the most likely distinct completions, best first.
    Status beams(const std::string & prompt, const BeamParams & p, Timings & t, std::vector<Hypothesis> & out);

    // Log-probability of each continuation right after the prompt, all scored in one batch over the
    // cached prompt. The prompt and each continuation are tokenized together, so a token that spans
    // the join is scored too; every continuation is scored from the same position, so the sums compare.
    Status score(const std::string & prompt, const std::vector<std::string> & continuations, Timings & t,
                 std::vector<float> & out);

    // Tokens whose text has an ASCII letter, built on first use.
    const std::vector<llama_token> & latin_tokens();
    std::vector<llama_token> latin;
    std::vector<char> is_latin;  // by token id; empty until latin_tokens() runs

    // Decodes the prompt into sequence 0, reusing the cached start. Logits for its last token are
    // ready afterwards. extra = tokens that will be generated after it (for the context check).
    Status decode_prompt(const std::string & prompt, int extra, Timings & t, int & n_prompt);

    std::string piece(llama_token tok) const;
};

// Length of the longest prefix of s that ends on a UTF-8 character boundary.
size_t complete_utf8_prefix(const std::string & s);

int64_t now_us();

// log softmax(logits)[tok]
float token_logprob(const float * logits, int n_vocab, llama_token tok);

template <typename F>
Status Engine::generate(const std::string & prompt, const Sampling & s, Timings & t, F && on_piece) {
    const int64_t t0 = now_us();
    int n_prompt = 0;
    Status st = decode_prompt(prompt, s.max_tokens, t, n_prompt);
    if (st != OK) return st;

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (s.ban_latin) {
        std::vector<llama_logit_bias> bias;
        for (llama_token tok : latin_tokens()) bias.push_back({tok, -INFINITY});
        llama_sampler_chain_add(smpl, llama_sampler_init_logit_bias(llama_vocab_n_tokens(vocab), (int32_t) bias.size(), bias.data()));
    }
    // top-k first: the repetition penalty is slow over the whole 120k vocabulary.
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(s.top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, s.repeat_penalty, 0.0f, 0.0f));
    if (s.temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(s.top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(s.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(s.seed));
    }

    Status status = OK;
    std::string pending;
    for (int i = 0; i < s.max_tokens; ++i) {
        if (cancel.load()) { status = CANCELLED; break; }
        const float * logits = llama_get_logits_ith(ctx, -1);
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        t.logprob += token_logprob(logits, llama_vocab_n_tokens(vocab), tok);
        if (llama_vocab_is_eog(vocab, tok)) break;
        t.generated_tokens++;
        if (t.generated_tokens == 1) t.first_token_us = now_us() - t0;

        pending += piece(tok);
        const size_t ready = complete_utf8_prefix(pending);
        if (ready > 0) {
            const bool more = on_piece(pending.substr(0, ready));
            pending.erase(0, ready);
            if (!more) break;
            if (cancel.load()) { status = CANCELLED; break; }
        }

        llama_batch batch = llama_batch_get_one(&tok, 1);
        int rc = llama_decode(ctx, batch);
        if (rc == 2) { status = CANCELLED; break; }
        if (rc != 0) { status = FAILED; break; }
    }
    llama_sampler_free(smpl);
    // Generated tokens are in the cache after the prompt; the next prompt only reuses the prompt.
    llama_memory_seq_rm(llama_get_memory(ctx), 0, n_prompt, -1);
    t.total_us = now_us() - t0;
    return status;
}

}  // namespace lb
