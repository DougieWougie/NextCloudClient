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
}

data class DocumentFileInfo(
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String
)
