# Diagnostics Package

Persistent ring buffer of the last 5 transcription attempts, exposed via the Settings → "Copy Diagnostic Logs" button.

## Files

| File | Purpose |
|------|---------|
| `TranscriptionLogStore.kt` | Singleton: append/read entries, redaction helper |

Three data types:
- `ChunkLogLine` — one HTTP request: index, size, http_code, retries, retry_after, duration, optional error summary
- `TranscriptionEntry` — one full transcription: timestamp, backend, model, total ms, outcome, list of `ChunkLogLine`
- (Companion `redact()` on `TranscriptionLogStore`)

## Storage

- Plain JSON file at `context.filesDir/transcription_logs.json`
- Ring buffer capped at 5 entries (newest kept)
- Atomic full-file rewrite on each append (small file, 5 entries is plenty fine)
- All read/write operations are `@Synchronized`

## Redaction guarantees

`TranscriptionLogStore.redact()` strips three credential patterns before any value is written or rendered:

1. `key=<anything>` query-param fragments → `key=***`
2. `"apiKey":"<anything>"` JSON fragments → `"apiKey":"***"`
3. `Bearer <token>` Authorization headers → `Bearer ***`

Audio bytes are **never** logged. Error bodies are truncated to 120 chars after redaction.

## Adding a new field

1. Add the field to `ChunkLogLine` or `TranscriptionEntry`.
2. Update `toJson()`, `fromJson()`, and `toLine()` / `toPlainText()`.
3. Bump `MAX_ENTRIES` only if the new field is large; the file is otherwise small.

## Manual inspection (dev)

```bash
adb shell run-as com.example.whispertoinput cat files/transcription_logs.json
```
