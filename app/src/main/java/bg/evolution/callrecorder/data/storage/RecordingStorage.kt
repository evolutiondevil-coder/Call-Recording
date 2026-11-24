package bg.evolution.callrecorder.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import bg.evolution.callrecorder.core.AudioFormat
import bg.evolution.callrecorder.core.FileNames
import bg.evolution.callrecorder.core.Logx
import java.io.File

/**
 * Owns the recording files. While a call is recorded the audio goes to a hidden temp file
 * in app storage; when it ends, the file is renamed (app storage) or copied into the folder
 * the user picked through the system file picker.
 */
class RecordingStorage(private val context: Context) {

    val recordingsDir: File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newTempFile(format: AudioFormat, startedAt: Long): File {
        recordingsDir.mkdirs()
        return File(recordingsDir, "$TEMP_PREFIX$startedAt.${format.extension}")
    }

    /** Leftovers of recordings that were interrupted by a crash or a forced stop. */
    fun orphanedTempFiles(): List<File> =
        recordingsDir.listFiles { f -> f.name.startsWith(TEMP_PREFIX) }?.toList().orEmpty()

    data class Stored(val uri: Uri, val fileName: String, val sizeBytes: Long)

    /** Moves a finished temp file to its final place. Falls back to app storage if the chosen folder fails. */
    fun commit(temp: File, fileName: String, format: AudioFormat, treeUri: String?): Stored {
        val size = temp.length()
        if (treeUri != null) {
            runCatching { copyToTree(temp, fileName, format, Uri.parse(treeUri)) }
                .onSuccess { uri ->
                    temp.delete()
                    return Stored(uri, fileName, size)
                }
                .onFailure { Logx.w("Could not save to chosen folder, keeping in app storage", it) }
        }
        val target = uniqueFile(recordingsDir, fileName)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return Stored(Uri.fromFile(target), target.name, size)
    }

    private fun copyToTree(temp: File, fileName: String, format: AudioFormat, tree: Uri): Uri {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, format.mimeType, fileName)
            ?: error("createDocument returned null")
        try {
            resolver.openOutputStream(doc, "w").use { out ->
                requireNotNull(out) { "No output stream" }
                temp.inputStream().use { it.copyTo(out, 256 * 1024) }
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        return doc
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    fun delete(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return try {
            if (uri.scheme == "file") {
                val f = File(requireNotNull(uri.path))
                !f.exists() || f.delete()
            } else {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            Logx.w("Delete failed for $uriString", e)
            // The file is gone or unreachable; the database entry should still be removed.
            !exists(uriString)
        }
    }

    fun exists(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return try {
            if (uri.scheme == "file") File(requireNotNull(uri.path)).exists()
            else context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** A URI other apps can read (FileProvider for app storage, the document itself for SAF). */
    fun shareableUri(uriString: String): Uri {
        val uri = Uri.parse(uriString)
        return if (uri.scheme == "file") {
            FileProvider.getUriForFile(context, "${context.packageName}.files", File(requireNotNull(uri.path)))
        } else uri
    }

    fun shareIntent(uris: List<String>, mimeType: String): Intent {
        val shareable = uris.map(::shareableUri)
        val intent = if (shareable.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, shareable.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(shareable))
        }
        intent.type = mimeType
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, null)
    }

    /** Copies a recording into a document the user created with the system "save as" dialog. */
    fun exportTo(uriString: String, target: Uri): Boolean = try {
        val resolver = context.contentResolver
        resolver.openInputStream(Uri.parse(uriString)).use { input ->
            resolver.openOutputStream(target, "w").use { out ->
                requireNotNull(input); requireNotNull(out)
                input.copyTo(out, 256 * 1024)
            }
        }
        true
    } catch (e: Exception) {
        Logx.e("Export failed", e)
        false
    }

    fun hasTreePermission(treeUri: String?): Boolean {
        if (treeUri == null) return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == treeUri && it.isWritePermission
        }
    }

    fun treeDisplayName(treeUri: String?): String? {
        if (treeUri == null) return null
        return runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
            // "primary:Recordings/Calls" -> "Recordings/Calls"
            id.substringAfter(':', id).ifBlank { "/" }
        }.getOrNull()
    }

    fun fileNameFor(startedAt: Long, title: String?, appLabel: String?, format: AudioFormat): String =
        FileNames.forRecording(startedAt, title, appLabel, format.extension)

    companion object {
        const val TEMP_PREFIX = ".rec_"
    }
}
