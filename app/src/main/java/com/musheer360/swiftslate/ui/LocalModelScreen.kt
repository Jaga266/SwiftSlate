package com.musheer360.swiftslate.ui

import android.content.SharedPreferences
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.model.ProviderType
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LocalModelScreen(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modelPath by remember { mutableStateOf(prefs.getString(PrefKeys.LOCAL_MODEL_PATH, "").orEmpty()) }
    var modelName by remember { mutableStateOf(prefs.getString(PrefKeys.LOCAL_MODEL_NAME, "").orEmpty()) }
    var isImporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var providerType by remember { mutableStateOf(prefs.getString(PrefKeys.PROVIDER_TYPE, ProviderType.GEMINI).orEmpty()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isImporting = true
            message = null
            try {
                val displayName = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                        ?.takeIf { it.endsWith(".gguf", ignoreCase = true) }
                        ?: "model.gguf"
                }

                val target = withContext(Dispatchers.IO) {
                    val dir = File(context.filesDir, "models").apply { mkdirs() }
                    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val out = File(dir, safeName)
                    val tmp = File(dir, "$safeName.part")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().buffered().use { output -> input.copyTo(output) }
                    } ?: error("Unable to open selected file")
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) {
                        tmp.copyTo(out, overwrite = true)
                        tmp.delete()
                    }
                    out
                }

                modelPath = target.absolutePath
                modelName = displayName
                prefs.edit()
                    .putString(PrefKeys.LOCAL_MODEL_PATH, modelPath)
                    .putString(PrefKeys.LOCAL_MODEL_NAME, modelName)
                    .putInt(PrefKeys.LOCAL_CONTEXT_SIZE, 2048)
                    .putInt(PrefKeys.LOCAL_THREADS, 4)
                    .putInt(PrefKeys.LOCAL_MAX_TOKENS, 384)
                    .apply()
                message = "Model imported successfully."
            } catch (e: Exception) {
                message = e.message ?: "Model import failed."
            } finally {
                isImporting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        ScreenTitle("Local model")

        SlateCard {
            Text(
                text = "On-device AI",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (modelName.isBlank()) "No GGUF model imported" else modelName,
                style = MaterialTheme.typography.titleMedium
            )
            if (modelPath.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = modelPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Importing…")
                } else {
                    Text(if (modelPath.isBlank()) "Import GGUF" else "Replace model")
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    providerType = ProviderType.LOCAL
                    prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.LOCAL).apply()
                    message = "Local model is now the active AI provider."
                },
                enabled = modelPath.isNotBlank() && File(modelPath).isFile && providerType != ProviderType.LOCAL,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (providerType == ProviderType.LOCAL) "Local provider active" else "Use local model")
            }

            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SlateCard {
            Text("S24 starter configuration", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Context 2048 • 4 CPU threads • 384 max output tokens. Start with a 0.5B–2B Q4 GGUF; larger models will use considerably more RAM and battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
