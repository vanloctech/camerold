package vn.camerold.signaling

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

/** Single-threaded executor whose delayed tasks are discarded on shutdown (they would touch released objects). */
fun serialExecutor(): ScheduledExecutorService = ScheduledThreadPoolExecutor(1).apply {
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
