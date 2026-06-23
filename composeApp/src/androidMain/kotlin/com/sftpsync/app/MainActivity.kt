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
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 전역 context 홀더에 Application Context를 주입하여 
        // 하위 백그라운드 서비스 및 플랫폼 Util 파일들이 안전하게 접근할 수 있도록 바인딩합니다.
        AndroidContext.context = applicationContext

        // Android 13 (API level 33) 이상 기기에서 백그라운드 포그라운드 서비스 알림이 정상 차단되지 않도록
        // 런타임 알림 승인 권한(POST_NOTIFICATIONS)을 앱 시작 시 사용자에게 자동 요청합니다.
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 101)
            }
        }

        // Storage Access Framework(SAF) 파일/폴더 선택 트리 런처를 등록하고 결과 수신 시 바인딩합니다.
        // Android 생명주기 요구사항에 맞춰 반드시 onCreate() 도중에 등록을 끝마쳐야 합니다.
        folderPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            AndroidFolderPicker.onResult(uri, contentResolver)
        }

        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            com.sftpsync.app.utils.AndroidFilePicker.onResult(uri)
        }

        // Expose launcher to the singleton so PlatformUtils can invoke it
        AndroidFolderPicker.launcher = folderPickerLauncher
        com.sftpsync.app.utils.AndroidFilePicker.launcher = filePickerLauncher

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AndroidFolderPicker.launcher = null
        com.sftpsync.app.utils.AndroidFilePicker.launcher = null
    }
}
