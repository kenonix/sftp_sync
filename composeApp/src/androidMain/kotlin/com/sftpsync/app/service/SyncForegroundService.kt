package com.sftpsync.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sftpsync.app.utils.exitApplicationProcess
import kotlinx.coroutines.*

/**
 * 실시간 양방향 동기화를 백그라운드 프로세스에서 지속하기 위한 Android 포그라운드 서비스(Foreground Service)입니다.
 * 
 * 포그라운드 알림을 활성화하여 앱이 백그라운드로 전환되거나 기기 화면이 꺼진(Doze) 상태에서도,
 * 시스템의 프로세스 정리 정책(OS Low Memory Killer)에 의해 강제 종료되지 않도록 보장합니다.
 * 백그라운드 환경에 맞게 스레드 최적화 및 비동기 동기화 런타임을 자체적으로 소유하고 동작시킵니다.
 */
class SyncForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "sftp_bisync_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_SHUTDOWN = "com.sftpsync.app.ACTION_SHUTDOWN"
    }

    // 서비스의 라이프사이클에 직접 바인딩된 백그라운드 비동기 루프 전용 Coroutine Scope입니다.
    // Dispatchers.Default 스레드 풀을 기반으로 비동기 동기화 엔진 및 폴링 작업이 분산 실행됩니다.
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 각 프로필별로 바인딩되어 활성화된 네이티브 디렉토리 변경 감시자(FileWatcherJob) 맵입니다.
    private val activeObservers = java.util.concurrent.ConcurrentHashMap<String, com.sftpsync.app.utils.FileWatcherJob>()

    // 각 프로필별로 원격 SFTP 서버 변경 상태를 확인하기 위해 주기적으로 실행되는 타이머 코루틴 Job 맵입니다.
    private val remotePollingJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    // 로컬 파일 변경 발생 시 무분별한 연속 트리거를 제어하기 위한 디바운스(Debounce) 코루틴 Job 맵입니다.
    private val debounceJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    override fun onCreate() {
        super.onCreate()
        // 오레오(API 26) 이상에서 필수가 된 포그라운드 서비스용 알림 채널 개설
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 사용자가 상단 알림창의 "즉시 완전 종료" 버튼을 눌렀을 경우 강제 정지 처리
        if (intent?.action == ACTION_SHUTDOWN) {
            exitApplicationProcess()
            stopSelf()
            return START_NOT_STICKY
        }

        // 초기 프리미엄 네온 바이올렛/시안 테마 알림창 생성 및 장착
        val notification = buildForegroundNotification("실시간 양방향 동기화 백그라운드 가동 중")

        // Android 10 (Q) 이상부터 도입된Foreground Service Type (dataSync) 규격 준수
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 서비스 진입 혹은 프로필이 갱신되어 다시 구동되었을 때, 최신 프로필 기준 파일 감시자 및 타이머 재장착
        reloadAutoSyncRoutines()

        // 시스템 메모리 부족 등으로 서비스가 강제 종료되어도 가능한 즉시 알아서 복구/부팅되도록 START_STICKY 반환
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Bound Service 형태로 컴포넌트 간 통신을 하지 않으므로 null을 반환합니다.
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        // 서비스 파괴 시 작동 중인 모든 파일 감시 시스템 해제 (이벤트 리소스 해제)
        activeObservers.values.forEach { it.cancel() }
        activeObservers.clear()
        
        // 원격지 검사용 폴링 타이머 중단
        remotePollingJobs.values.forEach { it.cancel() }
        remotePollingJobs.clear()
        
        // 디바운스 타이머 중단
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
        
        // 서비스 코루틴 영역을 취소하여 모든 활성 백그라운드 스레드를 즉각 해제
        serviceScope.cancel()
    }

    /**
     * 등록된 동기화 설정 파일을 로드하여, 실시간 동기화가 설정된 프로필들에 한해
     * 파일 변경 감시(FileObserver) 장착 및 서버 폴링 타이머를 구동합니다.
     */
    private fun reloadAutoSyncRoutines() {
        // 혹시 작동 중일 수 있는 이전 찌꺼기 감시 시스템들 우선 완전 초기화
        activeObservers.values.forEach { it.cancel() }
        activeObservers.clear()
        remotePollingJobs.values.forEach { it.cancel() }
        remotePollingJobs.clear()
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()

        // JSON 프로필 데이터 복원
        val profiles = com.sftpsync.app.utils.ProfileManager.loadProfiles()
        val autoSyncProfiles = profiles.filter { it.autoSyncEnabled }

        for (profile in autoSyncProfiles) {
            // 1. Android 네이티브 디렉토리 변경 감시자 바인딩
            val watcher = com.sftpsync.app.utils.startFileWatcher(profile.id, profile.localPath) {
                // 로컬 폴더 내의 변경 이벤트 수신 시 디바운스 트리거 실행
                triggerAutoSync(profile)
            }
            if (watcher != null) {
                activeObservers[profile.id] = watcher
            }

            // 2. 30초 단위 원격지 SFTP/Git 변경 체크 타이머 가동
            remotePollingJobs[profile.id] = serviceScope.launch {
                while (isActive) {
                    delay(30000) // 30초 주기 대기
                    
                    // 매 주기마다 최신 프로필 설정값 로드하여 설정 변경 여부 반영
                    val currentProfiles = com.sftpsync.app.utils.ProfileManager.loadProfiles()
                    val currentProfile = currentProfiles.firstOrNull { it.id == profile.id }
                    if (currentProfile != null && currentProfile.autoSyncEnabled) {
                        startBackgroundSync(currentProfile)
                    } else {
                        break // 비활성화 되었다면 루프 중단
                    }
                }
            }
        }
    }

    /**
     * 로컬 파일의 대량 생성/삭제/수정 등 이벤트가 동시다발적으로 일어날 때,
     * 매 이벤트마다 동기화 엔진을 실행하면 무한 루프나 부하가 걸리므로 3초 간 디바운스 처리를 가하여 일괄 수행합니다.
     */
    private fun triggerAutoSync(profile: com.sftpsync.app.models.SyncProfile) {
        debounceJobs[profile.id]?.cancel() // 신규 이벤트 추가 진입 시 이전 디바운스 타이머 파기
        debounceJobs[profile.id] = serviceScope.launch {
            delay(3000) // 3초 안정기 대기 (연속 파일 변경 병합용)
            
            val currentProfiles = com.sftpsync.app.utils.ProfileManager.loadProfiles()
            val currentProfile = currentProfiles.firstOrNull { it.id == profile.id }
            if (currentProfile != null && currentProfile.autoSyncEnabled) {
                startBackgroundSync(currentProfile)
            }
        }
    }

    /**
     * 실제 백그라운드 환경에서 각 프로필의 동기화 엔진(Git 또는 SFTP)을 비동기 구동합니다.
     */
    private fun startBackgroundSync(profile: com.sftpsync.app.models.SyncProfile) {
        // 이미 타 컴포넌트 혹은 다른 동기화 스케줄이 작동 중이면 무시 (동시 쓰기 레이스 차단)
        if (com.sftpsync.app.utils.SyncLock.isSyncing) return
        com.sftpsync.app.utils.SyncLock.isSyncing = true

        serviceScope.launch {
            try {
                // 알림창의 내용을 동기화 모드로 역동적으로 갱신
                updateNotification("동기화 진행 중: ${profile.name}")

                val localClient = com.sftpsync.app.utils.createLocalFileClient()

                if (profile.syncMode == com.sftpsync.app.models.SyncMode.GIT) {
                    // Git 동기화 모드
                    val gitClient = com.sftpsync.app.sftp.JGitClient(
                        repositoryPath = profile.localPath,
                        sshKeyPath = profile.gitSshKeyPath,
                        author = profile.gitCommitAuthor,
                        email = profile.gitCommitEmail,
                        remoteUrl = profile.gitRepositoryUrl,
                        branch = profile.gitBranch
                    )
                    val runner = com.sftpsync.app.sync.GitSyncEngineRunner(gitClient, localClient)
                    val lastState = com.sftpsync.app.utils.ProfileManager.loadGitState(profile.id)
                    val updatedState = runner.executeSync(
                        profile = profile,
                        lastState = lastState,
                        onProgress = { _, _ -> },
                        onLog = { log -> com.sftpsync.app.utils.ProfileManager.addLog(log) },
                        checkDirectoryApproval = { _, _ -> true } // 백그라운드에서는 팝업 대기를 하지 않고 즉시 자동 승인 처리
                    )
                    com.sftpsync.app.utils.ProfileManager.saveGitState(updatedState)
                } else {
                    // SFTP 양방향 동기화 모드
                    val sftpClient = com.sftpsync.app.utils.createSftpClient(profile)
                    val runner = com.sftpsync.app.sync.BiSyncEngineRunner(sftpClient, localClient)
                    val lastState = com.sftpsync.app.utils.ProfileManager.loadState(profile.id)
                    val updatedState = runner.executeSync(
                        profile = profile,
                        lastState = lastState,
                        onProgress = { _, _ -> },
                        onLog = { log -> com.sftpsync.app.utils.ProfileManager.addLog(log) },
                        checkDirectoryApproval = { _, _ -> true } // 백그라운드에서는 팝업 대기를 하지 않고 즉시 자동 승인 처리
                    )
                    com.sftpsync.app.utils.ProfileManager.saveState(updatedState)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 락을 반드시 해제하고 알림 내용을 상시 모드로 복구
                com.sftpsync.app.utils.SyncLock.isSyncing = false
                updateNotification("실시간 양방향 동기화 백그라운드 가동 중")
            }
        }
    }

    /**
     * 포그라운드 서비스의 상단 영구 노출 알림 객체를 생성합니다.
     */
    private fun buildForegroundNotification(contentText: String): Notification {
        val shutdownIntent = Intent(this, SyncForegroundService::class.java).apply {
            action = ACTION_SHUTDOWN
        }
        val shutdownPendingIntent = PendingIntent.getService(
            this,
            0,
            shutdownIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val mainActivityClass = try {
            Class.forName("com.sftpsync.app.MainActivity")
        } catch (e: Exception) {
            null
        }
        val contentPendingIntent = mainActivityClass?.let {
            val mainIntent = Intent(this, it).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            PendingIntent.getActivity(
                this,
                0,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SFTP BiSync")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setColor(0xFF00F0FF.toInt()) // 테크니컬하고 고급스러운 시안 계열의 브랜드 컬러
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "동기화 즉시 완전 종료",
                shutdownPendingIntent
            )
            .build()
    }

    /**
     * 실행 상태 변화에 맞게 알림 내용을 실시간 갱신합니다.
     */
    private fun updateNotification(contentText: String) {
        val notification = buildForegroundNotification(contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Android Oreo 이상 기기를 위해 OS 알림 제어용 시스템 채널을 개설합니다.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SFTP BiSync 서비스 알림",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "실시간 백그라운드 폴더 동기화를 유지하기 위한 알림입니다."
                enableLights(true)
                lightColor = 0xFF00F0FF.toInt()
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
