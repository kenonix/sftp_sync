package com.sftpsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sftpsync.app.ui.App
import com.sftpsync.app.utils.AndroidContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContext.context = applicationContext
        setContent {
            App()
        }
    }
}
