package com.sftpsync.app.utils

/**
 * Returns the platform name ("Android" or "Desktop").
 */
expect fun getPlatformName(): String

/**
 * Resolves a safe application configuration directory path to save configuration JSON files.
 */
expect fun getAppConfigDir(): String

/**
 * Opens a local folder in the platform's default file explorer.
 */
expect fun openFolderInExplorer(path: String)

/**
 * Opens a local file in the platform's default file viewer (e.g. text editor).
 */
expect fun openFileInSystemViewer(path: String)

/**
 * Triggers a platform-native folder picker and returns the absolute path if selected.
 * Note: On Android, this can return a directory path or custom folder path.
 */
expect suspend fun pickFolder(initialPath: String? = null): String?

/**
 * Writes a text file inside the application config directory.
 */
expect fun writeTextFile(fileName: String, content: String)

/**
 * Reads a text file from the application config directory.
 */
expect fun readTextFile(fileName: String): String?

/**
 * Factory function to create a LocalFileClient.
 */
expect fun createLocalFileClient(): com.sftpsync.app.sftp.LocalFileClient

/**
 * Factory function to create an SftpClient.
 */
expect fun createSftpClient(profile: com.sftpsync.app.models.SyncProfile): com.sftpsync.app.sftp.SftpClient

/**
 * Checks if the platform has all required storage permissions.
 */
expect fun checkManageStoragePermission(): Boolean

/**
 * Requests the system-level 'Manage External Storage' (All Files Access) permission.
 */
expect fun requestManageStoragePermission()

/**
 * Represents a running background directory watch job that can be cancelled.
 */
expect interface FileWatcherJob {
    fun cancel()
}

/**
 * Starts watching a local directory recursively for file changes (creation, modification, deletion).
 * Invokes the onChanged callback when changes are detected.
 * Returns a FileWatcherJob instance to control the watch lifecycle.
 */
expect fun startFileWatcher(
    profileId: String,
    localPath: String,
    onChanged: () -> Unit
): FileWatcherJob?

/**
 * Shuts down the entire application process cleanly or forcefully.
 */
expect fun exitApplicationProcess()

/**
 * Instructs the platform to start its background persistent service (if supported).
 */
expect fun startPlatformBackgroundService()

/**
 * Instructs the platform to stop its background persistent service (if supported).
 */
expect fun stopPlatformBackgroundService(): Unit

/**
 * Returns a formatted date string for conflict resolution (e.g. "20260623").
 */
expect fun getConflictTimestamp(): String

/**
 * Exports the JSON content of settings/profiles to a file.
 * Returns the destination path where it was saved, or null if cancelled/failed.
 */
expect suspend fun exportSettings(jsonContent: String): String?

/**
 * Imports settings JSON content from a selected file.
 * Returns the JSON content of the file, or null if cancelled/failed.
 */
expect suspend fun importSettings(): String?






