#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <algorithm>
#include <string>
#include <thread>
#include <vector>

struct Engine {
    whisper_context *context;
    std::atomic<bool> cancelled{false};
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeCreate(JNIEnv *env, jobject, jstring path) {
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
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray input, jboolean adaptive_context) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    const auto wordClass = env->FindClass("com/example/rivulet/data/lyrics/SpeechWord");
    const auto ctor = env->GetMethodID(wordClass, "<init>", "(Ljava/lang/String;JJ)V");
    const int size = env->GetArrayLength(input);
    std::vector<float> pcm(size);
    env->GetFloatArrayRegion(input, 0, size, pcm.data());
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // Bound CPU use while letting recognition stay ahead of normal playback on multicore devices.
    const auto cores = std::thread::hardware_concurrency();
    params.n_threads = cores ? std::min(4u, cores) : 2;
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.print_progress = params.print_realtime = params.print_special = params.print_timestamps = false;
    params.token_timestamps = true;
    params.max_len = 1;
    params.split_on_word = true;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    // Keep two seconds of silence/context padding. Full-size input retains the original context.
    // Short batches avoid repeatedly running a 30-second encoder for 12 seconds of speech.
    if (adaptive_context) {
        const int frames = (size + 319) / 320; // encoder frames are 20ms
        params.audio_ctx = std::min(whisper_n_audio_ctx(engine->context), std::max(512, ((frames + 100 + 127) / 128) * 128));
    }
    params.abort_callback = [](void *data) { return static_cast<Engine *>(data)->cancelled.load(); };
    params.abort_callback_user_data = engine;
    if (whisper_full(engine->context, params, pcm.data(), size) != 0 || engine->cancelled.load()) return env->NewObjectArray(0, wordClass, nullptr);
    const int count = whisper_full_n_segments(engine->context);
    auto result = env->NewObjectArray(count, wordClass, nullptr);
    for (int i = 0; i < count; ++i) {
        auto text = env->NewStringUTF(whisper_full_get_segment_text(engine->context, i));
        auto word = env->NewObject(wordClass, ctor, text,
            static_cast<jlong>(whisper_full_get_segment_t0(engine->context, i) * 10),
            static_cast<jlong>(whisper_full_get_segment_t1(engine->context, i) * 10));
        env->SetObjectArrayElement(result, i, word);
        env->DeleteLocalRef(word);
        env->DeleteLocalRef(text);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rivulet_data_lyrics_WhisperEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine) { whisper_free(engine->context); delete engine; }
}
