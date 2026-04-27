# Chunking Package

This package implements chunked parallel transcription for handling long audio recordings, used by every backend (OpenAI, Whisper ASR, NVIDIA NIM, Gemini).

## Overview

When audio recordings exceed 30 seconds, the audio is split into overlapping M4A chunks, transcribed in parallel against the configured backend, and merged via word-overlap deduplication.

## Key Components

| File | Purpose |
|------|---------|
| `ChunkConfig.kt` | Configuration constants (durations, concurrency, retry limits) |
| `AudioChunk.kt` | Data classes for chunk metadata and transcripts |
| `AudioChunker.kt` | Splits audio with MediaExtractor/MediaMuxer; sweeps stale `chunk_*.m4a` orphans |
| `ChunkTranscriptionService.kt` | Parallel orchestration, Retry-After-aware retry, fail-loudly semantics |
| `TranscriptMerger.kt` | Word-overlap deduplication |
| `ChunkProgressListener.kt` | Progress callbacks to UI |

## Configuration (ChunkConfig)

- **Chunk Duration**: 30 s
- **Overlap**: 2 s (for context continuity)
- **Chunking Threshold**: 30 s (≤ threshold → single API call)
- **Max Concurrent Chunks**: 4 parallel API calls (shared `OkHttpClient` singleton in `WhisperTranscriber`)
- **Max Retries**: 3 attempts per chunk
- **Retry Base Delay**: 1.5 s (doubles per attempt: 1.5 / 3 / 6)
- **Retry Max Delay**: 30 s (caps any server `Retry-After` hint)
- **Stale Chunk Sweep Age**: 5 min

## Retry policy

`ChunkTranscriptionService.transcribeWithRetry`:
1. On 429 / 5xx, parse `Retry-After` header **and** Gemini's `error.details[].@type=...RetryInfo.retryDelay`.
2. Backoff target = `max(serverHint, exponential)` capped at `RETRY_MAX_DELAY_MS`.
3. Apply **full jitter**: actual delay uniform in `[target/2, target * 1.5)`.
4. On non-429 4xx (auth, bad model), fail fast — those are misconfig and won't fix themselves.

## Failure semantics

If **any** chunk fails after all retries, `ChunkedTranscriptionException` is thrown — the whole transcription is aborted with the failed chunk indices listed in the message. Partial transcripts are never silently committed.

## Data flow

1. `AudioChunker.splitAudio(file)` → `List<AudioChunk>` (writes to `cacheDir/chunk_<i>_<ts>.m4a`).
2. `ChunkTranscriptionService(transcriber, listener).transcribe(chunks)` → `TranscriptionRunResult(mergedText, chunkLogLines)`.
3. `TranscriptMerger.merge(sortedChunks)` → final string.

`WhisperInputService.transcribeWithChunking` is the single caller, used for **all** backends. The returned `chunkLogLines` are persisted via `diagnostics/TranscriptionLogStore`.

## Testing

```bash
adb logcat *:D | grep -E "(CHUNK|AudioChunker|ChunkTranscriptionService|WhisperInputService)"
```

After a transcription, copy the diagnostics from Settings → "Copy Diagnostic Logs" to see per-chunk size, http_code, retries, retry_after, and timing.

Chunk files are created in `cacheDir`, cleaned on success/failure via `try/finally`, and any orphans (e.g. from app kill) are swept on the next IME create.
