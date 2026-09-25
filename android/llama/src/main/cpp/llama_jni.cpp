// JNI bridge for app.langboard.llama.LlamaNative over lb::Engine (engine.cpp): load a GGUF, run
// one completion or one beam search at a time, stream output as UTF-8 bytes, cancel from another
// thread. Prompts are passed in already formatted (special tokens are parsed), so this file has no
// model-specific knowledge.
//
// Nothing here logs prompt or output text.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "engine.h"
#include "ggml-backend.h"

#define TAG "LangboardLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, TAG, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, TAG, text);
    }
}

std::string to_string(JNIEnv * env, jstring js) {
    const char * c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// Java strings can carry emoji; GetStringUTFChars gives modified UTF-8, so go through bytes.
std::string to_utf8(JNIEnv * env, jbyteArray bytes) {
    const jsize n = env->GetArrayLength(bytes);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

std::vector<std::string> to_utf8_list(JNIEnv * env, jobjectArray arr) {
    std::vector<std::string> out;
    const jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; ++i) {
        auto b = (jbyteArray) env->GetObjectArrayElement(arr, i);
        out.push_back(to_utf8(env, b));
        env->DeleteLocalRef(b);
    }
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return bytes;
}

lb::Engine * engine(jlong handle) { return reinterpret_cast<lb::Engine *>(handle); }

// {status, promptTokens, generatedTokens, promptUs, firstTokenUs, totalUs, reusedTokens, logprob in micro-nats}
void timings(JNIEnv * env, jlongArray arr, lb::Status status, const lb::Timings & t) {
    jlong out[8] = {status, t.prompt_tokens, t.generated_tokens, t.prompt_us, t.first_token_us, t.total_us, t.reused_tokens,
                    (jlong) (t.logprob * 1e6)};
    env->SetLongArrayRegion(arr, 0, 8, out);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_app_langboard_llama_LlamaNative_init(JNIEnv * env, jobject, jstring native_lib_dir) {
    llama_log_set(log_callback, nullptr);
    ggml_backend_load_all_from_path(to_string(env, native_lib_dir).c_str());
    llama_backend_init();
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_langboard_llama_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_langboard_llama_LlamaNative_load(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint max_beams) {
    auto * e = lb::Engine::load(to_string(env, jpath).c_str(), n_ctx, n_threads, max_beams);
    if (!e) {
        LOGE("model load failed");
        return 0;
    }
    LOGI("loaded: n_ctx=%d threads=%d beams=%d", n_ctx, n_threads, max_beams);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_app_langboard_llama_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    engine(handle)->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_app_langboard_llama_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    delete engine(handle);
}

/**
 * One completion. [callback] is a TokenSink: `boolean onBytes(byte[])` receives each piece of
 * output on a UTF-8 boundary and returns false when it has enough. Timings go into [out].
 */
extern "C" JNIEXPORT jlong JNICALL
Java_app_langboard_llama_LlamaNative_generate(
        JNIEnv * env, jobject, jlong handle, jbyteArray jprompt, jint max_tokens,
        jfloat temp, jint top_k, jfloat top_p, jfloat repeat_penalty, jint seed, jboolean ban_latin, jobject callback,
        jlongArray out) {
    jclass cls = env->GetObjectClass(callback);
    jmethodID on_bytes = env->GetMethodID(cls, "onBytes", "([B)Z");
    lb::Sampling s;
    s.max_tokens = max_tokens;
    s.temperature = temp;
    s.top_k = top_k;
    s.top_p = top_p;
    s.repeat_penalty = repeat_penalty;
    s.seed = (uint32_t) seed;
    s.ban_latin = ban_latin;
    lb::Timings t;
    bool failed = false;
    lb::Status status = engine(handle)->generate(to_utf8(env, jprompt), s, t, [&](const std::string & piece) {
        jbyteArray bytes = to_bytes(env, piece);
        const bool more = env->CallBooleanMethod(callback, on_bytes, bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) { failed = true; return false; }
        return more;
    });
    if (failed) status = lb::FAILED;
    timings(env, out, status, t);
    return status;
}

/**
 * Beam search. Returns the distinct completions best first, as UTF-8 byte arrays; their
 * total log-probabilities go into [scores] (as long as the array allows).
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_langboard_llama_LlamaNative_beams(
        JNIEnv * env, jobject, jlong handle, jbyteArray jprompt, jint beams, jint max_tokens,
        jfloat length_alpha, jboolean stop_at_newline, jboolean ban_latin, jobjectArray stops, jbyteArray stop_text,
        jboolean keep_unfinished, jfloatArray scores, jlongArray out) {
    lb::BeamParams p;
    p.beams = beams;
    p.max_tokens = max_tokens;
    p.length_alpha = length_alpha;
    p.stop_at_newline = stop_at_newline;
    p.ban_latin = ban_latin;
    p.stops = to_utf8_list(env, stops);
    p.stop_text = to_utf8(env, stop_text);
    p.keep_unfinished = keep_unfinished;
    lb::Timings t;
    std::vector<lb::Hypothesis> hyps;
    const lb::Status status = engine(handle)->beams(to_utf8(env, jprompt), p, t, hyps);
    timings(env, out, status, t);

    jclass bytes_cls = env->FindClass("[B");
    jobjectArray arr = env->NewObjectArray((jsize) hyps.size(), bytes_cls, nullptr);
    const jsize n_scores = env->GetArrayLength(scores);
    std::vector<jfloat> sc(hyps.size());
    for (size_t i = 0; i < hyps.size(); ++i) {
        jbyteArray b = to_bytes(env, hyps[i].text);
        env->SetObjectArrayElement(arr, (jsize) i, b);
        env->DeleteLocalRef(b);
        sc[i] = hyps[i].logprob;
    }
    env->SetFloatArrayRegion(scores, 0, std::min((jsize) sc.size(), n_scores), sc.data());
    return arr;
}

/**
 * The log-probability of each continuation right after the prompt, into [logprobs] (as long as
 * the array allows). Timings go into [out]. Returns the status.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_app_langboard_llama_LlamaNative_score(
        JNIEnv * env, jobject, jlong handle, jbyteArray jprompt, jobjectArray continuations, jfloatArray logprobs,
        jlongArray out) {
    lb::Timings t;
    std::vector<float> lps;
    const lb::Status status = engine(handle)->score(to_utf8(env, jprompt), to_utf8_list(env, continuations), t, lps);
    timings(env, out, status, t);
    const jsize n = std::min((jsize) lps.size(), env->GetArrayLength(logprobs));
    env->SetFloatArrayRegion(logprobs, 0, n, lps.data());
    return status;
}
