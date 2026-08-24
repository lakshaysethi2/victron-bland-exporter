package com.lakshaysethi.victronbleexporter.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the versionCode comparison driving the update decision. */
class UpdateCheckerTest {

    @Test
    fun `newer served version is an update`() {
        assertTrue(UpdateChecker.isNewer(servedVersionCode = 2, currentVersionCode = 1))
    }

    @Test
    fun `same version is not an update`() {
        assertFalse(UpdateChecker.isNewer(servedVersionCode = 1, currentVersionCode = 1))
    }

    @Test
    fun `older served version is not an update`() {
        assertFalse(UpdateChecker.isNewer(servedVersionCode = 1, currentVersionCode = 2))
    }

    @Test
    fun `update check asks the log host and the public github release`() {
        assertEquals(
            listOf(UpdateChecker.LATEST_URL, UpdateChecker.GITHUB_LATEST_JSON),
            UpdateChecker.CANDIDATE_URLS
        )
    }
}
