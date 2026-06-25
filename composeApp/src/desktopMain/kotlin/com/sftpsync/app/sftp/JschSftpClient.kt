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
    private var lastErrorMsg: String? = null

    override fun getLastError(): String? = lastErrorMsg

    override val isConnected: Boolean
        get() = session?.isConnected == true && channelSftp?.isConnected == true

    override fun connect(): Boolean {
        lastErrorMsg = null
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
                
                setTimeout(5000) // 5 seconds read timeout
                connect(5000) // 5 seconds connect timeout
            }

            val channel = session?.openChannel("sftp")
            channel?.connect(5000)
            channelSftp = channel as ChannelSftp
            
            return isConnected
        } catch (e: Exception) {
            e.printStackTrace()
            lastErrorMsg = e.message ?: e.toString()
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
        lastErrorMsg = null
        if (!isConnected && !connect()) return false
        val sftp = channelSftp ?: return false

        val localTempFile = java.io.File("$localFilePath.tmp")
        val localTargetFile = java.io.File(localFilePath)

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
            
            // Ensure local parent directories exist
            val parentFile = localTargetFile.parentFile
            if (parentFile != null && !parentFile.exists()) {
                parentFile.mkdirs()
            }
            
            sftp.get(remoteFilePath, localTempFile.absolutePath, monitor)
            
            if (localTargetFile.exists()) {
                localTargetFile.delete()
            }
            val ok = localTempFile.renameTo(localTargetFile)
            if (!ok) {
                if (localTempFile.exists()) {
                    localTempFile.delete()
                }
                lastErrorMsg = "임시 파일 이름을 변경하지 못했습니다."
                false
            } else {
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            lastErrorMsg = e.message ?: e.toString()
            if (localTempFile.exists()) {
                localTempFile.delete()
            }
            false
        }
    }

    override fun uploadFile(
        localFilePath: String,
        remoteFilePath: String,
        onProgress: (Long) -> Unit
    ): Boolean {
        lastErrorMsg = null
        if (!isConnected && !connect()) return false
        val sftp = channelSftp ?: return false

        val tempRemotePath = "$remoteFilePath.tmp"

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
            sftp.put(localFilePath, tempRemotePath, monitor)
            
            if (exists(remoteFilePath)) {
                deleteFile(remoteFilePath)
            }
            sftp.rename(tempRemotePath, remoteFilePath)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            lastErrorMsg = e.message ?: e.toString()
            try {
                if (exists(tempRemotePath)) {
                    sftp.rm(tempRemotePath)
                }
            } catch (ex: Exception) {}
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
        lastErrorMsg = null
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
            lastErrorMsg = e.message ?: e.toString()
            false
        }
    }

    override fun createDirectory(remotePath: String): Boolean {
        lastErrorMsg = null
        if (!isConnected && !connect()) return false
        val sftp = channelSftp ?: return false

        return try {
            sftp.mkdir(remotePath)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            lastErrorMsg = e.message ?: e.toString()
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

    override fun getFileHash(remoteFilePath: String): String? {
        val hash = getFileHashViaExec(remoteFilePath)
        if (hash != null) return hash
        
        // Streaming fallback can be extremely slow on large files.
        // Limit to <= 2MB.
        return try {
            val sftp = channelSftp ?: return null
            val attrs = sftp.stat(remoteFilePath)
            if (attrs.size <= 2 * 1024 * 1024) {
                getFileHashViaStreaming(remoteFilePath)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileHashViaExec(remoteFilePath: String): String? {
        val sess = session ?: return null
        if (!sess.isConnected) return null
        var channel: ChannelExec? = null
        return try {
            channel = sess.openChannel("exec") as ChannelExec
            val escapedPath = remoteFilePath.replace("'", "'\\''")
            channel.setCommand("sha256sum '$escapedPath' || shasum -a 256 '$escapedPath' || openssl dgst -sha256 '$escapedPath'")
            val inputStream = channel.inputStream
            channel.connect(5000)
            
            // 버그 #4 수정: 채널이 닫힐 때까지 대기한 후 스트림 읽기 (타임아웃 5초)
            val start = System.currentTimeMillis()
            while (!channel.isClosed && (System.currentTimeMillis() - start) < 5000) {
                Thread.sleep(50)
            }
            
            // 채널이 타임아웃 내에 닫히지 않으면 포기, 닫힌 후에만 안전하게 스트림 읽기
            if (!channel.isClosed) {
                null
            } else {
                val output = inputStream.bufferedReader().use { it.readText() }
                if (channel.exitStatus == 0) {
                    val parts = output.trim().split(Regex("\\s+"))
                    val first = parts.firstOrNull() ?: ""
                    if (first.length == 64 && first.all { it.isLetterOrDigit() }) {
                        first
                    } else {
                        val last = parts.lastOrNull() ?: ""
                        if (last.length == 64 && last.all { it.isLetterOrDigit() }) {
                            last
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                channel?.disconnect()
            } catch (e: Exception) {}
        }
    }

    private fun getFileHashViaStreaming(remoteFilePath: String): String? {
        if (!isConnected && !connect()) return null
        val sftp = channelSftp ?: return null
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            sftp.get(remoteFilePath).use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead = inputStream.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = inputStream.read(buffer)
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
