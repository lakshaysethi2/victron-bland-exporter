package com.lakshaysethi.victronbleexporter.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a minimal 64-bit little-endian ELF image in memory (header + program
 * headers + optional interp bytes) — no file I/O, so tests are immune to
 * filesystem caching quirks on CI hosts. [phdrs] = list of (p_type, p_offset,
 * p_filesz); remaining program-header fields are zero.
 */
private fun buildElf64(
    phdrs: List<Triple<Long, Long, Long>>,
    interpBytes: ByteArray = ByteArray(0),
): ByteArray {
    val buf = ByteBuffer.allocate(64 + phdrs.size * 56 + interpBytes.size)
        .order(ByteOrder.LITTLE_ENDIAN)
    buf.put(
        byteArrayOf(
            0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(),
            2, // ELFCLASS64
            1, // little endian
            1, // EV_CURRENT
            0, 0, 0, 0, 0, 0, 0, 0, 0, // EI_PAD (9 bytes) — ident must be 16 bytes
        ),
    )
    buf.putShort(2) // ET_EXEC
    buf.putShort(0xB7) // EM_AARCH64
    buf.putInt(1) // e_version
    buf.putLong(0) // e_entry
    buf.putLong(64) // e_phoff
    buf.putLong(0) // e_shoff
    buf.putInt(0) // e_flags
    buf.putShort(64) // e_ehsize
    buf.putShort(56) // e_phentsize
    buf.putShort(phdrs.size.toShort()) // e_phnum
    buf.putShort(0) // e_shentsize
    buf.putShort(0) // e_shnum
    buf.putShort(0) // e_shstrndx
    for (ph in phdrs) {
        buf.putInt(ph.first.toInt()) // p_type
        buf.putInt(0) // p_flags
        buf.putLong(ph.second) // p_offset
        buf.putLong(0) // p_vaddr
        buf.putLong(0) // p_paddr
        buf.putLong(ph.third) // p_filesz
        buf.putLong(0) // p_memsz
        buf.putLong(0) // p_align
    }
    buf.put(interpBytes)
    return buf.array()
}

/** Minimal fake controller for self-test binary checks (bind always succeeds). */
private class FakeProcessNetworkControllerForTest : ProcessNetworkController {
    override fun activeNetworkLabel(): String? = "100"
    override fun bindProcessToActiveNetwork(): Boolean = true
    override fun clearProcessNetworkBinding() {}
    override fun networkDiagnostics(): NetworkDiagnostics = NetworkDiagnostics(
        activeNetworkLabel = "100",
        hasInternet = true,
        isValidated = true,
        dnsServers = listOf("1.1.1.1"),
    )
}

class TunnelBinaryInspectorTest {

    @Test
    fun `bundled libcloudflared is a dynamic aarch64 ELF using linker64`() {
        // Regression guard: the child's DNS fix depends on the shipped binary
        // being the cgo/NDK build (dynamic, bionic getaddrinfo → netd). If a
        // static pure-Go build ever lands back in jniLibs, this test fails.
        val bundled = File("src/main/jniLibs/arm64-v8a/libcloudflared.so")
        assertTrue("bundled binary missing: ${bundled.absolutePath}", bundled.exists())
        assertTrue("bundled binary suspiciously small", bundled.length() > 1_000_000)

        val info = TunnelBinaryInspector.inspect(bundled)

        assertTrue(info.isElf)
        assertTrue(info.is64Bit)
        assertEquals("AArch64", info.machine)
        assertTrue("bundled binary must be dynamically linked (cgo/NDK build)", info.isDynamic)
        assertEquals("/system/bin/linker64", info.interp)
        assertNull(info.error)
        val summary = info.summary()
        assertTrue(summary.contains("dynamic"))
        assertTrue(summary.contains("netd"))
    }

    @Test
    fun `dynamic elf is detected with interp`() {
        val interp = "/system/bin/linker64\u0000".toByteArray(Charsets.US_ASCII)
        val interpOff = 64 + 56L * 3
        val bytes = buildElf64(
            listOf(
                Triple(0L, 0L, 0L), // null header
                Triple(2L, 200L, 16L), // PT_DYNAMIC
                Triple(3L, interpOff, interp.size.toLong()), // PT_INTERP
            ),
            interpBytes = interp,
        )
        val info = TunnelBinaryInspector.inspectBytes(bytes)
        assertTrue(info.isElf)
        assertTrue(info.is64Bit)
        assertEquals("AArch64", info.machine)
        assertTrue(info.isDynamic)
        assertEquals("/system/bin/linker64", info.interp)
        assertNull(info.error)
    }

