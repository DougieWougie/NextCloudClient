package com.nextcloud.sync.controllers.fileops

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.utils.DocumentFileHelper
import com.nextcloud.sync.utils.FileOperationsHelper
import com.nextcloud.sync.utils.PathValidator
import com.nextcloud.sync.utils.SafeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller for file operations (view, download, delete, rename, move, copy).
 *
 * Follows the MVC pattern: This controller contains business logic and delegates
 * to appropriate helpers based on file type (remote vs local) and storage mode
 * (content URI vs file path).
 *
 * @property webDavClient Client for remote file operations
 * @property context Application context for content URIs
 */
class FileOperationsController(
    private val webDavClient: WebDavClient,
    private val context: Context
) {

    private val documentFileHelper = DocumentFileHelper(context)

    /**
     * Callback interface for file operations.
     * Provides async feedback to ViewModels.
     */
    interface FileOperationsCallback {
        fun onSuccess()
        fun onError(message: String)
        fun onProgress(current: Int, total: Int)
    }

    /**
     * Delete a file or folder.
     *
     * @param path File path (content URI, file path, or remote WebDAV path)
     * @param isLocal True for local file, false for remote
     * @param callback Operation callback
     */
    suspend fun deleteFile(path: String, isLocal: Boolean, callback: FileOperationsCallback) = withContext(Dispatchers.IO) {
        try {
            // Validate path
            if (path.isEmpty()) {
                callback.onError("Invalid path")
                return@withContext
            }

            val success = if (isLocal) {
                deleteLocalFile(path)
            } else {
                // Validate remote path
                if (PathValidator.validateRelativePath(path) == null) {
                    callback.onError("Invalid remote path")
                    return@withContext
                }
                webDavClient.deleteFile(path)
            }

            if (success) {
                callback.onSuccess()
            } else {
                callback.onError("Delete operation failed")
            }
        } catch (e: Exception) {
            SafeLogger.e("FileOperationsController", "Delete failed", e)
            callback.onError(e.message ?: "Delete failed")
        }
    }

    /**
     * Delete multiple files (batch operation).
     *
     * @param paths List of file paths
     * @param isLocal True for local files, false for remote
     * @param callback Operation callback with progress
     */
    suspend fun deleteFiles(paths: List<String>, isLocal: Boolean, callback: FileOperationsCallback) = withContext(Dispatchers.IO) {
        try {
            var successCount = 0
            val total = paths.size

            paths.forEachIndexed { index, path ->
                val success = if (isLocal) {
                    deleteLocalFile(path)
                } else {
                    if (PathValidator.validateRelativePath(path) != null) {
                        webDavClient.deleteFile(path)
                    } else {
                        false
                    }
                }

                if (success) {
                    successCount++
                }

                callback.onProgress(index + 1, total)
            }

            if (successCount == total) {
                callback.onSuccess()
            } else if (successCount > 0) {
                callback.onError("Deleted $successCount of $total files")
            } else {
                callback.onError("Delete operation failed")
            }
        } catch (e: Exception) {
            SafeLogger.e("FileOperationsController", "Batch delete failed", e)
            callback.onError(e.message ?: "Batch delete failed")
        }
    }

    /**
     * Rename a file or folder.
     *
     * @param oldPath Current path
     * @param newPath New path (should be in same directory, just different name)
     * @param isLocal True for local file, false for remote
     * @param callback Operation callback
     */
    suspend fun renameFile(oldPath: String, newPath: String, isLocal: Boolean, callback: FileOperationsCallback) = withContext(Dispatchers.IO) {
        try {
            // Extract just the filename from newPath for validation
            val newName = newPath.substringAfterLast('/')

            // Validate new name
            val sanitizedName = PathValidator.sanitizeFileName(newName)
            if (sanitizedName != newName) {
                callback.onError("Invalid filename: contains unsafe characters")
                return@withContext
            }

            val success = if (isLocal) {
                renameLocalFile(oldPath, newPath)
            } else {
                // Validate remote paths
                if (PathValidator.validateRelativePath(oldPath) == null || PathValidator.validateRelativePath(newPath) == null) {
                    callback.onError("Invalid path")
                    return@withContext
                }
                webDavClient.renameFile(oldPath, newPath)
            }

            if (success) {
                callback.onSuccess()
            } else {
                callback.onError("Rename operation failed")
            }
        } catch (e: Exception) {
            SafeLogger.e("FileOperationsController", "Rename failed", e)
            callback.onError(e.message ?: "Rename failed")
        }
    }

    /**
     * Move file(s) to a different directory.
     *
     * @param sourcePath Source file path
     * @param destPath Destination path (full path including filename)
     * @param isLocal True for local files, false for remote
     * @param callback Operation callback
     */
    suspend fun moveFile(sourcePath: String, destPath: String, isLocal: Boolean, callback: FileOperationsCallback) = withContext(Dispatchers.IO) {
        try {
            val success = if (isLocal) {
                moveLocalFile(sourcePath, destPath)
            } else {
                // Validate remote paths
                if (PathValidator.validateRelativePath(sourcePath) == null || PathValidator.validateRelativePath(destPath) == null) {
                    callback.onError("Invalid path")
                    return@withContext
                }
                webDavClient.moveFile(sourcePath, destPath)
            }

            if (success) {
                callback.onSuccess()
            } else {
                callback.onError("Move operation failed")
            }
        } catch (e: Exception) {
            SafeLogger.e("FileOperationsController", "Move failed", e)
            callback.onError(e.message ?: "Move failed")
        }
    }

    /**
     * Move multiple files (batch operation).
     */
    suspend fun moveFiles(paths: List<String>, destDir: String, isLocal: Boolean, callback: FileOperationsCallback) = withContext(Dispatchers.IO) {
        try {
            var successCount = 0
            val total = paths.size

            paths.forEachIndexed { index, sourcePath ->
                val fileName = sourcePath.substringAfterLast('/')
                val destPath = "$destDir/$fileName"

                val success = if (isLocal) {
                    moveLocalFile(sourcePath, destPath)
                } else {
                    if (PathValidator.validateRelativePath(sourcePath) != null && PathValidator.validateRelativePath(destPath) != null) {
                        webDavClient.moveFile(sourcePath, destPath)
                    } else {
                        false
                    }
                }

                if (success) {
                    successCount++
                }

                callback.onProgress(index + 1, total)
            }

            if (successCount == total) {
                callback.onSuccess()
            } else if (successCount > 0) {
                callback.onError("Moved $successCount of $total files")
            } else {
                callback.onError("Move operation failed")
            }
        } catch (e: Exception) {
            SafeLogger.e("FileOperationsController", "Batch move failed", e)
            callback.onError(e.message ?: "Batch move failed")
        }
    }

    /**
     * Copy a file or folder.
     *
     * @param sourcePath Source file path
     * @param destPath Destination path (full path including filename)
     * @param isLocal True for local files, false for remote
     * @param callback Operation callback
     */
    suspend fun copyFile(sourcePath: String, destPath: String, isLocal: Boolean, callback: FileOperationsCallback) = withContext(Dispatchers.IO) {
        try {
            val success = if (isLocal) {
                copyLocalFile(sourcePath, destPath)
            } else {
                // Validate remote paths
                if (PathValidator.validateRelativePath(sourcePath) == null || PathValidator.validateRelativePath(destPath) == null) {
                    callback.onError("Invalid path")
                    return@withContext
                }
                webDavClient.copyFile(sourcePath, destPath)
            }

            if (success) {
                callback.onSuccess()
            } else {
                callback.onError("Copy operation failed")
            }
        } catch (e: Exception) {
            SafeLogger.e("FileOperationsController", "Copy failed", e)
            callback.onError(e.message ?: "Copy failed")
        }
    }

    /**
     * Copy multiple files (batch operation).
     */
    suspend fun copyFiles(paths: List<String>, destDir: String, isLocal: Boolean, callback: FileOperationsCallback) = withContext(Dispatchers.IO) {
        try {
            var successCount = 0
            val total = paths.size

            paths.forEachIndexed { index, sourcePath ->
                val fileName = sourcePath.substringAfterLast('/')
                val destPath = "$destDir/$fileName"

                val success = if (isLocal) {
                    copyLocalFile(sourcePath, destPath)
                } else {
                    if (PathValidator.validateRelativePath(sourcePath) != null && PathValidator.validateRelativePath(destPath) != null) {
                        webDavClient.copyFile(sourcePath, destPath)
                    } else {
                        false
                    }
                }

                if (success) {
                    successCount++
                }

                callback.onProgress(index + 1, total)
            }

            if (successCount == total) {
                callback.onSuccess()
            } else if (successCount > 0) {
                callback.onError("Copied $successCount of $total files")
            } else {
                callback.onError("Copy operation failed")
            }
        } catch (e: Exception) {
            SafeLogger.e("FileOperationsController", "Batch copy failed", e)
            callback.onError(e.message ?: "Batch copy failed")
        }
    }

    /**
     * Download a remote file to local storage.
     *
     * @param remotePath Remote WebDAV path
     * @param localPath Local path (content URI or file path)
     * @param callback Operation callback
     */
    suspend fun downloadFile(remotePath: String, localPath: String, callback: FileOperationsCallback) = withContext(Dispatchers.IO) {
        try {
            // Validate remote path
            if (PathValidator.validateRelativePath(remotePath) == null) {
                callback.onError("Invalid remote path")
                return@withContext
            }

            val success = if (localPath.startsWith("content://")) {
                // Download to content URI
                val uri = Uri.parse(localPath)
                val outputStream = documentFileHelper.openOutputStream(uri)
                if (outputStream != null) {
                    val inputStream = webDavClient.getFileStream(remotePath)
                    if (inputStream != null) {
                        inputStream.use { input ->
                            outputStream.use { output ->
                                input.copyTo(output)
                            }
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            } else {
                // Download to file path
                val localFile = File(localPath)
                webDavClient.downloadFile(remotePath, localFile)
            }

            if (success) {
                callback.onSuccess()
            } else {
                callback.onError("Download failed")
            }
        } catch (e: Exception) {
            SafeLogger.e("FileOperationsController", "Download failed", e)
            callback.onError(e.message ?: "Download failed")
        }
    }

    // ============= Private Helper Methods =============

    private fun deleteLocalFile(path: String): Boolean {
        return if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            documentFile?.let { documentFileHelper.delete(it) } ?: false
        } else {
            FileOperationsHelper.deleteFile(File(path))
        }
    }

    private fun renameLocalFile(oldPath: String, newPath: String): Boolean {
        return if (oldPath.startsWith("content://")) {
            val uri = Uri.parse(oldPath)
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            val newName = newPath.substringAfterLast('/')
            documentFile?.let { documentFileHelper.renameFile(it, newName) } ?: false
        } else {
            val newName = newPath.substringAfterLast('/')
            FileOperationsHelper.renameFile(File(oldPath), newName)
        }
    }

    private fun moveLocalFile(sourcePath: String, destPath: String): Boolean {
        return if (sourcePath.startsWith("content://")) {
            val sourceUri = Uri.parse(sourcePath)
            val sourceFile = DocumentFile.fromSingleUri(context, sourceUri) ?: return false

            val destUri = Uri.parse(destPath)
            val destDir = DocumentFile.fromTreeUri(context, destUri) ?: return false

            documentFileHelper.moveFile(sourceFile, destDir) != null
        } else {
            FileOperationsHelper.moveFile(File(sourcePath), File(destPath))
        }
    }

    private fun copyLocalFile(sourcePath: String, destPath: String): Boolean {
        return if (sourcePath.startsWith("content://")) {
            val sourceUri = Uri.parse(sourcePath)
            val sourceFile = DocumentFile.fromSingleUri(context, sourceUri) ?: return false

            val destUri = Uri.parse(destPath)
            val destDir = DocumentFile.fromTreeUri(context, destUri) ?: return false

            documentFileHelper.copyFile(sourceFile, destDir) != null
        } else {
            FileOperationsHelper.copyFile(File(sourcePath), File(destPath))
        }
    }
}
