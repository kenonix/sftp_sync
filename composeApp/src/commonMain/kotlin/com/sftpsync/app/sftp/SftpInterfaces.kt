package com.sftpsync.app.sftp

import com.sftpsync.app.models.SyncFile

interface SftpClient {
    fun connect(): Boolean
    fun disconnect()
    val isConnected: Boolean
    fun listFiles(remotePath: String): List<SyncFile>
    fun downloadFile(remoteFilePath: String, localFilePath: String, onProgress: (Long) -> Unit): Boolean
    fun uploadFile(localFilePath: String, remoteFilePath: String, onProgress: (Long) -> Unit): Boolean
    fun deleteFile(remoteFilePath: String): Boolean
    fun createDirectory(remotePath: String): Boolean
    fun getFileLastModified(remoteFilePath: String): Long
    fun exists(remotePath: String): Boolean
}

interface LocalFileClient {
    fun listFiles(localPath: String): List<SyncFile>
    fun deleteFile(localFilePath: String): Boolean
    fun createDirectory(localPath: String): Boolean
    fun setLastModified(localFilePath: String, timestamp: Long): Boolean
    fun exists(localFilePath: String): Boolean
}
