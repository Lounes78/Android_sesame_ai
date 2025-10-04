# Sesame AI Voice Chat - Android App

A simple Android application that recreates the voice chat experience from the Python `voice_chat.py` example. This app provides real-time voice conversations with Sesame AI characters Miles and Maya.

## Features

- **Real-time voice chat** with AI characters (Miles and Maya)
- **WebSocket communication** using Sesame AI's API
- **Voice activity detection** for smart audio transmission
- **Simple UI** with character selection and connection controls
- **Audio recording** at 16kHz PCM format
- **Audio playback** at server-determined sample rate (typically 24kHz)
- **Permission handling** for microphone access
- **Connection lifecycle management**

## Architecture

The app follows the Zen of Python principles - Simple is better than complex:

- **MainActivity.kt** - Main UI and lifecycle management
- **SesameWebSocket.kt** - WebSocket communication with Sesame AI
- **AudioManager.kt** - Microphone recording with voice activity detection  
- **AudioPlayer.kt** - Audio playback for AI responses
- **Hardcoded authentication** - Uses token from token.json for simplicity

## Requirements

- **Android API 21+** (Android 5.0)
- **Microphone permission** for voice input
- **Internet connection** for WebSocket communication
- **Kotlin** and **OkHttp** dependencies

## Setup

1. **Open in Android Studio**:
   ```bash
   # Open the android_sesame_ai folder in Android Studio
   ```

2. **Build the project**:
   - Android Studio will automatically download dependencies
   - Ensure you have the Android SDK installed

3. **Run on device/emulator**:
   - Connect an Android device or start an emulator
   - Click "Run" in Android Studio

## Usage

1. **Grant permissions**: Allow microphone access when prompted
2. **Select character**: Choose between Miles (default) or Maya
3. **Connect**: Tap "Connect" to start the voice chat
4. **Speak naturally**: The AI will respond to your voice input
5. **Disconnect**: Tap "Disconnect" to end the conversation

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

### Authentication
- **Hardcoded token**: Firebase ID token from `token.json`
- **Character selection**: Miles or Maya via WebSocket parameters
- **Client identification**: "RP-Android" client name

## Dependencies

```gradle
// WebSocket client
implementation 'com.squareup.okhttp3:okhttp:4.12.0'

// JSON handling  
implementation 'org.json:json:20231013'

// Audio processing
implementation 'androidx.media:media:1.7.0'
```

## Permissions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Troubleshooting

### Audio Issues
- **No microphone input**: Check microphone permissions in Settings
- **No audio output**: Ensure device volume is up and not muted
- **Audio feedback**: Use headphones to prevent feedback loops

### Connection Issues  
- **Connection failed**: Check internet connectivity
- **Token expired**: The hardcoded token may need refreshing
- **Character not responding**: Try disconnecting and reconnecting

### Performance Issues
- **Lag in conversation**: Ensure stable internet connection
- **App crashes**: Check logcat for audio permission or memory issues

## Differences from Python Version

1. **Simplified authentication**: Hardcoded token instead of TokenManager
2. **Android audio APIs**: AudioRecord/AudioTrack instead of PyAudio  
3. **UI integration**: Native Android UI instead of command line
4. **Lifecycle management**: Android activity lifecycle handling
5. **Permission system**: Android runtime permissions

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

## License

This Android implementation follows the same MIT license as the original Python library.

## Support

For issues related to:
- **Sesame AI API**: Check the original Python repository
- **Android implementation**: Review logcat output and ensure permissions are granted
- **Audio problems**: Test with headphones and check device audio settings