package com.sftpsync.app.utils

import com.sftpsync.app.models.BandwidthConfig

/**
 * 토큰 버킷(Token Bucket) 알고리즘 기반의 전역 네트워크 대역폭 제한기입니다.
 *
 * 병렬 동기화(다중 SFTP 세션)가 활성화된 환경에서도 모든 스레드가 동일한 버킷을 공유하므로,
 * 전체 업로드 및 다운로드 합산 속도가 설정된 제한치를 초과하지 않도록 정밀하게 제어합니다.
 */
object BandwidthLimiter {

    @kotlin.concurrent.Volatile
    private var enabled: Boolean = false

    private val uploadBucket = TokenBucket()
    private val downloadBucket = TokenBucket()

    fun applyConfig(config: BandwidthConfig) {
        enabled = config.enabled
        uploadBucket.setLimit(if (config.enabled) config.uploadLimitBytesPerSec else 0L)
        downloadBucket.setLimit(if (config.enabled) config.downloadLimitBytesPerSec else 0L)
    }

    fun throttleUpload(bytes: Long) {
        if (!enabled || bytes <= 0L) return
        uploadBucket.throttle(bytes) { enabled }
    }

    fun throttleDownload(bytes: Long) {
        if (!enabled || bytes <= 0L) return
        downloadBucket.throttle(bytes) { enabled }
    }

    private class TokenBucket {
        @kotlin.concurrent.Volatile
        var limitBytesPerSec: Long = 0L
            private set

        private var availableTokens: Double = 0.0
        private var lastRefillNanos: Long = System.nanoTime()
        private val lock = Any()

        fun setLimit(bytesPerSec: Long) {
            synchronized(lock) {
                limitBytesPerSec = bytesPerSec
                availableTokens = 0.0
                lastRefillNanos = System.nanoTime()
            }
        }

        fun throttle(bytes: Long, isEnabled: () -> Boolean) {
            if (bytes <= 0L || !isEnabled() || limitBytesPerSec <= 0L) return

            var remaining = bytes
            while (remaining > 0L) {
                if (!isEnabled()) return
                val currentLimit = limitBytesPerSec
                if (currentLimit <= 0L) return

                // 한 번에 처리할 청크 크기: 제한 속도의 약 100ms 분량 (최소 1KB, 최대 16KB)
                val step = remaining.coerceAtMost((currentLimit / 10L).coerceIn(1024L, 16384L))

                val waitMs: Long = synchronized(lock) {
                    val activeLimit = limitBytesPerSec
                    if (activeLimit <= 0L || !isEnabled()) return

                    val now = System.nanoTime()
                    val elapsedSec = (now - lastRefillNanos).coerceAtLeast(0L) / 1_000_000_000.0
                    lastRefillNanos = now

                    // 최대 버스트 크기를 0.1초 분량(1KB ~ 64KB)으로 제한하여 초기 속도 급증 방지
                    val maxBurst = (activeLimit * 0.1).coerceIn(1024.0, 65536.0)
                    availableTokens = (availableTokens + elapsedSec * activeLimit).coerceAtMost(maxBurst)

                    availableTokens -= step.toDouble()
                    if (availableTokens < 0.0) {
                        val deficitBytes = -availableTokens
                        ((deficitBytes / activeLimit) * 1000.0).toLong()
                    } else {
                        0L
                    }
                }

                remaining -= step

                if (waitMs > 0L) {
                    var remainingSleep = waitMs
                    while (remainingSleep > 0L) {
                        if (!isEnabled() || limitBytesPerSec != currentLimit || limitBytesPerSec <= 0L) {
                            break
                        }
                        val slice = minOf(remainingSleep, 50L)
                        try {
                            Thread.sleep(slice)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                        remainingSleep -= slice
                    }
                }
            }
        }
    }
}
