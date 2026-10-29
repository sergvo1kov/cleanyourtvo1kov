package com.phairplay.util

import android.os.Process

/**
 * Runs [block] with the calling thread's scheduling priority raised to [priority] (an
 * `android.os.Process.THREAD_PRIORITY_*` value), then restores the previous priority.
 *
 * WHY restore: the streaming loops run on pooled `Dispatchers.IO` threads. A raised priority left
 * behind would leak onto whatever unrelated coroutine the pool runs on that thread next.
 * Failures (a priority the OS refuses) are ignored — the block still runs, just at the old priority.
 */
inline fun <T> withThreadPriority(priority: Int, block: () -> T): T {
    val previous = runCatching { Process.getThreadPriority(Process.myTid()) }
        .getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
    runCatching { Process.setThreadPriority(priority) }
    try {
        return block()
    } finally {
        runCatching { Process.setThreadPriority(previous) }
    }
}
