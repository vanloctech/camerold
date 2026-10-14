package vn.camerold.signaling

import android.util.Log
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/*
 * Callbacks from WebRTC, OkHttp and Camera2 run on their own threads and can still arrive after we stop.
 * Posting to a shut-down executor throws RejectedExecutionException on *their* thread, which crashes the app.
 * These helpers drop such late work instead.
 */

/**
 * Single-threaded executor whose delayed tasks are discarded on shutdown (they would touch released objects).
 * A task that throws is logged: the executor keeps the exception inside the task's future, where nobody looks.
 */
fun serialExecutor(): ScheduledExecutorService = object : ScheduledThreadPoolExecutor(1) {
    override fun afterExecute(r: Runnable?, t: Throwable?) {
        super.afterExecute(r, t)
        val f = r as? Future<*> ?: return
        if (!f.isDone || f.isCancelled) return // periodic tasks stay "not done" while they run fine
        try { f.get() } catch (e: ExecutionException) { Log.e("Exec", "task failed", e.cause) } catch (_: Exception) {}
    }
}.apply {
    executeExistingDelayedTasksAfterShutdownPolicy = false
    continueExistingPeriodicTasksAfterShutdownPolicy = false
}

fun ScheduledExecutorService.post(task: () -> Unit) {
    try { execute(task) } catch (_: RejectedExecutionException) {}
}

fun ScheduledExecutorService.later(task: () -> Unit, delay: Long, unit: TimeUnit): ScheduledFuture<*>? =
    try { schedule(task, delay, unit) } catch (_: RejectedExecutionException) { null }

fun ScheduledExecutorService.every(task: () -> Unit, period: Long, unit: TimeUnit): ScheduledFuture<*>? =
    try { scheduleWithFixedDelay(task, period, period, unit) } catch (_: RejectedExecutionException) { null }
