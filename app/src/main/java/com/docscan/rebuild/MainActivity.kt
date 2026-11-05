package com.docscan.rebuild

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import com.docscan.rebuild.ui.ScanApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF008F86), secondary = Color(0xFF006B63), background = Color(0xFFF7F9F9), surface = Color.White)) {
                ScanApp((application as ScanApplication).store)
            }
        }
    }
}
