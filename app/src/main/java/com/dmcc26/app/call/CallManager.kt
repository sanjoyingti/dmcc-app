package com.dmcc26.app.call

import android.content.Context
import android.media.AudioManager
import com.dmcc26.app.data.Notifier
import com.dmcc26.app.data.Repo
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CallState { Idle, Ringing, Calling, Connecting, Connected, Ended }

data class CallInfo(val id: String, val peerUid: String, val peerName: String, val video: Boolean, val incoming: Boolean)

/** One-to-one voice/video calls over WebRTC, with Firestore used to exchange connection details. */
object CallManager {
    val state = MutableStateFlow(CallState.Idle)
    val info = MutableStateFlow<CallInfo?>(null)
    val micOn = MutableStateFlow(true)
    val camOn = MutableStateFlow(true)
    val speakerOn = MutableStateFlow(false)
    val remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideoFlow = MutableStateFlow<VideoTrack?>(null)
    val endReason = MutableStateFlow("")
    val connectedAt = MutableStateFlow(0L)

    val busy get() = state.value != CallState.Idle && state.value != CallState.Ended

    var egl: EglBase? = null
        private set
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var audioTrack: AudioTrack? = null
    private var localVideo: VideoTrack? = null
    private val regs = mutableListOf<ListenerRegistration>()
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private val pendingLocalIce = mutableListOf<IceCandidate>()
    private var remoteSet = false
    private var docReady = false
    private var mySide = "from"
    private var callId = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appCtx: Context? = null
    private var factoryInitDone = false

    private fun callRef(id: String) = Repo.db.collection("calls").document(id)

