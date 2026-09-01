package com.driverremote.agent.webrtc

import android.content.Context
import android.content.Intent
import com.driverremote.agent.util.AppLogger
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Collections

class WebRTCManager(
    private val context: Context,
    private val onIceCandidateGenerated: (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit,
    private val onLocalAnswerCreated: (sdp: String) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit,
    private val onDataChannelMessageReceived: ((message: String) -> Unit)? = null
) {
    private val TAG = "WebRTCManager"
    private val lock = Any()

    private val eglBase: EglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private val screenCaptureManager: ScreenCaptureManager

    private var localVideoTrack: VideoTrack? = null
    private var localVideoSender: RtpSender? = null
    private var activeDataChannel: DataChannel? = null

    private val pendingRemoteIceCandidates = Collections.synchronizedList(mutableListOf<IceCandidate>())
    @Volatile
    private var isRemoteDescriptionSet = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        screenCaptureManager = ScreenCaptureManager(context, eglBase, peerConnectionFactory)
    }

    fun startScreenCapture(
        projectionData: Intent,
        targetHeight: Int = WebRTCConfig.DEFAULT_TARGET_HEIGHT,
        targetFps: Int = WebRTCConfig.DEFAULT_FPS,
        onStopped: (() -> Unit)? = null
    ): Boolean {
        synchronized(lock) {
            val track = screenCaptureManager.startCapture(
                projectionData = projectionData,
                targetHeight = targetHeight,
                targetFps = targetFps,
                onStoppedCallback = onStopped
            )
            localVideoTrack = track

            if (track != null) {
                attachLocalVideoTrackIfNeeded()
                return true
            }
            return false
        }
    }

    fun stopScreenCapture() {
        synchronized(lock) {
            screenCaptureManager.stopCapture()
            localVideoTrack = null
            localVideoSender?.let { sender ->
                try { sender.setTrack(null, true) } catch (e: Exception) {}
            }
            localVideoSender = null
        }
    }

    fun isScreenCaptureActive(): Boolean = screenCaptureManager.isScreenCaptureActive()

    private fun attachLocalVideoTrackIfNeeded() {
        synchronized(lock) {
            val pc = peerConnection ?: return
            val track = localVideoTrack ?: return

            try {
                val existingSender = pc.senders.firstOrNull { it.track()?.kind() == "video" || it.id() == WebRTCConfig.VIDEO_TRACK_ID }
                if (existingSender != null) {
                    existingSender.setTrack(track, true)
                    localVideoSender = existingSender
                } else {
                    val sender = pc.addTrack(track, listOf(WebRTCConfig.VIDEO_STREAM_ID))
                    localVideoSender = sender
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to attach video track: ${e.message}", e)
            }
        }
    }

    private fun createOrGetPeerConnection(): PeerConnection? {
        synchronized(lock) {
            if (peerConnection != null) return peerConnection

            isRemoteDescriptionSet = false
            pendingRemoteIceCandidates.clear()

            val rtcConfig = PeerConnection.RTCConfiguration(WebRTCConfig.buildIceServers()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                enableDtlsSrtp = true
            }

            val pcObserver = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        onIceCandidateGenerated(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                    }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    if (newState != null) onConnectionStateChanged(newState)
                }
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}

                override fun onDataChannel(dataChannel: DataChannel?) {
                    if (dataChannel == null) return
                    activeDataChannel = dataChannel
                    dataChannel.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(previousAmount: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(buffer: DataChannel.Buffer) {
                            try {
                                val data: ByteBuffer = buffer.data
                                val bytes = ByteArray(data.remaining())
                                data.get(bytes)
                                val textMessage = String(bytes, StandardCharsets.UTF_8)
                                onDataChannelMessageReceived?.invoke(textMessage)
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "DataChannel message error: ${e.message}")
                            }
                        }
                    })
                }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
            }

            val pc = peerConnectionFactory.createPeerConnection(rtcConfig, pcObserver)
            peerConnection = pc
            attachLocalVideoTrackIfNeeded()
            return pc
        }
    }

    fun handleRemoteOffer(remoteSdpString: String) {
        synchronized(lock) {
            val pc = createOrGetPeerConnection() ?: return
            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, remoteSdpString)

            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    isRemoteDescriptionSet = true
                    synchronized(pendingRemoteIceCandidates) {
                        for (candidate in pendingRemoteIceCandidates) {
                            pc.addIceCandidate(candidate)
                        }
                        pendingRemoteIceCandidates.clear()
                    }
                    createAnswer()
                }
                override fun onSetFailure(error: String?) {
                    AppLogger.e(TAG, "Set Remote Description FAILED: $error")
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sessionDescription)
        }
    }

    private fun createAnswer() {
        val pc = peerConnection ?: return
        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(answerSdp: SessionDescription?) {
                if (answerSdp == null) return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        onLocalAnswerCreated(answerSdp.description)
                    }
                    override fun onSetFailure(error: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, answerSdp)
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, mediaConstraints)
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        val pc = peerConnection

        if (pc != null && isRemoteDescriptionSet) {
            pc.addIceCandidate(iceCandidate)
        } else {
            pendingRemoteIceCandidates.add(iceCandidate)
        }
    }

    fun sendDataChannelMessage(message: String): Boolean {
        val dc = activeDataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return try {
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(StandardCharsets.UTF_8)), false)
            dc.send(buffer)
        } catch (e: Exception) {
            false
        }
    }

    fun closePeerConnection() {
        synchronized(lock) {
            isRemoteDescriptionSet = false
            pendingRemoteIceCandidates.clear()
            activeDataChannel?.close()
            activeDataChannel = null
            try { localVideoSender?.setTrack(null, false) } catch (e: Exception) {}
            localVideoSender = null
            try { peerConnection?.dispose() } catch (e: Exception) {}
            peerConnection = null
        }
    }

    fun release() {
        synchronized(lock) {
            closePeerConnection()
            stopScreenCapture()
            try {
                peerConnectionFactory.dispose()
                eglBase.release()
            } catch (e: Exception) {}
        }
    }
}