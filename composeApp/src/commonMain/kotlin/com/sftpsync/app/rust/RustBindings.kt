package com.sftpsync.app.rust

/**
 * UniFFI(Rust)와 Kotlin 간에 교환되는 네이티브 레코드 및 인터페이스 정의입니다.
 */

enum class NativeConflictStrategy {
    NEWER_WINS,
    LOCAL_WINS,
    REMOTE_WINS,
    KEEP_BOTH
}

enum class NativeSyncCondition {
    TIME_DIFFERENT,
    TIME_AND_SIZE_DIFFERENT,
    SIZE_DIFFERENT
}

enum class NativeActionType {
    UPLOAD,
    DOWNLOAD,
    DELETE_LOCAL,
    DELETE_REMOTE,
    CONFLICT,
    NONE
}

data class NativeFileMetadata(
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val hash: String?
)

data class NativeSyncAction(
    val relativePath: String,
    val actionType: NativeActionType,
    val size: Long,
    val localModified: Long,
    val remoteModified: Long
)

data class NativeBandwidthConfig(
    val enabled: Boolean,
    val uploadLimitKbps: Long,
    val downloadLimitKbps: Long,
    val blockMeteredDownload: Boolean
)
