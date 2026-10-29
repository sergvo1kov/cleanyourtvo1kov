package com.phairplay.airplay

/**
 * Local view of the AirPlay 2 grandmaster (PTP) clock. Implemented by the PTP slave, consumed by the
 * multi-room playout scheduler. Times are nanoseconds. "Local" = [System.nanoTime]; "master" = PTP time
 * of the grandmaster identified by [clockId] (same timeline as networkTimeSecs/Frac in SETRATEANCHORTIME).
 */
interface PtpClock {
    /** True once the offset to the grandmaster is estimated from enough stable samples. */
    val synced: Boolean

    /** Grandmaster ClockIdentity (64-bit) the estimate refers to, or 0 if unknown. */
    val clockId: Long

    /** Estimated master time for a local [System.nanoTime] value, or null if not [synced]. */
    fun masterNanosForLocal(localNanos: Long): Long?

    /** Estimated local [System.nanoTime] for a master time, or null if not [synced]. */
    fun localNanosForMaster(masterNanos: Long): Long?

    /** Last estimated mean path delay in nanoseconds (diagnostics), or null. */
    val pathDelayNanos: Long?
}
