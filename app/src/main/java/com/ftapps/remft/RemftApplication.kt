package com.ftapps.remft

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ftapps.remft.app.AppStartup
import com.ftapps.remft.data.alarm.AlarmStartup
import com.ftapps.remft.data.alarm.DirectBoot
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RemftApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var startup: Lazy<AppStartup>

    @Inject
    lateinit var alarmStartup: Lazy<AlarmStartup>

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (DirectBoot.isUnlocked(this)) startAll() else DirectBoot.whenUnlocked(this, ::startAll)
    }

    private fun startAll() {
        startup.get().start()
        alarmStartup.get().start()
    }
}
