// Reads one JSON request per line on stdin and writes one JSON result per line on stdout, using
// the same engine as the phone. Requests:
//   {"prompt": "...", "mode": "beams", "beams": 10, "max_tokens": 24, "length_alpha": 0.6}
//   {"prompt": "...", "mode": "greedy", "max_tokens": 64, "repeat_penalty": 1.05}
//   {"prompt": "...", "mode": "score", "continuations": ["...", "..."]}
// Beams and greedy also take "ban_latin": true, or a softer "latin_penalty": 4.0; beams take "stops": ["。", "，"]; greedy takes
// "stops" too (output ends after the first piece containing one). Both take "stop_text": output ends
// once it contains this after its first byte; beams take "keep_unfinished".
// Usage: hymt_eval model.gguf [threads]

#include <cstdio>
#include <iostream>
#include <string>

#include "engine.h"
#include "nlohmann/json.hpp"

using json = nlohmann::json;

int main(int argc, char ** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s model.gguf [threads]\n", argv[0]);
        return 2;
    }
    llama_log_set([](ggml_log_level level, const char * text, void *) {
        if (level == GGML_LOG_LEVEL_ERROR) std::fputs(text, stderr);
    }, nullptr);
    llama_backend_init();
    const int threads = argc > 2 ? std::atoi(argv[2]) : 8;
    lb::Engine * e = lb::Engine::load(argv[1], 2048, threads, 16);
    if (!e) {
        std::fprintf(stderr, "could not load %s\n", argv[1]);
        return 1;
    }
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        json req = json::parse(line);
        json res;
        lb::Timings t;
        lb::Status st;
        if (req.value("mode", "beams") == "beams") {
            lb::BeamParams p;
            p.beams = req.value("beams", 10);
            p.max_tokens = req.value("max_tokens", 24);
            p.length_alpha = req.value("length_alpha", 0.6f);
            p.ban_latin = req.value("ban_latin", false);
            p.latin_penalty = req.value("latin_penalty", 0.0f);
            if (req.contains("stops")) p.stops = req["stops"].get<std::vector<std::string>>();
            p.stop_text = req.value("stop_text", "");
            p.keep_unfinished = req.value("keep_unfinished", false);
            std::vector<lb::Hypothesis> hyps;
            st = e->beams(req["prompt"].get<std::string>(), p, t, hyps);
            res["candidates"] = json::array();
            for (auto & h : hyps) res["candidates"].push_back({{"text", h.text}, {"score", h.score}, {"logprob", h.logprob}});
        } else if (req["mode"] == "score") {
            std::vector<float> lps;
            st = e->score(req["prompt"].get<std::string>(), req["continuations"].get<std::vector<std::string>>(), t, lps);
            res["logprobs"] = lps;
        } else {
            lb::Sampling s;
            s.ban_latin = req.value("ban_latin", false);
            s.latin_penalty = req.value("latin_penalty", 0.0f);
            const std::vector<std::string> stops = req.value("stops", std::vector<std::string>{});
            const std::string stop_text = req.value("stop_text", "");
            s.max_tokens = req.value("max_tokens", 64);
            s.temperature = req.value("temperature", 0.0f);
            s.top_k = req.value("top_k", 20);
            s.top_p = req.value("top_p", 0.6f);
            s.repeat_penalty = req.value("repeat_penalty", 1.05f);
            std::string text;
            st = e->generate(req["prompt"].get<std::string>(), s, t, [&](const std::string & p) {
                text += p;
                for (auto & stop : stops) if (text.find(stop) != std::string::npos) return false;
                if (!stop_text.empty() && text.find(stop_text, 1) != std::string::npos) return false;
                return text.find('\n') == std::string::npos;
            });
            res["text"] = text;
            res["logprob"] = t.logprob;
        }
        res["status"] = (int) st;
        res["prompt_tokens"] = t.prompt_tokens;
        res["ms"] = t.total_us / 1000;
        std::cout << res.dump(-1, ' ', false, json::error_handler_t::replace) << std::endl;
    }
    delete e;
    return 0;
}
