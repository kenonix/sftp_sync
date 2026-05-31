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

/**
 * Android 네이티브 [android.os.FileObserver]를 사용하여 디렉토리를 재귀적으로 감시하는 클래스입니다.
 * 
 * 기존 Java NIO의 [WatchService]가 Android 외부 저장소(FUSE/sdcardfs) 환경에서
 * 파일 변경 이벤트를 유실하거나 배터리를 과다 소모하는 한계를 극복하기 위해 설계되었습니다.
 * 리눅스 커널의 inotify 시스템을 직접 활용하여 신속하고 높은 신뢰성의 파일 감시를 보장합니다.
 */
class AndroidRecursiveFileObserver(
    private val rootPath: String,
    private val onChanged: () -> Unit
) : FileWatcherJob {
    // 모든 하위 폴더별로 등록할 FileObserver 객체들을 유지하는 리스트입니다.
    // (GC에 의해 해제되는 것을 방지하기 위해 강한 참조로 수집하여 유지합니다.)
    private val observers = mutableListOf<android.os.FileObserver>()

    init {
        // 감시 시작
        startWatching()
    }

    /**
     * 감시 대상 루트 폴더로부터 모든 하위 폴더를 스캔하여 각각 FileObserver를 장착하고 작동시킵니다.
     */
    private fun startWatching() {
        val rootFile = java.io.File(rootPath)
        // 디렉토리가 존재하지 않거나 디렉토리가 아닌 경우 즉시 반환
        if (!rootFile.exists() || !rootFile.isDirectory) return

        // 감시할 파일 이벤트 마스크 설정 (생성, 수정, 삭제, 이동)
        val mask = android.os.FileObserver.CREATE or
                   android.os.FileObserver.MODIFY or
                   android.os.FileObserver.DELETE or
                   android.os.FileObserver.MOVED_TO or
                   android.os.FileObserver.MOVED_FROM

        // Kotlin 표준 라이브러리의 walkTopDown()을 활용하여 하위 폴더들을 재귀적으로 스캔
        rootFile.walkTopDown().forEach { file ->
            if (file.isDirectory) {
                val dirName = file.name
                // 대용량 개발용 폴더나 형상관리 폴더는 모니터링 대상에서 배제하여 불필요한 이벤트 유발 차단
                if (dirName != ".git" && dirName != "node_modules" && dirName != ".gradle") {
                    try {
                        // API 26-28 하위 호환성을 위해 문자열 경로 생성자를 활용 (DEPRECATION 경고 무시)
                        val observer = @Suppress("DEPRECATION") object : android.os.FileObserver(file.absolutePath, mask) {
                            override fun onEvent(event: Int, path: String?) {
                                if (path != null) {
                                    // 내부 동기화 상태 파일이나 시스템이 생성하는 불필요한 메타데이터 파일 무시
                                    if (path == ".sftp-sync-state.json" || path.startsWith(".sftp-sync") || path == ".git" || path == "Thumbs.db" || path == ".DS_Store") {
                                        return
                                    }
                                }
                                // 파일 시스템 변경 감지 시 콜백을 실행하여 백그라운드 동기화 트리거
                                onChanged()
                            }
                        }
                        observer.startWatching()
                        observers.add(observer)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    /**
     * 모든 하위 디렉토리에 걸려있던 파일 감시 동작을 멈추고 메모리를 정리합니다.
     */
    override fun cancel() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }
}

/**
 * 플랫폼별 파일 시스템 변경 감시 라이프사이클을 생성하는 [startFileWatcher] 실제 구현체입니다.
 */
actual fun startFileWatcher(
    profileId: String,
    localPath: String,
    onChanged: () -> Unit
): FileWatcherJob? {
    return try {
        // Android 환경에 완벽히 호환되는 재귀형 네이티브 FileObserver 생성 후 반환
        AndroidRecursiveFileObserver(localPath, onChanged)
    } catch (e: Exception) {
        e.printStackTrace()
        null
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

