package com.sftpsync.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sftpsync.app.ui.App
import com.sftpsync.app.ui.viewmodel.SyncViewModel
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import com.sftpsync.app.utils.readTextFile
import com.sftpsync.app.utils.writeTextFile
import dorkbox.systemTray.SystemTray
import dorkbox.systemTray.MenuItem

fun main() {
    // Check if the current OS is Linux to selectively apply the AppIndicator / GTK 3 workaround
    val osName = System.getProperty("os.name", "").lowercase()
    val isLinux = osName.contains("nix") || osName.contains("nux") || osName.contains("aix")

    if (isLinux) {
        // Set system properties for JVM/Swing/AWT GTK 3 compliance at startup
        System.setProperty("swing.gtk.version", "3")
        System.setProperty("jdk.gtk.version", "3")
        System.setProperty("SystemTray.FORCE_LINUX_TYPE", "2")
        System.setProperty("SystemTray.FORCE_GTK2", "false")
        System.setProperty("SystemTray.PREFER_GTK3", "true")

        // Configure SystemTray static fields directly via reflection to avoid compile-time dependency differences
        try {
            val clazz = Class.forName("dorkbox.systemTray.SystemTray")

            // Try to set FORCE_TRAY_TYPE
            try {
                val field = clazz.getField("FORCE_TRAY_TYPE")
                val trayTypeClass = Class.forName("dorkbox.systemTray.SystemTray\$TrayType")
                val appIndicatorVal = trayTypeClass.getField("AppIndicator").get(null)
                field.set(null, appIndicatorVal)
            } catch (ignored: Throwable) {}

            // Try to set FORCE_GTK2
            try {
                val field = clazz.getField("FORCE_GTK2")
                field.set(null, false)
            } catch (ignored: Throwable) {}

            // Try to set PREFER_GTK3
            try {
                val field = clazz.getField("PREFER_GTK3")
                field.set(null, true)
            } catch (ignored: Throwable) {}
        } catch (ignored: Throwable) {}
    }

    application {
        val viewModel = remember { SyncViewModel() }
        var isWindowVisible by remember { mutableStateOf(true) }
        val windowState = rememberWindowState(size = DpSize(960.dp, 640.dp))
        var uiScale by remember {
            mutableStateOf(
                try {
                    val saved = readTextFile("ui_scale.txt")
                    saved?.trim()?.toFloatOrNull() ?: 1.0f
                } catch (e: Exception) {
                    1.0f
                }
            )
        }

        // Initialize dorkbox SystemTray for cross-platform AppIndicator / Wayland compatibility
        val systemTray = remember {
            try {
                println("Attempting SystemTray.get()...")
                val tray = SystemTray.get()
                if (tray == null) {
                    println("SystemTray.get() returned null!")
                } else {
                    println("SystemTray.get() successfully created: $tray")
                    try {
                        val menuField = tray.javaClass.getDeclaredField("menu")
                        menuField.isAccessible = true
                        val menuObj = menuField.get(tray)
                        println("Active SystemTray Implementation: ${menuObj.javaClass.name}")
                    } catch (e: Throwable) {
                        println("Could not read menu field: ${e.message}")
                    }
                }
                tray?.apply {
                    status = "SFTP BiSync"
                    val iconUrl = object {}.javaClass.getResource("/icon.png")
                    if (iconUrl != null) {
                        setImage(iconUrl)
                    }
                    menu.add(MenuItem("SFTP BiSync 열기") {
                        isWindowVisible = true
                    })
                    menu.add(MenuItem("애플리케이션 완전 종료") {
                        viewModel.dispose()
                        shutdown()
                        exitApplication()
                    })
                }
            } catch (e: Throwable) {
                println("CRITICAL: Exception during SystemTray.get()!")
                e.printStackTrace()
                null
            }
        }

        Window(
            onCloseRequest = {
                if (systemTray != null) {
                    isWindowVisible = false // Minimize to system tray (invisible)
                } else {
                    viewModel.dispose()
                    exitApplication() // Safe fallback if system tray is not available
                }
            },
            state = windowState,
            title = "SFTP Bi-directional Synchronizer",
            visible = isWindowVisible,
            onPreviewKeyEvent = { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.isCtrlPressed) {
                    when (keyEvent.key) {
                        Key.Equals, Key.Plus, Key.NumPadAdd -> {
                            val newScale = (uiScale + 0.1f).coerceIn(0.5f, 3.0f)
                            if (newScale != uiScale) {
                                uiScale = newScale
                                try {
                                    writeTextFile("ui_scale.txt", newScale.toString())
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            true
                        }
                        Key.Minus, Key.NumPadSubtract -> {
                            val newScale = (uiScale - 0.1f).coerceIn(0.5f, 3.0f)
                            if (newScale != uiScale) {
                                uiScale = newScale
                                try {
                                    writeTextFile("ui_scale.txt", newScale.toString())
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
        ) {
            val originalDensity = LocalDensity.current
            val scaledDensity = remember(originalDensity, uiScale) {
                Density(
                    density = originalDensity.density * uiScale,
                    fontScale = originalDensity.fontScale * uiScale
                )
            }
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                App(viewModel = viewModel)
            }
        }
    }
}
