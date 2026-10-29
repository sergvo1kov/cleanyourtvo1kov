package com.phairplay.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.phairplay.util.Logger

/**
 * Keeps the Wi-Fi radio out of power-save while an AirPlay session is streaming.
 *
 * With power-save allowed, the AP buffers unicast frames until the station's next beacon wake-up, so
 * RTP audio and mirror-video packets arrive in delayed bursts (jitter → underruns, resend requests,
 * queue overflow). Only a [WifiManager.WifiLock] disables that; the app previously held none (the
 * multicast lock in [MdnsService] only covers mDNS). Battery is irrelevant on a mains-powered TV.
 *
 * Two locks, because they have different reach: FULL_HIGH_PERF works whatever the app's state
 * (audio-only sessions run with the UI in the background), FULL_LOW_LATENCY (API 29+) additionally
 * asks the driver for low-latency mode but is only honoured while the app is in the foreground with
 * the screen on. Both are non-reference-counted, so [acquire]/[release] are idempotent.
 */
class StreamWifiLock(context: Context) {

    private val locks: List<WifiManager.WifiLock> = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")   // HIGH_PERF is deprecated on API 29+ but still the only mode that works in the background
        val modes = buildList {
            add(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
        }
        modes.map { wifi.createWifiLock(it, "PhairPlay-stream-$it").apply { setReferenceCounted(false) } }
    }.getOrElse {
        Logger.w("Could not create Wi-Fi locks: ${it.message}")
        emptyList()
    }

    fun acquire() {
        var newlyHeld = false
        for (lock in locks) {
            runCatching { if (!lock.isHeld) { lock.acquire(); newlyHeld = true } }
                .onFailure { Logger.w("Wi-Fi lock acquire failed: ${it.message}") }
        }
        if (newlyHeld) Logger.d("Wi-Fi stream lock acquired")
    }

    fun release() {
        var released = false
        for (lock in locks) {
            runCatching { if (lock.isHeld) { lock.release(); released = true } }
        }
        if (released) Logger.d("Wi-Fi stream lock released")
    }
}
