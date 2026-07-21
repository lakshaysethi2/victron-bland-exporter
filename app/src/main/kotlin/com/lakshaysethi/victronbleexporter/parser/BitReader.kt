package com.lakshaysethi.victronbleexporter.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BitReader ported from keshavdv/victron-ble BitReader.
 * Reads bits LSB-first from a byte array.
 */
class BitReader(private val data: ByteArray) {
    private var byteIndex = 0
    private var bitOffset = 0

    fun readUnsignedInt(bits: Int): Int {
        var result = 0
        var remaining = bits
        var currentBit = bitOffset
        var currentByte = byteIndex

        while (remaining > 0) {
            if (currentByte >= data.size) break
            val byteVal = data[currentByte].toInt() and 0xFF
            val bitsInThisByte = 8 - currentBit
            val bitsToTake = minOf(remaining, bitsInThisByte)

            val mask = ((1 shl bitsToTake) - 1) shl currentBit
            val extracted = (byteVal and mask) shr currentBit
            result = result or (extracted shl (bits - remaining))

            remaining -= bitsToTake
            currentBit += bitsToTake
            if (currentBit >= 8) {
                currentBit = 0
                currentByte++
            }
        }

        byteIndex = currentByte
        bitOffset = currentBit
        return result
    }

    fun readSignedInt(bits: Int): Int {
        val unsigned = readUnsignedInt(bits)
        val signBit = 1 shl (bits - 1)
        return if (unsigned and signBit != 0) {
            unsigned - (1 shl bits)
        } else {
            unsigned
        }
    }

    fun hasMore(): Boolean {
        return byteIndex < data.size || (byteIndex == data.size - 1 && bitOffset < 8)
    }

    fun skip(bits: Int) {
        var remaining = bits
        bitOffset += remaining
        while (bitOffset >= 8) {
            bitOffset -= 8
            byteIndex++
        }
    }
}