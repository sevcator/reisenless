package com.topjohnwu.magisk.core.utils

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.Charset

class AXML(b: ByteArray) {

    var bytes = b
        private set

    companion object {
        private const val CHUNK_SIZE_OFF = 4
        private const val STRING_INDICES_OFF = 7 * 4
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val XML_NODE_HEADER_SIZE = 16
        private const val UTF8_FLAG = 1 shl 8
        private val UTF_8 = Charset.forName("UTF-8")
        private val UTF_16LE = Charset.forName("UTF-16LE")
    }


    fun patchIntAttribute(name: String, value: Int): Boolean {
        return patchIntAttributes(name) { value }
    }


    fun patchIntAttributes(name: String, valueAt: (Int) -> Int): Boolean {
        val buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        val strings = readStrings(buffer) ?: return false
        var patched = false
        var matchIndex = 0
        var offset = 8
        while (offset + 8 <= bytes.size) {
            val type = buffer.getShort(offset).toInt() and 0xffff
            val size = buffer.getInt(offset + CHUNK_SIZE_OFF)
            if (size < 8 || offset + size > bytes.size) return false
            if (type == RES_XML_START_ELEMENT_TYPE) {
                if (size < XML_NODE_HEADER_SIZE + 20) return false
                val attributeStart = buffer.getShort(offset + 24).toInt() and 0xffff
                val attributeSize = buffer.getShort(offset + 26).toInt() and 0xffff
                val attributeCount = buffer.getShort(offset + 28).toInt() and 0xffff
                if (attributeSize < 20) return false
                var attribute = offset + XML_NODE_HEADER_SIZE + attributeStart
                repeat(attributeCount) {
                    if (attribute + attributeSize > offset + size) return false
                    val nameIndex = buffer.getInt(attribute + 4)
                    if (nameIndex in strings.indices && strings[nameIndex] == name) {
                        buffer.putInt(attribute + 16, valueAt(matchIndex++))
                        patched = true
                    }
                    attribute += attributeSize
                }
            }
            offset += size
        }
        return patched
    }

    private fun readStrings(buffer: ByteBuffer): List<String>? {
        var start = 8
        while (start + 8 <= bytes.size) {
            val size = buffer.getInt(start + CHUNK_SIZE_OFF)
            if (size < 8 || start + size > bytes.size) return null
            if (buffer.getInt(start) == 0x1C0001) break
            start += size
        }
        if (start + 8 > bytes.size) return null

        val count = buffer.getInt(start + 8)
        val styleCount = buffer.getInt(start + 12)
        val flags = buffer.getInt(start + 16)
        val dataOff = start + buffer.getInt(start + 20)
        if (count < 0 || styleCount != 0 || dataOff !in start until bytes.size) return null
        val utf8 = flags and UTF8_FLAG != 0
        return List(count) { index ->
            val indexOff = start + STRING_INDICES_OFF + index * 4
            if (indexOff + 4 > bytes.size) return null
            val off = dataOff + buffer.getInt(indexOff)
            decodeString(buffer, off, utf8) ?: return null
        }
    }

    private fun decodeString(buffer: ByteBuffer, offset: Int, utf8: Boolean): String? {
        if (offset !in bytes.indices) return null
        return if (utf8) {
            val utf16Length = readUtf8Length(buffer, offset) ?: return null
            val byteLength = readUtf8Length(buffer, offset + utf16Length.second) ?: return null
            val data = offset + utf16Length.second + byteLength.second
            if (byteLength.first < 0 || data + byteLength.first > bytes.size) return null
            String(bytes, data, byteLength.first, UTF_8)
        } else {
            val length = readUtf16Length(buffer, offset) ?: return null
            val data = offset + length.second
            val byteLength = length.first.toLong() * 2L
            if (length.first < 0 || byteLength > Int.MAX_VALUE || data + byteLength > bytes.size) {
                return null
            }
            String(bytes, data, byteLength.toInt(), UTF_16LE)
        }
    }

    private fun readUtf8Length(buffer: ByteBuffer, offset: Int): Pair<Int, Int>? {
        if (offset !in bytes.indices) return null
        val first = buffer.get(offset).toInt() and 0xff
        if (first and 0x80 == 0) return first to 1
        if (offset + 1 >= bytes.size) return null
        return (((first and 0x7f) shl 8) or (buffer.get(offset + 1).toInt() and 0xff)) to 2
    }

