package com.nextcloud.sync.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

/**
 * Helper class to work with DocumentFile and content URIs from the Storage Access Framework
 */
class DocumentFileHelper(private val context: Context) {

    /**
     * Get DocumentFile from a tree URI
     */
    fun getDocumentFile(treeUri: String): DocumentFile? {
        return try {
            val uri = Uri.parse(treeUri)
            DocumentFile.fromTreeUri(context, uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * List all files recursively from a DocumentFile directory
     */
    fun listAllFiles(documentFile: DocumentFile): List<DocumentFileInfo> {
        val files = mutableListOf<DocumentFileInfo>()
        listFilesRecursive(documentFile, "", files)
        return files
    }

    private fun listFilesRecursive(
        directory: DocumentFile,
        currentPath: String,
        files: MutableList<DocumentFileInfo>
    ) {
        directory.listFiles().forEach { file ->
            val filePath = if (currentPath.isEmpty()) {
                file.name ?: ""
            } else {
                "$currentPath/${file.name ?: ""}"
            }

            if (file.isFile) {
                files.add(
                    DocumentFileInfo(
                        uri = file.uri,
                        name = file.name ?: "",
                        relativePath = filePath,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        mimeType = file.type ?: "application/octet-stream"
                    )
                )
            } else if (file.isDirectory) {
                listFilesRecursive(file, filePath, files)
            }
        }
    }

    /**
     * Open input stream for reading a file
     */
    fun openInputStream(uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Open output stream for writing a file
     */
    fun openOutputStream(uri: Uri): OutputStream? {
        return try {
            context.contentResolver.openOutputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create a new file in a directory
     */
    fun createFile(directory: DocumentFile, fileName: String, mimeType: String): DocumentFile? {
        return try {
            directory.createFile(mimeType, fileName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create a new directory
     */
    fun createDirectory(parent: DocumentFile, dirName: String): DocumentFile? {
        return try {
            parent.createDirectory(dirName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Find a file by relative path within a directory
     */
    fun findFileByPath(rootDirectory: DocumentFile, relativePath: String): DocumentFile? {
        if (relativePath.isEmpty()) return rootDirectory

        val parts = relativePath.split("/")
        var current = rootDirectory

        for (part in parts) {
            val found = current.listFiles().find { it.name == part }
            if (found == null) return null
            current = found
        }

        return current
    }

    /**
     * Create directories recursively to ensure path exists
     */
    fun ensureDirectoryPath(rootDirectory: DocumentFile, relativePath: String): DocumentFile? {
        if (relativePath.isEmpty()) return rootDirectory

        val parts = relativePath.split("/")
        var current = rootDirectory

        for (part in parts) {
            if (part.isEmpty()) continue

            val existing = current.listFiles().find { it.name == part && it.isDirectory }
            current = existing ?: createDirectory(current, part) ?: return null
        }

        return current
    }

    /**
     * Delete a file or directory
     */
    fun delete(documentFile: DocumentFile): Boolean {
        return try {
            documentFile.delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if a file exists at the given path
     */
    fun exists(rootDirectory: DocumentFile, relativePath: String): Boolean {
        return findFileByPath(rootDirectory, relativePath) != null
    }

    /**
     * Rename a file or directory.
     *
     * @param documentFile The file to rename
     * @param newName New name (without path)
     * @return True if successful, false otherwise
     */
    fun renameFile(documentFile: DocumentFile, newName: String): Boolean {
        return try {
            documentFile.renameTo(newName)
        } catch (e: Exception) {
            SafeLogger.e("DocumentFileHelper", "Rename failed", e)
            false
        }
    }

    /**
     * Move a file to a different directory by copying and deleting the original.
     * Note: DocumentFile doesn't support direct move, so we copy + delete.
     *
     * @param sourceFile The file to move
     * @param destDirectory The destination directory
     * @return The new DocumentFile if successful, null otherwise
     */
    fun moveFile(sourceFile: DocumentFile, destDirectory: DocumentFile): DocumentFile? {
        return try {
            // First copy the file
            val copiedFile = copyFile(sourceFile, destDirectory, null) ?: return null

            // Delete the original
            if (sourceFile.delete()) {
                copiedFile
            } else {
                // If delete failed, clean up the copy and return null
                copiedFile.delete()
                null
            }
        } catch (e: Exception) {
            SafeLogger.e("DocumentFileHelper", "Move failed", e)
            null
        }
    }

    /**
     * Copy a file to a different directory.
     * Creates a new file in the destination and copies content via streams.
     *
     * @param sourceFile The file to copy
     * @param destDirectory The destination directory
     * @param newName Optional new name (uses source name if null)
     * @return The new DocumentFile if successful, null otherwise
     */
    fun copyFile(sourceFile: DocumentFile, destDirectory: DocumentFile, newName: String? = null): DocumentFile? {
        return try {
            val fileName = newName ?: sourceFile.name ?: return null
            val mimeType = sourceFile.type ?: "application/octet-stream"

            // Create new file in destination
            val newFile = createFile(destDirectory, fileName, mimeType) ?: return null

            // Copy content
            openInputStream(sourceFile.uri)?.use { input ->
                openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }

            newFile
        } catch (e: Exception) {
            SafeLogger.e("DocumentFileHelper", "Copy failed", e)
            null
        }
    }
}

data class DocumentFileInfo(
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String
)
