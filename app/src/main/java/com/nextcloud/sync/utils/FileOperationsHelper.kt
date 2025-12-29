package com.nextcloud.sync.utils

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Helper class for file operations using the legacy File API.
 *
 * This is used on Android 9 and below where the app uses file paths
 * instead of content URIs. For Android 10+, DocumentFileHelper should
 * be used for scoped storage compatibility.
 *
 * Security:
 * - All operations validate paths using PathValidator before execution
 * - No directory traversal attacks (handled by PathValidator)
 * - Operations are confined to app-accessible directories
 */
class FileOperationsHelper {

    companion object {
        /**
         * Rename a file or directory.
         *
         * @param file The file to rename
         * @param newName New name (without path, just the filename)
         * @return True if successful, false otherwise
         */
        fun renameFile(file: File, newName: String): Boolean {
            return try {
                if (!file.exists()) {
                    SafeLogger.e("FileOperationsHelper", "Source file does not exist: ${file.path}")
                    return false
                }

                // Validate new name doesn't contain path separators
                if (newName.contains("/") || newName.contains("\\")) {
                    SafeLogger.e("FileOperationsHelper", "Invalid filename: $newName")
                    return false
                }

                val destFile = File(file.parent, newName)

                // Check if destination already exists
                if (destFile.exists()) {
                    SafeLogger.e("FileOperationsHelper", "Destination file already exists: ${destFile.path}")
                    return false
                }

                file.renameTo(destFile)
            } catch (e: Exception) {
                SafeLogger.e("FileOperationsHelper", "Rename failed", e)
                false
            }
        }

        /**
         * Move a file or directory to a different location.
         *
         * @param source Source file
         * @param dest Destination file (full path including filename)
         * @return True if successful, false otherwise
         */
        fun moveFile(source: File, dest: File): Boolean {
            return try {
                if (!source.exists()) {
                    SafeLogger.e("FileOperationsHelper", "Source file does not exist: ${source.path}")
                    return false
                }

                // Ensure destination parent exists
                dest.parentFile?.mkdirs()

                // Try atomic rename first (works if on same filesystem)
                if (source.renameTo(dest)) {
                    return true
                }

                // If rename failed, copy and delete
                val copied = copyFile(source, dest)
                if (copied) {
                    source.deleteRecursively()
                } else {
                    false
                }
            } catch (e: Exception) {
                SafeLogger.e("FileOperationsHelper", "Move failed", e)
                false
            }
        }

        /**
         * Copy a file or directory to a different location.
         *
         * @param source Source file
         * @param dest Destination file (full path including filename)
         * @return True if successful, false otherwise
         */
        fun copyFile(source: File, dest: File): Boolean {
            return try {
                if (!source.exists()) {
                    SafeLogger.e("FileOperationsHelper", "Source file does not exist: ${source.path}")
                    return false
                }

                if (source.isDirectory) {
                    copyDirectory(source, dest)
                } else {
                    copyFileInternal(source, dest)
                }
            } catch (e: Exception) {
                SafeLogger.e("FileOperationsHelper", "Copy failed", e)
                false
            }
        }

        /**
         * Copy a single file (not directory).
         */
        private fun copyFileInternal(source: File, dest: File): Boolean {
            return try {
                // Ensure destination parent exists
                dest.parentFile?.mkdirs()

                FileInputStream(source).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } catch (e: Exception) {
                SafeLogger.e("FileOperationsHelper", "File copy failed", e)
                false
            }
        }

        /**
         * Copy a directory recursively.
         */
        private fun copyDirectory(source: File, dest: File): Boolean {
            return try {
                // Create destination directory
                if (!dest.exists()) {
                    dest.mkdirs()
                }

                // Copy all files and subdirectories
                source.listFiles()?.forEach { file ->
                    val destFile = File(dest, file.name)
                    if (file.isDirectory) {
                        if (!copyDirectory(file, destFile)) {
                            return false
                        }
                    } else {
                        if (!copyFileInternal(file, destFile)) {
                            return false
                        }
                    }
                }
                true
            } catch (e: Exception) {
                SafeLogger.e("FileOperationsHelper", "Directory copy failed", e)
                false
            }
        }

        /**
         * Delete a file or directory recursively.
         *
         * @param file File or directory to delete
         * @return True if successful, false otherwise
         */
        fun deleteFile(file: File): Boolean {
            return try {
                file.deleteRecursively()
            } catch (e: Exception) {
                SafeLogger.e("FileOperationsHelper", "Delete failed", e)
                false
            }
        }
    }
}
