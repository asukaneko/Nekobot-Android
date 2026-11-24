package app.lianxu.independent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Safe to surface without the key, sentence, provider message or response body. */
internal class BreezeBlueSpeechException(val reason: Reason, val code: Int? = null) : IOException(reason.name) {
    enum class Reason { INVALID_INPUT, MISSING_CREDENTIAL, HTTP, INVALID_AUDIO, TOO_LARGE, BUSY, TIMEOUT, NETWORK }
}

/** A single short utterance in, MP3 bytes out. Does not log the utterance or audio or store a result. */
internal class BreezeBlueSpeechProvider(
    private val speechKey: () -> String?,
    private val connections: HttpsConnectionFactory = HttpsConnectionFactory { it.openConnection() as HttpsURLConnection },
    private val networkTimeoutMillis: Long = 30_000L
) {
    companion object {
        private const val ENDPOINT = "https://api.breeze.blue/v1/text-to-speech"
        private const val MODEL = "breeze-tts-2"
        internal const val MAX_TEXT = 300 // Unicode code points
        private const val MAX_AUDIO = 2 * 1024 * 1024

        private const val MAX_CONNECTIONS = 4
        private val networkWorkers = ThreadPoolExecutor(
            MAX_CONNECTIONS, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_CONNECTIONS),
            { work -> Thread(work, "breezeblue-speech-http").apply { isDaemon = true } }
        ).apply { allowCoreThreadTimeOut(true) }
        private val disconnectWorkers = ThreadPoolExecutor(
            MAX_CONNECTIONS, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_CONNECTIONS),
            { work -> Thread(work, "breezeblue-speech-disconnect").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy()
        ).apply { allowCoreThreadTimeOut(true) }
        private val mpeg1Bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        private val mpeg2Bitrates = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        private val sampleRates = intArrayOf(44_100, 48_000, 32_000)

        /** Look up only a dedicated speech record. Never fall back to the selected chat account. */
        fun fromVault(context: Context): BreezeBlueSpeechProvider {
            val app = context.applicationContext
            return BreezeBlueSpeechProvider(speechKey = {
                ModelVault.existingSecret(app.noBackupFilesDir, "breezeblue-speech", "breezeblue-tts")
            })
        }
    }

    suspend fun synthesize(text: String, voiceId: String): ByteArray {
        if (text.length > 2 * MAX_TEXT || text.isBlank() || text.codePointCount(0, text.length) > MAX_TEXT ||
            voiceId.length !in 1..256 || voiceId.isBlank() || voiceId == "." || voiceId == ".." ||
            voiceId.any { it.isISOControl() || it == '/' || it == '\\' } ||
            !Charsets.UTF_8.newEncoder().canEncode(voiceId) || !Charsets.UTF_8.newEncoder().canEncode(text)) {
            fail(BreezeBlueSpeechException.Reason.INVALID_INPUT)
        }
        val payload = JSONObject().put("text", text).put("model_id", MODEL).toString().toByteArray(Charsets.UTF_8)
        val segment = URLEncoder.encode(voiceId, "UTF-8").replace("+", "%20")
        val url = URL("$ENDPOINT/$segment?output_format=mp3&delivery=sync")
        return try {
            withTimeoutOrNull(networkTimeoutMillis.coerceIn(1L, 30_000L)) { exchange(url, payload) }
                ?: fail(BreezeBlueSpeechException.Reason.TIMEOUT)
        } catch (e: BreezeBlueSpeechException) {
            throw e
        } catch (_: SocketTimeoutException) {
            fail(BreezeBlueSpeechException.Reason.TIMEOUT)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            fail(BreezeBlueSpeechException.Reason.NETWORK)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            fail(BreezeBlueSpeechException.Reason.NETWORK)
        }
    }

    private fun requireKey(): String {
        val key = try {
            speechKey()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fail(BreezeBlueSpeechException.Reason.MISSING_CREDENTIAL)
        }
        if (key.isNullOrEmpty() || key.length > 8192 || key.any { it.code !in 33..126 }) {
            fail(BreezeBlueSpeechException.Reason.MISSING_CREDENTIAL)
        }
        return key
    }

    private suspend fun exchange(url: URL, payload: ByteArray): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpsURLConnection?>()
            fun disconnect() {
                try { active.get()?.disconnect() } catch (_: Exception) { /* Best effort. */ }
            }
            val task = FutureTask<Unit> {
                val result = try {
                    continuation.context.ensureActive()
                    val key = requireKey()
                    continuation.context.ensureActive()
                    val connection = connections.open(url)
                    active.set(connection)
                    continuation.context.ensureActive()
                    val bytes = post(connection, key, payload) { continuation.context.ensureActive() }
                    Result.success(bytes)
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    disconnect()
                    active.set(null)
                }
                continuation.resumeWith(result)
            }
            continuation.invokeOnCancellation {
                task.cancel(true)
                networkWorkers.remove(task)
                // A slow platform disconnect must not stall the coroutine deadline scheduler.
                if (active.get() != null) disconnectWorkers.execute { disconnect() }
            }
            try {
                if (!task.isCancelled) networkWorkers.execute(task)
                if (task.isCancelled) networkWorkers.remove(task)
            } catch (_: RejectedExecutionException) {
                continuation.resumeWith(Result.failure(BreezeBlueSpeechException(BreezeBlueSpeechException.Reason.BUSY)))
            }
        }

    private fun post(connection: HttpsURLConnection, key: String, payload: ByteArray, checkActive: () -> Unit): ByteArray {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(payload.size)
        connection.setRequestProperty("xi-api-key", key)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "audio/mpeg")
        connection.setRequestProperty("Accept-Encoding", "identity")
        checkActive()
        connection.outputStream.use { output ->
            checkActive()
            output.write(payload)
        }
        checkActive()
        val status = connection.responseCode
        if (status != 200) fail(BreezeBlueSpeechException.Reason.HTTP, status)
        if (!connection.contentType.orEmpty().substringBefore(';').trim().equals("audio/mpeg", true))
            fail(BreezeBlueSpeechException.Reason.INVALID_AUDIO)
        val declaredSize = connection.contentLengthLong
        if (declaredSize > MAX_AUDIO) fail(BreezeBlueSpeechException.Reason.TOO_LARGE)
        val response = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > MAX_AUDIO - response.size()) fail(BreezeBlueSpeechException.Reason.TOO_LARGE)
                response.write(buffer, 0, count)
            }
        }
        if (declaredSize >= 0 && connection.contentEncoding.orEmpty().let { it.isEmpty() || it.equals("identity", true) } &&
            declaredSize != response.size().toLong()) fail(BreezeBlueSpeechException.Reason.INVALID_AUDIO)
        val audio = response.toByteArray()
        if (!hasCompleteMp3Frames(audio)) fail(BreezeBlueSpeechException.Reason.INVALID_AUDIO)
        return audio
    }

    // Skip an optional ID3v2 tag before walking complete MPEG Layer III frames.
    private fun hasCompleteMp3Frames(audio: ByteArray): Boolean {
        if (audio.size < 4) return false
        var frame = 0
        if (audio[0] == 0x49.toByte() && audio[1] == 0x44.toByte() && audio[2] == 0x33.toByte()) {
            if (audio.size < 10) return false
            var size = 0
            for (i in 6..9) {
                val part = audio[i].toInt()
                if (part < 0) return false
                size = (size shl 7) or part
            }
            val footer = audio[3].toInt() == 4 && (audio[5].toInt() and 0x10) != 0
            frame = 10 + size + if (footer) 10 else 0
        }
        var frames = 0
        while (frame < audio.size) {
            val remaining = audio.size - frame
            if (frames > 0 && remaining == 128 && audio[frame] == 0x54.toByte() &&
                audio[frame + 1] == 0x41.toByte() && audio[frame + 2] == 0x47.toByte()) return true // ID3v1
            if (remaining < 4 || audio[frame] != 0xff.toByte()) return false
            val flags = audio[frame + 1].toInt() and 255
            val version = (flags ushr 3) and 3
            if ((flags and 0xe0) != 0xe0 || (flags and 6) != 2 || version == 1) return false
            val rate = audio[frame + 2].toInt() and 255
            val bitrate = (if (version == 3) mpeg1Bitrates else mpeg2Bitrates)[rate ushr 4]
            val frequencyIndex = (rate ushr 2) and 3
            if (bitrate == 0 || frequencyIndex == 3) return false
            val divisor = when (version) { 3 -> 1; 2 -> 2; else -> 4 }
            val hz = sampleRates[frequencyIndex] / divisor
            val length = (if (version == 3) 144_000 else 72_000) * bitrate / hz + ((rate ushr 1) and 1)
            if (length > remaining) return false
            frame += length
            frames++
        }
        return frames > 0
    }

    private fun fail(reason: BreezeBlueSpeechException.Reason, code: Int? = null): Nothing =
        throw BreezeBlueSpeechException(reason, code)
}
