# Sesame AI Voice Chat - Android App

A simple Android application that recreates the voice chat experience from Sesame. This app provides real-time voice conversations with Sesame AI characters Miles and Maya.

## Features

- **Real-time voice chat** with AI characters (Miles and Maya)
- **WebSocket communication** using Sesame AI's API
- **Voice activity detection** for smart audio transmission
- **Simple UI** with character selection and connection controls
- **Audio recording** at 16kHz PCM format
- **Audio playback** at server-determined sample rate (typically 24kHz)
- **Permission handling** for microphone access
- **Connection lifecycle management**

## Technical Details

### WebSocket Protocol
The app implements the same WebSocket protocol as the Python version:
- Connection to `wss://sesameai.app/agent-service-0/v1/connect`
- Message types: `initialize`, `call_connect`, `audio`, `ping`, `call_disconnect`
- Base64 encoded audio data transmission
- Firebase authentication token handling

### Audio Processing
- **Recording**: 16kHz, 16-bit PCM, mono
- **Playback**: Server sample rate (24kHz), 16-bit PCM, mono
- **Voice Activity Detection**: RMS-based threshold detection
- **Buffer management**: Circular buffers for real-time processing


## Files Structure

```
android_sesame_ai/
├── app/
│   ├── build.gradle                          # App dependencies
│   ├── src/main/
│   │   ├── AndroidManifest.xml               # Permissions & app config
│   │   ├── java/com/sesame/voicechat/
│   │   │   ├── MainActivity.kt               # Main UI & lifecycle
│   │   │   ├── SesameWebSocket.kt           # WebSocket communication
│   │   │   ├── AudioManager.kt              # Microphone recording
│   │   │   └── AudioPlayer.kt               # Audio playback
│   │   └── res/
│   │       ├── layout/activity_main.xml     # UI layout
│   │       ├── values/strings.xml           # App strings
│   │       └── values/themes.xml            # App theme
├── build.gradle                             # Project config
├── settings.gradle                          # Gradle settings
└── README.md                               # This file
```