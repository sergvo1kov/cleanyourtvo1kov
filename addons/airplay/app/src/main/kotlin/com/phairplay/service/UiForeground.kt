package com.phairplay.service

/**
 * Whether MainActivity is resumed, i.e. visibly in front. Written on the main thread from
 * `MainActivity.onResume/onPause`, read from receiver (IO) threads — hence @Volatile.
 * Process-wide on purpose: the service outlives any one activity instance.
 */
object UiForeground {
    @Volatile
    var resumed: Boolean = false
}
