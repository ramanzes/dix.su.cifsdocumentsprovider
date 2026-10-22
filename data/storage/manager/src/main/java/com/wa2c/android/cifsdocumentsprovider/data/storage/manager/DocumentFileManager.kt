package com.wa2c.android.cifsdocumentsprovider.data.storage.manager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.wa2c.android.cifsdocumentsprovider.common.utils.getFileName
import com.wa2c.android.cifsdocumentsprovider.common.utils.logE
import com.wa2c.android.cifsdocumentsprovider.common.values.BUFFER_SIZE
import com.wa2c.android.cifsdocumentsprovider.data.storage.interfaces.StorageFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Get DocumentFile
     */
    private fun getDocumentFile(uri: Uri): DocumentFile? {
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(context, uri) ?: return null
        } else {
            DocumentFile.fromSingleUri(context, uri) ?: return null
        }
    }

    /**
     * Get StorageFile from DocumentFile URI
     */
    fun getStorageFileAndMimeType(uri: Uri): Pair<StorageFile, String>? {
        val file = getDocumentFile(uri) ?: return null
        return Pair(
            StorageFile(
                name = file.name ?: file.uri.getFileName(context),
                size = file.length(),
                uri = file.uri.toString(),
                isDirectory = file.isDirectory,
            ),
            file.type?.ifEmpty { null } ?: OTHER_MIME_TYPE,
        )
    }

    /**
     * Get output file URI
     * @param mayExist False if the caller knows [outputName] is not in [outputUri] yet; the file is
     * then created without looking it up.
     */
    fun getPermittedFileUri(outputUri: Uri, outputName: String, mimeType: String, mayExist: Boolean = true): Uri? {
        return getDocumentFile(outputUri)?.let { df ->
            if (df.isDirectory) {
                // One listing - findFile() costs an extra provider query per file in the folder.
                val file = if (mayExist) queryChildren(df.uri)[outputName]?.uri else null
                file ?: df.createFile(mimeType, outputName)?.uri
                // NOTE : build URI may not have access permission
            } else {
                df.uri
            }
        }
    }

    fun loadFile(uri: String): ByteArray {
        return context.contentResolver.openInputStream(uri.toUri())?.use {
            it.readBytes()
        } ?: throw FileNotFoundException()
    }

    /**
     * True if target exists.
     */
    fun existsFile(uri: Uri, name: String): Boolean {
        return getDocumentFile(uri)?.let { target ->
            if (target.isDirectory) {
                target.findFile(name)
            } else {
                target
            }?.let { file ->
                file.exists() && file.length() > 0
            }
        } ?: false
    }

    /**
     * Recursively collects all files under [sourceFolderUri], creating mirror subdirectories
     * under [targetDirUri] as needed. Returns a flat list of (sourceFileUri, targetDirUri) pairs
     * ready to feed into send pipeline.
     * [onFolder] is called on entering each folder with its path relative to [sourceFolderUri]
     * and the number of files found so far.
     */
    fun collectFilesRecursively(
        sourceFolderUri: Uri,
        targetDirUri: Uri,
        onFolder: (path: String, filesFound: Int) -> Unit = { _, _ -> },
    ): List<Pair<Uri, Uri>> {
        val sourceDir = DocumentFile.fromTreeUri(context, sourceFolderUri) ?: return emptyList()
        if (!DocumentsContract.isTreeUri(targetDirUri)) return emptyList()
        val targetDocUri = DocumentsContract.buildDocumentUriUsingTree(targetDirUri, targetDirUri.documentIdInTree())
        return mutableListOf<Pair<Uri, Uri>>().also { collectRecursive(sourceDir, targetDocUri, "", it, onFolder) }
    }

    /**
     * Name to size of the files directly under [dirUri].
     */
    fun listFileSizes(dirUri: Uri): Map<String, Long> {
        return queryChildren(dirUri).filterValues { !it.isDirectory }.mapValues { it.value.size }
    }

    private fun collectRecursive(
        sourceDir: DocumentFile,
        targetDirUri: Uri,
        path: String,
        result: MutableList<Pair<Uri, Uri>>,
        onFolder: (path: String, filesFound: Int) -> Unit,
    ) {
        onFolder(path, result.size)
        // Listed lazily, once per directory - not one findFile() (a full listing) per subdirectory.
        var targetChildren: Map<String, ChildDocument>? = null
        for (child in sourceDir.listFiles()) {
            when {
                child.isFile -> result.add(child.uri to targetDirUri)
                child.isDirectory -> {
                    val name = child.name ?: continue
                    val children = targetChildren ?: queryChildren(targetDirUri).also { targetChildren = it }
                    val subTargetUri = children[name]?.takeIf { it.isDirectory }?.uri
                        ?: createDirectory(targetDirUri, name)
                        ?: continue
                    collectRecursive(child, subTargetUri, if (path.isEmpty()) name else "$path/$name", result, onFolder)
                }
            }
        }
    }

    /**
     * Lists the children of tree document [dirUri] with a single queryChildDocuments call.
     * Going through DocumentFile.listFiles() instead costs an extra query per child for
     * every name / type / length read.
     */
    private fun queryChildren(dirUri: Uri): Map<String, ChildDocument> {
        if (!DocumentsContract.isTreeUri(dirUri)) return emptyMap()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, dirUri.documentIdInTree())
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        put(name, ChildDocument(
                            uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, documentId),
                            isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                        ))
                    }
                }
            } ?: emptyMap()
        } catch (e: Exception) {
            logE(e)
            emptyMap()
        }
    }

    private fun createDirectory(parentUri: Uri, name: String): Uri? {
        return try {
            DocumentsContract.createDocument(context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
        } catch (e: Exception) {
            logE(e)
            null
        }
    }

    private fun Uri.documentIdInTree(): String {
        return if (DocumentsContract.isDocumentUri(context, this)) {
            DocumentsContract.getDocumentId(this)
        } else {
            DocumentsContract.getTreeDocumentId(this)
        }
    }

    private data class ChildDocument(
        val uri: Uri,
        val isDirectory: Boolean,
        val size: Long,
    )

    fun deleteFile(targetUri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, targetUri)
        } catch (e: Exception) {
            logE(e)
            false
        }
    }

    /**
     * Send single file
     * @return False if canceled
     */
    suspend fun sendFile(
        sourceUri: Uri,
        targetUri: Uri,
        bufferSize: Int = BUFFER_SIZE,
        updateProgress: suspend (progressSize: Long) -> Boolean
    ): Boolean {
        val buffer = ByteArray(bufferSize)
        var progressSize = 0L
        (context.contentResolver.openInputStream(sourceUri) ?: return false).use { input ->
            (context.contentResolver.openOutputStream(targetUri)
                ?: return false).use { output ->
                while (true) {
                    val length = input.read(buffer)
                    if (length <= 0) break // End of Data
                    output.write(buffer, 0, length)
                    progressSize += length
                    if (!updateProgress(progressSize)) return false
                }
            }
        }
        return true
    }

    companion object {
        private const val OTHER_MIME_TYPE =  "application/octet-stream"
    }

}
