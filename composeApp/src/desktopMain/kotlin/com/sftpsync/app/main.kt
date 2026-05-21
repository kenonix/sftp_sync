package com.sftpsync.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sftpsync.app.ui.App

fun main() = application {
    var isWindowVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(size = DpSize(960.dp, 640.dp))

    // Premium Cyberpunk system tray integration
    val trayIcon = rememberVectorPainter(Icons.Default.Sync)
    Tray(
        icon = trayIcon,
        tooltip = "SFTP BiSync - 백그라운드 자동 동기화중",
        onAction = { isWindowVisible = true }, // Restore window on double click/single click
        menu = {
            Item("SFTP BiSync 열기", onClick = { isWindowVisible = true })
            Separator()
            Item("애플리케이션 완전 종료", onClick = {
                exitApplication()
            })
        }
    )

    if (isWindowVisible) {
        Window(
            onCloseRequest = { isWindowVisible = false }, // Close window to minimize to tray
            state = windowState,
            title = "SFTP Bi-directional Synchronizer"
        ) {
            App()
        }
    }
}
