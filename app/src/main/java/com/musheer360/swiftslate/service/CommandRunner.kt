package com.musheer360.swiftslate.service

import android.content.Context
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.api.ApiClientUtils
import com.musheer360.swiftslate.api.ApiError
import com.musheer360.swiftslate.api.ApiException
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.LocalLlmClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.provider.Providers
import com.musheer360.swiftslate.provider.Transport
import java.util.Locale

sealed interface CommandOutcome {
    data class Success(val text: String) : CommandOutcome
    data object Refusal : CommandOutcome
    data class Unavailable(val message: String) : CommandOutcome
    data class Failure(val message: String) : CommandOutcome
}

private const val DEFAULT_TEMPERATURE = 0.5f
private const val STRUCTURED_OUTPUT_RETRY_MS = 86_400_000L

suspend fun runTextCommand(
    context: Context,
    keyManager: KeyManager,
    geminiClient: GeminiClient,
    openAIClient: OpenAICompatibleClient,
    prompt: String,
    text: String,
    onFirstAttempt: () -> Unit = {}
): CommandOutcome {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val provider = Providers.forType(prefs.getString(PrefKeys.PROVIDER_TYPE, null))
    val model = provider.sanitizeModel(prefs.getString(provider.modelPrefKey, provider.defaultModel))
    val endpoint = provider.resolveEndpoint(prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "")
    if (!provider.isConfigured(model, endpoint)) {
        return CommandOutcome.Unavailable(
            if (provider.transport == Transport.LOCAL) "Import a GGUF model in Settings first."
            else context.getString(R.string.toast_custom_not_configured)
        )
    }

    val temperatureFloat = prefs.getFloat(PrefKeys.TEMPERATURE, DEFAULT_TEMPERATURE)

    // Local inference deliberately bypasses Keystore/key rotation: no credential or network is
    // involved. Keeping it here also means both the accessibility trigger and Process Text entry
    // points automatically use the same on-device path.
    if (provider.transport == Transport.LOCAL) {
        onFirstAttempt()
        return LocalLlmClient.generate(context, prompt, text, temperatureFloat).fold(
            onSuccess = { generated ->
                if (ApiClientUtils.isModelRefusal(generated)) CommandOutcome.Refusal
                else CommandOutcome.Success(generated)
            },
            onFailure = { error ->
                CommandOutcome.Failure(error.message ?: "Local model inference failed")
            }
        )
    }

    // Remote providers need the encrypted API-key store.
    if (!keyManager.keystoreAvailable) {
        return CommandOutcome.Unavailable(context.getString(R.string.keys_keystore_error))
    }

    val temperature = temperatureFloat.toDouble()
    val useStructuredOutput = System.currentTimeMillis() -
        prefs.getLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, 0L) > STRUCTURED_OUTPUT_RETRY_MS

    var lastErrorMsg: String? = null
    var lastErrorWasRateLimit = false
    var lastErrorWasPermission = false
    var lastFailedKey: String? = null
    var started = false
    val tried = mutableSetOf<String>()

    val maxAttempts = keyManager.getKeys().size.coerceAtLeast(1)
    while (tried.size < maxAttempts) {
        val key = keyManager.getNextKey(tried) ?: break
        tried.add(key)
        if (!started) {
            started = true
            onFirstAttempt()
        }

        val result = when (provider.transport) {
            Transport.OPENAI_COMPAT -> openAIClient.generate(
                prompt, text, key, model, temperature, endpoint,
                useJsonObjectMode = provider.useJsonObjectMode(useStructuredOutput),
                extraParams = provider.reasoningParams(model)
            )
            Transport.GEMINI_NATIVE -> geminiClient.generate(
                prompt, text, key, model, temperature, useStructuredOutput,
                thinkingLevel = provider.thinkingLevel(model)
            )
            Transport.LOCAL -> error("Local transport must be handled before API-key rotation")
        }

        result.onSuccess { generated ->
            if (ApiClientUtils.isModelRefusal(generated.text)) return CommandOutcome.Refusal
            if (generated.structuredOutputFailed) {
                prefs.edit()
                    .putLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, System.currentTimeMillis())
                    .apply()
            }
            val outputText = if (generated.truncated) {
                generated.text + "\n\n" + context.getString(R.string.note_response_truncated)
            } else {
                generated.text
            }
            return CommandOutcome.Success(outputText)
        }

        val error = result.exceptionOrNull()
        val msg = error?.message ?: ""
        lastErrorMsg = msg
        when (val apiError = (error as? ApiException)?.apiError) {
            is ApiError.RateLimit -> {
                lastErrorWasRateLimit = true
                keyManager.reportRateLimit(key, apiError.retryAfterSeconds?.toLong() ?: 60)
            }
            is ApiError.InvalidKey -> {
                lastErrorWasRateLimit = false
                if (msg.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER)) {
                    lastFailedKey = null
                    lastErrorWasPermission = false
                } else {
                    lastFailedKey = key
                    val m = msg.lowercase(Locale.ROOT)
                    lastErrorWasPermission = m.contains("permission") ||
                        m.contains("does not have access") || m.contains("not been used in project")
                    if (keyManager.getKeys().size > 1) {
                        keyManager.markInvalid(key)
                    }
                }
            }
            is ApiError.ServerError -> lastErrorWasRateLimit = false
            else -> {
                lastErrorWasRateLimit = false
                break
            }
        }
    }

    val waitMs = keyManager.getShortestWaitTimeMs()
    val failedKey = lastFailedKey
    val raw = lastErrorMsg
    return CommandOutcome.Failure(
        when {
            waitMs != null && (raw == null || lastErrorWasRateLimit) ->
                context.getString(R.string.toast_key_rate_limited, ((waitMs + 999) / 1000).coerceAtLeast(1))
            lastErrorWasPermission -> context.getString(R.string.error_no_model_access)
            raw != null -> {
                val mapped = ErrorMessages.map(raw)
                if (mapped == R.string.error_invalid_key && failedKey != null && keyManager.getKeys().size > 1) {
                    context.getString(R.string.error_invalid_key_with_hint, "••••" + failedKey.takeLast(4))
                } else {
                    context.getString(mapped)
                }
            }
            keyManager.getKeys().isEmpty() -> context.getString(R.string.toast_no_keys)
            else -> context.getString(R.string.toast_all_keys_invalid)
        }
    )
}
