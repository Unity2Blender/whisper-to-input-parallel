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

package com.example.whispertoinput.diagnostics

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TranscriptionLogStore {
    private const val TAG = "TranscriptionLogStore"
    private const val FILE_NAME = "transcription_logs.json"
    private const val MAX_ENTRIES = 5

    @Synchronized
    fun append(context: Context, entry: TranscriptionEntry) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val existing = readEntriesSafely(file)
            val updated = (existing + entry).takeLast(MAX_ENTRIES)
            val arr = JSONArray()
            updated.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append log entry: ${e.message}")
        }
    }

    @Synchronized
    fun readAsPlainText(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return "(no transcriptions logged yet)"
        val entries = readEntriesSafely(file)
        if (entries.isEmpty()) return "(no transcriptions logged yet)"
        return entries.joinToString("\n\n") { it.toPlainText() }
    }

    private fun readEntriesSafely(file: File): List<TranscriptionEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                try {
                    TranscriptionEntry.fromJson(arr.getJSONObject(i))
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read log file: ${e.message}")
            emptyList()
        }
    }

    fun redact(s: String): String {
        return s
            .replace(Regex("""key=[^&\s"]+"""), "key=***")
            .replace(Regex("""(?i)"apiKey"\s*:\s*"[^"]+""""), "\"apiKey\":\"***\"")
            .replace(Regex("""Bearer\s+[A-Za-z0-9._\-]+"""), "Bearer ***")
    }
}

data class ChunkLogLine(
    val index: Int,
    val total: Int,
    val sizeBytes: Int,
    val httpCode: Int,
    val retries: Int,
    val retryAfterMs: Long?,
    val durationMs: Long,
    val errorSummary: String? = null
) {
    fun toLine(): String = buildString {
        append("chunk ${index + 1}/$total | ${sizeBytes / 1024}KB | http=$httpCode")
        if (retries > 0) append(" | retries=$retries")
        retryAfterMs?.let { append(" | retry_after=${it / 1000}s") }
        append(" | ${durationMs}ms")
        errorSummary?.let { append(" | err=${TranscriptionLogStore.redact(it).take(120)}") }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("total", total)
        put("sizeBytes", sizeBytes)
        put("httpCode", httpCode)
        put("retries", retries)
        retryAfterMs?.let { put("retryAfterMs", it) }
        put("durationMs", durationMs)
        errorSummary?.let { put("errorSummary", TranscriptionLogStore.redact(it)) }
    }

    companion object {
        fun fromJson(o: JSONObject): ChunkLogLine = ChunkLogLine(
            index = o.getInt("index"),
            total = o.getInt("total"),
            sizeBytes = o.getInt("sizeBytes"),
            httpCode = o.getInt("httpCode"),
            retries = o.getInt("retries"),
            retryAfterMs = if (o.has("retryAfterMs")) o.getLong("retryAfterMs") else null,
            durationMs = o.getLong("durationMs"),
            errorSummary = if (o.has("errorSummary")) o.getString("errorSummary") else null
        )
    }
}

data class TranscriptionEntry(
    val timestamp: Long,
    val backend: String,
    val model: String,
    val totalChunks: Int,
    val totalMs: Long,
    val outcome: String,
    val chunkLines: List<ChunkLogLine>
) {
    fun toPlainText(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
        val header = "=== Transcription | $ts | backend=$backend model=${TranscriptionLogStore.redact(model)} | total=${totalMs}ms | $outcome ==="
        return (listOf(header) + chunkLines.map { "  " + it.toLine() }).joinToString("\n")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("backend", backend)
        put("model", TranscriptionLogStore.redact(model))
        put("totalChunks", totalChunks)
        put("totalMs", totalMs)
        put("outcome", TranscriptionLogStore.redact(outcome))
        val arr = JSONArray()
        chunkLines.forEach { arr.put(it.toJson()) }
        put("chunkLines", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): TranscriptionEntry {
            val arr = o.getJSONArray("chunkLines")
            return TranscriptionEntry(
                timestamp = o.getLong("timestamp"),
                backend = o.getString("backend"),
                model = o.getString("model"),
                totalChunks = o.getInt("totalChunks"),
                totalMs = o.getLong("totalMs"),
                outcome = o.getString("outcome"),
                chunkLines = (0 until arr.length()).map { ChunkLogLine.fromJson(arr.getJSONObject(it)) }
            )
        }
    }
}
