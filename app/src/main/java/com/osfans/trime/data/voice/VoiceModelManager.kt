/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.osfans.trime.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Stores the locally imported speech recognition models.
 *
 * Models are **never** bundled or downloaded by the app: the user picks a
 * folder through the system file picker and it is copied into the app-private
 * storage. This keeps the input method free of the INTERNET permission. See
 * `doc/voice-input.md`.
 */
object VoiceModelManager {
    private const val MANIFEST_NAME = "voice-model.json"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    val rootDir: File
        get() = File(appContext.filesDir, "voice/models")

    fun modelDir(id: String): File = File(rootDir, id)

    fun listModels(): List<VoiceModel> = rootDir
        .listFiles { file -> file.isDirectory }
        ?.mapNotNull { dir -> readManifest(dir) }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    fun findModel(id: String): VoiceModel? = listModels().firstOrNull { it.id == id }

    fun deleteModel(model: VoiceModel): Boolean = modelDir(model.id).deleteRecursively()

    /**
     * Copies every file found in the picked document tree into the app-private
     * storage and returns the detected model.
     *
     * @throws IllegalArgumentException when the folder does not look like a
     *   supported sherpa-onnx offline model.
     */
    fun importFromTree(context: Context, treeUri: Uri): VoiceModel {
        val displayName = queryTreeDisplayName(context, treeUri)
        val documents = queryChildren(context, treeUri)
        if (documents.isEmpty()) {
            throw IllegalArgumentException("empty folder")
        }

        val fileNames = documents.map { it.name }
        val candidate = detect(displayName, fileNames)

        val baseId = sanitize(displayName).ifEmpty { "model" }
        val targetDir = uniqueDir(baseId)
        targetDir.mkdirs()

        try {
            documents.forEach { document ->
                val target = File(targetDir, document.name)
                context.contentResolver.openInputStream(document.uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("cannot read ${document.name}")
            }
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            throw e
        }

        val model = candidate.copy(id = targetDir.name, name = displayName.ifBlank { targetDir.name })
        File(targetDir, MANIFEST_NAME).writeText(json.encodeToString(VoiceModel.serializer(), model))
        Timber.i("Imported voice model %s (%s)", model.name, model.type)
        return model
    }

    private fun readManifest(dir: File): VoiceModel? {
        val manifest = File(dir, MANIFEST_NAME)
        if (!manifest.isFile) return null
        return runCatching { json.decodeFromString(VoiceModel.serializer(), manifest.readText()) }
            .onFailure { Timber.w(it, "Failed to read voice model manifest in %s", dir) }
            .getOrNull()
            ?.takeIf { it.id == dir.name }
    }

    private fun uniqueDir(baseId: String): File {
        var dir = File(rootDir, baseId)
        if (!dir.exists()) return dir
        var i = 1
        while (dir.exists()) {
            dir = File(rootDir, "$baseId-$i")
            i++
        }
        return dir
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * Best-effort detection of the model type from the folder name and the
     * files inside it. A wrong guess only affects which files are wired into
     * the sherpa-onnx config; the user can delete and re-import.
     */
    private fun detect(dirName: String, files: List<String>): VoiceModel {
        val onnx = files.filter { it.endsWith(".onnx", ignoreCase = true) }
        if (onnx.isEmpty()) {
            throw IllegalArgumentException("no .onnx model found")
        }
        val tokens = files.firstOrNull { it.contains("tokens", ignoreCase = true) }

        val encoder = choose(onnx, "encoder")
        val decoder = choose(onnx, "decoder")
        val joiner = choose(onnx, "joiner")

        val lowerName = dirName.lowercase()
        return when {
            joiner != null -> VoiceModel(
                id = "",
                name = dirName,
                type = VoiceModelType.TRANSDUCER,
                tokens = tokens.orEmpty(),
                encoder = encoder.orEmpty(),
                decoder = decoder ?: "",
                joiner = joiner,
            )

            encoder != null && decoder != null && lowerName.contains("whisper") ->
                VoiceModel(
                    id = "",
                    name = dirName,
                    type = VoiceModelType.WHISPER,
                    tokens = tokens.orEmpty(),
                    encoder = encoder,
                    decoder = decoder,
                )

            lowerName.contains("paraformer") -> VoiceModel(
                id = "",
                name = dirName,
                type = VoiceModelType.PARAFORMER,
                model = choose(onnx, "model") ?: onnx.first(),
                tokens = requireTokens(tokens),
            )

            lowerName.contains("zipformer") && lowerName.contains("ctc") -> VoiceModel(
                id = "",
                name = dirName,
                type = VoiceModelType.ZIPFORMER_CTC,
                model = choose(onnx, "model") ?: onnx.first(),
                tokens = requireTokens(tokens),
            )

            else -> VoiceModel(
                id = "",
                name = dirName,
                type = VoiceModelType.SENSE_VOICE,
                model = choose(onnx, "model") ?: onnx.first(),
                tokens = requireTokens(tokens),
            )
        }
    }

    private fun requireTokens(tokens: String?): String = tokens ?: throw IllegalArgumentException("tokens.txt not found")

    /** Picks the best matching onnx file, preferring int8/fp16 quantizations. */
    private fun choose(onnx: List<String>, keyword: String): String? = onnx
        .filter { it.contains(keyword, ignoreCase = true) }
        .minByOrNull { name ->
            when {
                name.contains("int8", ignoreCase = true) -> 0
                name.contains("fp16", ignoreCase = true) -> 1
                else -> 2
            }
        }

    private data class Document(
        val uri: Uri,
        val name: String,
    )

    private fun queryTreeDisplayName(context: Context, treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return ""
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
    }

    private fun queryChildren(context: Context, treeUri: Uri): List<Document> {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val result = mutableListOf<Document>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val name = cursor.getString(1)
                val mime = cursor.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (name.startsWith(".")) continue
                result += Document(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                    name = name,
                )
            }
        }
        return result
    }
}
