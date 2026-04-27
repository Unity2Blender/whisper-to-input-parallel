/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
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

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import com.example.whispertoinput.chunking.AudioChunk
import com.example.whispertoinput.chunking.AudioChunker
import com.example.whispertoinput.chunking.ChunkProgressListener
import com.example.whispertoinput.chunking.ChunkTranscriptionService
import com.example.whispertoinput.chunking.ChunkedTranscriptionException
import com.example.whispertoinput.diagnostics.ChunkLogLine
import com.example.whispertoinput.diagnostics.TranscriptionEntry
import com.example.whispertoinput.diagnostics.TranscriptionLogStore
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.constants.TransType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val RECORDED_AUDIO_FILENAME_M4A = "recorded.m4a"
private const val RECORDED_AUDIO_FILENAME_OGG = "recorded.ogg"
private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val AUDIO_MEDIA_TYPE_OGG = "audio/ogg"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    private val TAG = "WhisperInputService"
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var audioChunker: AudioChunker? = null
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = AUDIO_MEDIA_TYPE_M4A
    private var useOggFormat: Boolean = false
    private var isFirstTime: Boolean = true

    private fun transcriptionCallback(text: String?) {
        if (!text.isNullOrEmpty()) {
            currentInputConnection?.commitText(text, 1)
            // Check if auto-switch-back is enabled and switch if so
            CoroutineScope(Dispatchers.Main).launch {
                val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                    preferences[AUTO_SWITCH_BACK] ?: false
                }.first()
                if (autoSwitchBack) {
                    onSwitchIme()
                }
            }
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
    }

    private suspend fun updateAudioFormat() {
        val backend = dataStore.data.map { preferences: Preferences ->
            preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_openai_api)
        }.first()
        
        useOggFormat = backend == getString(R.string.settings_option_nvidia_nim)
        if (useOggFormat) {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_OGG}"
            audioMediaType = AUDIO_MEDIA_TYPE_OGG
        } else {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_M4A}"
            audioMediaType = AUDIO_MEDIA_TYPE_M4A
        }
    }

    override fun onCreateInputView(): View {
        // Initialize members with regard to this context
        recorderManager = RecorderManager(this)
        audioChunker = AudioChunker(this)

        // Sweep any orphaned chunk files left from prior IME crashes / dismissals.
        audioChunker?.sweepStaleChunks()

        // Preload conversion table
        ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)
        ChineseUtils.preLoad(true, TransType.TAIWAN_TO_SIMPLE)

        // Initialize audio format based on backend setting
        CoroutineScope(Dispatchers.Main).launch {
            updateAudioFormat()
        }

        // Should offer ime switch?
        val shouldOfferImeSwitch: Boolean =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
            }

        // Sets up recorder manager
        recorderManager!!.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }

        // Returns the keyboard after setting it up and inflating its layout
        return whisperKeyboard.setup(layoutInflater,
            shouldOfferImeSwitch,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { shouldShowRetry() },
        )
    }

    private fun onStartRecording() {
        // Upon starting recording, check whether audio permission is granted.
        if (!recorderManager!!.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

        recorderManager!!.start(this, recordedAudioFilename, useOggFormat)
    }

    // when mic amplitude is updated, notify the keyboard
    // this callback is registered to the recorder manager
    private fun onUpdateMicrophoneAmplitude(amplitude: Int) {
        whisperKeyboard.updateMicrophoneAmplitude(amplitude)
    }

    private fun onCancelRecording() {
        recorderManager!!.stop()
    }

    private fun onStartTranscription(attachToEnd: String) {
        recorderManager!!.stop()

        CoroutineScope(Dispatchers.Main).launch {
            transcribeWithChunking(attachToEnd)
        }
    }

    private suspend fun transcribeWithChunking(attachToEnd: String) {
        val audioFile = File(recordedAudioFilename)
        val started = System.currentTimeMillis()
        var chunks: List<AudioChunk> = emptyList()
        var chunkLogLines: List<ChunkLogLine> = emptyList()
        var outcome = "OK"
        var backendForLog = "?"
        var modelForLog = ""

        try {
            if (!audioFile.exists()) {
                outcome = "FAIL: audio file missing"
                transcriptionExceptionCallback(getString(R.string.error_audio_not_found))
                return
            }

            val postprocessing = dataStore.data.map { preferences: Preferences ->
                preferences[POSTPROCESSING] ?: getString(R.string.settings_option_no_conversion)
            }.first()

            val addTrailingSpace = dataStore.data.map { preferences: Preferences ->
                preferences[ADD_TRAILING_SPACE] ?: false
            }.first()

            backendForLog = dataStore.data.map { preferences: Preferences ->
                preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_openai_api)
            }.first()
            modelForLog = dataStore.data.map { preferences: Preferences ->
                preferences[MODEL] ?: ""
            }.first()
            if (backendForLog == getString(R.string.settings_option_gemini_api) && modelForLog.isBlank()) {
                modelForLog = WhisperTranscriber.DEFAULT_GEMINI_MODEL
            }

            Log.d(TAG, "Starting chunked transcription for: ${audioFile.name}")

            chunks = audioChunker!!.splitAudio(audioFile)
            Log.d(TAG, "Audio split into ${chunks.size} chunks")

            // AudioChunker outputs M4A chunks regardless of input format (MediaMuxer limitation),
            // so use M4A media type for any post-split chunk.
            val chunkMediaType = AUDIO_MEDIA_TYPE_M4A

            val rawText: String = if (chunks.size == 1) {
                val isOriginalFile = chunks[0].file == audioFile
                val singleStarted = System.currentTimeMillis()
                val result = whisperTranscriber.transcribeChunk(
                    this@WhisperInputService,
                    chunks[0].file,
                    if (isOriginalFile) audioMediaType else chunkMediaType
                )
                chunkLogLines = listOf(
                    ChunkLogLine(
                        index = 0,
                        total = 1,
                        sizeBytes = result.sizeBytes,
                        httpCode = result.httpCode,
                        retries = 0,
                        retryAfterMs = null,
                        durationMs = System.currentTimeMillis() - singleStarted
                    )
                )
                result.text
            } else {
                val service = ChunkTranscriptionService(
                    transcriber = { chunk ->
                        whisperTranscriber.transcribeChunk(
                            this@WhisperInputService,
                            chunk.file,
                            chunkMediaType
                        )
                    },
                    progressListener = createProgressListener()
                )
                val runResult = service.transcribe(chunks)
                chunkLogLines = runResult.chunkLogLines
                runResult.mergedText
            }

            val processedText = when (postprocessing) {
                getString(R.string.settings_option_to_simplified) -> ChineseUtils.tw2s(rawText)
                getString(R.string.settings_option_to_traditional) -> ChineseUtils.s2tw(rawText)
                else -> rawText
            }

            val finalText = if (attachToEnd.isEmpty()) {
                processedText + if (addTrailingSpace) " " else ""
            } else {
                processedText + attachToEnd
            }

            transcriptionCallback(finalText)
        } catch (e: ChunkedTranscriptionException) {
            chunkLogLines = e.chunkLogLines
            outcome = "FAIL: ${e.message?.take(200)}"
            Log.e(TAG, "Chunked transcription failed: ${e.message}", e)
            transcriptionExceptionCallback(e.message ?: "Transcription failed")
        } catch (e: Exception) {
            outcome = "FAIL: ${e.message?.take(200)}"
            Log.e(TAG, "Chunked transcription failed: ${e.message}", e)
            transcriptionExceptionCallback(e.message ?: "Transcription failed")
        } finally {
            try {
                if (chunks.isNotEmpty()) audioChunker?.cleanup(chunks)
                audioFile.delete()
                val totalMs = System.currentTimeMillis() - started
                withContext(Dispatchers.IO) {
                    TranscriptionLogStore.append(
                        this@WhisperInputService,
                        TranscriptionEntry(
                            timestamp = started,
                            backend = backendForLog,
                            model = modelForLog,
                            totalChunks = chunks.size,
                            totalMs = totalMs,
                            outcome = outcome,
                            chunkLines = chunkLogLines
                        )
                    )
                }
            } catch (cleanupError: Exception) {
                Log.w(TAG, "Cleanup/logging failed: ${cleanupError.message}")
            }
        }
    }

    private fun createProgressListener(): ChunkProgressListener {
        return object : ChunkProgressListener {
            override fun onChunkingStarted(totalChunks: Int) {
                runOnMainThread {
                    whisperKeyboard.updateStatusText(getString(R.string.splitting_audio))
                }
            }

            override fun onChunkStarted(index: Int) {
                // No action needed
            }

            override fun onChunkCompleted(index: Int, completed: Int, total: Int) {
                runOnMainThread {
                    whisperKeyboard.updateStatusText(getString(R.string.transcribing_chunk, completed, total))
                }
            }

            override fun onChunkFailed(index: Int, error: Exception) {
                Log.w(TAG, "Chunk $index failed: ${error.message}")
            }

            override fun onMergingStarted() {
                runOnMainThread {
                    whisperKeyboard.updateStatusText(getString(R.string.merging_chunks))
                }
            }

            override fun onTranscriptionComplete(text: String) {
                // Progress will be reset by transcriptionCallback
            }
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            action()
        }
    }

    private fun onCancelTranscription() {
        whisperTranscriber.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun onEnter() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    private fun onSpaceBar() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(" ", 1)
    }

    private fun shouldShowRetry(): Boolean {
        val exists = File(recordedAudioFilename).exists()
        return exists
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()

        // If this is the first time calling onWindowShown, it means this IME is just being switched to.
        // Automatically starts recording after switching to Whisper Input. (if settings enabled)
        // Dispatch a coroutine to do this task.
        CoroutineScope(Dispatchers.Main).launch {
            // Update audio format based on current backend setting
            updateAudioFormat()
            if (!isFirstTime) return@launch
            isFirstTime = false
            val isAutoStartRecording = dataStore.data.map { preferences: Preferences ->
                preferences[AUTO_RECORDING_START] ?: true
            }.first()
            if (isAutoStartRecording) {
                whisperKeyboard.tryStartRecording()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }
}
