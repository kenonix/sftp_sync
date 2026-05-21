package com.sftpsync.app.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Singleton holder to store the Android application context.
 */
object AndroidContext {
    lateinit var context: Context
}

actual fun getPlatformName(): String = "Android"

actual fun getAppConfigDir(): String {
    return AndroidContext.context.filesDir.absolutePath
}

actual fun openFolderInExplorer(path: String) {
    try {
        val context = AndroidContext.context
        val file = java.io.File(path)
        if (!file.exists()) {
            file.mkdirs()
        }
        
        // Try launching via Action View with resource/folder type
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            val uri = android.net.Uri.parse(path)
            setDataAndType(uri, "resource/folder")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e1: Exception) {
        try {
            // Fallback 1: Open default android external storage files viewer
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"), "*/*")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AndroidContext.context.startActivity(intent)
        } catch (e2: Exception) {
            // Fallback 2: Toast notification
            Toast.makeText(AndroidContext.context, "폴더 경로: $path", Toast.LENGTH_LONG).show()
        }
    }
}

actual fun openFileInSystemViewer(path: String) {
    try {
        val file = java.io.File(path)
        if (!file.exists()) return
        
        val context = AndroidContext.context
        val content = file.readText()
        
        val sendIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, content)
            type = "text/plain"
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val shareIntent = android.content.Intent.createChooser(sendIntent, "로그 파일 보기/공유").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(shareIntent)
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(AndroidContext.context, "로그를 열 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

actual suspend fun pickFolder(initialPath: String?): String? {
    // For Android, we allow direct text path input with modern UI preset shortcuts
    // which is much more reliable and performant than SAF (Storage Access Framework)
    return null
}

actual fun writeTextFile(fileName: String, content: String) {
    java.io.File(getAppConfigDir(), fileName).writeText(content)
}

actual fun readTextFile(fileName: String): String? {
    val file = java.io.File(getAppConfigDir(), fileName)
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

actual fun checkManageStoragePermission(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        true
    }
}

actual fun requestManageStoragePermission() {
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = android.net.Uri.parse("package:${AndroidContext.context.packageName}")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AndroidContext.context.startActivity(intent)
        }
    } catch (e: Exception) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                AndroidContext.context.startActivity(intent)
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}
actual interface FileWatcherJob {
    actual fun cancel()
}

class AndroidFileWatcherJob(
    private val watchService: java.nio.file.WatchService,
    private val scope: CoroutineScope,
    private val job: Job
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

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
                        withContext(Dispatchers.Main) {
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

        return AndroidFileWatcherJob(watchService, scope, job)
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}
