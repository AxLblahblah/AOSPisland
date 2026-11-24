package com.faroquetech.aospisland

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Real-time audio visualizer: captures the device's output mix FFT and exposes 5 band amplitudes (0..1).
 * Needs RECORD_AUDIO + MODIFY_AUDIO_SETTINGS; unavailable combos (or denied permission) set [failed] and
 * every user falls back to the fake animated bars. The session idles out on its own after 8 s without a kick.
 */
object VisualizerEngine {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var viz: android.media.audiofx.Visualizer? = null
    @Volatile private var failed = false
    @Volatile private var lastKick = 0L
    @Volatile private var amps: FloatArray? = null
    const val BANDS = 5

    fun available() = !failed

    /** Latest 5-band amplitudes, written into [out]; false when there is no live data this frame. */
    fun bands(out: FloatArray): Boolean {
        val a = amps ?: return false
        for (i in out.indices) out[i] = a[i.coerceAtMost(a.lastIndex)]
        return true
    }

    /** Kept called from drawing code while playing wave bars are visible; starts the capture on first use. */
    fun kick() {
        if (failed) return
        lastKick = SystemClock.uptimeMillis()
        synchronized(this) {
            if (viz == null) try {
                val v = android.media.audiofx.Visualizer(0)
                val range = android.media.audiofx.Visualizer.getCaptureSizeRange()
                v.captureSize = range[1].coerceAtMost(1024)
                val listener = object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 12) return
                        val n = fft.size / 2 - 1
                        val out = FloatArray(BANDS)
                        val per = (n / BANDS).coerceAtLeast(1)
                        for (b in 0 until BANDS) {
                            var peak = 0f
                            for (i in b * per until minOf(n, (b + 1) * per)) {
                                val re = fft[2 * i].toFloat(); val im = fft[2 * i + 1].toFloat()
                                val m = kotlin.math.sqrt(re * re + im * im) / 128f
                                if (m > peak) peak = m
                            }
                            out[b] = peak.coerceIn(0f, 1f)
                        }
                        amps = out
                    }
                }
                v.setDataCaptureListener(listener, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, false, true)
                v.enabled = true
                viz = v
            } catch (_: Throwable) {
                failed = true; viz = null
            }
        }
        main.removeCallbacks(idleStop)
        main.postDelayed(idleStop, 8000)
    }

    private val idleStop = Runnable {
        synchronized(this) { try { viz?.enabled = false; viz?.release() } catch (_: Throwable) { }; viz = null }
    }
}
