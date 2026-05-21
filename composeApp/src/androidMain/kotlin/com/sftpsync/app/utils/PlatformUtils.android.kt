package com.sftpsync.app.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
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

/**
 * Bridge between MainActivity's ActivityResultLauncher and the suspend pickFolder function.
 * MainActivity registers the launcher; PlatformUtils.android calls it via this singleton.
 */
object AndroidFolderPicker {
    var launcher: androidx.activity.result.ActivityResultLauncher<android.net.Uri?>? = null
    private var pending: CompletableDeferred<Uri?>? = null

    /** Called by MainActivity when SAF picker returns a result. */
    fun onResult(uri: Uri?, contentResolver: ContentResolver) {
        pending?.complete(uri)
        pending = null
    }

    /** Launches the SAF folder tree picker and suspends until the user picks or cancels. */
    suspend fun pick(initialUri: Uri? = null): Uri? {
        val currentLauncher = launcher ?: return null
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        currentLauncher.launch(initialUri)
        return deferred.await()
    }
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
    return try {
        // Build initial URI hint from path if provided
        val initialUri: Uri? = if (!initialPath.isNullOrEmpty()) {
            val file = java.io.File(initialPath)
            if (file.exists()) {
                // Try to build a SAF URI hint for the initial directory
                try {
                    Uri.fromFile(file)
                } catch (e: Exception) { null }
            } else null
        } else null

        val resultUri = AndroidFolderPicker.pick(initialUri) ?: return null

        // Persist permission so we can access across restarts
        try {
            AndroidContext.context.contentResolver.takePersistableUriPermission(
                resultUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Not all URIs support persistable permissions — continue anyway
        }

        // Convert SAF tree URI → absolute filesystem path
        uriToAbsolutePath(resultUri, AndroidContext.context)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Converts a SAF content:// tree URI into an absolute filesystem path where possible.
 *
 * Handles three URI authorities:
 *  - primary internal storage  (com.android.externalstorage.documents, "primary")
 *  - SD card / adoptable storage (com.android.externalstorage.documents, volume ID)
 *  - Downloads provider
 */
private fun uriToAbsolutePath(uri: Uri, context: Context): String? {
    // Normalize tree URI → document URI
    val docUri = DocumentsContract.buildDocumentUriUsingTree(
        uri, DocumentsContract.getTreeDocumentId(uri)
    )

    val authority = docUri.authority ?: return null
    val docId = DocumentsContract.getDocumentId(docUri)

    return when {
        // ── Internal / SD card storage ──────────────────────────
        authority == "com.android.externalstorage.documents" -> {
            val parts = docId.split(":")
            val volumeId = parts.getOrNull(0) ?: return null
            val relativePath = parts.getOrNull(1) ?: ""

            if (volumeId.equals("primary", ignoreCase = true)) {
                // Internal storage
                val base = Environment.getExternalStorageDirectory().absolutePath
                if (relativePath.isEmpty()) base else "$base/$relativePath"
            } else {
                // SD card — locate the volume root
                val storageRoot = findSdCardRoot(context, volumeId)
                if (storageRoot != null) {
                    if (relativePath.isEmpty()) storageRoot else "$storageRoot/$relativePath"
                } else {
                    // Fallback: /storage/<volumeId>
                    val base = "/storage/$volumeId"
                    if (relativePath.isEmpty()) base else "$base/$relativePath"
                }
            }
        }

        // ── Downloads provider ───────────────────────────────────
        authority == "com.android.providers.downloads.documents" -> {
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ).absolutePath
        }

        // ── Last resort: file:// URIs ────────────────────────────
        uri.scheme == "file" -> uri.path

        else -> null
    }
}

/**
 * Searches mounted external storage volumes for a path matching the given volume ID.
 */
private fun findSdCardRoot(context: Context, volumeId: String): String? {
    return try {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageManager.storageVolumes
                .firstOrNull { vol ->
                    val uuid = vol.uuid
                    uuid != null && (uuid.equals(volumeId, ignoreCase = true) ||
                            uuid.replace("-", "").equals(volumeId.replace("-", ""), ignoreCase = true))
                }
                ?.let { vol ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        vol.directory?.absolutePath
                    } else {
                        // Reflection fallback for API 24-29
                        try {
                            vol.javaClass.getMethod("getPath").invoke(vol) as? String
                        } catch (e: Exception) { null }
                    }
                }
        } else null
    } catch (e: Exception) {
        null
    }
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

actual fun exitApplicationProcess() {
    try {
        stopPlatformBackgroundService()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    android.os.Process.killProcess(android.os.Process.myPid())
    java.lang.System.exit(0)
}

actual fun startPlatformBackgroundService() {
    try {
        val context = AndroidContext.context
        val intent = android.content.Intent(context, Class.forName("com.sftpsync.app.service.SyncForegroundService"))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

actual fun stopPlatformBackgroundService() {
    try {
        val context = AndroidContext.context
        val intent = android.content.Intent(context, Class.forName("com.sftpsync.app.service.SyncForegroundService"))
        context.stopService(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

