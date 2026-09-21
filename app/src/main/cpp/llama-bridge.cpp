#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <mutex>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::string g_loaded_model_path = "";
static bool g_backend_initialized = false;

static void cleanup_locked() {
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
    g_loaded_model_path.clear();
}

static bool load_model_locked(const char* model_path) {
    if (g_model != nullptr && g_ctx != nullptr && g_loaded_model_path == model_path) {
        return true; // Already warm in RAM
    }

    cleanup_locked();

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    LOGI("Loading GGUF model into memory: %s", model_path);
    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;

    g_model = llama_load_model_from_file(model_path, mparams);
    if (!g_model) {
        LOGE("Failed to load GGUF model from: %s", model_path);
        return false;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create llama context");
        llama_free_model(g_model);
        g_model = nullptr;
        return false;
    }

    g_loaded_model_path = model_path;
    LOGI("Model successfully loaded and cached in memory!");
    return true;
}

static std::string run_inference_locked(const char* model_path, const char* prompt, int max_new_tokens) {
    try {
        if (!load_model_locked(model_path)) {
            return "Error: Could not load GGUF model into memory.";
        }

        // Clear KV cache for a clean state
        llama_kv_cache_clear(g_ctx);

        std::string user_prompt = prompt;
        int32_t prompt_len = static_cast<int32_t>(user_prompt.length());

        std::vector<llama_token> tokens(prompt_len + 64);
        int n_tokens = llama_tokenize(g_model, user_prompt.c_str(), prompt_len, tokens.data(), tokens.size(), true, true);
        if (n_tokens < 0) {
            tokens.resize(-n_tokens);
            n_tokens = llama_tokenize(g_model, user_prompt.c_str(), prompt_len, tokens.data(), tokens.size(), true, true);
        }

        if (n_tokens <= 0) {
            return "Error: Tokenization resulted in empty tokens.";
        }

        llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens, 0, 0);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode prompt failed");
            return "Error: Model decode evaluation failed.";
        }

        std::string response;
        llama_token curr_token = 0;

        for (int i = 0; i < max_new_tokens; ++i) {
            // Use llama_get_logits(g_ctx) which is equivalent to llama_get_logits_ith(g_ctx, -1)
            float* logits = llama_get_logits(g_ctx);
            if (!logits) {
                LOGE("llama_get_logits returned NULL");
                break;
            }

            int n_vocab = llama_n_vocab(g_model);

            llama_token new_token_id = 0;
            float max_p = -1e9f;
            for (int v = 0; v < n_vocab; ++v) {
                if (logits[v] > max_p) {
                    max_p = logits[v];
                    new_token_id = v;
                }
            }

            if (new_token_id == llama_token_eos(g_model)) {
                break;
            }

            char buf[128];
            int n = llama_token_to_piece(g_model, new_token_id, buf, sizeof(buf), 0, true);
            if (n > 0) {
                response.append(buf, n);
                // Check for ChatML termination tokens
                if (response.find("<|im_end|>") != std::string::npos) {
                    size_t pos = response.find("<|im_end|>");
                    response = response.substr(0, pos);
                    break;
                }
                if (response.find("<|endoftext|>") != std::string::npos) {
                    size_t pos = response.find("<|endoftext|>");
                    response = response.substr(0, pos);
                    break;
                }
            }

            curr_token = new_token_id;
            llama_batch next_batch = llama_batch_get_one(&curr_token, 1, n_tokens + i, 0);
            if (llama_decode(g_ctx, next_batch) != 0) {
                LOGE("llama_decode token failed at step %d", i);
                break;
            }
        }

        // Clean any trailing whitespace
        while (!response.empty() && (response.back() == ' ' || response.back() == '\n' || response.back() == '\r')) {
            response.pop_back();
        }

        return response.empty() ? "I am here to help you." : response;
    } catch (const std::exception& e) {
        LOGE("Native inference exception: %s", e.what());
        return std::string("Error: ") + e.what();
    } catch (...) {
        LOGE("Unknown native inference exception caught");
        return "Error: Unknown native execution failure.";
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sriox_vasateysec_utils_LlamaBridge_nativeGenerateResponse(
        JNIEnv* env,
        jobject thiz,
        jstring j_model_path,
        jstring j_prompt) {

    const char* model_path = env->GetStringUTFChars(j_model_path, nullptr);
    const char* prompt = env->GetStringUTFChars(j_prompt, nullptr);

    std::lock_guard<std::mutex> lock(g_mutex);
    std::string response = run_inference_locked(model_path, prompt, 128);

    env->ReleaseStringUTFChars(j_model_path, model_path);
    env->ReleaseStringUTFChars(j_prompt, prompt);

    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sriox_vasateysec_utils_LlamaBridge_nativeGenerateResponseWithTokens(
        JNIEnv* env,
        jobject thiz,
        jstring j_model_path,
        jstring j_prompt,
        jint j_max_tokens) {

    const char* model_path = env->GetStringUTFChars(j_model_path, nullptr);
    const char* prompt = env->GetStringUTFChars(j_prompt, nullptr);
    int max_tokens = j_max_tokens > 0 ? j_max_tokens : 128;

    std::lock_guard<std::mutex> lock(g_mutex);
    std::string response = run_inference_locked(model_path, prompt, max_tokens);

    env->ReleaseStringUTFChars(j_model_path, model_path);
    env->ReleaseStringUTFChars(j_prompt, prompt);

    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sriox_vasateysec_utils_LlamaBridge_nativeInitModel(
        JNIEnv* env,
        jobject thiz,
        jstring j_model_path) {

    const char* model_path = env->GetStringUTFChars(j_model_path, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    bool ok = load_model_locked(model_path);
    env->ReleaseStringUTFChars(j_model_path, model_path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sriox_vasateysec_utils_LlamaBridge_nativeUnloadModel(
        JNIEnv* env,
        jobject thiz) {

    std::lock_guard<std::mutex> lock(g_mutex);
    cleanup_locked();
    LOGI("Model explicitly unloaded from memory.");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sriox_vasateysec_utils_LlamaBridge_nativeIsModelLoaded(
        JNIEnv* env,
        jobject thiz) {

    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}
