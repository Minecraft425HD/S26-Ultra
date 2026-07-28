// JNI-Brücke zwischen Kotlin und llama.cpp.
//
// ACHTUNG: Diese Datei ist die einzige im Projekt, die nie kompiliert wurde — sie
// verlangt das Android-NDK und die llama.cpp-Quellen, die beide nicht Teil des
// Repositories sind. Sie ist gegen die llama.cpp-API geschrieben, wie sie im Sommer 2026
// aussieht; llama.cpp ändert seine Schnittstellen regelmäßig. Beim ersten Bauen mit
// -Pneon.buildNative=true ist daher mit Anpassungen an den Funktionsnamen zu rechnen.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "NeonLlamaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/// Alles, was zu einem geladenen Modell gehört. Der Zeiger darauf wandert als long
/// nach Kotlin und kommt bei jedem Aufruf zurück.
struct NeonContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int threads = 8;
};

/// Einmalige Initialisierung des llama.cpp-Backends.
struct BackendGuard {
    BackendGuard() { llama_backend_init(); }
    ~BackendGuard() { llama_backend_free(); }
};
BackendGuard g_backend;

std::string to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

/// Wandelt ein einzelnes Token in seinen Textausschnitt.
std::string token_to_text(const llama_vocab *vocab, llama_token token) {
    char buffer[256];
    const int length = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
    if (length < 0) return {};
    return std::string(buffer, length);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_de_neon_inference_LlamaCppEngine_nativeLoad(
        JNIEnv *env, jobject /*thiz*/, jstring path, jint threads, jint gpuLayers) {

    const std::string model_path = to_string(env, path);

    llama_model_params model_params = llama_model_default_params();
    // Speicherabbild statt Einlesen: Genau daran hängt der schnelle Modellwechsel.
    // Der Kernel hält zuletzt benutzte Seiten im Cache, sodass ein kürzlich geladenes
    // Modell fast ohne Kosten zurückkommt.
    model_params.use_mmap = true;
    model_params.use_mlock = false;
    model_params.n_gpu_layers = gpuLayers;

    llama_model *model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("Modell konnte nicht geladen werden: %s", model_path.c_str());
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGE("Kontext konnte nicht erzeugt werden");
        llama_model_free(model);
        return 0;
    }

    auto *neon = new NeonContext{model, ctx, threads};
    LOGI("Modell geladen: %s", model_path.c_str());
    return reinterpret_cast<jlong>(neon);
}

JNIEXPORT void JNICALL
Java_de_neon_inference_LlamaCppEngine_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *neon = reinterpret_cast<NeonContext *>(handle);
    if (neon == nullptr) return;
    if (neon->ctx) llama_free(neon->ctx);
    if (neon->model) llama_model_free(neon->model);
    delete neon;
}

JNIEXPORT void JNICALL
Java_de_neon_inference_LlamaCppEngine_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt, jint maxTokens,
        jfloat temperature, jfloat topP, jstring /*grammar*/, jobjectArray /*stopSequences*/,
        jobject onToken) {

    auto *neon = reinterpret_cast<NeonContext *>(handle);
    if (neon == nullptr || neon->ctx == nullptr) return;

    const llama_vocab *vocab = llama_model_get_vocab(neon->model);
    const std::string prompt_text = to_string(env, prompt);

    // Rückruf nach Kotlin: (String) -> Boolean. Ein false beendet die Erzeugung —
    // so lässt sich eine laufende Antwort abbrechen, wenn der Nutzer dazwischenredet.
    jclass callback_class = env->GetObjectClass(onToken);
    jmethodID invoke = env->GetMethodID(
            callback_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    jclass boolean_class = env->FindClass("java/lang/Boolean");
    jmethodID boolean_value = env->GetMethodID(boolean_class, "booleanValue", "()Z");

    // Prompt in Token zerlegen.
    const int n_prompt = -llama_tokenize(
            vocab, prompt_text.c_str(), static_cast<int>(prompt_text.size()),
            nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt_text.c_str(), static_cast<int>(prompt_text.size()),
                       tokens.data(), n_prompt, true, true) < 0) {
        LOGE("Der Prompt ließ sich nicht zerlegen");
        return;
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int>(tokens.size()));

    for (int generated = 0; generated < maxTokens; ++generated) {
        if (llama_decode(neon->ctx, batch) != 0) {
            LOGE("llama_decode ist fehlgeschlagen");
            break;
        }

        const llama_token token = llama_sampler_sample(sampler, neon->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        const std::string piece = token_to_text(vocab, token);
        if (!piece.empty()) {
            jstring js = env->NewStringUTF(piece.c_str());
            jobject result = env->CallObjectMethod(onToken, invoke, js);
            const bool keep_going = result != nullptr &&
                                    env->CallBooleanMethod(result, boolean_value);
            env->DeleteLocalRef(js);
            if (result) env->DeleteLocalRef(result);
            if (!keep_going) break;
        }

        batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
    }

    llama_sampler_free(sampler);
}

} // extern "C"
