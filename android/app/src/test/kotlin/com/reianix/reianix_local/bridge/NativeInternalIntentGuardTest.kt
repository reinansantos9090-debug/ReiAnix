package com.reianix.reianix_local.bridge

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NativeInternalIntentGuardTest {
    @Test
    fun externalNativeLinkCannotIssueCommandsWithoutAppPrivateToken() {
        val app = RuntimeEnvironment.getApplication()
        val forged = Intent(Intent.ACTION_VIEW, Uri.parse("reianix://native?action=scan_all_storage"))
        assertFalse(NativeInternalIntentGuard.isTrusted(app, forged))
        forged.putExtra("com.reianix.reianix_local.private_command_token", "fake")
        assertFalse(NativeInternalIntentGuard.isTrusted(app, forged))
    }

    @Test
    fun legitimateInternalCommandIsAuthenticatedAcrossIntents() {
        val app = RuntimeEnvironment.getApplication()
        val trusted = Intent(Intent.ACTION_VIEW, Uri.parse("reianix://native?action=open_library"))
        NativeInternalIntentGuard.stamp(app, trusted)
        assertTrue(NativeInternalIntentGuard.isTrusted(app, trusted))
        val untrusted = Intent(Intent.ACTION_VIEW, trusted.data)
        assertFalse(NativeInternalIntentGuard.isTrusted(app, untrusted))
    }
}
