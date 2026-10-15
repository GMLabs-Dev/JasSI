#include <jni.h>
#include <cstring>
#include <string>
#include <vector>
#include "llama.h"

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
Java_dev_gmlabs_jarvis_NativeBridge_backendName(JNIEnv* env, jobject) {
    return env->NewStringUTF("Hexagon NPU bridge placeholder");
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gmlabs_jarvis_NativeBridge_nativeGenerate(
    JNIEnv* env, jobject, jstring model_path_string, jstring prompt_string) {
    const char* model_path = env->GetStringUTFChars(model_path_string, nullptr);
    const char* prompt = env->GetStringUTFChars(prompt_string, nullptr);
    llama_backend_init();

    auto model_params = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(model_path, model_params);
    if (!model) {
        env->ReleaseStringUTFChars(model_path_string, model_path);
        env->ReleaseStringUTFChars(prompt_string, prompt);
        return env->NewStringUTF("Could not load the GGUF model.");
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);
    const int prompt_size = -llama_tokenize(
        vocab, prompt, static_cast<int32_t>(strlen(prompt)), nullptr, 0, true, true
    );
    std::vector<llama_token> prompt_tokens(prompt_size);
    if (llama_tokenize(
            vocab, prompt, static_cast<int32_t>(strlen(prompt)),
            prompt_tokens.data(), prompt_size, true, true
        ) < 0) {
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path_string, model_path);
        env->ReleaseStringUTFChars(prompt_string, prompt);
        return env->NewStringUTF("Could not tokenize the prompt.");
    }

    auto context_params = llama_context_default_params();
    context_params.n_ctx = prompt_size + 128;
    context_params.n_batch = std::max(prompt_size, 1);
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    llama_context* context = llama_init_from_model(model, context_params);
    if (!context) {
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path_string, model_path);
        env->ReleaseStringUTFChars(prompt_string, prompt);
        return env->NewStringUTF("Could not create the GGUF inference context.");
    }

    auto sampler_params = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    llama_batch_ext* batch = llama_batch_ext_init(context);
    set_batch_tokens(batch, prompt_tokens.data(), prompt_size, 0);

    std::string result;
    int position = 0;
    for (int generated = 0; generated < 128; ++generated) {
        if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            result = "GGUF inference failed while decoding.";
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

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(context);
    llama_model_free(model);
    env->ReleaseStringUTFChars(model_path_string, model_path);
    env->ReleaseStringUTFChars(prompt_string, prompt);
    return env->NewStringUTF(result.c_str());
}
