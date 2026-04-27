/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2026 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput.chunking

import android.util.Log
import com.example.whispertoinput.ChunkResult
import com.example.whispertoinput.GeminiHttpException
import com.example.whispertoinput.diagnostics.ChunkLogLine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class TranscriptionRunResult(
    val mergedText: String,
    val chunkLogLines: List<ChunkLogLine>
)

class ChunkTranscriptionService(
    private val transcriber: suspend (AudioChunk) -> ChunkResult,
    private val progressListener: ChunkProgressListener? = null
) {
    private val TAG = "ChunkTranscriptionService"

    suspend fun transcribe(chunks: List<AudioChunk>): TranscriptionRunResult = coroutineScope {
        val semaphore = Semaphore(ChunkConfig.MAX_CONCURRENT_CHUNKS)
        val totalChunks = chunks.size

        Log.d(TAG, "Starting transcription of $totalChunks chunks (concurrency=${ChunkConfig.MAX_CONCURRENT_CHUNKS})")
        progressListener?.onChunkingStarted(totalChunks)

        val deferred = chunks.map { chunk ->
            async {
                semaphore.withPermit {
                    progressListener?.onChunkStarted(chunk.index)
                    transcribeWithRetry(chunk, totalChunks)
                }
            }
        }

        val transcripts = mutableListOf<ChunkTranscript>()
        val errors = mutableListOf<Pair<Int, Exception>>()
        val logLines = mutableListOf<ChunkLogLine>()
        var completedCount = 0

        deferred.forEachIndexed { idx, d ->
            try {
                val (result, log) = d.await()
                logLines.add(log)
                val chunk = chunks[idx]
                transcripts.add(
                    ChunkTranscript(
                        text = result.text,
                        index = chunk.index,
                        startTimeMs = chunk.startTimeMs,
                        endTimeMs = chunk.endTimeMs
                    )
                )
                completedCount++
                Log.d(TAG, "Chunk ${chunk.index} completed: $completedCount/$totalChunks")
                progressListener?.onChunkCompleted(chunk.index, completedCount, totalChunks)
            } catch (e: ChunkAttemptFailure) {
                logLines.add(e.log)
                errors.add(idx to e)
                Log.e(TAG, "Chunk $idx failed: ${e.message}")
                progressListener?.onChunkFailed(idx, e)
            } catch (e: Exception) {
                errors.add(idx to e)
                Log.e(TAG, "Chunk $idx failed (untyped): ${e.message}")
                progressListener?.onChunkFailed(idx, e)
            }
        }

        // Fail loudly: if ANY chunk failed, abort the whole transcription.
        if (errors.isNotEmpty()) {
            val indices = errors.joinToString(",") { it.first.toString() }
            val lastMsg = errors.last().second.message ?: "unknown error"
            throw ChunkedTranscriptionException(
                message = "Transcription failed: ${errors.size}/$totalChunks chunks could not be transcribed (indices: $indices). Last error: $lastMsg",
                errors = errors,
                chunkLogLines = logLines.toList()
            )
        }

        Log.d(TAG, "Merging ${transcripts.size} transcripts")
        progressListener?.onMergingStarted()
        val merged = TranscriptMerger.merge(transcripts.sortedBy { it.index })
        progressListener?.onTranscriptionComplete(merged)

        Log.d(TAG, "Transcription complete: ${merged.length} characters")
        TranscriptionRunResult(mergedText = merged, chunkLogLines = logLines.sortedBy { it.index })
    }

    private suspend fun transcribeWithRetry(chunk: AudioChunk, total: Int): Pair<ChunkResult, ChunkLogLine> {
        var retries = 0
        var lastRetryAfterMs: Long? = null
        val started = System.currentTimeMillis()

        repeat(ChunkConfig.MAX_RETRIES) { attempt ->
            try {
                Log.d(TAG, "Transcribing chunk ${chunk.index}, attempt ${attempt + 1}/${ChunkConfig.MAX_RETRIES}")
                val result = transcriber(chunk)
                val log = ChunkLogLine(
                    index = chunk.index,
                    total = total,
                    sizeBytes = result.sizeBytes,
                    httpCode = result.httpCode,
                    retries = retries,
                    retryAfterMs = lastRetryAfterMs,
                    durationMs = System.currentTimeMillis() - started
                )
                return result to log
            } catch (e: GeminiHttpException) {
                Log.w(TAG, "Chunk ${chunk.index} attempt ${attempt + 1} GeminiHttpException ${e.statusCode}: ${e.message?.take(120)}")
                lastRetryAfterMs = e.retryAfterMs ?: lastRetryAfterMs
                val isFinalAttempt = attempt == ChunkConfig.MAX_RETRIES - 1
                val isNonRetryable4xx = e.statusCode in 400..499 && e.statusCode != 429
                if (isNonRetryable4xx || isFinalAttempt) {
                    throw ChunkAttemptFailure(
                        log = ChunkLogLine(
                            index = chunk.index,
                            total = total,
                            sizeBytes = chunk.file.length().toInt(),
                            httpCode = e.statusCode,
                            retries = retries + (if (isFinalAttempt) 1 else 0),
                            retryAfterMs = e.retryAfterMs,
                            durationMs = System.currentTimeMillis() - started,
                            errorSummary = e.message
                        ),
                        cause = e
                    )
                }
                retries++
                val baseDelay = ChunkConfig.RETRY_BASE_DELAY_MS shl attempt
                val target = maxOf(e.retryAfterMs ?: 0L, baseDelay).coerceAtMost(ChunkConfig.RETRY_MAX_DELAY_MS)
                delay(jitter(target))
            } catch (e: Exception) {
                Log.w(TAG, "Chunk ${chunk.index} attempt ${attempt + 1} exception: ${e.message?.take(120)}")
                if (attempt == ChunkConfig.MAX_RETRIES - 1) {
                    throw ChunkAttemptFailure(
                        log = ChunkLogLine(
                            index = chunk.index,
                            total = total,
                            sizeBytes = chunk.file.length().toInt(),
                            httpCode = -1,
                            retries = retries + 1,
                            retryAfterMs = null,
                            durationMs = System.currentTimeMillis() - started,
                            errorSummary = e.message
                        ),
                        cause = e
                    )
                }
                retries++
                val baseDelay = ChunkConfig.RETRY_BASE_DELAY_MS shl attempt
                delay(jitter(baseDelay))
            }
        }
        // Unreachable: every path inside repeat either returns or throws.
        error("transcribeWithRetry exited the retry loop unexpectedly")
    }

    /** Full-jitter backoff: uniform [target/2, target * 1.5). */
    private fun jitter(target: Long): Long =
        (target * (0.5 + Math.random())).toLong().coerceAtMost(ChunkConfig.RETRY_MAX_DELAY_MS)
}

class ChunkAttemptFailure(
    val log: ChunkLogLine,
    cause: Throwable
) : Exception(cause.message, cause)

class ChunkedTranscriptionException(
    message: String,
    val errors: List<Pair<Int, Exception>>,
    val chunkLogLines: List<ChunkLogLine> = emptyList()
) : Exception(message)
