# DRIVER REMOTE AGENT (Android Native)

This repository contains the complete Android Kotlin production source code for the **Driver Remote Agent**.

## Features
- **Official WebRTC Screen Streaming**: Hardware-accelerated `ScreenCapturerAndroid` via `MediaProjection` at 720p @ 30 FPS.
- **WebSocket Signaling Client**: OkHttp client with exponential backoff (1s, 2s, 4s, 8s, 10s max), ping/pong handler, and automatic device registration.
- **Android Accessibility Engine**: Official `AccessibilityService` for Back, Home, Recents, Notifications, and touch coordinate injection.
- **Foreground Service**: `foregroundServiceType="mediaProjection"` with notification and Stop Remote action.
- **Jetpack Compose UI**: Modern Material 3 UI with connection state badges, copyable credentials, and live telemetry.

## How to Build & Run
1. Open this directory in **Android Studio** (Ladybug or newer).
2. Allow Gradle sync to complete.
3. Connect a physical Android phone running Android 7.0+ (API 24+) with USB Debugging enabled.
4. Click **Run 'app'**.
5. Enable Accessibility Service in Settings when prompted by the app.
6. Enter your Signaling Server WebSocket URL and tap **START REMOTE SHARING**.
