package com.driverremote.agent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.driverremote.agent.R
import com.driverremote.agent.device.DeviceStateManager
import com.driverremote.agent.remote.AndroidRemoteCommandHandler
import com.driverremote.agent.remote.TouchHandler
import com.driverremote.agent.remote.models.CommandResult
import com.driverremote.agent.remote.models.HardwareCommand
import com.driverremote.agent.remote.models.TouchCommand
import com.driverremote.agent.signaling.*
import com.driverremote.agent.ui.MainActivity
import com.driverremote.agent.util.AppLogger
import com.driverremote.agent.webrtc.WebRTCManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.webrtc.PeerConnection

class RemoteForegroundService : Service() {
    private val TAG = "RemoteForegroundService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()

    private lateinit var signalingClient: SignalingClient
    private lateinit var deviceStateManager: DeviceStateManager
    private lateinit var commandHandler: AndroidRemoteCommandHandler
    private lateinit var touchHandler: TouchHandler
    private var webRtcManager: WebRTCManager? = null

    private var currentDeviceId: String = ""
    private var currentPairingCode: String = ""
    private var currentServerUrl: String = ""
    private var currentDeviceName: String = ""
    private var targetFps: Int = 30
    private var targetResolutionHeight: Int = 720

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _pairedController = MutableStateFlow<String?>(null)
    val pairedController: StateFlow<String?> = _pairedController.asStateFlow()