    @Test
    fun `static elf reports not dynamic and no interp`() {
        val bytes = buildElf64(
            listOf(
                Triple(0L, 0L, 0L), // null header
                Triple(1L, 100L, 0x1000L), // PT_LOAD only
            ),
        )
        val info = TunnelBinaryInspector.inspectBytes(bytes)
        assertTrue(info.isElf)
        assertTrue(info.is64Bit)
        assertFalse(info.isDynamic)
        assertNull(info.interp)
        assertTrue(info.summary().contains("STATIC"))
    }

    @Test
    fun `non-elf and tiny buffers are rejected`() {
        val garbage = "this is not an elf file at all".toByteArray()
        val info = TunnelBinaryInspector.inspectBytes(garbage)
        assertFalse(info.isElf)
        assertFalse(info.isDynamic)
        assertTrue(info.summary().startsWith("not an ELF"))

        val tinyInfo = TunnelBinaryInspector.inspectBytes(ByteArray(10))
        assertFalse(tinyInfo.isElf)
        assertTrue(tinyInfo.error != null && tinyInfo.error!!.contains("too small"))
    }

    @Test
    fun `inspect of missing file reports error not crash`() {
        val info = TunnelBinaryInspector.inspect(File("/nonexistent/nope.so"))
        assertFalse(info.isElf)
        assertTrue(info.error != null && info.error!!.contains("missing"))
    }

    @Test
    fun `dns self-test fails when bundled binary is static`() {
        // A static ELF binary must turn the self-test red: child DNS would use
        // the pure-Go resolver and fail on device even with a green app preflight.
        val staticBytes = buildElf64(
            listOf(Triple(0L, 0L, 0L), Triple(1L, 100L, 0x1000L)),
        )
        val fakeBinary = File.createTempFile("static-cloudflared", ".so").apply {
            writeBytes(ByteArray(150_000) { 1 })
            deleteOnExit()
        }
        val controller = FakeProcessNetworkControllerForTest()
        val report = TunnelDnsSelfTest.run(
            controller = controller,
            binaryFile = fakeBinary,
            isMainThread = { false },
            threadName = { "bg" },
            resolve = { listOf("1.1.1.1") },
            httpProbe = { "HTTP 200" },
            binaryInspector = { TunnelBinaryInspector.inspectBytes(staticBytes) },
        )
        assertFalse(report.passed)
        assertTrue(report.lines.any { it.contains("resolver path") && it.contains("STATIC") })
        assertTrue(
            report.lines.any {
                it.contains("FAIL") && it.contains("statically linked")
            },
        )
    }

    @Test
    fun `dns self-test reports dynamic binary as ok`() {
        val interp = "/system/bin/linker64\u0000".toByteArray(Charsets.US_ASCII)
        val interpOff = 64 + 56L * 2
        val dynamicBytes = buildElf64(
            listOf(
                Triple(2L, 200L, 16L),
                Triple(3L, interpOff, interp.size.toLong()),
            ),
            interpBytes = interp,
        )
        val fakeBinary = File.createTempFile("dynamic-cloudflared", ".so").apply {
            writeBytes(ByteArray(150_000) { 1 })
            deleteOnExit()
        }
        val controller = FakeProcessNetworkControllerForTest()
        val report = TunnelDnsSelfTest.run(
            controller = controller,
            binaryFile = fakeBinary,
            isMainThread = { false },
            threadName = { "bg" },
            resolve = { listOf("1.1.1.1") },
            httpProbe = { "HTTP 200" },
            binaryInspector = { TunnelBinaryInspector.inspectBytes(dynamicBytes) },
        )
        assertTrue(report.passed)
        assertTrue(report.lines.any { it.contains("resolver path") && it.contains("dynamic") })
        assertTrue(report.lines.any { it.contains("OK: libcloudflared.so dynamically linked") })
    }
}
