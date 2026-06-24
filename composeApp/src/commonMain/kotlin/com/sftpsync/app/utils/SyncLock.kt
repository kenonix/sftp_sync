package com.sftpsync.app.utils

/**
 * 전역 동기화 락(Lock) 싱글톤 객체입니다.
 * 
 * Android의 백그라운드 서비스(SyncForegroundService)와 포그라운드 UI(SyncViewModel)가
 * 동시에 동일한 프로필이나 파일들에 대해 동기화 작업을 수행하는 것을 방지하여,
 * 파일 쓰기 충돌이나 레이스 컨디션(Race Condition)을 안전하게 예방합니다.
 */
import kotlinx.coroutines.flow.MutableStateFlow

object SyncLock {
    val isSyncingFlow = MutableStateFlow(false)

    @kotlin.concurrent.Volatile
    private var _isSyncing: Boolean = false

    var isSyncing: Boolean
        get() = _isSyncing
        set(value) {
            _isSyncing = value
            isSyncingFlow.value = value
        }
}
