package com.wa2c.android.cifsdocumentsprovider.data.storage.manager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.wa2c.android.cifsdocumentsprovider.common.exception.SendException
import com.wa2c.android.cifsdocumentsprovider.common.utils.getFileName
import com.wa2c.android.cifsdocumentsprovider.common.utils.logE
import com.wa2c.android.cifsdocumentsprovider.common.values.BUFFER_SIZE
import com.wa2c.android.cifsdocumentsprovider.data.storage.interfaces.StorageFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
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
                // Not df.createFile(): it swallows the provider's exception, and with it the reason
                file ?: DocumentsContract.createDocument(context.contentResolver, df.uri, mimeType, outputName)
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

    /**
     * Walks [localFolderUri] and [remoteFolderUri] side by side and lists every path found on
     * either side, without changing anything. Folders found on one side only are walked too.
     * A folder that cannot be listed is reported in [TreeComparison.failedFolders] and skipped,
     * since taking it as empty would make its contents look missing.
     * [onFolder] is called on entering each folder with its path relative to the roots and the
     * numbers of folders and paths seen so far.
     */
    fun compareTrees(
        localFolderUri: Uri,
        remoteFolderUri: Uri,
        onFolder: (path: String, folders: Int, paths: Int) -> Unit = { _, _, _ -> },
    ): TreeComparison {
        if (!DocumentsContract.isTreeUri(localFolderUri) || !DocumentsContract.isTreeUri(remoteFolderUri)) {
            return TreeComparison(failedFolders = listOf(""))
        }
        val entries = mutableListOf<TreeEntry>()
        val failedFolders = mutableListOf<String>()
        var folders = 0

        fun walk(localDirUri: Uri?, remoteDirUri: Uri?, path: String) {
            onFolder(path, ++folders, entries.size)
            // One listing per directory and side
            val local = localDirUri?.let { queryChildrenOrNull(it) ?: run { failedFolders += path; return } } ?: emptyMap()
            val remote = remoteDirUri?.let { queryChildrenOrNull(it) ?: run { failedFolders += path; return } } ?: emptyMap()
            for (name in local.keys + remote.keys) {
                if (name.isSyncTempName()) continue
                val l = local[name]
                val r = remote[name]
                val childPath = if (path.isEmpty()) name else "$path/$name"
                entries += TreeEntry(childPath, l?.toNode(), r?.toNode())
                if (l != null && r != null && l.isDirectory != r.isDirectory) continue
                if (l?.isDirectory == true || r?.isDirectory == true) {
                    walk(l?.uri, r?.uri, childPath)
                }
            }
        }

        walk(
            DocumentsContract.buildDocumentUriUsingTree(localFolderUri, localFolderUri.documentIdInTree()),
            DocumentsContract.buildDocumentUriUsingTree(remoteFolderUri, remoteFolderUri.documentIdInTree()),
            "",
        )
        return TreeComparison(entries, failedFolders)
    }

    private fun ChildDocument.toNode() = TreeNode(
        uri = uri,
        isDirectory = isDirectory,
        size = size,
        mimeType = mimeType,
        lastModified = lastModified,
    )

    /**
     * Finds the folders below tree [rootTreeUri] by their paths relative to it, creating the
     * missing ones if asked to, and remembers what it found.
     */
    inner class DirectoryResolver(rootTreeUri: Uri) {
        private val folders = mutableMapOf(
            "" to DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, rootTreeUri.documentIdInTree())
        )
        private val listings = mutableMapOf<Uri, Map<String, ChildDocument>>()

        fun resolve(path: String, create: Boolean = true): Uri? {
            folders[path]?.let { return it }
            val parent = resolve(path.substringBeforeLast('/', ""), create) ?: return null
            val name = path.substringAfterLast('/')
            val existing = listings.getOrPut(parent) { queryChildren(parent) }[name]?.takeIf { it.isDirectory }?.uri
            return (existing ?: if (create) createDirectory(parent, name) else null)?.also { folders[path] = it }
        }
    }

    /**
     * Moves the file or folder [uri] at [path] (relative to the root of [folders]) into
     * [TRASH_DIR]/[trashName]/ under the same root, keeping its relative path there.
     * @return False if it could not be moved
     */
    fun moveToTrash(folders: DirectoryResolver, path: String, uri: Uri, trashName: String): Boolean {
        return try {
            val parentPath = path.substringBeforeLast('/', "")
            val sourceParent = folders.resolve(parentPath, create = false) ?: return false
            val trashParent = folders.resolve(listOf(TRASH_DIR, trashName, parentPath).filter { it.isNotEmpty() }.joinToString("/"))
                ?: return false
            DocumentsContract.moveDocument(context.contentResolver, uri, sourceParent, trashParent) != null
        } catch (e: Exception) {
            logE(e)
            false
        }
    }

    /**
     * True if this app may create and overwrite files in tree [treeUri] - either granted
     * persistently or picked during this process.
     */
    fun canWriteTree(treeUri: Uri): Boolean {
        return DocumentFile.fromTreeUri(context, treeUri)?.canWrite() == true
    }

    /**
     * Creates an empty temporary file in [dirUri] to download [name] into, so that the existing
     * file of that name stays intact until the download is complete. See [replaceWithTemp].
     */
    fun createTempFile(dirUri: Uri, name: String): Uri? {
        return DocumentsContract.createDocument(context.contentResolver, dirUri, OTHER_MIME_TYPE, name + TEMP_SUFFIX)
    }

    /**
     * Puts completely written [tempUri] in place of the file [name] in [dirUri]. The old file is
     * moved aside first and put back if the temporary one cannot take its name.
     * @return URI of the replaced file
     */
    fun replaceWithTemp(dirUri: Uri, name: String, tempUri: Uri): Uri {
        val resolver = context.contentResolver
        val oldUri = queryChildren(dirUri)[name]?.uri?.let {
            DocumentsContract.renameDocument(resolver, it, name + OLD_SUFFIX) ?: it
        }
        val newUri = try {
            DocumentsContract.renameDocument(resolver, tempUri, name) ?: throw IOException("Cannot rename the download to $name")
        } catch (e: Exception) {
            oldUri?.let { runCatching { DocumentsContract.renameDocument(resolver, it, name) }.onFailure { logE(it) } }
            throw e
        }
        oldUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) }.onFailure { logE(it) } }
        return newUri
    }

    private fun String.isSyncTempName() = endsWith(TEMP_SUFFIX) || endsWith(OLD_SUFFIX) || this == TRASH_DIR

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
                    val name = child.name?.takeIf { it != TRASH_DIR } ?: continue
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
        return queryChildrenOrNull(dirUri) ?: emptyMap()
    }

    /** Like [queryChildren], but null if the listing failed. */
    private fun queryChildrenOrNull(dirUri: Uri): Map<String, ChildDocument>? {
        if (!DocumentsContract.isTreeUri(dirUri)) return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, dirUri.documentIdInTree())
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val mimeType = cursor.getString(2)
                        put(name, ChildDocument(
                            uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, documentId),
                            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                            mimeType = mimeType?.ifEmpty { null } ?: OTHER_MIME_TYPE,
                            lastModified = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            logE(e)
            null
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
        val mimeType: String,
        /** Milliseconds, 0 if unknown */
        val lastModified: Long,
    )

    /**
     * Size of document [uri] as its provider reports it, null if unknown.
     */
    fun getFileSize(uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            logE(e)
            null
        }
    }

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
        // Each step throws its own SendException so the failure can tell what went wrong
        val input = try {
            context.contentResolver.openInputStream(sourceUri)
        } catch (e: Exception) {
            throw SendException.ReadSource(e)
        } ?: throw SendException.ReadSource()
        input.use {
            val output = try {
                context.contentResolver.openOutputStream(targetUri)
            } catch (e: Exception) {
                throw SendException.WriteTarget(e)
            } ?: throw SendException.WriteTarget()
            output.use {
                while (true) {
                    val length = try { input.read(buffer) } catch (e: IOException) { throw SendException.ReadSource(e) }
                    if (length <= 0) break // End of Data
                    try { output.write(buffer, 0, length) } catch (e: IOException) { throw SendException.WriteTarget(e) }
                    progressSize += length
                    if (!updateProgress(progressSize)) return false
                }
            }
        }
        return true
    }

    companion object {
        private const val OTHER_MIME_TYPE =  "application/octet-stream"
        /** Suffix of a download in progress; such files are never synced themselves */
        const val TEMP_SUFFIX = ".cdpsync-part"
        /** Suffix of a file being replaced by a finished download */
        const val OLD_SUFFIX = ".cdpsync-old"
        /** Folder deleted files are moved into by a mirror sync, on the side they were deleted from */
        const val TRASH_DIR = ".cdpsync-trash"
    }

}
