# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Whisper To Input is an Android IME (Input Method Editor) keyboard that performs speech-to-text using OpenAI Whisper or compatible backends. Supports English, Chinese, Japanese, Taiwanese, and mixed languages.

## Build Commands

All commands run from the `android/` directory:

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (requires device/emulator)
./gradlew clean build            # Clean and build
```

Output APK: `android/app/build/outputs/apk/debug/app-debug.apk`

## Architecture

### Component Overview

```
WhisperInputService (InputMethodService)
    ├── WhisperKeyboard (UI state machine & button handlers)
    ├── RecorderManager (MediaRecorder wrapper)
    └── WhisperTranscriber (HTTP client for STT APIs)
```

### Key Files

| File | Purpose |
|------|---------|
| `app/src/main/java/.../WhisperInputService.kt` | Core IME service, orchestrates recording/transcription |
| `app/src/main/java/.../keyboard/WhisperKeyboard.kt` | Keyboard UI with 3-state FSM (Idle/Recording/Transcribing) |
| `app/src/main/java/.../WhisperTranscriber.kt` | API client for OpenAI, Whisper ASR, NVIDIA NIM backends |
| `app/src/main/java/.../recorder/RecorderManager.kt` | Audio recording with amplitude reporting |
| `app/src/main/java/.../MainActivity.kt` | Settings UI with DataStore persistence |

### Data Flow

1. User taps mic → `WhisperKeyboard` → state to Recording
2. `RecorderManager` records audio, reports amplitude for visualization
3. User taps again → state to Transcribing
4. `WhisperTranscriber` sends audio to backend API
5. Result committed via `InputConnection.commitText()`
6. Optional: auto-switch back to previous IME

### Backend Support

- **OpenAI API**: Bearer token auth, M4A audio format
- **Whisper ASR Webservice**: No auth, self-hosted
- **NVIDIA NIM**: Multi-language support, OGG audio format

## Configuration

- **SDK**: Compile/Target SDK 34, Min SDK 24
- **Language**: Kotlin 1.9.0, JVM target 1.8
- **Persistence**: AndroidX DataStore (preferences)
- **HTTP**: Ktor client with OkHttp

## Debugging

```bash
adb logcat *:E    # Errors only
adb logcat *:D    # Debug and above
```

All releases are debug builds with full logging.

## Permissions

- `RECORD_AUDIO` - Microphone access for voice input
- `POST_NOTIFICATIONS` - Background error toasts
- `INTERNET` - API requests
