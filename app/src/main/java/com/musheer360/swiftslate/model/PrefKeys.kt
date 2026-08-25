package com.musheer360.swiftslate.model

/**
 * Single source of truth for the SharedPreferences keys used by the
 * provider/model configuration flow. Centralizing these prevents silent
 * breakage from mistyped string literals scattered across UI, service, and
 * client code.
 */
object PrefKeys {
    /** Active provider ("gemini" | "groq" | "custom" | "local") — see [ProviderType]. */
    const val PROVIDER_TYPE = "provider_type"

    /** Selected Gemini model id. */
    const val GEMINI_MODEL = "model"

    /** Selected Groq model id. */
    const val GROQ_MODEL = "groq_model"

    /** Custom (OpenAI-compatible) model id. */
    const val CUSTOM_MODEL = "custom_model"

    /** Custom (OpenAI-compatible) endpoint base URL. */
    const val CUSTOM_ENDPOINT = "custom_endpoint"

    /** App-private path to the imported local GGUF model. */
    const val LOCAL_MODEL_PATH = "local_model_path"

    /** Original display name for the imported local GGUF model. */
    const val LOCAL_MODEL_NAME = "local_model_name"

    /** llama.cpp context window used for local inference. */
    const val LOCAL_CONTEXT_SIZE = "local_context_size"

    /** CPU thread count used for local inference. */
    const val LOCAL_THREADS = "local_threads"

    /** Max output tokens for local inference. */
    const val LOCAL_MAX_TOKENS = "local_max_tokens"

    /** Sampling temperature (Float). */
    const val TEMPERATURE = "temperature"

    /** Epoch millis when structured output was last disabled (0 = never). */
    const val STRUCTURED_OUTPUT_DISABLED_AT = "structured_output_disabled_at"
}
