#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <string>
#include <fstream>
#include <unistd.h>
#include <vector>
#include "llama.h"
#include "ggml-backend.h"
#include "ggml-hexagon.h"
#include "whisper.h"

static constexpr const char * TAG = "JasSI-GGUF";

static std::string native_library_directory() {
    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        const size_t path_start = line.find('/');
        if (path_start == std::string::npos) {
            continue;
        }
        const std::string path = line.substr(path_start);
        const size_t name_start = path.rfind('/');
        if (name_start != std::string::npos &&
            path.find("libjarvis_native.so", name_start + 1) != std::string::npos) {
            return path.substr(0, name_start);
        }
    }
    return {};
}

static void initialize_backends(const char * supplied_library_directory = nullptr) {
    const std::string library_directory =
        supplied_library_directory != nullptr && supplied_library_directory[0] != '\0'
            ? supplied_library_directory
            : native_library_directory();
    if (!library_directory.empty()) {
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "Loading runtime backends from %s",
            library_directory.c_str()
        );
        ggml_backend_load_all_from_path(library_directory.c_str());
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Could not locate native library directory");
    }
    llama_backend_init();
#if defined(GGML_USE_HEXAGON)
    const ggml_backend_reg_t hexagon = ggml_backend_hexagon_reg();
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "Explicit Hexagon registration: %s",
        hexagon == nullptr ? "unavailable" : "available"
    );
    if (hexagon != nullptr) {
        ggml_backend_register(hexagon);
    }
#endif
}

