package com.yesmin.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private fun rms(b: ByteArray, n: Int): Float {
    var sum = 0.0
    var i = 0
    val cnt = n / 2
    if (cnt == 0) return 0f
    while (i + 1 < n) {
        val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
        sum += v.toDouble() * v
        i += 2
    }
    return (sqrt(sum / cnt) / 8000.0).coerceIn(0.0, 1.0).toFloat()
}

/** Mic hamesha khula: 16kHz mono, echo cancel + noise suppress + auto gain. */
class MicStream(private val onChunk: (ByteArray, Int) -> Unit) {
    private var rec: AudioRecord? = null
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    @Volatile var muted = false
    @Volatile var lastFrameAt = System.currentTimeMillis()
    @Volatile var level = 0f

    @Suppress("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        rec = r
        val sid = r.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sid)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sid)?.also { it.enabled = true }
        if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sid)?.also { it.enabled = true }
        r.startRecording()
        lastFrameAt = System.currentTimeMillis()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        job = sc.launch {
            val buf = ByteArray(3200) // 100 ms
            while (isActive) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    lastFrameAt = System.currentTimeMillis()
                    level = if (muted) 0f else rms(buf, n)
                    if (!muted) onChunk(buf, n)
                } else if (n < 0) {
                    delay(50)
                }
            }
        }
    }

    fun stop() {
        scope?.cancel(); scope = null; job = null
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null
        aec?.release(); ns?.release(); agc?.release()
        aec = null; ns = null; agc = null
    }
}

/** Yesmin ki awaaz (24kHz) stream karke bajata hai; interrupt par turant flush. */
class PcmPlayer {
    private val q = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val track: AudioTrack
    @Volatile private var queued = 0L
    @Volatile var level = 0f

    init {
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min * 4, 48000))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play()
        scope.launch {
            for (c in q) {
                queued -= c.size
                level = rms(c, c.size)
                track.write(c, 0, c.size)
            }
        }
    }

    fun write(b: ByteArray) { queued += b.size; q.trySend(b) }

    fun flush() {
        while (q.tryReceive().isSuccess) { /* drop */ }
        queued = 0; level = 0f
        try { track.pause(); track.flush(); track.play() } catch (_: Exception) {}
    }

    suspend fun awaitDrain() {
        while (queued > 0) delay(50)
        delay(700)
    }

    fun release() {
        scope.cancel(); q.close()
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }
}

object Pcm {
    /** Voice preview ke liye ek baar bajao. */
    suspend fun playOnce(pcm: ByteArray, rate: Int = 24000) = withContext(Dispatchers.IO) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(pcm, 0, pcm.size)
        t.play()
        delay(pcm.size * 1000L / (rate * 2) + 250)
        t.release()
    }
}
