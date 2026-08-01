package com.lakshaysethi.victronbleexporter

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowToast

/**
 * End-user-facing behavior for the quick-tunnel URL copy/share feature:
 *  - URL text is rendered next to Copy URL / Share URL buttons
 *  - URL appears on the clipboard (with toast) the moment the tunnel comes up
 *  - Same URL is not re-toasted on recomposition; a new URL after a stop is re-copied
 *  - Copy URL button copies to clipboard with toast
 *  - Share URL button opens the Android share sheet (ACTION_SEND text/plain)
 */
@RunWith(AndroidJUnit4::class)
// ConscryptMode OFF: Robolectric's bundled Conscrypt has no glibc-2.31-compatible aarch64 native
// on this arm64 Linux host; nothing under test needs real TLS.
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TunnelUrlCopyShareTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetAppState() {
        AppState.tunnelUrl = null
        AppState.lastAutoCopiedTunnelUrl = null
    }

    @After
    fun tearDown() {
        AppState.tunnelUrl = null
        AppState.lastAutoCopiedTunnelUrl = null
        ShadowToast.reset()
    }

    private fun clipboardText(): CharSequence? =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            ?.getItemAt(0)?.text

    private fun runUiLoop(times: Int = 1) {
        // The screen refreshes AppState every 1000ms inside a LaunchedEffect loop.
        repeat(times) {
            composeRule.mainClock.advanceTimeBy(1100)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun tunnelUrlBecomesVisible_withCopyAndShareButtons_andIsAutoCopied() {
        composeRule.waitForIdle()
        AppState.tunnelUrl = "https://abcd-1234.trycloudflare.com"
        runUiLoop()

        // URL is rendered, with both affordances next to it.
        composeRule.onNodeWithText("Public URL: https://abcd-1234.trycloudflare.com").assertExists()
        composeRule.onNodeWithText("Share URL").assertExists()
        composeRule.onNodeWithText("Copy URL").assertExists()

        // Auto-copied the moment it appeared, with a visible toast.
        assertEquals("https://abcd-1234.trycloudflare.com", clipboardText().toString())
        assertEquals("Tunnel URL copied", ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())

        // Same URL after more loop ticks: no re-toast, clipboard unchanged.
        runUiLoop(times = 3)
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("https://abcd-1234.trycloudflare.com", clipboardText().toString())
    }

    @Test
    fun newUrlAfterStop_isAutoCopiedAgain() {
        composeRule.waitForIdle()
        AppState.tunnelUrl = "https://first.trycloudflare.com"
        runUiLoop()
        assertEquals("https://first.trycloudflare.com", clipboardText().toString())
        assertEquals(1, ShadowToast.shownToastCount())

        // Tunnel stopped -> URL cleared.
        AppState.tunnelUrl = null
        runUiLoop()

        // Fresh URL after restart -> auto-copied again (fresh restart is instantly shareable).
        AppState.tunnelUrl = "https://second.trycloudflare.com"
        runUiLoop()
        assertEquals("https://second.trycloudflare.com", clipboardText().toString())
        assertEquals(2, ShadowToast.shownToastCount())
    }

    @Test
    fun copyUrlButton_copiesUrlWithToast() {
        AppState.tunnelUrl = "https://copy-me.trycloudflare.com"
        runUiLoop()
        val toastsBeforeClick = ShadowToast.shownToastCount()

        composeRule.onNodeWithText("Copy URL").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("https://copy-me.trycloudflare.com", clipboardText().toString())
        assertEquals("Tunnel URL copied", ShadowToast.getTextOfLatestToast())
        assertEquals(toastsBeforeClick + 1, ShadowToast.shownToastCount())
    }

    @Test
    fun shareUrlButton_opensAndroidShareSheet_withUrlAsText() {
        AppState.tunnelUrl = "https://share-me.trycloudflare.com"
        runUiLoop()

        composeRule.onNodeWithText("Share URL").performScrollTo().performClick()
        composeRule.waitForIdle()

        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity
        assertNotNull("Expected an activity to be started for sharing", started)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("Chooser must wrap the ACTION_SEND intent", send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals("text/plain", send.type)
        assertEquals("https://share-me.trycloudflare.com", send.getStringExtra(Intent.EXTRA_TEXT))
    }
}
