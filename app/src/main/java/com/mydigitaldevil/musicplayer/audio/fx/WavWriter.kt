package com.mydigitaldevil.musicplayer.audio.fx

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streams mono float samples into a 16-bit PCM WAV, patching the header sizes on close. */
class WavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L
    private var scratch = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    init {
        raf.write(header(0))
    }

    fun write(samples: FloatArray, count: Int) {
        val needed = count * 2
        if (scratch.capacity() < needed) scratch = ByteBuffer.allocate(needed).order(ByteOrder.LITTLE_ENDIAN)
        scratch.clear()
        for (i in 0 until count) {
            scratch.putShort((samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        }
        raf.write(scratch.array(), 0, needed)
        dataBytes += needed
    }

    override fun close() {
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.close()
    }

    private fun header(dataLen: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + dataLen).toInt())
        put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16)
        putShort(1) // PCM
        putShort(1) // mono
        putInt(sampleRate)
        putInt(sampleRate * 2) // byte rate
        putShort(2) // block align
        putShort(16) // bits per sample
        put("data".toByteArray()); putInt(dataLen.toInt())
    }.array()
}
