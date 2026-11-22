package com.ftapps.remft.ui.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BackupShareReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BackupShareChoice.chosen = true
    }
}
