package com.example

import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.DiagnosticsScreen
import com.example.ui.theme.WebViewDiagnosticsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure WebView remote debugging stays explicitly disabled at process startup.
        WebView.setWebContentsDebuggingEnabled(false)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            WebViewDiagnosticsTheme {
                DiagnosticsScreen()
            }
        }
    }
}
