package com.musheer360.swiftslate.provider

import com.musheer360.swiftslate.model.GeminiModels
import com.musheer360.swiftslate.model.GroqModels
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.model.ProviderType

/** Which transport client handles a provider's requests. */
enum class Transport { GEMINI_NATIVE, OPENAI_COMPAT, LOCAL }

interface ProviderConfig {
    val type: String
    val transport: Transport
    val modelPrefKey: String
    val defaultModel: String

    fun sanitizeModel(stored: String?): String
    fun resolveEndpoint(customEndpoint: String): String
    fun reasoningParams(model: String): Map<String, Any> = emptyMap()
    fun thinkingLevel(model: String): String? = null
    fun useJsonObjectMode(structuredOutputEnabled: Boolean): Boolean = false
    fun isConfigured(model: String, endpoint: String): Boolean = true
}

object GeminiConfig : ProviderConfig {
    override val type = ProviderType.GEMINI
    override val transport = Transport.GEMINI_NATIVE
    override val modelPrefKey = PrefKeys.GEMINI_MODEL
    override val defaultModel = GeminiModels.DEFAULT
    override fun sanitizeModel(stored: String?): String = GeminiModels.sanitize(stored)
    override fun resolveEndpoint(customEndpoint: String): String = ""
    override fun thinkingLevel(model: String): String? = GeminiModels.thinkingLevel(model)
}

object GroqConfig : ProviderConfig {
    const val ENDPOINT = "https://api.groq.com/openai/v1"

    override val type = ProviderType.GROQ
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.GROQ_MODEL
    override val defaultModel = GroqModels.DEFAULT
    override fun sanitizeModel(stored: String?): String = GroqModels.sanitize(stored)
    override fun resolveEndpoint(customEndpoint: String): String = ENDPOINT
    override fun reasoningParams(model: String): Map<String, Any> = GroqModels.reasoningParams(model)
    override fun useJsonObjectMode(structuredOutputEnabled: Boolean): Boolean = structuredOutputEnabled
}

object CustomConfig : ProviderConfig {
    override val type = ProviderType.CUSTOM
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.CUSTOM_MODEL
    override val defaultModel = ""
    override fun sanitizeModel(stored: String?): String = stored?.trim() ?: ""
    override fun resolveEndpoint(customEndpoint: String): String = customEndpoint
    override fun isConfigured(model: String, endpoint: String): Boolean =
        model.isNotBlank() && endpoint.isNotBlank()
}

/** On-device GGUF model handled by LocalLlmClient. */
object LocalConfig : ProviderConfig {
    override val type = ProviderType.LOCAL
    override val transport = Transport.LOCAL
    override val modelPrefKey = PrefKeys.LOCAL_MODEL_PATH
    override val defaultModel = ""
    override fun sanitizeModel(stored: String?): String = stored?.trim() ?: ""
    override fun resolveEndpoint(customEndpoint: String): String = ""
    override fun isConfigured(model: String, endpoint: String): Boolean = model.isNotBlank()
}

object Providers {
    fun forType(type: String?): ProviderConfig = when (ProviderType.sanitize(type)) {
        ProviderType.GROQ -> GroqConfig
        ProviderType.CUSTOM -> CustomConfig
        ProviderType.LOCAL -> LocalConfig
        else -> GeminiConfig
    }
}
