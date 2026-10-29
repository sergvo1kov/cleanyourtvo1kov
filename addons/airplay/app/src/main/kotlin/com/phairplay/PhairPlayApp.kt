package com.phairplay

import android.app.Application
import timber.log.Timber

/**
 * PhairPlayApp — The Application class for PhairPlay.
 *
 * WHY: Android requires an Application subclass to run initialization code before
 * any Activity or Service starts. We use this to set up logging (Timber) once
 * at startup.
 *
 * HOW: This class is referenced in AndroidManifest.xml via android:name=".PhairPlayApp".
 * It is instantiated automatically by the Android framework when the app process starts.
 *
 * Example (automatic — no user code needed):
 *   The app starts → Android creates PhairPlayApp → onCreate() runs → Timber is ready.
 */
class PhairPlayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initLogging()
    }

    /**
     * Sets up Timber logging.
     *
     * In debug builds: logs to Android logcat with full file/line info.
     * In release builds: only logs warnings and errors (no verbose/debug output).
     *
     * WHY: Timber is a tiny wrapper around android.util.Log that adds:
     * - Automatic class name as a log tag (no more TAG constants everywhere)
     * - Easy filtering by log level in release builds
     * - The ability to swap the log backend (useful for crash reporting in future)
     */
    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            // Debug tree: logs everything, shows file names and line numbers
            Timber.plant(Timber.DebugTree())
        }
        else {
            // Flick AirPlay: release builds keep info, warnings and errors in logcat (no debug detail), so a
            // failed connection can be diagnosed on the TV with `adb logcat`.
            Timber.plant(object : Timber.Tree() {
                override fun isLoggable(tag: String?, priority: Int) = priority >= android.util.Log.INFO
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    android.util.Log.println(priority, tag ?: "FlickAirPlay", if (t == null) message else message + "\n" + android.util.Log.getStackTraceString(t))
                }
            })
        }
    }
}
