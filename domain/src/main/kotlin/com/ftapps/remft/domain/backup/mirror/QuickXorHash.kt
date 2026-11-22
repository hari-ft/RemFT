package com.ftapps.remft.domain.backup.mirror

import java.io.File
import java.io.InputStream
import java.util.Base64

class QuickXorHash {
    private val data = LongArray(3)
    private var shiftSoFar = 0
    private var lengthSoFar = 0L

    fun add(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        val cbSize = length
        if (cbSize == 0) return
        var vectorArrayIndex = shiftSoFar / 64
        var vectorOffset = shiftSoFar % 64
        val iterations = if (cbSize < WIDTH_IN_BITS) cbSize else WIDTH_IN_BITS
        for (i in 0 until iterations) {
            val isLastCell = vectorArrayIndex == data.size - 1
            val bitsInVectorCell = if (isLastCell) WIDTH_IN_BITS % 64 else 64
            if (vectorOffset <= bitsInVectorCell - 8) {
                var j = i
                while (j < cbSize) {
                    data[vectorArrayIndex] = data[vectorArrayIndex] xor ((bytes[offset + j].toLong() and 0xff) shl vectorOffset)
                    j += WIDTH_IN_BITS
                }
            } else {
                val index1 = vectorArrayIndex
                val index2 = if (isLastCell) 0 else vectorArrayIndex + 1
                val low = bitsInVectorCell - vectorOffset
                var xored = 0L
                var j = i
                while (j < cbSize) {
                    xored = xored xor (bytes[offset + j].toLong() and 0xff)
                    j += WIDTH_IN_BITS
                }
                data[index1] = data[index1] xor (xored shl vectorOffset)
                data[index2] = data[index2] xor (xored ushr low)
            }
            vectorOffset += SHIFT
            while (vectorOffset >= bitsInVectorCell) {
                vectorArrayIndex = if (isLastCell) 0 else vectorArrayIndex + 1
                vectorOffset -= bitsInVectorCell
            }
        }
        shiftSoFar = ((shiftSoFar + SHIFT.toLong() * (cbSize % WIDTH_IN_BITS)) % WIDTH_IN_BITS).toInt()
        lengthSoFar += cbSize
    }

    fun digest(): ByteArray {
        val out = ByteArray(20)
        putLittleEndian(out, 0, data[0], 8)
        putLittleEndian(out, 8, data[1], 8)
        putLittleEndian(out, 16, data[2] and 0xffffffffL, 4)
        for (i in 0 until 8) {
            out[12 + i] = (out[12 + i].toInt() xor ((lengthSoFar ushr (8 * i)).toInt() and 0xff)).toByte()
        }
        return out
    }

    fun base64Digest(): String = Base64.getEncoder().encodeToString(digest())

    private fun putLittleEndian(out: ByteArray, at: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) out[at + i] = (value ushr (8 * i)).toByte()
    }

    companion object {
        private const val WIDTH_IN_BITS = 160
        private const val SHIFT = 11

        fun of(bytes: ByteArray): String = QuickXorHash().apply { add(bytes) }.base64Digest()

        fun of(input: InputStream): String {
            val hash = QuickXorHash()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                hash.add(buffer, 0, read)
            }
            return hash.base64Digest()
        }

        fun of(file: File): String = file.inputStream().use { of(it) }
    }
}
