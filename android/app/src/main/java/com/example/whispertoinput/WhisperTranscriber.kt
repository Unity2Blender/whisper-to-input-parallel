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

package com.example.whispertoinput

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import com.github.liuyueyi.quick.transfer.ChineseUtils

class WhisperTranscriber {
    private data class Config(
        val endpoint: String,
        val languageCode: String,
        val speechToTextBackend: String,
        val apiKey: String,
        val model: String,
        val postprocessing: String,
        val addTrailingSpace: Boolean
    )

    companion object {
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }
        const val DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite-preview"
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val TAG = "WhisperTranscriber"
    private var currentTranscriptionJob: Job? = null

    fun startAsync(
        context: Context,
        filename: String,
        mediaType: String,
        attachToEnd: String,
        callback: (String?) -> Unit,
        exceptionCallback: (String) -> Unit
    ) {
        suspend fun makeWhisperRequest(): String {
            val (endpoint, languageCode, speechToTextBackend, apiKey, model, postprocessing, addTrailingSpace) = context.dataStore.data.map { preferences: Preferences ->
                Config(
                    preferences[ENDPOINT] ?: "",
                    preferences[LANGUAGE_CODE] ?: "",
                    preferences[SPEECH_TO_TEXT_BACKEND] ?: context.getString(R.string.settings_option_openai_api),
                    preferences[API_KEY] ?: "",
                    preferences[MODEL] ?: "",
                    preferences[POSTPROCESSING] ?: context.getString(R.string.settings_option_no_conversion),
                    preferences[ADD_TRAILING_SPACE] ?: false
                )
            }.first()

            if (endpoint == "") {
                throw Exception(context.getString(R.string.error_endpoint_unset))
            }

            val request = buildWhisperRequest(
                context,
                filename,
                mediaType,
                speechToTextBackend,
                endpoint,
                languageCode,
                apiKey,
                model
            )
            val response = sharedHttpClient.newCall(request).execute()

            if (!response.isSuccessful || response.code / 100 != 2) {
                throw Exception(response.body!!.string().replace('\n', ' '))
            }

            var rawText = response.body!!.string().trim()

            if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim) &&
                rawText.startsWith("\"") && rawText.endsWith("\"")) {
                rawText = rawText.substring(1, rawText.length - 1).trim()
            }

            val processedText = when (postprocessing) {
                context.getString(R.string.settings_option_to_simplified) -> ChineseUtils.tw2s(rawText)
                context.getString(R.string.settings_option_to_traditional) -> ChineseUtils.s2tw(rawText)
                else -> rawText
            }

