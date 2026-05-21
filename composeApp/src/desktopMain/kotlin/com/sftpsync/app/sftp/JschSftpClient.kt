package com.sftpsync.app.sftp

import com.jcraft.jsch.*
import com.sftpsync.app.models.AuthType
import com.sftpsync.app.models.SimpleSyncFile
import com.sftpsync.app.models.SyncFile
import java.io.ByteArrayInputStream
import java.util.*

class JschSftpClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val authType: AuthType,
    private val password: String = "",
    private val privateKeyContent: String? = null,
    private val passphrase: String? = null
) : SftpClient {

    private var jsch = JSch()
    private var session: Session? = null
    private var channelSftp: ChannelSftp? = null

    override val isConnected: Boolean
        get() = session?.isConnected == true && channelSftp?.isConnected == true

    override fun connect(): Boolean {
        try {
            if (isConnected) return true
            
            jsch = JSch()
            
            if (authType == AuthType.PRIVATE_KEY && !privateKeyContent.isNullOrEmpty()) {
                val prvKeyBytes = privateKeyContent.toByteArray()
                val passBytes = passphrase?.toByteArray()
                jsch.addIdentity("sftp_key", prvKeyBytes, null, passBytes)
            }

            session = jsch.getSession(username, host, port).apply {
                if (authType == AuthType.PASSWORD) {
                    setPassword(password)
                }
                
                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                // Support older or newer algorithms automatically
                config["PreferredAuthentications"] = "publickey,keyboard-interactive,password"
                setConfig(config)
                
                connect(15000) // 15 seconds timeout
            }

            val channel = session?.openChannel("sftp")
            channel?.connect(15000)
            channelSftp = channel as ChannelSftp
            
            return isConnected
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            return false
        }
    }

    override fun disconnect() {
        try {
            channelSftp?.disconnect()
        } catch (e: Exception) {}
        try {
            session?.disconnect()
        } catch (e: Exception) {}
        channelSftp = null
        session = null
    }

    override fun listFiles(remotePath: String): List<SyncFile> {
        if (!isConnected && !connect()) return emptyList()
        
        val result = mutableListOf<SyncFile>()
        try {
            // Clean remote path to avoid double slashes
            val cleanRoot = remotePath.trimEnd('/')
            listRecursive(cleanRoot, "", result)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private fun listRecursive(basePath: String, currentPath: String, result: MutableList<SyncFile>) {
        val sftp = channelSftp ?: return
        val fullPath = if (currentPath.isEmpty()) basePath else "$basePath/$currentPath"
        
        val list = sftp.ls(fullPath)
        for (entry in list) {
            val sftpEntry = entry as ChannelSftp.LsEntry
            val filename = sftpEntry.filename
            if (filename == "." || filename == "..") continue
            
            val entryRelativePath = if (currentPath.isEmpty()) filename else "$currentPath/$filename"
            val attrs = sftpEntry.attrs
            
            if (attrs.isDir) {
                // Directories themselves are recorded but skipped in file comparisons
                result.add(SimpleSyncFile(
                    relativePath = entryRelativePath,
                    size = 0L,
                    lastModified = attrs.mTime.toLong() * 1000L, // seconds to ms
                    isDirectory = true
                ))
                // Recurse
                listRecursive(basePath, entryRelativePath, result)
            } else {
                result.add(SimpleSyncFile(
                    relativePath = entryRelativePath,
                    size = attrs.size,
                    lastModified = attrs.mTime.toLong() * 1000L,
                    isDirectory = false
                ))
            }
        }
    }

    override fun downloadFile(
        remoteFilePath: String,
        localFilePath: String,
        onProgress: (Long) -> Unit
    ): Boolean {
        if (!isConnected && !connect()) return false
        val sftp = channelSftp ?: return false

        return try {
            val monitor = object : SftpProgressMonitor {
                private var bytesTransferred = 0L
                override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                override fun count(count: Long): Boolean {
                    bytesTransferred += count
                    onProgress(bytesTransferred)
                    return true
                }
                override fun end() {}
            }
            sftp.get(remoteFilePath, localFilePath, monitor)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun uploadFile(
        localFilePath: String,
        remoteFilePath: String,
        onProgress: (Long) -> Unit
    ): Boolean {
        if (!isConnected && !connect()) return false
        val sftp = channelSftp ?: return false

        return try {
            ensureRemoteParentDirsExist(remoteFilePath)
            
            val monitor = object : SftpProgressMonitor {
                private var bytesTransferred = 0L
                override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                override fun count(count: Long): Boolean {
                    bytesTransferred += count
                    onProgress(bytesTransferred)
                    return true
                }
                override fun end() {}
            }
            sftp.put(localFilePath, remoteFilePath, monitor)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun ensureRemoteParentDirsExist(remoteFilePath: String) {
        val sftp = channelSftp ?: return
        val parts = remoteFilePath.split("/")
        var current = ""
        // Loop through everything except the filename
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            if (part.isEmpty()) continue
            current += "/$part"
            try {
                sftp.mkdir(current)
            } catch (e: Exception) {
                // If it already exists, JSch throws SftpException, which we can safely ignore
            }
        }
    }

    override fun deleteFile(remoteFilePath: String): Boolean {
        if (!isConnected && !connect()) return false
        val sftp = channelSftp ?: return false

        return try {
            try {
                sftp.rm(remoteFilePath)
            } catch (e: Exception) {
                // Might be a directory
                sftp.rmdir(remoteFilePath)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun createDirectory(remotePath: String): Boolean {
        if (!isConnected && !connect()) return false
        val sftp = channelSftp ?: return false

        return try {
            sftp.mkdir(remotePath)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun getFileLastModified(remoteFilePath: String): Long {
        if (!isConnected && !connect()) return System.currentTimeMillis()
        val sftp = channelSftp ?: return System.currentTimeMillis()
        return try {
            val attrs = sftp.stat(remoteFilePath)
            attrs.mTime.toLong() * 1000L
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    override fun exists(remotePath: String): Boolean {
        if (!isConnected && !connect()) return false
        val sftp = channelSftp ?: return false
        return try {
            sftp.stat(remotePath)
            true
        } catch (e: Exception) {
            false
        }
    }
}
