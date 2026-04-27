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

- **OpenAI API**: Bearer token auth, M4A (AAC) audio format
- **Whisper ASR Webservice**: No auth, self-hosted, M4A (AAC)
- **NVIDIA NIM**: Multi-language support, OGG/Opus audio format
- **Google Gemini API**: `?key=` query auth, M4A (AAC) audio inline base64 via `generateContent`. Model is read from the `MODEL` Settings field; default `gemini-3.1-flash-lite-preview` if blank. Hard-coded constants live in `WhisperTranscriber.kt` companion object.

### Recording format

All M4A backends record AAC at 16 kHz mono, 24 kbps (`RecorderManager.kt`). NVIDIA NIM uses its own OGG/Opus path. AMR-NB is not used — Gemini's documented audio support does not include it.

### Chunked parallel transcription

Long recordings (> `CHUNKING_THRESHOLD_SECONDS`) are split into overlapping chunks and transcribed in parallel via `chunking/ChunkTranscriptionService` (semaphore-bounded at `MAX_CONCURRENT_CHUNKS=4`). Per-chunk retries respect `Retry-After` and Gemini's `RetryInfo.retryDelay`; full-jitter exponential backoff. Any chunk failure aborts the whole transcription (fail-loudly).

### Diagnostics

`diagnostics/TranscriptionLogStore` keeps a 5-entry ring buffer of structured per-chunk log lines at `filesDir/transcription_logs.json`. Settings → "Copy Diagnostic Logs" copies a plain-text dump to the clipboard. API keys / Bearer tokens are redacted before write.

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
