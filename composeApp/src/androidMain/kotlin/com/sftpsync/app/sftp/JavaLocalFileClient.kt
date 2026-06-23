package com.sftpsync.app.sftp

import com.sftpsync.app.models.SimpleSyncFile
import com.sftpsync.app.models.SyncFile
import java.io.File

class JavaLocalFileClient : LocalFileClient {

    override fun listFiles(localPath: String): List<SyncFile> {
        val root = File(localPath)
        if (!root.exists() || !root.isDirectory) return emptyList()

        val result = mutableListOf<SyncFile>()
        val rootUri = root.toURI()

        root.walk().forEach { file ->
            if (file.absolutePath == root.absolutePath) return@forEach
            
            // Calculate relative path
            val relativeUri = rootUri.relativize(file.toURI()).path
            // Remove trailing slash for directories
            val cleanRelativePath = if (relativeUri.endsWith("/")) {
                relativeUri.substring(0, relativeUri.length - 1)
            } else {
                relativeUri
            }

            result.add(SimpleSyncFile(
                relativePath = cleanRelativePath,
                size = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified(),
                isDirectory = file.isDirectory
            ))
        }

        return result
    }

    override fun deleteFile(localFilePath: String): Boolean {
        return File(localFilePath).delete()
    }

    override fun createDirectory(localPath: String): Boolean {
        return File(localPath).mkdirs()
    }

    override fun setLastModified(localFilePath: String, timestamp: Long): Boolean {
        return File(localFilePath).setLastModified(timestamp)
    }

    override fun exists(localFilePath: String): Boolean {
        return File(localFilePath).exists()
    }

    override fun getFileHash(localFilePath: String): String? {
        val file = File(localFilePath)
        if (!file.exists() || file.isDirectory) return null
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead = fis.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = fis.read(buffer)
                }
            }
            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
