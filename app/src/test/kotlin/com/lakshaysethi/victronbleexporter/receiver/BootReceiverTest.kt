package com.lakshaysethi.victronbleexporter.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    @Test
    fun `starts exporter after boot, OEM quick-boot, and APK replace`() {
        assertTrue(shouldStartOn("android.intent.action.BOOT_COMPLETED"))
        assertTrue(shouldStartOn("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(shouldStartOn("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(shouldStartOn("com.htc.intent.action.QUICKBOOT_POWERON"))
    }

    @Test
    fun `ignores unrelated broadcasts`() {
        assertFalse(shouldStartOn(null))
        assertFalse(shouldStartOn("android.intent.action.SCREEN_OFF"))
        assertFalse(shouldStartOn("android.intent.action.PACKAGE_REPLACED"))
    }
}
