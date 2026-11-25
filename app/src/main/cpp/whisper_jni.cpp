#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <ggml-backend.h>
#include <whisper.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct Engine {
    whisper_context *context;
    std::atomic<bool> cancelled{false};
};

namespace {

void ensureBackendsLoaded() {
    static std::once_flag once;
    std::call_once(once, []() {
        Dl_info dlInfo{};
        std::string libDir;
        if (dladdr(reinterpret_cast<const void *>(&ensureBackendsLoaded), &dlInfo) != 0 && dlInfo.dli_fname != nullptr) {
            const std::string path(dlInfo.dli_fname);
            const size_t slash = path.find_last_of('/');
            if (slash != std::string::npos) {
                libDir = path.substr(0, slash);
            }
        }
        if (!libDir.empty()) {
            ggml_backend_load_all_from_path(libDir.c_str());
        } else {
            ggml_backend_load_all();
        }
        const size_t devs = ggml_backend_dev_count();
        for (size_t i = 0; i < devs; ++i) {
            const auto dev = ggml_backend_dev_get(i);
            __android_log_print(
                ANDROID_LOG_INFO,
                "ReadAlongPerf",
                "ggml_backend dev=%s desc=%s dir=%s",
                ggml_backend_dev_name(dev),
                ggml_backend_dev_description(dev),
                libDir.c_str()
            );
        }
        if (devs == 0) {
            __android_log_print(
                ANDROID_LOG_WARN,
                "ReadAlongPerf",
                "ggml_backend loaded 0 devices from dir=%s",
                libDir.c_str()
            );
        }
    });
}

std::vector<jchar> utf8ToUtf16(const std::string &utf8) {
    std::vector<jchar> out;
    out.reserve(utf8.size());
    const size_t n = utf8.size();
    size_t i = 0;
    while (i < n) {
        const auto c0 = static_cast<uint8_t>(utf8[i]);
        uint32_t cp = 0;
        size_t len = 0;
        if (c0 > 0 && c0 < 0x80) {
            cp = c0;
            len = 1;
        } else if ((c0 & 0xE0) == 0xC0 && i + 1 < n) {
            const auto c1 = static_cast<uint8_t>(utf8[i + 1]);
            if ((c1 & 0xC0) == 0x80) {
                cp = ((c0 & 0x1Fu) << 6) | (c1 & 0x3Fu);
                if (cp >= 0x80) len = 2;
            }
        } else if ((c0 & 0xF0) == 0xE0 && i + 2 < n) {
            const auto c1 = static_cast<uint8_t>(utf8[i + 1]);
            const auto c2 = static_cast<uint8_t>(utf8[i + 2]);
            if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80) {
                cp = ((c0 & 0x0Fu) << 12) | ((c1 & 0x3Fu) << 6) | (c2 & 0x3Fu);
                if (cp >= 0x800 && !(cp >= 0xD800 && cp <= 0xDFFF)) len = 3;
            }
        } else if ((c0 & 0xF8) == 0xF0 && i + 3 < n) {
            const auto c1 = static_cast<uint8_t>(utf8[i + 1]);
            const auto c2 = static_cast<uint8_t>(utf8[i + 2]);
            const auto c3 = static_cast<uint8_t>(utf8[i + 3]);
            if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80 && (c3 & 0xC0) == 0x80) {
                cp = ((c0 & 0x07u) << 18) | ((c1 & 0x3Fu) << 12) | ((c2 & 0x3Fu) << 6) | (c3 & 0x3Fu);
                if (cp >= 0x10000 && cp <= 0x10FFFF) len = 4;
            }
        }
        if (len == 0) {
            // Skip stray/malformed UTF-8 bytes so ART JNI NewString never aborts on incomplete BPE sequences.
            ++i;
            continue;
        }
        if (cp <= 0xFFFFu) {
            out.push_back(static_cast<jchar>(cp));
        } else {
            const uint32_t adj = cp - 0x10000u;
            out.push_back(static_cast<jchar>(0xD800u + (adj >> 10)));
            out.push_back(static_cast<jchar>(0xDC00u + (adj & 0x3FFu)));
        }
        i += len;
    }
    return out;
}

struct WordSpan {
    std::string text;
    int64_t t0Cs;
    int64_t t1Cs;
};

