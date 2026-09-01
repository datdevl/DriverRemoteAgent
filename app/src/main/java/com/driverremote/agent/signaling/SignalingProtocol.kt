package com.driverremote.agent.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

@Serializable
data class SdpPayload(val type: String, val sdp: String)

@Serializable
data class IceCandidatePayload(val sdpMid: String? = null, val sdpMLineIndex: Int = 0, val candidate: String)

@Serializable
data class DeviceStatePayload(
    val deviceId: String,
    val batteryLevel: Int,
    val networkType: String,
    val signalStrength: Int,
    val isCharging: Boolean
)

@Serializable
sealed interface SignalingMessage

@Serializable
@SerialName("register")
data class RegisterMessage(
    val type: String = "register",
    val role: String = "agent",
    val deviceId: String,
    val pairingCode: String,
    val deviceName: String
) : SignalingMessage

@Serializable
@SerialName("registered")
data class RegisteredResponse(
    val type: String = "registered",
    val role: String = "agent",
    val clientId: String,
    val deviceId: String
) : SignalingMessage

@Serializable
@SerialName("controller_paired")
data class ControllerPairedMessage(
    val type: String = "controller_paired",
    val sessionId: String,
    val deviceId: String,
    val clientName: String = "DRIVER REMOTE Web Controller (PWA)"
) : SignalingMessage

@Serializable
@SerialName("controller_disconnected")
data class ControllerDisconnectedMessage(
    val type: String = "controller_disconnected",
    val sessionId: String? = null,
    val deviceId: String? = null,
    val reason: String? = null
) : SignalingMessage

@Serializable
@SerialName("device_offline")
data class DeviceOfflineMessage(
    val type: String = "device_offline",
    val deviceId: String? = null,
    val reason: String? = null
) : SignalingMessage

@Serializable
@SerialName("pair")
data class PairMessage(
    val type: String = "pair",
    val deviceId: String,
    val pairingCode: String,
    val clientName: String? = null
) : SignalingMessage

@Serializable
@SerialName("webrtc_offer")
data class WebRtcOfferMessage(val type: String = "webrtc_offer", val deviceId: String, val sdp: SdpPayload) : SignalingMessage

@Serializable
@SerialName("webrtc_answer")
data class WebRtcAnswerMessage(val type: String = "webrtc_answer", val deviceId: String, val sdp: SdpPayload) : SignalingMessage

@Serializable
@SerialName("ice_candidate")
data class IceCandidateMessage(val type: String = "ice_candidate", val deviceId: String, val candidate: IceCandidatePayload) : SignalingMessage

@Serializable
@SerialName("command")
data class CommandMessage(val type: String = "command", val command: String) : SignalingMessage

@Serializable
@SerialName("touch")
data class TouchMessage(
    val type: String = "touch",
    val action: String,
    val x: Float,
    val y: Float,
    val pointerId: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) : SignalingMessage

@Serializable
@SerialName("device_state")
data class DeviceStateMessage(
    val type: String = "device_state",
    val deviceId: String,
    val batteryLevel: Int,
    val networkType: String,
    val signalStrength: Int,
    val isCharging: Boolean
) : SignalingMessage

@Serializable
@SerialName("ping")
data class PingMessage(val type: String = "ping", val timestamp: Long) : SignalingMessage

@Serializable
@SerialName("pong")
data class PongMessage(val type: String = "pong", val timestamp: Long) : SignalingMessage

@Serializable
@SerialName("unpair")
data class UnpairMessage(val type: String = "unpair") : SignalingMessage