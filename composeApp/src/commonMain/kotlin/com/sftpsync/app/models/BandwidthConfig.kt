package com.sftpsync.app.models

import kotlinx.serialization.Serializable

@Serializable
enum class BandwidthUnit {
    KBPS,
    MBPS
}

@Serializable
data class BandwidthConfig(
    val enabled: Boolean = false,
    val uploadLimitKbps: Long = 0L,
    val downloadLimitKbps: Long = 0L,
    val uploadUnit: BandwidthUnit = BandwidthUnit.MBPS,
    val downloadUnit: BandwidthUnit = BandwidthUnit.MBPS,
    val blockMeteredDownload: Boolean = false
) {
    val uploadLimitBytesPerSec: Long
        get() = if (enabled && uploadLimitKbps > 0L) uploadLimitKbps * 1024L else 0L

    val downloadLimitBytesPerSec: Long
        get() = if (enabled && downloadLimitKbps > 0L) downloadLimitKbps * 1024L else 0L

    fun formatUploadLimit(): String = formatKbps(uploadLimitKbps)
    fun formatDownloadLimit(): String = formatKbps(downloadLimitKbps)

    companion object {
        fun formatKbps(kbps: Long): String {
            if (kbps <= 0L) return "제한 없음"
            return if (kbps >= 1024L) {
                if (kbps % 1024L == 0L) {
                    "${kbps / 1024L} MB/s"
                } else {
                    String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024.0)
                }
            } else {
                "$kbps KB/s"
            }
        }
    }
}
