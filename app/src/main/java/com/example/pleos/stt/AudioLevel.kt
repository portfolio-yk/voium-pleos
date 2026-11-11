package com.example.pleos.stt

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** 마이크 소리 크기 계산입니다 (16bit PCM → dBFS). */
object AudioLevel {
    /** 무음일 때의 dBFS 값입니다. */
    const val SILENT_DBFS = -96.0

    data class Measure(val dbfs: Double, val peak: Int)

    /** 16bit little-endian PCM의 RMS 크기(dBFS)와 최대 진폭을 구합니다. */
    fun measure(buffer: ByteArray, length: Int): Measure {
        var sum = 0.0
        var peak = 0
        val samples = length / 2
        for (i in 0 until samples) {
            val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            peak = maxOf(peak, abs(sample))
        }
        val rms = if (samples > 0) sqrt(sum / samples) else 0.0
        val dbfs = if (rms > 0) maxOf(SILENT_DBFS, 20 * log10(rms / 32768.0)) else SILENT_DBFS
        return Measure(dbfs, peak)
    }
}
