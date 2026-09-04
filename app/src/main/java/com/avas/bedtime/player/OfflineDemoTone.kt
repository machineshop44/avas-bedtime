package com.avas.bedtime.player

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin

/**
 * Builds a soft local WAV so bedtime can keep playing with no network
 * (Plex unreachable / trip with no data). Never uses a remote URL.
 */
object OfflineDemoTone {
    private const val FILE_NAME = "offline_bedtime_demo.wav"
    private const val SAMPLE_RATE = 22_050
    private const val DURATION_SEC = 48

    fun file(context: Context): File {
        val out = File(context.filesDir, FILE_NAME)
        if (out.exists() && out.length() > 44L) return out
        writeSoftLoop(out)
        return out
    }

    private fun writeSoftLoop(file: File) {
        val totalSamples = SAMPLE_RATE * DURATION_SEC
        val dataBytes = totalSamples * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            // RIFF header
            raf.writeBytes("RIFF")
            raf.writeIntLE(36 + dataBytes)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1) // PCM
            raf.writeShortLE(1) // mono
            raf.writeIntLE(SAMPLE_RATE)
            raf.writeIntLE(SAMPLE_RATE * 2)
            raf.writeShortLE(2)
            raf.writeShortLE(16)
            raf.writeBytes("data")
            raf.writeIntLE(dataBytes)

            // Quiet layered sines (C4 / E4 / G4) with slow amplitude swell — lullaby-ish, not a beep.
            val freqs = doubleArrayOf(261.63, 329.63, 392.00)
            val amp = 0.18
            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = 0.35 + 0.65 * (0.5 + 0.5 * sin(2.0 * PI * t / 12.0))
                var sample = 0.0
                for (f in freqs) {
                    sample += sin(2.0 * PI * f * t)
                }
                sample = (sample / freqs.size) * amp * envelope
                val pcm = (sample * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                raf.writeShortLE(pcm)
            }
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }
}