    private fun readUtf16Length(buffer: ByteBuffer, offset: Int): Pair<Int, Int>? {
        if (offset < 0 || offset + 2 > bytes.size) return null
        val first = buffer.getShort(offset).toInt() and 0xffff
        if (first and 0x8000 == 0) return first to 2
        if (offset + 4 > bytes.size) return null
        val second = buffer.getShort(offset + 2).toInt() and 0xffff
        return (((first and 0x7fff) shl 16) or second) to 4
    }














    fun patchStrings(mapFn: (String) -> String): Boolean {
        val buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)

        fun findStringPool(): Int {
            var offset = 8
            while (offset < bytes.size) {
                if (buffer.getInt(offset) == 0x1C0001)
                    return offset
                offset += buffer.getInt(offset + CHUNK_SIZE_OFF)
            }
            return -1
        }

        val start = findStringPool()
        if (start < 0)
            return false


        val size = buffer.getInt(start + 4)
        val count = buffer.getInt(start + 8)
        val styleCount = buffer.getInt(start + 12)
        val flags = buffer.getInt(start + 16)
        val dataOff = start + buffer.getInt(start + 20)
        if (count < 0 || styleCount != 0 || dataOff !in start until bytes.size) return false
        val utf8 = flags and UTF8_FLAG != 0

        val strList = ArrayList<String>(count)

        for (i in 0 until count) {
            val indexOff = start + STRING_INDICES_OFF + i * 4
            if (indexOff + 4 > bytes.size) return false
            val off = dataOff + buffer.getInt(indexOff)
            strList.add(decodeString(buffer, off, utf8) ?: return false)
        }

        val strArr = strList.toTypedArray()
        for (i in strArr.indices) {
            strArr[i] = mapFn(strArr[i])
        }


        val baos = RawByteStream()
        baos.write(bytes, 0, dataOff)


        val offList = IntArray(count)
        for (i in 0 until count) {
            offList[i] = baos.size() - dataOff
            val str = strArr[i]
            if (utf8) {
                val encoded = str.toByteArray(UTF_8)
                baos.write(str.length.toUtf8LengthBytes())
                baos.write(encoded.size.toUtf8LengthBytes())
                baos.write(encoded)
            } else {
                baos.write(str.length.toUtf16LengthBytes())
                baos.write(str.toByteArray(UTF_16LE))
            }

            baos.write(0)
            baos.write(0)
        }
        baos.align()

        val sizeDiff = baos.size() - start - size
        val newBuffer = ByteBuffer.wrap(baos.buffer).order(LITTLE_ENDIAN)


        newBuffer.putInt(CHUNK_SIZE_OFF, buffer.getInt(CHUNK_SIZE_OFF) + sizeDiff)

        newBuffer.putInt(start + CHUNK_SIZE_OFF, size + sizeDiff)

        newBuffer.position(start + STRING_INDICES_OFF)
        val newIntBuf = newBuffer.asIntBuffer()
        offList.forEach { newIntBuf.put(it) }


        val nextOff = start + size
        baos.write(bytes, nextOff, bytes.size - nextOff)

        bytes = baos.toByteArray()
        return true
    }

    private fun Int.toUtf8LengthBytes(): ByteArray {
        require(this in 0..0x7fff)
        return if (this < 0x80) {
            byteArrayOf(toByte())
        } else {
            byteArrayOf(((this shr 8) or 0x80).toByte(), toByte())
        }
    }

    private fun Int.toUtf16LengthBytes(): ByteArray {
        require(this >= 0)
        val b = ByteBuffer.allocate(if (this < 0x8000) 2 else 4).order(LITTLE_ENDIAN)
        if (this < 0x8000) {
            b.putShort(toShort())
        } else {
            b.putShort(((this shr 16) or 0x8000).toShort())
            b.putShort(toShort())
        }
        return b.array()
    }

    private class RawByteStream : ByteArrayOutputStream() {
        val buffer: ByteArray get() = buf

        fun align(alignment: Int = 4) {
            val newCount = (count + alignment - 1) / alignment * alignment
            for (i in 0 until (newCount - count))
                write(0)
        }
    }
}
