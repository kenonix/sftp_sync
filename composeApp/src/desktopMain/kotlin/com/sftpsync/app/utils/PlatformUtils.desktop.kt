package com.sftpsync.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

actual fun getPlatformName(): String = "Desktop"

actual fun getAppConfigDir(): String {
    val appData = System.getenv("APPDATA")
    val baseDir = if (appData != null) {
        File(appData, "SftpSyncApp")
    } else {
        File(System.getProperty("user.home"), ".sftp_sync")
    }
    if (!baseDir.exists()) {
        baseDir.mkdirs()
    }
    return baseDir.absolutePath
}

actual fun openFolderInExplorer(path: String) {
    try {
        val file = File(path)
        if (file.exists() && java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().open(file)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

actual fun openFileInSystemViewer(path: String) {
    try {
        val file = File(path)
        if (file.exists() && java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().open(file)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

actual suspend fun pickFolder(initialPath: String?): String? = withContext(Dispatchers.IO) {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (e: Exception) {
        // Fallback to default Swing look and feel
    }
    
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "동기화할 로컬 폴더 선택"
        if (!initialPath.isNullOrEmpty()) {
            val initFile = File(initialPath)
            if (initFile.exists()) {
                currentDirectory = initFile
            }
        }
    }
    
    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}

actual fun writeTextFile(fileName: String, content: String) {
    File(getAppConfigDir(), fileName).writeText(content)
}

actual fun readTextFile(fileName: String): String? {
    val file = File(getAppConfigDir(), fileName)
    return if (file.exists()) file.readText() else null
}

actual fun createLocalFileClient(): com.sftpsync.app.sftp.LocalFileClient {
    return com.sftpsync.app.sftp.JavaLocalFileClient()
}

actual fun createSftpClient(profile: com.sftpsync.app.models.SyncProfile): com.sftpsync.app.sftp.SftpClient {
    return com.sftpsync.app.sftp.JschSftpClient(
        host = profile.host,
        port = profile.port,
        username = profile.username,
        authType = profile.authType,
        password = profile.password,
        privateKeyContent = profile.privateKeyContent,
        passphrase = profile.passphrase
    )
}

actual fun checkManageStoragePermission(): Boolean = true

actual fun requestManageStoragePermission() {}

actual interface FileWatcherJob {
    actual fun cancel()
}

class DesktopFileWatcherJob(
    private val watchService: java.nio.file.WatchService,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val job: kotlinx.coroutines.Job
) : FileWatcherJob {
    override fun cancel() {
        try {
            watchService.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        job.cancel()
    }
}

actual fun startFileWatcher(
    profileId: String,
    localPath: String,
    onChanged: () -> Unit
): FileWatcherJob? {
    try {
        val rootPath = java.nio.file.Paths.get(localPath)
        if (!java.nio.file.Files.exists(rootPath) || !java.nio.file.Files.isDirectory(rootPath)) {
            return null
        }

        val watchService = java.nio.file.FileSystems.getDefault().newWatchService()
        val keys = java.util.concurrent.ConcurrentHashMap<java.nio.file.WatchKey, java.nio.file.Path>()

        fun registerAll(start: java.nio.file.Path) {
            java.nio.file.Files.walkFileTree(start, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    val dirName = dir.fileName?.toString() ?: ""
                    if (dirName == ".git" || dirName == "node_modules" || dirName == ".gradle") {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }
                    try {
                        val key = dir.register(
                            watchService,
                            java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                            java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                            java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
                        )
                        keys[key] = dir
                    } catch (e: Exception) {
                        // Ignore failures on locked subdirs
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }

        registerAll(rootPath)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val job = scope.launch {
            try {
                while (isActive) {
                    val key = watchService.take() ?: break
                    val dir = keys[key] ?: continue

                    var hasValidChanges = false
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == java.nio.file.StandardWatchEventKinds.OVERFLOW) continue

                        val context = event.context() as? java.nio.file.Path ?: continue
                        val child = dir.resolve(context)
                        val fileName = child.fileName.toString()

                        // Ignore state files and common noise to avoid infinite loops
                        if (fileName == ".sftp-sync-state.json" || fileName.startsWith(".sftp-sync") || fileName == ".git" || fileName == "Thumbs.db" || fileName == ".DS_Store") {
                            continue
                        }

                        hasValidChanges = true

                        // Recursively monitor dynamically created subdirectories
                        if (kind == java.nio.file.StandardWatchEventKinds.ENTRY_CREATE) {
                            if (java.nio.file.Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                                registerAll(child)
                            }
                        }
                    }

                    val valid = key.reset()
                    if (!valid) {
                        keys.remove(key)
                        if (keys.isEmpty()) {
                            break
                        }
                    }

                    if (hasValidChanges) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onChanged()
                        }
                    }
                }
            } catch (e: java.nio.file.ClosedWatchServiceException) {
                // Ignore closing exception
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return DesktopFileWatcherJob(watchService, scope, job)
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}



