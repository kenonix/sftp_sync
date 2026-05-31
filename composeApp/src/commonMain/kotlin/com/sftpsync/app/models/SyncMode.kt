package com.sftpsync.app.models

import kotlinx.serialization.Serializable

@Serializable
enum class SyncMode {
    SFTP,
    GIT
}
