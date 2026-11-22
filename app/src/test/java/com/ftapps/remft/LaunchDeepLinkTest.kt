package com.ftapps.remft

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchDeepLinkTest {
    @Test
    fun aFreshLaunchKeepsItsLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("remft://add"))
        assertEquals("remft://add", launchDeepLink(intent))
    }

    @Test
    fun aRelaunchFromRecentsDoesNotReplayTheLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("remft://add")).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(launchDeepLink(intent))
    }

    @Test
    fun noIntentMeansNoLink() {
        assertNull(launchDeepLink(null))
    }
}
