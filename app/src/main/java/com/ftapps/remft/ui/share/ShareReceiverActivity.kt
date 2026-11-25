package com.ftapps.remft.ui.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ftapps.remft.MainActivity
import com.ftapps.remft.data.alarm.AppLogs

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardIntent(this, intent, restored = savedInstanceState != null)?.let { target ->
            try {
                startActivity(target)
            } catch (e: RuntimeException) {
                AppLogs.warn("files.share_in_failed", e, mapOf("stage" to "forward"))
            }
        }
        finish()
    }

    companion object {
        const val FORWARD_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_GRANT_READ_URI_PERMISSION

        fun forwardIntent(context: Context, intent: Intent?, restored: Boolean = false): Intent? {
            val share = ShareIntents.atLaunch(intent, restored)?.takeUnless { it.isEmpty } ?: return null
            val type = intent?.type ?: ClipDescription.MIMETYPE_UNKNOWN
            val forward = Intent(context, MainActivity::class.java).setType(type).addFlags(FORWARD_FLAGS)
            when {
                share.uris.size > 1 -> forward.setAction(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(share.uris))
                share.uris.size == 1 -> forward.setAction(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, share.uris.single())
                else -> forward.setAction(Intent.ACTION_SEND)
            }
            share.text?.let { forward.putExtra(Intent.EXTRA_TEXT, it) }
            if (share.uris.isNotEmpty()) {
                forward.clipData = ClipData(ClipDescription(CLIP_LABEL, arrayOf(type)), ClipData.Item(share.uris.first())).apply {
                    share.uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
            return forward
        }

        private const val CLIP_LABEL = "share"
    }
}
