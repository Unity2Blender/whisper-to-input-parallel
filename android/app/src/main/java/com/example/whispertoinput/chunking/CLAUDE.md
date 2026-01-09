# Chunking Package

This package implements chunked parallel transcription for handling long audio recordings.

## Overview

When audio recordings exceed 30 seconds, this package splits them into overlapping chunks, transcribes them in parallel using Google Gemini API, and merges the results into a unified transcript.

## Key Components

| File | Purpose |
|------|---------|
| `ChunkConfig.kt` | Configuration constants (chunk duration, overlap, concurrency limits) |
| `AudioChunk.kt` | Data classes for chunk metadata and transcripts |
| `AudioChunker.kt` | Splits audio files using MediaExtractor/MediaMuxer |
| `ChunkTranscriptionService.kt` | Orchestrates parallel transcription with Semaphore-based concurrency |
| `TranscriptMerger.kt` | Word-based overlap deduplication algorithm |
| `ChunkProgressListener.kt` | Interface for progress callbacks to update UI |

## Configuration (ChunkConfig)

- **Chunk Duration**: 30 seconds
- **Overlap**: 2 seconds (for context continuity)
- **Chunking Threshold**: 30 seconds (audio shorter than this uses single API call)
- **Max Concurrent Chunks**: 3 parallel API calls
- **Max Retries**: 3 attempts per chunk with exponential backoff

## Data Flow

1. `AudioChunker.splitAudio()` - Splits audio into M4A chunks
2. `ChunkTranscriptionService.transcribe()` - Parallel API calls with semaphore limiting
3. `TranscriptMerger.merge()` - Deduplicates overlapping text between chunks

## Integration

This package is used by `WhisperInputService.transcribeWithGeminiChunked()` when:
- Backend is set to "Gemini API" in settings
- User stops recording

Progress is reported to `WhisperKeyboard` via `ChunkProgressListener` interface.

## Testing

Debug logs can be monitored with:
```bash
adb logcat *:D | grep -E "(CHUNK|AudioChunker|ChunkTranscriptionService)"
```

Chunk files are created in the app's cache directory and automatically cleaned up after transcription.
