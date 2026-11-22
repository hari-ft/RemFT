package com.ftapps.remft.data.onedrive

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackupWakeBookerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = Executors.newCachedThreadPool()
    private val started = CountDownLatch(1)
    private val finish = CountDownLatch(1)

    class HeldWake(
        context: Context,
        params: WorkerParameters,
        private val started: CountDownLatch,
        private val finish: CountDownLatch,
    ) : Worker(context, params) {
        override fun doWork(): Result {
            started.countDown()
            finish.await(20, TimeUnit.SECONDS)
            return Result.success()
        }
    }

    @Before
    fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == BackupWakeWorker::class.java.name) HeldWake(appContext, workerParameters, started, finish) else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(factory).setExecutor(executor).build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
    }

    @After
    fun tearDown() {
        finish.countDown()
        WorkManagerTestInitHelper.closeWorkDatabase()
        executor.shutdownNow()
    }

    private val work: WorkManager get() = WorkManager.getInstance(context)

    private fun infos(name: String): List<WorkInfo> = work.getWorkInfosForUniqueWork(name).get()

    private fun stateOf(id: UUID): WorkInfo.State? = work.getWorkInfoById(id).get()?.state

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun bookingTheNextSlotLeavesARunningWakeAlone() {
        val booker = WorkManagerWakeBooker(context)
        booker.book(Duration.ofMinutes(5))
        val first = infos(BackupWake.WORK_NAME).single().id
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(first)
        driver.setInitialDelayMet(first)
        waitFor("the wake to start") { started.count == 0L }
        waitFor("the wake to run") { stateOf(first) == WorkInfo.State.RUNNING }

        booker.book(Duration.ofHours(6))
        booker.book(Duration.ofHours(5))

        assertEquals(WorkInfo.State.RUNNING, stateOf(first))
        val next = infos(BackupWake.NEXT_WORK_NAME).filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, next.size)

        finish.countDown()
        waitFor("the running wake to finish") { stateOf(first) == WorkInfo.State.SUCCEEDED }

        booker.book(Duration.ofHours(4))
        assertEquals(1, infos(BackupWake.WORK_NAME).count { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(infos(BackupWake.NEXT_WORK_NAME).none { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun turningOffCancelsTheBookedSlotButNotARunningWake() {
        val booker = WorkManagerWakeBooker(context)
        booker.book(Duration.ofMinutes(5))
        val first = infos(BackupWake.WORK_NAME).single().id
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(first)
        driver.setInitialDelayMet(first)
        waitFor("the wake to start") { started.count == 0L }
        waitFor("the wake to run") { stateOf(first) == WorkInfo.State.RUNNING }
        booker.book(Duration.ofHours(6))
        val next = infos(BackupWake.NEXT_WORK_NAME).single().id

        booker.book(null)

        assertEquals(WorkInfo.State.RUNNING, stateOf(first))
        assertEquals(WorkInfo.State.CANCELLED, stateOf(next))
    }
}
