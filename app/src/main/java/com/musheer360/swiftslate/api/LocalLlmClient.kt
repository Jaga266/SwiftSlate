package com.musheer360.swiftslate.api

import android.content.Context
import com.musheer360.swiftslate.model.PrefKeys
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Thin on-device provider backed by llama.cpp.
 *
 * The active model is cached in native memory and reloaded only when the model path or
 * inference configuration changes. Access is serialized because one LlamaModel is not
 * thread-safe.
 */
object LocalLlmClient {
    private const val DEFAULT_CONTEXT_SIZE = 2048
    private const val DEFAULT_THREADS = 4
    private const val DEFAULT_MAX_TOKENS = 384

    private val mutex = Mutex()
    private var loadedModel: LlamaModel? = null
    private var loadedKey: String? = null

    suspend fun generate(
        context: Context,
        instruction: String,
        text: String,
        temperature: Float,
    ): Result<String> = mutex.withLock {
        runCatching {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val modelPath = prefs.getString(PrefKeys.LOCAL_MODEL_PATH, "")?.trim().orEmpty()
            if (modelPath.isBlank()) error("No local model selected")
            if (!File(modelPath).isFile) error("Local model file is missing")

            val contextSize = prefs.getInt(PrefKeys.LOCAL_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE)
                .coerceIn(512, 8192)
            val threads = prefs.getInt(PrefKeys.LOCAL_THREADS, DEFAULT_THREADS)
                .coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            val maxTokens = prefs.getInt(PrefKeys.LOCAL_MAX_TOKENS, DEFAULT_MAX_TOKENS)
                .coerceIn(32, 2048)
            val safeTemperature = temperature.coerceIn(0f, 2f)

            val cacheKey = "$modelPath|$contextSize|$threads|$safeTemperature"
            var model = loadedModel
            if (model == null || loadedKey != cacheKey) {
                model?.let(Llama::releaseModel)
                model = Llama.loadModel(
                    modelPath = modelPath,
                    config = LlamaConfig(
                        contextSize = contextSize,
                        threads = threads,
                        temperature = safeTemperature,
                    ),
                )
                loadedModel = model
                loadedKey = cacheKey
            }

            val result = Llama.complete(
                model = requireNotNull(model),
                prompt = "Instruction:\n$instruction\n\nText:\n$text",
                systemPrompt = "You edit or transform text exactly as requested. Return only the final transformed text, with no explanation, labels, quotes, or markdown fences.",
                maxTokens = maxTokens,
            )
            result.text.trim()
        }
    }

    suspend fun unload() = mutex.withLock {
        loadedModel?.let(Llama::releaseModel)
        loadedModel = null
        loadedKey = null
    }
}
