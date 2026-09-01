package com.driverremote.agent.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import com.driverremote.agent.util.AppLogger
import org.webrtc.*

class ScreenCaptureManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val peerConnectionFactory: PeerConnectionFactory
) {
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var isCapturing = false

    fun startCapture(
        projectionData: Intent,
        targetHeight: Int = 720,
        targetFps: Int = 30,
        onStoppedCallback: (() -> Unit)? = null
    ): VideoTrack? {
        if (isCapturing) return localVideoTrack

        try {
            val mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopCapture()
                    onStoppedCallback?.invoke()
                }
            }

            videoCapturer = ScreenCapturerAndroid(projectionData, mediaProjectionCallback)
            surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
            videoSource = peerConnectionFactory.createVideoSource(true)

            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)

            val (width, height) = calculateAdaptiveResolution(targetHeight)
            videoCapturer?.startCapture(width, height, targetFps)

            localVideoTrack = peerConnectionFactory.createVideoTrack(WebRTCConfig.VIDEO_TRACK_ID, videoSource).apply {
                setEnabled(true)
            }

            isCapturing = true
            return localVideoTrack
        } catch (e: Exception) {
            stopCapture()
            return null
        }
    }

    private fun calculateAdaptiveResolution(targetHeight: Int): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)

        val aspectRatio = displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels.toFloat()
        var calculatedHeight = targetHeight
        var calculatedWidth = (calculatedHeight * aspectRatio).toInt()

        if (calculatedWidth % 2 != 0) calculatedWidth += 1
        if (calculatedHeight % 2 != 0) calculatedHeight += 1
        return Pair(calculatedWidth, calculatedHeight)
    }

    fun stopCapture() {
        if (!isCapturing) return
        try { videoCapturer?.stopCapture() } catch (e: Exception) {}
        try { videoCapturer?.dispose() } catch (e: Exception) {}
        videoCapturer = null

        localVideoTrack?.dispose()
        localVideoTrack = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        isCapturing = false
    }

    fun isScreenCaptureActive(): Boolean = isCapturing
}