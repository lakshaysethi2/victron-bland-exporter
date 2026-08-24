package com.lakshaysethi.victronbleexporter.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cloud backup / device-transfer must not ship Instant Readout keys, the
 * named-tunnel token, the remote-control secret, or the diagnostics log.
 */
class BackupRulesTest {

    @Test
    fun `backup and transfer rules exclude every secret prefs file`() {
        val required = listOf(
            "victron_devices.xml",
            "victron_devices_fallback.xml",
            "victron_remote_settings.xml",
            "victron_charger_settings.xml",
            "victron_app_log.xml",
            "victron_diagnostics.xml",
        )
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val text = xml(name).readText()
            for (pref in required) {
                assertTrue("$name must exclude $pref", text.contains("path=\"$pref\""))
            }
        }
    }

    private fun xml(name: String): File {
        val candidates = listOf(
            File("src/main/res/xml/$name"),
            File("app/src/main/res/xml/$name"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("missing res/xml/$name (cwd=${File(".").canonicalPath})")
    }
}
