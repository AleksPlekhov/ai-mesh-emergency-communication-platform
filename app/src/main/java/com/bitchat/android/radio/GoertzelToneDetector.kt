package com.bitchat.android.radio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Goertzel Tone Detector
 *
 * Detects the EAS (Emergency Alert System) dual-tone header:
 *   853 Hz + 960 Hz simultaneously
 *
 * The Goertzel algorithm computes the DFT magnitude at a single target
 * frequency without a full FFT. It's optimal for detecting a small number
 * of known frequencies in a real-time audio stream.
 *
 * Usage:
 *   val detector = GoertzelToneDetector(sampleRateHz = 8000)
 *   val result = detector.detect(audioChunk)
 *   if (result.isEasAlert) { ... }
 */
class GoertzelToneDetector(private val sampleRateHz: Int = 8000) {

    companion object {
        /** EAS attention signal frequencies (ANSI S42.502-2010) */
        private const val EAS_FREQ_1_HZ = 853.0
        private const val EAS_FREQ_2_HZ = 960.0

        /** Detection threshold — magnitude squared relative to chunk energy.
         *  Tuned for typical FM audio levels; adjust if false-positive rate is too high. */
        private const val DETECTION_THRESHOLD = 0.35

        /** Minimum number of consecutive chunks that must trigger before alerting.
         *  At 8000 Hz / 256 samples per chunk = ~31 chunks/s; 5 chunks = ~160 ms. */
        private const val CONSECUTIVE_CHUNKS_REQUIRED = 5
    }

    data class DetectionResult(
        val magnitude853: Double,
        val magnitude960: Double,
        val chunkEnergy: Double,
        val isEasAlert: Boolean
    )

    private var consecutiveHits = 0

    /**
     * Analyze one chunk of audio samples (16-bit PCM, any chunk size).
     *
     * @param samples Raw PCM samples (typically 256–512 per chunk)
     * @return Detection result with individual magnitudes and alert flag
     */
    fun detect(samples: ShortArray): DetectionResult {
        val n = samples.size
        val mag853 = goertzel(samples, EAS_FREQ_1_HZ, n)
        val mag960 = goertzel(samples, EAS_FREQ_2_HZ, n)
        val energy = chunkEnergy(samples)

        // Normalize against chunk energy to be invariant to volume
        val rel853 = if (energy > 0.0) mag853 / energy else 0.0
        val rel960 = if (energy > 0.0) mag960 / energy else 0.0

        val bothPresent = rel853 > DETECTION_THRESHOLD && rel960 > DETECTION_THRESHOLD

        if (bothPresent) {
            consecutiveHits++
        } else {
            consecutiveHits = 0
        }

        val isAlert = consecutiveHits >= CONSECUTIVE_CHUNKS_REQUIRED

        return DetectionResult(
            magnitude853 = mag853,
            magnitude960 = mag960,
            chunkEnergy = energy,
            isEasAlert = isAlert
        )
    }

    /**
     * Reset the consecutive-hit counter (call when monitoring resumes after a gap).
     */
    fun reset() {
        consecutiveHits = 0
    }

    // ── Goertzel algorithm ────────────────────────────────────────────────────

    private fun goertzel(samples: ShortArray, targetFreq: Double, n: Int): Double {
        val k = (0.5 + n * targetFreq / sampleRateHz).toLong()
        val omega = 2.0 * PI * k / n
        val coeff = 2.0 * cos(omega)

        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0

        for (sample in samples) {
            q0 = coeff * q1 - q2 + sample.toDouble()
            q2 = q1
            q1 = q0
        }

        // Magnitude squared (avoids sqrt for comparison)
        return sqrt(q1 * q1 + q2 * q2 - q1 * q2 * coeff)
    }

    private fun chunkEnergy(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) {
            val d = s.toDouble()
            sum += d * d
        }
        return sqrt(sum / samples.size)
    }
}
