package com.dmcc26.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import com.dmcc26.app.data.Watcher
import com.dmcc26.app.ui.AppRoot
import com.dmcc26.app.ui.DmccTheme

class MainActivity : ComponentActivity() {
    /** A route to open (from a notification or an invite link). Consumed by the UI. */
    val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent { DmccTheme { AppRoot(pendingRoute) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("route")?.takeIf { it.isNotBlank() }?.let { pendingRoute.value = it }
        val data = i.data
        if (data != null && data.scheme == "dmcc26" && data.host == "join") {
            val code = data.pathSegments.firstOrNull() ?: ""
            pendingRoute.value = "join?code=$code"
        }
    }

    override fun onResume() { super.onResume(); Watcher.inForeground = true }
    override fun onPause() { super.onPause(); Watcher.inForeground = false }
}
