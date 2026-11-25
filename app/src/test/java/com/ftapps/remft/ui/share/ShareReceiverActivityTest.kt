package com.ftapps.remft.ui.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.MainActivity
import com.ftapps.remft.ui.files.FileOpener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ShareReceiverActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val first = Uri.parse("content://gallery.test/media/1")
    private val second = Uri.parse("content://gallery.test/media/2")

    private fun galleryIntent(action: String, type: String): Intent = Intent(action)
        .setType(type)
        .setPackage(context.packageName)
        .addFlags(GALLERY_FLAGS)

    private fun resolvedNames(intent: Intent): List<String> =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.name }

    @Test
    fun shareActionsResolveOnlyToTheTrampoline() {
        listOf(
            galleryIntent(Intent.ACTION_SEND, "image/jpeg"),
            galleryIntent(Intent.ACTION_SEND_MULTIPLE, "image/jpeg"),
            galleryIntent(Intent.ACTION_SEND, "text/plain"),
            galleryIntent(Intent.ACTION_SEND, "application/pdf"),
        ).forEach { assertEquals(listOf(ShareReceiverActivity::class.java.name), resolvedNames(it)) }
    }

    @Test
    fun theTrampolineStaysOutOfRecentsAndHistory() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, ShareReceiverActivity::class.java), 0)
        assertTrue(info.exported)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(info.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertEquals(ActivityInfo.DOCUMENT_LAUNCH_NEVER, info.documentLaunchMode)
        assertTrue(info.taskAffinity.isNullOrEmpty())
        assertEquals(ShareReceiverActivity::class.java.name, FileOpener.SELF_ACTIVITY)
    }

    @Test
    fun aMultiImageShareIsForwardedIntoTheSingleAppTaskWithItsGrant() {
        val share = galleryIntent(Intent.ACTION_SEND_MULTIPLE, "image/jpeg")
            .setPackage(null)
            .setComponent(ComponentName(context, ShareReceiverActivity::class.java))
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, share).create().get()

        assertTrue(activity.isFinishing)
        val forwarded = shadowOf(activity).nextStartedActivity
        assertEquals(ComponentName(context, MainActivity::class.java), forwarded.component)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, forwarded.action)
        assertEquals("image/jpeg", forwarded.type)
        assertEquals(listOf(first, second), IntentCompat.getParcelableArrayListExtra(forwarded, Intent.EXTRA_STREAM, Uri::class.java))
        val clip = forwarded.clipData!!
        assertEquals(listOf(first, second), (0 until clip.itemCount).map { clip.getItemAt(it).uri })
        val required = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION
        assertEquals(required, forwarded.flags and required)
        val forbidden = Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertEquals(0, forwarded.flags and forbidden)
        assertEquals(listOf(first, second), ShareIntents.parse(forwarded)!!.uris)
    }

    @Test
    fun aSingleShareCarriedOnlyInClipDataKeepsEveryUri() {
        val share = Intent(Intent.ACTION_SEND).setType("image/*")
            .setComponent(ComponentName(context, ShareReceiverActivity::class.java))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        share.clipData = ClipData.newRawUri("", first).apply { addItem(ClipData.Item(second)) }
        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, share).create().get()

        val forwarded = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_SEND_MULTIPLE, forwarded.action)
        assertEquals(listOf(first, second), ShareIntents.parse(forwarded)!!.uris)
        assertEquals(2, forwarded.clipData!!.itemCount)
    }

    @Test
    fun aTextShareIsForwardedWithoutClipData() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .setComponent(ComponentName(context, ShareReceiverActivity::class.java))
            .putExtra(Intent.EXTRA_TEXT, "  buy milk  ")
        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, share).create().get()

        assertTrue(activity.isFinishing)
        val forwarded = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, forwarded.action)
        assertEquals("buy milk", forwarded.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(ShareReceiverActivity.forwardIntent(context, share)!!.clipData)
        assertTrue(forwarded.clipData.let { clip -> clip == null || (0 until clip.itemCount).all { clip.getItemAt(it).uri == null } })
        assertEquals(IncomingShare(emptyList(), "buy milk"), ShareIntents.parse(forwarded))
    }

    @Test
    fun nothingIsForwardedForAnEmptyShareOrAHistoryRelaunch() {
        val empty = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .setComponent(ComponentName(context, ShareReceiverActivity::class.java))
        val emptyActivity = Robolectric.buildActivity(ShareReceiverActivity::class.java, empty).create().get()
        assertTrue(emptyActivity.isFinishing)
        assertNull(shadowOf(emptyActivity).nextStartedActivity)

        val history = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .setComponent(ComponentName(context, ShareReceiverActivity::class.java))
            .putExtra(Intent.EXTRA_STREAM, first)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        val historyActivity = Robolectric.buildActivity(ShareReceiverActivity::class.java, history).create().get()
        assertTrue(historyActivity.isFinishing)
        assertNull(shadowOf(historyActivity).nextStartedActivity)
    }

    private companion object {
        const val GALLERY_FLAGS = 0x1b080101
    }
}