static void set_batch_tokens(
    llama_batch_ext * batch,
    const llama_token * tokens,
    int32_t count,
    llama_pos start_position
) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < count; ++i) {
        const int32_t index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos position = start_position + i;
        llama_batch_ext_set_pos(batch, index, &position);
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gmlabs_jarvis_NativeBridge_backendName(JNIEnv* env, jobject, jstring library_dir_string) {
    const char * library_dir = library_dir_string == nullptr
        ? nullptr
        : env->GetStringUTFChars(library_dir_string, nullptr);
    initialize_backends(library_dir);
    if (library_dir != nullptr) {
        env->ReleaseStringUTFChars(library_dir_string, library_dir);
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Backend device count: %zu", ggml_backend_dev_count());
    std::string names;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        const ggml_backend_dev_t device = ggml_backend_dev_get(i);
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "Backend device %zu: %s (%s)",
            i,
            ggml_backend_dev_name(device),
            ggml_backend_dev_description(device)
        );
        if (!names.empty()) names += ", ";
        names += ggml_backend_dev_name(device);
    }
    return env->NewStringUTF(names.empty() ? "CPU fallback" : names.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gmlabs_jarvis_NativeBridge_nativeGenerate(
    JNIEnv* env, jobject, jstring model_path_string, jstring prompt_string) {
    const char* model_path = env->GetStringUTFChars(model_path_string, nullptr);
    const char* prompt = env->GetStringUTFChars(prompt_string, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Starting GGUF inference: %s", model_path);
    initialize_backends();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Available backends: %zu", ggml_backend_dev_count());
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "Backend device %zu: %s",
            i,
            ggml_backend_dev_name(ggml_backend_dev_get(i))
        );
    }

    auto model_params = llama_model_default_params();
    // A negative value asks llama.cpp to offload all possible layers to the
    // first available accelerator backend (Hexagon when it was built in,
    // otherwise the normal CPU path).
    model_params.n_gpu_layers = -1;
    llama_model* model = llama_model_load_from_file(model_path, model_params);
    if (!model) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Model load failed");
        env->ReleaseStringUTFChars(model_path_string, model_path);
        env->ReleaseStringUTFChars(prompt_string, prompt);
        return env->NewStringUTF("Could not load the GGUF model.");
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Model loaded");

    const llama_vocab* vocab = llama_model_get_vocab(model);
    std::string formatted_prompt(prompt);
    const char * chat_template = llama_model_chat_template(model, nullptr);
    if (chat_template != nullptr) {
        llama_chat_message message{"user", prompt};
        const int32_t required = llama_chat_apply_template(
            chat_template, &message, 1, true, nullptr, 0
        );
        if (required > 0) {
            std::vector<char> buffer(static_cast<size_t>(required) + 1);
            const int32_t written = llama_chat_apply_template(
                chat_template, &message, 1, true, buffer.data(), static_cast<int32_t>(buffer.size())
            );
            if (written > 0) {
                formatted_prompt.assign(buffer.data(), written);
            }
        }
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Prompt bytes=%zu", formatted_prompt.size());
    const int prompt_size = -llama_tokenize(
        vocab, formatted_prompt.c_str(), static_cast<int32_t>(formatted_prompt.size()),
        nullptr, 0, false, true
    );
    std::vector<llama_token> prompt_tokens(prompt_size);
    if (llama_tokenize(
            vocab, formatted_prompt.c_str(), static_cast<int32_t>(formatted_prompt.size()),
            prompt_tokens.data(), prompt_size, false, true
        ) < 0) {
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path_string, model_path);
        env->ReleaseStringUTFChars(prompt_string, prompt);
        return env->NewStringUTF("Could not tokenize the prompt.");
    }

    auto context_params = llama_context_default_params();
    const int32_t trained_context = llama_model_n_ctx_train(model);
    context_params.n_ctx = std::min(
        trained_context > 0 ? trained_context : 2048,
        std::max(prompt_size + 128, 256)
    );
    context_params.n_batch = std::min(std::max(prompt_size, 1), 256);
    context_params.n_ubatch = context_params.n_batch;
    const int cpu_threads = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    const int inference_threads = std::max(2, std::min(cpu_threads - 1, 6));
    context_params.n_threads = inference_threads;
    context_params.n_threads_batch = inference_threads;
    llama_context* context = llama_init_from_model(model, context_params);
    if (!context) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Context creation failed");
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path_string, model_path);
        env->ReleaseStringUTFChars(prompt_string, prompt);
        return env->NewStringUTF("Could not create the GGUF inference context.");
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Context created; prompt tokens=%d", prompt_size);

    auto sampler_params = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    llama_batch_ext* batch = llama_batch_ext_init(context);
    set_batch_tokens(batch, prompt_tokens.data(), prompt_size, 0);

    std::string result;
    int position = 0;
    const auto started = std::chrono::steady_clock::now();
    for (int generated = 0; generated < 16; ++generated) {
        if (std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::steady_clock::now() - started).count() >= 30) {
            result = "GGUF inference timed out after 30 seconds. Use a smaller quantized model.";
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Generation timed out");
            break;
        }
        if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            result = "GGUF inference failed while decoding.";
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Decode failed at token %d", generated);
            break;
        }
        const llama_token token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[256];
        const int piece_size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (piece_size > 0) result.append(piece, piece_size);
        ++position;
        set_batch_tokens(batch, &token, 1, prompt_size + position - 1);
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Generation finished: %zu bytes", result.size());

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(context);
    llama_model_free(model);
    env->ReleaseStringUTFChars(model_path_string, model_path);
    env->ReleaseStringUTFChars(prompt_string, prompt);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gmlabs_jarvis_NativeBridge_nativeTranscribe(
    JNIEnv* env, jobject, jstring model_path_string, jfloatArray samples_array) {
    const char* model_path = env->GetStringUTFChars(model_path_string, nullptr);
    const jsize sample_count = env->GetArrayLength(samples_array);
    jfloat* samples = env->GetFloatArrayElements(samples_array, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Starting Whisper transcription: %s (%d samples)",
                        model_path, sample_count);

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = true;
    whisper_context* context = whisper_init_from_file_with_params(model_path, context_params);
    if (!context) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Whisper model initialization failed");
        env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);
        env->ReleaseStringUTFChars(model_path_string, model_path);
        return env->NewStringUTF("Could not load the Whisper model.");
    }

    const int cpu_threads = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(2, std::min(cpu_threads - 1, 6));
    params.language = "auto";
    params.detect_language = true;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = true;
    params.suppress_blank = true;

    std::string result;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Whisper decode started");
    if (whisper_full(context, params, samples, sample_count) == 0) {
        const int segments = whisper_full_n_segments(context);
        for (int i = 0; i < segments; ++i) {
            result += whisper_full_get_segment_text(context, i);
        }
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Whisper decode failed");
        result = "Whisper transcription failed.";
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Whisper decode finished: %zu bytes", result.size());
    whisper_free(context);
    env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(model_path_string, model_path);
    return env->NewStringUTF(result.c_str());
}
