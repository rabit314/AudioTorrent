// Standalone benchmark for devices that prohibit installation of a second test APK.
// Uses the exact pinned libraries linked into the app, and only public test audio.
#include <whisper.h>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <vector>
#include <sys/resource.h>

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    std::ifstream input(argv[2], std::ios::binary | std::ios::ate);
    if (!input) return 3;
    const auto size = static_cast<size_t>(input.tellg());
    if (size > 16000 * 30 * sizeof(float)) return 4;
    std::vector<float> pcm(size / sizeof(float));
    input.seekg(0); input.read(reinterpret_cast<char *>(pcm.data()), size);
    const auto began = std::chrono::steady_clock::now();
    auto cparams = whisper_context_default_params(); cparams.use_gpu = false;
    auto *context = whisper_init_from_file_with_params(argv[1], cparams);
    if (!context) return 5;
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 2; params.language = "en"; params.no_context = true;
    params.token_timestamps = true; params.max_len = 1; params.split_on_word = true;
    params.print_progress = params.print_realtime = params.print_special = params.print_timestamps = false;
    params.temperature = 0; params.temperature_inc = 0;
    const int status = whisper_full(context, params, pcm.data(), pcm.size());
    if (status != 0) { whisper_free(context); return 6; }
    for (int i = 0; i < whisper_full_n_segments(context); ++i)
        std::printf("WORD\t%lld\t%lld\t%s\n", static_cast<long long>(whisper_full_get_segment_t0(context, i)*10), static_cast<long long>(whisper_full_get_segment_t1(context, i)*10), whisper_full_get_segment_text(context, i));
    rusage usage{}; getrusage(RUSAGE_SELF, &usage);
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-began).count();
    std::printf("METRICS\taudioMs=%zu\telapsedMs=%lld\tpeakRssKb=%ld\n", pcm.size()*1000/16000, static_cast<long long>(ms), usage.ru_maxrss);
    whisper_free(context);
    return 0;
}