    private val _lastLogEvent = MutableStateFlow<String>("Service ready")
    val lastLogEvent: StateFlow<String> = _lastLogEvent.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): RemoteForegroundService = this@RemoteForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        signalingClient = SignalingClient(serviceScope)
        deviceStateManager = DeviceStateManager(this, serviceScope)
        commandHandler = AndroidRemoteCommandHandler(this)
        touchHandler = TouchHandler(this)

        initWebRTC()
        observeSignalingMessages()
        observeConnectionState()
    }

    private fun initWebRTC() {
        webRtcManager = WebRTCManager(
            context = this,
            onIceCandidateGenerated = { sdpMid, sdpMLineIndex, candidate ->
                if (currentDeviceId.isNotBlank()) signalingClient.sendIceCandidate(currentDeviceId, sdpMid, sdpMLineIndex, candidate)
            },
            onLocalAnswerCreated = { sdpAnswer ->
                if (currentDeviceId.isNotBlank()) {
                    signalingClient.sendWebRtcAnswer(currentDeviceId, sdpAnswer)
                    _lastLogEvent.value = "WebRTC Answer sent to controller"
                }
            },
            onConnectionStateChanged = { state ->
                when (state) {
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> {
                        _isStreaming.value = false
                        signalingClient.updateStreamingState(false)
                        updateNotification(isStreaming = false)
                    }
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        _isStreaming.value = true
                        signalingClient.updateStreamingState(true)
                        updateNotification(isStreaming = true)
                    }
                    else -> {}
                }
            },
            onDataChannelMessageReceived = { rawData ->
                handleDataChannelIncomingMessage(rawData)
            }
        )
    }

    private fun observeConnectionState() {
        serviceScope.launch {
            signalingClient.connectionState.collect { state ->
                _lastLogEvent.value = "Signaling: $state"
                if (state == WebSocketState.WAITING_FOR_CONTROLLER || state == WebSocketState.CONNECTED) {
                    deviceStateManager.startMonitoring(currentDeviceId, intervalSeconds = 15)
                } else if (state == WebSocketState.DISCONNECTED || state == WebSocketState.ERROR) {
                    deviceStateManager.stopMonitoring()
                }
            }
        }

        deviceStateManager.setOnStateChangedListener { state ->
            if (signalingClient.connectionState.value != WebSocketState.DISCONNECTED) {
                signalingClient.sendDeviceState(state)
            }
        }
    }

    private fun handleDataChannelIncomingMessage(rawData: String) {
        serviceScope.launch {
            try {
                val jsonElement = jsonParser.parseToJsonElement(rawData) as? JsonObject
                if (jsonElement != null) {
                    val type = jsonElement["type"]?.jsonPrimitive?.content ?: ""
                    val action = jsonElement["action"]?.jsonPrimitive?.content
                    val command = jsonElement["command"]?.jsonPrimitive?.content

                    if (type == "touch" || action != null) {
                        val touchAction = action ?: "down"
                        val x = jsonElement["x"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val y = jsonElement["y"]?.jsonPrimitive?.floatOrNull ?: 0f
                        val pointerId = jsonElement["pointerId"]?.jsonPrimitive?.intOrNull ?: 0
                        val timestamp = jsonElement["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

                        val result = touchHandler.handleTouch(TouchCommand(touchAction, x, y, pointerId, timestamp))
                        dispatchCommandResult(result)
                    } else if (type == "command" || command != null) {
                        val cmdKey = command ?: ""
                        val hardwareCmd = HardwareCommand.fromString(cmdKey)
                        val result = if (hardwareCmd != null) commandHandler.execute(hardwareCmd)
                        else CommandResult.unsupported(cmdKey)
                        dispatchCommandResult(result)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error handling DataChannel: ${e.message}")
            }
        }
    }

    private fun dispatchCommandResult(result: CommandResult) {
        val sent = try {
            val jsonResult = jsonParser.encodeToString(result)
            webRtcManager?.sendDataChannelMessage(jsonResult) ?: false
        } catch (e: Exception) { false }

        if (!sent) {
            signalingClient.sendCommandResponse(result)
        }
        _lastLogEvent.value = "Command: ${result.command} -> ${result.message}"
    }

    private fun observeSignalingMessages() {
        serviceScope.launch {
            signalingClient.messagesFlow.collect { message ->
                when (message) {
                    is ControllerPairedMessage -> {
                        _pairedController.value = message.clientName
                        _lastLogEvent.value = "Paired with ${message.clientName} (Session: ${message.sessionId})"
                        updateNotification(isStreaming = _isStreaming.value)
                    }
                    is ControllerDisconnectedMessage -> {
                        _pairedController.value = null
                        _isStreaming.value = false
                        webRtcManager?.closePeerConnection()
                        signalingClient.updateStreamingState(false)
                        _lastLogEvent.value = "Controller disconnected: ${message.reason ?: "Session ended"}"
                        updateNotification(isStreaming = false)
                    }
                    is DeviceOfflineMessage -> {
                        _lastLogEvent.value = "Device offline: ${message.reason ?: message.deviceId}"
                    }
                    is WebRtcOfferMessage -> {
                        _lastLogEvent.value = "Received WebRTC Offer"
                        webRtcManager?.handleRemoteOffer(message.sdp.sdp)
                    }
                    is IceCandidateMessage -> {
                        webRtcManager?.addRemoteIceCandidate(message.candidate.sdpMid, message.candidate.sdpMLineIndex, message.candidate.candidate)
                    }
                    is CommandMessage -> {
                        serviceScope.launch {
                            val cmd = HardwareCommand.fromString(message.command)
                            val result = if (cmd != null) commandHandler.execute(cmd)
                            else CommandResult.unsupported(message.command)
                            dispatchCommandResult(result)
                        }
                    }
                    is TouchMessage -> {
                        serviceScope.launch {
                            val touchCmd = TouchCommand(message.action, message.x, message.y, message.pointerId, message.timestamp)
                            val result = touchHandler.handleTouch(touchCmd)
                            dispatchCommandResult(result)
                        }
                    }
                    is UnpairMessage -> {
                        _pairedController.value = null
                        _isStreaming.value = false
                        webRtcManager?.closePeerConnection()
                        signalingClient.updateStreamingState(false)
                        updateNotification(isStreaming = false)
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AGENT -> {
                currentDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
                currentPairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE) ?: ""
                currentServerUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
                currentDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Android Agent"
                targetFps = intent.getIntExtra(EXTRA_FPS, 30)
                targetResolutionHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)

                val notification = buildNotification(isStreaming = false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                signalingClient.connect(currentServerUrl, currentDeviceId, currentPairingCode, currentDeviceName)
            }
            ACTION_START_SCREEN_CAPTURE -> {
                val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }

                if (projectionData != null) {
                    val started = webRtcManager?.startScreenCapture(projectionData, targetResolutionHeight, targetFps) {
                        _isStreaming.value = false
                        signalingClient.updateStreamingState(false)
                        updateNotification(isStreaming = false)
                    } ?: false

                    if (started) {
                        _isStreaming.value = true
                        signalingClient.updateStreamingState(true)
                        updateNotification(isStreaming = true)
                    }
                }
            }
            ACTION_STOP_SCREEN_CAPTURE -> {
                webRtcManager?.stopScreenCapture()
                _isStreaming.value = false
                signalingClient.updateStreamingState(false)
                updateNotification(isStreaming = false)
            }
            ACTION_STOP_SERVICE -> stopAgentService()
        }
        return START_STICKY
    }

    private fun stopAgentService() {
        webRtcManager?.release()
        signalingClient.disconnect()
        deviceStateManager.stopMonitoring()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isStreaming: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, RemoteForegroundService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val contentText = if (isStreaming) "🔴 Streaming screen to paired controller" else "Connected to Signaling Server. Waiting for pairing..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_stop_action), stopPendingIntent)
            .build()
    }

    private fun updateNotification(isStreaming: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isStreaming))
    }

    fun getSignalingClient(): SignalingClient = signalingClient

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webRtcManager?.release()
        signalingClient.disconnect()
        deviceStateManager.stopMonitoring()
    }

    companion object {
        const val CHANNEL_ID = "driver_remote_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_AGENT = "com.driverremote.agent.ACTION_START_AGENT"
        const val ACTION_START_SCREEN_CAPTURE = "com.driverremote.agent.ACTION_START_SCREEN_CAPTURE"
        const val ACTION_STOP_SCREEN_CAPTURE = "com.driverremote.agent.ACTION_STOP_SCREEN_CAPTURE"
        const val ACTION_STOP_SERVICE = "com.driverremote.agent.ACTION_STOP_SERVICE"

        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_PAIRING_CODE = "extra_pairing_code"
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
    }
}