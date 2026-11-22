package com.ftapps.remft

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ftapps.remft.app.AppStartup
import com.ftapps.remft.data.alarm.AlarmStartup
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RemftApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var startup: AppStartup

    @Inject
    lateinit var alarmStartup: AlarmStartup

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        startup.start()
        alarmStartup.start()
    }
}