jobjectArray runTranscribe(
    JNIEnv *env,
    jlong handle,
    jfloatArray input,
    jboolean adaptive_context,
    jstring initial_prompt,
    jint requested_threads
) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    const auto wordClass = env->FindClass("com/example/rivulet/data/lyrics/SpeechWord");
    const auto ctor = env->GetMethodID(wordClass, "<init>", "(Ljava/lang/String;JJ)V");
    const int size = env->GetArrayLength(input);
    std::vector<float> pcm(size);
    env->GetFloatArrayRegion(input, 0, size, pcm.data());

    std::string prompt;
    if (initial_prompt != nullptr) {
        const char *utf = env->GetStringUTFChars(initial_prompt, nullptr);
        if (utf != nullptr) {
            prompt = utf;
            env->ReleaseStringUTFChars(initial_prompt, utf);
        }
    }

    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const unsigned int cores = std::thread::hardware_concurrency();
    const unsigned int maxAllowed = cores ? std::min(4u, cores) : 4u;
    const unsigned int targetThreads = requested_threads > 0
        ? std::min(static_cast<unsigned int>(requested_threads), maxAllowed)
        : maxAllowed;
    params.n_threads = static_cast<int>(std::max(1u, targetThreads));
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.print_progress = params.print_realtime = params.print_special = params.print_timestamps = false;
    params.token_timestamps = true;
    params.max_len = 0;
    params.split_on_word = false;
    params.max_tokens = 128;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    if (!prompt.empty()) {
        params.initial_prompt = prompt.c_str();
        params.carry_initial_prompt = false;
    }
    // Keep ~1s of context padding and align to 256-frame flash-attn tiles so unused kv_cross tail slots never leak across calls.
    if (adaptive_context) {
        const int frames = (size + 319) / 320; // encoder frames are 20ms
        params.audio_ctx = std::min(whisper_n_audio_ctx(engine->context), std::max(512, ((frames + 48 + 255) / 256) * 256));
    }
    params.abort_callback = [](void *data) { return static_cast<Engine *>(data)->cancelled.load(); };
    params.abort_callback_user_data = engine;

    if (whisper_full(engine->context, params, pcm.data(), size) != 0 || engine->cancelled.load()) {
        return env->NewObjectArray(0, wordClass, nullptr);
    }

    const whisper_token eot = whisper_token_eot(engine->context);
    const int segCount = whisper_full_n_segments(engine->context);
    std::vector<WordSpan> words;
    words.reserve(segCount * 12);

    for (int i = 0; i < segCount; ++i) {
        const int64_t segT0 = std::max<int64_t>(0, whisper_full_get_segment_t0(engine->context, i));
        const int64_t segT1 = std::max<int64_t>(segT0, whisper_full_get_segment_t1(engine->context, i));
        const int tokCount = whisper_full_n_tokens(engine->context, i);

        std::string curWord;
        int64_t curT0 = -1;
        int64_t curT1 = -1;

        auto flushWord = [&]() {
            if (!curWord.empty()) {
                words.push_back(WordSpan{curWord, std::max<int64_t>(0, curT0), std::max(curT0, curT1)});
                curWord.clear();
                curT0 = -1;
                curT1 = -1;
            }
        };

        for (int t = 0; t < tokCount; ++t) {
            const auto tokData = whisper_full_get_token_data(engine->context, i, t);
            if (tokData.id >= eot) continue;
            const char *raw = whisper_full_get_token_text(engine->context, i, t);
            if (!raw || raw[0] == '\0') continue;

            const std::string piece(raw);
            const bool startsNewWord = piece[0] == ' ' || piece[0] == '\t' || piece[0] == '\r' || piece[0] == '\n';
            const size_t firstNonWs = piece.find_first_not_of(" \t\r\n");
            if (firstNonWs == std::string::npos) {
                flushWord();
                continue;
            }
            if (startsNewWord) {
                flushWord();
            }

            int64_t t0 = whisper_full_get_token_t0(engine->context, i, t);
            int64_t t1 = whisper_full_get_token_t1(engine->context, i, t);
            if (t0 < 0) t0 = segT0;
            if (t1 < t0) t1 = std::max(t0, segT1);
            if (t0 < segT0) t0 = segT0;
            if (segT1 > segT0 && t1 > segT1) t1 = segT1;
            if (t1 < t0) t1 = t0;

            if (curWord.empty()) {
                curT0 = t0;
                curT1 = t1;
            } else {
                curT0 = std::min(curT0, t0);
                curT1 = std::max(curT1, t1);
            }
            curWord.append(piece, firstNonWs, std::string::npos);
        }
        flushWord();
    }

    auto result = env->NewObjectArray(static_cast<jsize>(words.size()), wordClass, nullptr);
    jsize outIndex = 0;
    for (const auto &w : words) {
        const auto utf16 = utf8ToUtf16(w.text);
        if (utf16.empty()) continue;
        auto text = env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
        auto word = env->NewObject(
            wordClass,
            ctor,
            text,
            static_cast<jlong>(w.t0Cs * 10),
            static_cast<jlong>(w.t1Cs * 10)
        );
        env->SetObjectArrayElement(result, outIndex++, word);
        env->DeleteLocalRef(word);
        env->DeleteLocalRef(text);
    }
    if (outIndex == static_cast<jsize>(words.size())) {
        return result;
    }
    auto trimmed = env->NewObjectArray(outIndex, wordClass, nullptr);
    for (jsize k = 0; k < outIndex; ++k) {
        auto elem = env->GetObjectArrayElement(result, k);
        env->SetObjectArrayElement(trimmed, k, elem);
        env->DeleteLocalRef(elem);
    }
    env->DeleteLocalRef(result);
    return trimmed;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeCreate(JNIEnv *env, jobject, jstring path) {
    ensureBackendsLoaded();
    const char *name = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *context = whisper_init_from_file_with_params(name, params);
    env->ReleaseStringUTFChars(path, name);
    if (!context) return 0;
    return reinterpret_cast<jlong>(new Engine{context});
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeCancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeResetCancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->cancelled.store(false);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeTranscribe(
    JNIEnv *env,
    jobject,
    jlong handle,
    jfloatArray input,
    jboolean adaptive_context
) {
    return runTranscribe(env, handle, input, adaptive_context, nullptr, 0);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeTranscribeWithPrompt(
    JNIEnv *env,
    jobject,
    jlong handle,
    jfloatArray input,
    jboolean adaptive_context,
    jstring initial_prompt
) {
    return runTranscribe(env, handle, input, adaptive_context, initial_prompt, 0);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeTranscribeConfigured(
    JNIEnv *env,
    jobject,
    jlong handle,
    jfloatArray input,
    jboolean adaptive_context,
    jstring initial_prompt,
    jint n_threads
) {
    return runTranscribe(env, handle, input, adaptive_context, initial_prompt, n_threads);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine) { whisper_free(engine->context); delete engine; }
}
