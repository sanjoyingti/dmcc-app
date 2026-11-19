package com.dmcc26.app.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dmcc26.app.data.Watcher
import com.dmcc26.app.ui.DmccTheme
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_PEER_UID = "peerUid"
        const val EXTRA_PEER_NAME = "peerName"
        const val EXTRA_VIDEO = "video"

        fun outgoing(ctx: Context, peerUid: String, peerName: String, video: Boolean) {
            ctx.startActivity(Intent(ctx, CallActivity::class.java)
                .putExtra(EXTRA_PEER_UID, peerUid).putExtra(EXTRA_PEER_NAME, peerName).putExtra(EXTRA_VIDEO, video))
        }
    }

    private var pendingAction: (() -> Unit)? = null
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) pendingAction?.invoke()
        else { CallManager.hangup("Microphone/camera permission needed"); }
        pendingAction = null
    }

    private fun withPerms(video: Boolean, action: () -> Unit) {
        val need = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) action() else { pendingAction = action; perms.launch(need.toTypedArray()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handle(intent)
        setContent { DmccTheme { CallScreen(onAccept = { v -> withPerms(v) { CallManager.accept(this) } }, onClose = { finish() }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        val callId = i.getStringExtra(EXTRA_CALL_ID)
        val peer = i.getStringExtra(EXTRA_PEER_UID)
        if (!callId.isNullOrBlank()) {
            if (!CallManager.busy) CallManager.showIncoming(callId)
        } else if (!peer.isNullOrBlank()) {
            val video = i.getBooleanExtra(EXTRA_VIDEO, false)
            val name = i.getStringExtra(EXTRA_PEER_NAME) ?: ""
            withPerms(video) { CallManager.startOutgoing(this, peer, name, video) }
        }
    }

    override fun onResume() { super.onResume(); Watcher.inForeground = true }
    override fun onPause() { super.onPause(); Watcher.inForeground = false }
}

@Composable
private fun CallScreen(onAccept: (Boolean) -> Unit, onClose: () -> Unit) {
    val state by CallManager.state.collectAsState()
    val info by CallManager.info.collectAsState()
    val mic by CallManager.micOn.collectAsState()
    val cam by CallManager.camOn.collectAsState()
    val speaker by CallManager.speakerOn.collectAsState()
    val remote by CallManager.remoteVideo.collectAsState()
    val local by CallManager.localVideoFlow.collectAsState()
    val reason by CallManager.endReason.collectAsState()
    val since by CallManager.connectedAt.collectAsState()
    val tick = remember { mutableLongStateOf(0L) }

    val seenActive = remember { booleanArrayOf(false) }
    LaunchedEffect(state) {
        if (state != CallState.Idle && state != CallState.Ended) seenActive[0] = true
        if (state == CallState.Ended || (state == CallState.Idle && seenActive[0])) {
            delay(1200)
            if (CallManager.state.value == CallState.Idle || CallManager.state.value == CallState.Ended) onClose()
        }
        while (state == CallState.Connected) { tick.longValue = System.currentTimeMillis(); delay(1000) }
    }

    val video = info?.video == true
    Box(Modifier.fillMaxSize().background(Color(0xFF102418))) {
        if (video && remote != null) VideoView(remote!!, Modifier.fillMaxSize(), mirror = false)
        if (video && local != null && state != CallState.Ringing) {
            VideoView(local!!, Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(12.dp).size(110.dp, 160.dp), mirror = true, overlay = true)
        }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Text(info?.peerName ?: "", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            val status = when (state) {
                CallState.Ringing -> if (video) "Incoming video call" else "Incoming voice call"
                CallState.Calling -> "Ringing…"
                CallState.Connecting -> "Connecting…"
                CallState.Connected -> {
                    val s = ((tick.longValue - since).coerceAtLeast(0) / 1000)
                    "%02d:%02d".format(s / 60, s % 60)
                }
                CallState.Ended -> reason.ifBlank { "Call ended" }
                CallState.Idle -> ""
            }
            Text(status, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            if (state == CallState.Ringing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundBtn(Icons.Default.CallEnd, Color(0xFFD32F2F), "Decline") { CallManager.decline(it) }
                    RoundBtn(if (video) Icons.Default.Videocam else Icons.Default.Call, Color(0xFF2E7D32), "Accept") { onAccept(video) }
                }
            } else if (state != CallState.Ended && state != CallState.Idle) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundBtn(if (mic) Icons.Default.Mic else Icons.Default.MicOff, Color(0x55FFFFFF), if (mic) "Mute" else "Unmute") { CallManager.toggleMic() }
                    RoundBtn(Icons.AutoMirrored.Filled.VolumeUp, if (speaker) Color(0xFFF37021) else Color(0x55FFFFFF), "Speaker") { CallManager.setSpeaker(!speaker) }
                    if (video) {
                        RoundBtn(if (cam) Icons.Default.Videocam else Icons.Default.VideocamOff, Color(0x55FFFFFF), "Camera") { CallManager.toggleCam() }
                        RoundBtn(Icons.Default.Cameraswitch, Color(0x55FFFFFF), "Flip") { CallManager.switchCamera() }
                    }
                }
                Spacer(Modifier.height(24.dp))
                RoundBtn(Icons.Default.CallEnd, Color(0xFFD32F2F), "End") { CallManager.hangup() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, bg: Color, label: String, onClick: (Context) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = { onClick(ctx) },
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = bg, contentColor = Color.White),
        ) { Icon(icon, label, Modifier.size(30.dp)) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun VideoView(track: VideoTrack, modifier: Modifier, mirror: Boolean, overlay: Boolean = false) {
    val egl = CallManager.egl ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = remember(track) {
        SurfaceViewRenderer(ctx).apply {
            init(egl.eglBaseContext, null)
            setMirror(mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            if (overlay) setZOrderMediaOverlay(true)
        }
    }
    DisposableEffect(track) {
        track.addSink(view)
        onDispose {
            runCatching { track.removeSink(view) }
            runCatching { view.release() }
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
