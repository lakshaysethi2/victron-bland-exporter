package com.lakshaysethi.victronbleexporter.tunnel

import java.io.File

/**
 * Result of inspecting the bundled cloudflared ELF binary.
 *
 * The linking style decides which DNS resolver the child process uses, which is
 * the difference between the two failure modes seen on device:
 *
 * - **static** (no PT_DYNAMIC): cloudflared was built with CGO_ENABLED=0, so Go
 *   uses its own resolver, which reads `/etc/resolv.conf` (on Android that file
 *   points at loopback `::1`/`127.0.0.1` where nothing listens in the app
 *   sandbox) and gets `connection refused`. bindProcessToNetwork cannot fix this.
 * - **dynamic** (PT_DYNAMIC present, interp `/system/bin/linker64`, NEEDED libc):
 *   built with CGO_ENABLED=1 against the NDK, so Go prefers the cgo resolver on
 *   Android (see go/src/net/conf.go goosPrefersCgo) and DNS goes through bionic
 *   libc `getaddrinfo` → netd, exactly like the parent app's own resolution.
 */
internal data class BinaryInfo(
    val isElf: Boolean,
    val is64Bit: Boolean,
    val machine: String?,
    val isDynamic: Boolean,
    val interp: String?,
    val error: String?,
) {
    /** One-line human summary for debug logs / self-test. */
    fun summary(): String {
        if (!isElf) return "not an ELF binary${error?.let { " ($it)" } ?: ""}"
        val arch = machine ?: "unknown arch"
        val linkage = if (isDynamic) {
            "dynamic (DNS via bionic libc → netd)"
        } else {
            "STATIC (pure-Go resolver reads /etc/resolv.conf — child DNS fails on Android)"
        }
        val interpPart = interp?.let { ", interp $it" } ?: ""
        return "ELF${if (is64Bit) "64" else "32"} $arch, $linkage$interpPart"
    }
}

/**
 * Minimal ELF header parser — enough to tell how the bundled cloudflared binary
 * is linked (static Go vs cgo/bionic), which determines its DNS resolution path.
 *
 * Parses from a byte array read ONCE from the file (never seek/read on the file
 * directly) so every field comes from a single consistent snapshot; a 40MB
 * cloudflared only needs its first few KB read.
 */
internal object TunnelBinaryInspector {

    private const val ELF_MAGIC_0 = 0x7F
    private const val PT_INTERP: Long = 3
    private const val PT_DYNAMIC: Long = 2

    private val MACHINES = mapOf(
        0x3E to "x86-64",
        0xB7 to "AArch64",
        0x28 to "ARM",
        0x03 to "x86",
    )

    /** Bytes of the ELF header + program header table needed for the parse. */
    private const val MAX_HEADER_BYTES = 4096

    fun inspect(file: File): BinaryInfo {
        if (!file.exists() || file.length() < 52) {
            return BinaryInfo(
                isElf = false,
                is64Bit = false,
                machine = null,
                isDynamic = false,
                interp = null,
                error = "file missing or too small (${file.length()} bytes)",
            )
        }
        return try {
            val bytes = file.inputStream().use { input ->
                val buf = ByteArray(minOf(file.length(), MAX_HEADER_BYTES.toLong()).toInt())
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                buf
            }
            inspectBytes(bytes)
        } catch (e: Exception) {
            BinaryInfo(
                isElf = false,
                is64Bit = false,
                machine = null,
                isDynamic = false,
                interp = null,
                error = "${e.javaClass.simpleName}: ${e.message ?: ""}",
            )
        }
    }

    internal fun inspectBytes(b: ByteArray): BinaryInfo {
        if (b.size < 52) {
            return BinaryInfo(
                isElf = false,
                is64Bit = false,
                machine = null,
                isDynamic = false,
                interp = null,
                error = "buffer too small (${b.size} bytes)",
            )
        }
        if (b[0] != ELF_MAGIC_0.toByte() || b[1] != 'E'.code.toByte() ||
            b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()
        ) {
            return BinaryInfo(false, false, null, false, null, "bad magic")
        }
        val is64 = b[4] == 2.toByte()
        val le = b[5] == 1.toByte()
        if (b[4] != 1.toByte() && !is64) {
            return BinaryInfo(false, false, null, false, null, "unknown ELF class ${b[4]}")
        }
        if (!le) {
            return BinaryInfo(false, false, null, false, null, "big-endian ELF unsupported")
        }

        val machine = u16(b, 0x12)
        val ePhoff = if (is64) u64(b, 0x20) else u32(b, 0x1C)
        val ePhentsize = if (is64) u16(b, 0x36) else u16(b, 0x2A)
        val ePhnum = if (is64) u16(b, 0x38) else u16(b, 0x2C)

        if (ePhoff <= 0 || ePhnum == 0 || ePhentsize < 8) {
            return BinaryInfo(true, is64, MACHINES[machine], false, null, "no program headers")
        }

        var isDynamic = false
        var interp: String? = null
        for (i in 0 until ePhnum) {
            val off = (ePhoff + i.toLong() * ePhentsize).toInt()
            if (off + 16 > b.size) break
            val pType = u32(b, off)
            when (pType) {
                PT_DYNAMIC -> isDynamic = true
                PT_INTERP -> {
                    val pOffset = if (is64) u64(b, off + 0x08) else u32(b, off + 0x04)
                    val pFilesz = if (is64) u64(b, off + 0x20) else u32(b, off + 0x10)
                    if (pFilesz in 1..1024 && pOffset + pFilesz <= b.size) {
                        interp = String(b, pOffset.toInt(), pFilesz.toInt(), Charsets.US_ASCII)
                            .trimEnd('\u0000')
                    }
                }
            }
        }

        return BinaryInfo(
            isElf = true,
            is64Bit = is64,
            machine = MACHINES[machine],
            isDynamic = isDynamic,
            interp = interp,
            error = null,
        )
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }
}