    private fun setup(ctx: Context, video: Boolean) {
        val app = ctx.applicationContext
        appCtx = app
        if (!factoryInitDone) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
            factoryInitDone = true
        }
        val e = EglBase.create()
        egl = e
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(app).createAudioDeviceModule())
            .createPeerConnectionFactory()
        val f = factory!!
        audioTrack = f.createAudioTrack("audio0", f.createAudioSource(MediaConstraints()))
        if (video) {
            val en = Camera2Enumerator(app)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull()
            if (name != null) {
                val cap = en.createCapturer(name, null)
                val helper = SurfaceTextureHelper.create("capture", e.eglBaseContext)
                val src = f.createVideoSource(false)
                cap.initialize(helper, app, src.capturerObserver)
                cap.startCapture(1280, 720, 30)
                capturer = cap
                surfaceHelper = helper
                videoSource = src
                localVideo = f.createVideoTrack("video0", src)
                localVideoFlow.value = localVideo
            }
        }
        micOn.value = true
        camOn.value = video
        val am = app.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(video)
    }

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val list = mutableListOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer(),
        )
        // Optional TURN relay (helps when calls connect but no audio): Firestore doc config/app {turnUrl, turnUser, turnPass}
        runCatching {
            val c = Repo.db.collection("config").document("app").get().await()
            val url = c.getString("turnUrl")
            if (!url.isNullOrBlank()) {
                list += PeerConnection.IceServer.builder(url)
                    .setUsername(c.getString("turnUser") ?: "")
                    .setPassword(c.getString("turnPass") ?: "")
                    .createIceServer()
            }
        }
        return list
    }

    private suspend fun createPeer() {
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                scope.launch {
                    when (s) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                            if (state.value != CallState.Connected) {
                                state.value = CallState.Connected
                                connectedAt.value = System.currentTimeMillis()
                            }
                        }
                        PeerConnection.IceConnectionState.FAILED -> hangup("Connection failed")
                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c == null) return
                scope.launch { if (docReady) sendIce(c) else pendingLocalIce += c }
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            override fun onTrack(t: RtpTransceiver?) {
                val track = t?.receiver?.track()
                if (track is VideoTrack) scope.launch { remoteVideo.value = track }
            }
        })
        val p = pc ?: error("Couldn't start the call")
        audioTrack?.let { p.addTrack(it, listOf("s0")) }
        localVideo?.let { p.addTrack(it, listOf("s0")) }
    }

    private fun sendIce(c: IceCandidate) {
        if (callId.isBlank()) return
        callRef(callId).collection("ice").add(mapOf(
            "side" to mySide, "sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp,
        ))
    }

    private fun listenRemoteIce() {
        val other = if (mySide == "from") "to" else "from"
        regs += callRef(callId).collection("ice").whereEqualTo("side", other).addSnapshotListener { s, _ ->
            s?.documentChanges?.forEach { ch ->
                val d = ch.document
                val c = IceCandidate(d.getString("sdpMid") ?: "", (d.getLong("sdpMLineIndex") ?: 0).toInt(), d.getString("candidate") ?: "")
                if (remoteSet) pc?.addIceCandidate(c) else pendingRemoteIce += c
            }
        }
    }

    private fun flushIce() {
        docReady = true
        pendingLocalIce.forEach { sendIce(it) }
        pendingLocalIce.clear()
    }

    private fun drainRemote() {
        remoteSet = true
        pendingRemoteIce.forEach { pc?.addIceCandidate(it) }
        pendingRemoteIce.clear()
    }

    private fun listenStatus() {
        regs += callRef(callId).addSnapshotListener { s, _ ->
            if (s == null || !s.exists()) return@addSnapshotListener
            val st = s.getString("status") ?: ""
            when (st) {
                "ended", "declined", "missed", "busy" -> if (state.value != CallState.Ended && state.value != CallState.Idle) {
                    finish(when (st) { "declined" -> "Call declined"; "missed" -> "No answer"; "busy" -> "Busy"; else -> "Call ended" })
                }
                "accepted" -> if (mySide == "from" && !remoteSet) {
                    val ans = s.get("answer") as? Map<*, *> ?: return@addSnapshotListener
                    scope.launch {
                        runCatching {
                            state.value = CallState.Connecting
                            pc?.setSdp(false, SessionDescription(SessionDescription.Type.ANSWER, ans["sdp"] as String))
                            drainRemote()
                        }.onFailure { hangup("Call failed") }
                    }
                }
            }
        }
    }

    private fun constraints(video: Boolean) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString()))
    }

    /** Starts a call to another app user. */
    fun startOutgoing(ctx: Context, peerUid: String, peerName: String, video: Boolean) {
        if (busy) return
        reset()
        mySide = "from"
        info.value = CallInfo("", peerUid, peerName, video, false)
        state.value = CallState.Calling
        scope.launch {
            try {
                setup(ctx, video)
                createPeer()
                val me = Repo.me()
                val ref = Repo.db.collection("calls").document()
                callId = ref.id
                info.value = CallInfo(ref.id, peerUid, peerName, video, false)
                val offer = pc!!.createSdp(true, constraints(video))
                pc!!.setSdp(true, offer)
                ref.set(mapOf(
                    "from" to me.uid, "fromName" to me.name, "to" to peerUid, "toName" to peerName,
                    "video" to video, "status" to "ringing", "at" to System.currentTimeMillis(),
                    "offer" to mapOf("type" to "offer", "sdp" to offer.description),
                )).await()
                flushIce()
                listenRemoteIce()
                listenStatus()
                delay(45_000)
                if (state.value == CallState.Calling) {
                    runCatching { ref.update("status", "missed").await() }
                    finish("No answer")
                }
            } catch (e: Exception) {
                finish("Call failed: ${e.message ?: "error"}")
            }
        }
    }

    /** Shows an incoming call (ringing screen). */
    fun showIncoming(id: String) {
        if (busy) return
        reset()
        callId = id
        mySide = "to"
        state.value = CallState.Ringing
        scope.launch {
            val d: DocumentSnapshot = try { callRef(id).get().await() } catch (e: Exception) { finish("Call ended"); return@launch }
            if (d.getString("status") != "ringing") { finish("Call ended"); return@launch }
            info.value = CallInfo(id, d.getString("from") ?: "", d.getString("fromName") ?: "Call", d.getBoolean("video") ?: false, true)
            listenStatus()
        }
    }

    fun accept(ctx: Context) {
        val i = info.value ?: return
        if (state.value != CallState.Ringing) return
        Notifier.cancelCall(ctx)
        state.value = CallState.Connecting
        scope.launch {
            try {
                val d = callRef(callId).get().await()
                val offer = d.get("offer") as? Map<*, *> ?: error("No offer")
                setup(ctx, i.video)
                createPeer()
                pc!!.setSdp(false, SessionDescription(SessionDescription.Type.OFFER, offer["sdp"] as String))
                drainRemote()
                listenRemoteIce()
                val ans = pc!!.createSdp(false, constraints(i.video))
                pc!!.setSdp(true, ans)
                callRef(callId).update(mapOf(
                    "status" to "accepted", "answer" to mapOf("type" to "answer", "sdp" to ans.description),
                )).await()
                flushIce()
            } catch (e: Exception) {
                hangup("Call failed: ${e.message ?: "error"}")
            }
        }
    }

    fun decline(ctx: Context) {
        Notifier.cancelCall(ctx)
        if (callId.isNotBlank()) callRef(callId).update("status", "declined")
        finish("Call declined")
    }

    fun hangup(reason: String = "Call ended") {
        if (callId.isNotBlank()) callRef(callId).update("status", "ended")
        finish(reason)
    }

    fun toggleMic() {
        micOn.value = !micOn.value
        audioTrack?.setEnabled(micOn.value)
    }

    fun toggleCam() {
        camOn.value = !camOn.value
        localVideo?.setEnabled(camOn.value)
    }

    fun switchCamera() { capturer?.switchCamera(null) }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        speakerOn.value = on
        appCtx?.getSystemService(AudioManager::class.java)?.isSpeakerphoneOn = on
    }

    @Suppress("DEPRECATION")
    private fun resetAudio() {
        val am = appCtx?.getSystemService(AudioManager::class.java) ?: return
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun reset() {
        regs.forEach { it.remove() }
        regs.clear()
        pendingLocalIce.clear()
        pendingRemoteIce.clear()
        remoteSet = false
        docReady = false
        callId = ""
        endReason.value = ""
        connectedAt.value = 0L
        remoteVideo.value = null
        localVideoFlow.value = null
    }

    private fun finish(reason: String) {
        endReason.value = reason
        state.value = CallState.Ended
        regs.forEach { it.remove() }
        regs.clear()
        appCtx?.let { Notifier.cancelCall(it) }
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        runCatching { pc?.dispose() }
        pc = null
        localVideo = null
        audioTrack = null
        remoteVideo.value = null
        localVideoFlow.value = null
        runCatching { factory?.dispose() }
        factory = null
        resetAudio()
        scope.launch {
            delay(1500)
            if (state.value == CallState.Ended) {
                runCatching { egl?.release() }
                egl = null
                state.value = CallState.Idle
                info.value = null
            }
        }
    }
}

private suspend fun PeerConnection.createSdp(offer: Boolean, c: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d != null) cont.resume(d) else cont.resumeWithException(IllegalStateException("Empty description"))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) { cont.resumeWithException(IllegalStateException(e ?: "create failed")) }
            override fun onSetFailure(e: String?) {}
        }
        if (offer) createOffer(obs, c) else createAnswer(obs, c)
    }

private suspend fun PeerConnection.setSdp(local: Boolean, d: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException(e ?: "set failed")) }
        }
        if (local) setLocalDescription(obs, d) else setRemoteDescription(obs, d)
    }
