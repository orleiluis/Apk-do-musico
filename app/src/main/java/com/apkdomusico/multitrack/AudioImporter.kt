package com.apkdomusico.multitrack

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converte qualquer áudio que o Android saiba abrir (MP3, AAC/M4A, FLAC, OGG, WAV...)
 * num WAV PCM 16 bits. A conversão para 48 kHz acontece no motor C++ ao carregar.
 */
object AudioImporter {

    class ImportException(message: String) : Exception(message)

    fun decodeToWav(context: Context, uri: Uri, out: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            decode(extractor, out)
        } finally {
            extractor.release()
        }
    }

    fun decodeToWav(input: File, out: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            decode(extractor, out)
        } finally {
            extractor.release()
        }
    }

    private fun decode(extractor: MediaExtractor, out: File) {
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw ImportException("O arquivo não tem áudio.")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false

        val writer = PcmWavWriter(out)
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        if (!writer.started) writer.start(sampleRate, channels)
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        writer.write(buf.slice().order(ByteOrder.nativeOrder()), isFloat)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (!writer.started) throw ImportException("Não foi possível decodificar o áudio.")
        } finally {
            writer.close()
            codec.stop()
            codec.release()
        }
    }

    /** Escreve WAV 16 bits; o cabeçalho é corrigido no fim. */
    private class PcmWavWriter(private val file: File) {
        private var raf: RandomAccessFile? = null
        private var dataBytes = 0L
        private var channels = 1
        private var rate = 44_100
        var started = false
            private set

        fun start(sampleRate: Int, channelCount: Int) {
            rate = sampleRate
            channels = channelCount
            raf = RandomAccessFile(file, "rw").also {
                it.setLength(0)
                it.write(header(0))
            }
            started = true
        }

        fun write(pcm: ByteBuffer, isFloat: Boolean) {
            val r = raf ?: return
            val bytes: ByteArray
            if (isFloat) {
                val floats = pcm.asFloatBuffer()
                val out = ByteBuffer.allocate(floats.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (floats.hasRemaining()) {
                    val v = (floats.get().coerceIn(-1f, 1f) * 32767f).toInt()
                    out.putShort(v.toShort())
                }
                bytes = out.array()
            } else {
                val shorts = pcm.asShortBuffer()
                val out = ByteBuffer.allocate(shorts.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (shorts.hasRemaining()) out.putShort(shorts.get())
                bytes = out.array()
            }
            r.write(bytes)
            dataBytes += bytes.size
        }

        fun close() {
            val r = raf ?: return
            r.seek(0)
            r.write(header(dataBytes))
            r.close()
            raf = null
        }

        private fun header(data: Long): ByteArray {
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            val blockAlign = channels * 2
            b.put("RIFF".toByteArray())
            b.putInt((36 + data).coerceAtMost(0xFFFFFFFFL).toInt())
            b.put("WAVEfmt ".toByteArray())
            b.putInt(16)
            b.putShort(1)
            b.putShort(channels.toShort())
            b.putInt(rate)
            b.putInt(rate * blockAlign)
            b.putShort(blockAlign.toShort())
            b.putShort(16)
            b.put("data".toByteArray())
            b.putInt(data.coerceAtMost(0xFFFFFFFFL).toInt())
            return b.array()
        }
    }
}