            return if (attachToEnd == "") {
                processedText + if (addTrailingSpace) " " else ""
            } else {
                processedText + attachToEnd
            }
        }

        val job = CoroutineScope(Dispatchers.Main).launch {
            val (transcribedText, exceptionMessage) = withContext(Dispatchers.IO) {
                try {
                    val response = makeWhisperRequest()
                    File(filename).delete()
                    return@withContext Pair(response, null)
                } catch (e: CancellationException) {
                    return@withContext Pair(null, null)
                } catch (e: Exception) {
                    return@withContext Pair(null, e.message)
                }
            }

            callback.invoke(transcribedText)

            if (!exceptionMessage.isNullOrEmpty()) {
                Log.e(TAG, exceptionMessage)
                exceptionCallback(exceptionMessage)
            }
        }

        registerTranscriptionJob(job)
    }

    fun stop() {
        registerTranscriptionJob(null)
    }

    private fun registerTranscriptionJob(job: Job?) {
        currentTranscriptionJob?.cancel()
        currentTranscriptionJob = job
    }

    private fun buildWhisperRequest(
        context: Context,
        filename: String,
        mediaType: String,
        speechToTextBackend: String,
        endpoint: String,
        languageCode: String,
        apiKey: String,
        model: String
    ): Request {
        val file: File = File(filename)
        val fileBody: RequestBody = file.asRequestBody(mediaType.toMediaTypeOrNull())
        val requestBody: RequestBody = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            val formDataFilename = if (mediaType == "audio/ogg") "@audio.ogg" else "@audio.m4a"

            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api) ||
                speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim)) {
                addFormDataPart("file", formDataFilename, fileBody)
            } else if (speechToTextBackend == context.getString(R.string.settings_option_whisper_asr_webservice)) {
                addFormDataPart("audio_file", formDataFilename, fileBody)
            }
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api)) {
                addFormDataPart("model", model)
                addFormDataPart("response_format", "text")
            }
            if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim)) {
                addFormDataPart("language", languageCode)
                addFormDataPart("response_format", "text")
            }
        }.build()

        val requestHeaders: Headers = Headers.Builder().apply {
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api)) {
                if (apiKey == "") {
                    throw Exception(context.getString(R.string.error_apikey_unset))
                }
                add("Authorization", "Bearer $apiKey")
            }
            add("Content-Type", "multipart/form-data")
        }.build()

        val url = when (speechToTextBackend) {
            context.getString(R.string.settings_option_openai_api),
            context.getString(R.string.settings_option_whisper_asr_webservice) -> {
                "$endpoint?encode=true&task=transcribe&language=$languageCode&word_timestamps=false&output=txt"
            }
            else -> endpoint
        }

        return Request.Builder()
            .headers(requestHeaders)
            .url(url)
            .post(requestBody)
            .build()
    }

    suspend fun transcribeWithGemini(
        audioFile: File,
        apiKey: String,
        model: String
    ): ChunkResult = withContext(Dispatchers.IO) {
        val resolvedModel = model.ifBlank { DEFAULT_GEMINI_MODEL }
        Log.d(TAG, "Transcribing with Gemini (model=$resolvedModel): ${audioFile.name}")

        val audioData = audioFile.readBytes()
        val sizeBytes = audioData.size
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "Transcribe this audio. Return only the transcribed text with no additional commentary."))
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "audio/mp4")
                                put("data", base64Audio)
                            })
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url("$GEMINI_BASE_URL/$resolvedModel:generateContent?key=$apiKey")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val started = System.currentTimeMillis()
        val response = sharedHttpClient.newCall(request).execute()
        val durationMs = System.currentTimeMillis() - started

        val httpCode = response.code
        if (!response.isSuccessful || httpCode / 100 != 2) {
            val errorBody = response.body?.string() ?: "Unknown error"
            val retryAfterMs = parseRetryAfterMs(response.headers, errorBody)
            throw GeminiHttpException(
                statusCode = httpCode,
                retryAfterMs = retryAfterMs,
                errorBody = errorBody,
                message = "Gemini API error ($httpCode): ${errorBody.take(300)}"
            )
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response from Gemini API")
        val json = JSONObject(responseBody)

        val text = json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()

        Log.d(TAG, "Gemini transcription result (${durationMs}ms): ${text.take(100)}...")
        ChunkResult(text = text, httpCode = httpCode, sizeBytes = sizeBytes, durationMs = durationMs)
    }

    suspend fun transcribeChunk(
        context: Context,
        audioFile: File,
        mediaType: String
    ): ChunkResult = withContext(Dispatchers.IO) {
        val (endpoint, languageCode, speechToTextBackend, apiKey, model, _, _) = context.dataStore.data.map { preferences: Preferences ->
            Config(
                preferences[ENDPOINT] ?: "",
                preferences[LANGUAGE_CODE] ?: "",
                preferences[SPEECH_TO_TEXT_BACKEND] ?: context.getString(R.string.settings_option_openai_api),
                preferences[API_KEY] ?: "",
                preferences[MODEL] ?: "",
                preferences[POSTPROCESSING] ?: context.getString(R.string.settings_option_no_conversion),
                preferences[ADD_TRAILING_SPACE] ?: false
            )
        }.first()

        Log.d(TAG, "Transcribing chunk with backend: $speechToTextBackend")

        when (speechToTextBackend) {
            context.getString(R.string.settings_option_gemini_api) -> {
                val geminiApiKey = context.dataStore.data.map { preferences: Preferences ->
                    preferences[GEMINI_API_KEY] ?: ""
                }.first()
                if (geminiApiKey.isEmpty()) {
                    throw Exception(context.getString(R.string.error_gemini_apikey_unset))
                }
                transcribeWithGemini(audioFile, geminiApiKey, model)
            }
            else -> {
                if (endpoint.isEmpty()) {
                    throw Exception(context.getString(R.string.error_endpoint_unset))
                }

                val sizeBytes = audioFile.length().toInt()
                val request = buildWhisperRequest(
                    context,
                    audioFile.absolutePath,
                    mediaType,
                    speechToTextBackend,
                    endpoint,
                    languageCode,
                    apiKey,
                    model
                )

                val started = System.currentTimeMillis()
                val response = sharedHttpClient.newCall(request).execute()
                val durationMs = System.currentTimeMillis() - started
                val httpCode = response.code

                if (!response.isSuccessful || httpCode / 100 != 2) {
                    throw Exception(
                        "${speechToTextBackend} error ($httpCode): " +
                            (response.body?.string()?.replace('\n', ' ') ?: "Unknown error")
                    )
                }

                var rawText = response.body?.string()?.trim() ?: ""
                if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim) &&
                    rawText.startsWith("\"") && rawText.endsWith("\"")) {
                    rawText = rawText.substring(1, rawText.length - 1).trim()
                }

                ChunkResult(text = rawText, httpCode = httpCode, sizeBytes = sizeBytes, durationMs = durationMs)
            }
        }
    }

    private fun parseRetryAfterMs(headers: Headers, errorBody: String?): Long? {
        headers["Retry-After"]?.toLongOrNull()?.let { return it * 1000L }
        if (errorBody.isNullOrBlank()) return null
        return try {
            val root = JSONObject(errorBody)
            val error = root.optJSONObject("error") ?: return null
            val details = error.optJSONArray("details") ?: return null
            for (i in 0 until details.length()) {
                val item = details.optJSONObject(i) ?: continue
                val type = item.optString("@type")
                if (type.endsWith("RetryInfo")) {
                    val retryDelay = item.optString("retryDelay")
                    if (retryDelay.isNotBlank() && retryDelay.endsWith("s")) {
                        val seconds = retryDelay.removeSuffix("s").toDoubleOrNull() ?: continue
                        return (seconds * 1000).toLong()
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

data class ChunkResult(
    val text: String,
    val httpCode: Int,
    val sizeBytes: Int,
    val durationMs: Long
)

class GeminiHttpException(
    val statusCode: Int,
    val retryAfterMs: Long?,
    val errorBody: String?,
    message: String
) : Exception(message)
