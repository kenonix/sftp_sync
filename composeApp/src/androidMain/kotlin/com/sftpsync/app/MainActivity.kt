package com.sftpsync.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.sftpsync.app.ui.App
import com.sftpsync.app.utils.AndroidContext
import com.sftpsync.app.utils.AndroidFolderPicker
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    // SAF folder picker launcher — registered before onCreate per Android lifecycle requirements
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContext.context = applicationContext

        // Register SAF folder tree picker and wire result back to AndroidFolderPicker
        folderPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            AndroidFolderPicker.onResult(uri, contentResolver)
        }

        // Expose launcher to the singleton so PlatformUtils can invoke it
        AndroidFolderPicker.launcher = folderPickerLauncher

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AndroidFolderPicker.launcher = null
    }
}
