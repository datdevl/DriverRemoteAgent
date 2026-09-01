package com.driverremote.agent.signaling

import com.driverremote.agent.remote.models.CommandResult
import com.driverremote.agent.remote.models.TouchCommand
import com.driverremote.agent.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SignalingClient(private val scope: CoroutineScope) {
    private val TAG = "SignalingClient"
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var currentWebSocket: WebSocket? = null
    private var isManualDisconnect = false
    private val isConnectingOrConnected = AtomicBoolean(false)

    private var serverUrl: String = ""
    private var currentDeviceId: String = ""
    private var currentPairingCode: String = ""
    private var currentDeviceName: String = ""

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val reconnectDelays = listOf(1000L, 2000L, 4000L, 8000L, 10000L)

    private val _connectionState = MutableStateFlow(WebSocketState.DISCONNECTED)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()

    private val _messagesFlow = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val messagesFlow: SharedFlow<SignalingMessage> = _messagesFlow.asSharedFlow()

    fun connect(url: String, deviceId: String, pairingCode: String, deviceName: String) {
        if (url.isBlank()) return
        this.serverUrl = url.trim()
        this.currentDeviceId = deviceId
        this.currentPairingCode = pairingCode
        this.currentDeviceName = deviceName
        this.isManualDisconnect = false
        establishSocket()
    }

    private fun establishSocket() {
        if (isConnectingOrConnected.get()) return
        reconnectJob?.cancel()
        _connectionState.value = WebSocketState.CONNECTING

        try {
            val request = Request.Builder().url(serverUrl).build()
            isConnectingOrConnected.set(true)

            currentWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _connectionState.value = WebSocketState.CONNECTED
                    reconnectAttempt = 0
                    sendRegister()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingRawMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnectingOrConnected.set(false)
                    currentWebSocket = null
                    if (!isManualDisconnect) scheduleReconnect()
                    else _connectionState.value = WebSocketState.DISCONNECTED
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnectingOrConnected.set(false)
                    currentWebSocket = null
                    if (!isManualDisconnect) scheduleReconnect()
                    else _connectionState.value = WebSocketState.ERROR
                }
            })
        } catch (e: Exception) {
            isConnectingOrConnected.set(false)
            _connectionState.value = WebSocketState.ERROR
            if (!isManualDisconnect) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (isManualDisconnect) return
        val delayMs = reconnectDelays.getOrElse(reconnectAttempt) { 10000L }
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(reconnectDelays.size - 1)
        _connectionState.value = WebSocketState.RECONNECTING

        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (isActive && !isManualDisconnect) establishSocket()
        }
    }

    private fun handleIncomingRawMessage(text: String) {
        try {
            val jsonElement = jsonParser.parseToJsonElement(text) as? JsonObject ?: return
            val type = jsonElement["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                "registered" -> {
                    val response = jsonParser.decodeFromString<RegisteredResponse>(text)
                    _connectionState.value = WebSocketState.WAITING_FOR_CONTROLLER
                    scope.launch { _messagesFlow.emit(response) }
                }
                "controller_paired" -> {
                    val message = jsonParser.decodeFromString<ControllerPairedMessage>(text)
                    _connectionState.value = WebSocketState.PAIRED
                    scope.launch { _messagesFlow.emit(message) }
                }
                "controller_disconnected" -> {
                    val message = jsonParser.decodeFromString<ControllerDisconnectedMessage>(text)
                    _connectionState.value = WebSocketState.WAITING_FOR_CONTROLLER
                    scope.launch { _messagesFlow.emit(message) }
                }
                "device_offline" -> {
                    val message = jsonParser.decodeFromString<DeviceOfflineMessage>(text)
                    scope.launch { _messagesFlow.emit(message) }
                }
                "webrtc_offer" -> {
                    val message = jsonParser.decodeFromString<WebRtcOfferMessage>(text)
                    scope.launch { _messagesFlow.emit(message) }
                }
                "ice_candidate" -> {
                    val message = jsonParser.decodeFromString<IceCandidateMessage>(text)
                    scope.launch { _messagesFlow.emit(message) }
                }
                "command" -> {
                    val message = jsonParser.decodeFromString<CommandMessage>(text)
                    scope.launch { _messagesFlow.emit(message) }
                }
                "touch" -> {
                    val message = jsonParser.decodeFromString<TouchMessage>(text)
                    scope.launch { _messagesFlow.emit(message) }
                }
                "ping" -> {
                    val ping = jsonParser.decodeFromString<PingMessage>(text)
                    sendPong(ping.timestamp)
                }
                "unpair" -> {
                    val message = jsonParser.decodeFromString<UnpairMessage>(text)
                    _connectionState.value = WebSocketState.WAITING_FOR_CONTROLLER
                    scope.launch { _messagesFlow.emit(message) }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    fun sendRegister() {
        val registerMsg = RegisterMessage(deviceId = currentDeviceId, pairingCode = currentPairingCode, deviceName = currentDeviceName)
        sendMessage(jsonParser.encodeToString(registerMsg))
    }

    fun sendWebRtcAnswer(deviceId: String, sdpDescription: String) {
        val answer = WebRtcAnswerMessage(deviceId = deviceId, sdp = SdpPayload("answer", sdpDescription))
        sendMessage(jsonParser.encodeToString(answer))
    }

    fun sendIceCandidate(deviceId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val candidateMsg = IceCandidateMessage(deviceId = deviceId, candidate = IceCandidatePayload(sdpMid, sdpMLineIndex, candidate))
        sendMessage(jsonParser.encodeToString(candidateMsg))
    }

    fun sendDeviceState(state: DeviceStatePayload) {
        val message = DeviceStateMessage(deviceId = state.deviceId, batteryLevel = state.batteryLevel, networkType = state.networkType, signalStrength = state.signalStrength, isCharging = state.isCharging)
        sendMessage(jsonParser.encodeToString(message))
    }

    fun sendCommandResponse(result: CommandResult) {
        val response = CommandResponseMessage(result.command, result.success, result.message, result.isSupported, result.errorCode)
        sendMessage(jsonParser.encodeToString(response))
    }

    private fun sendPong(timestamp: Long) {
        sendMessage(jsonParser.encodeToString(PongMessage(timestamp = timestamp)))
    }

    fun updateStreamingState(isStreaming: Boolean) {
        if (isStreaming && _connectionState.value == WebSocketState.PAIRED) {
            _connectionState.value = WebSocketState.STREAMING
        } else if (!isStreaming && _connectionState.value == WebSocketState.STREAMING) {
            _connectionState.value = WebSocketState.PAIRED
        }
    }

    private fun sendMessage(json: String) {
        currentWebSocket?.send(json)
    }

    fun disconnect() {
        isManualDisconnect = true
        reconnectJob?.cancel()
        currentWebSocket?.close(1000, "Agent disconnect")
        currentWebSocket = null
        isConnectingOrConnected.set(false)
        _connectionState.value = WebSocketState.DISCONNECTED
    }
}