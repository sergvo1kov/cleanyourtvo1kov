package com.phairplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.phairplay.airplay.PlaybackInfo
import com.phairplay.R
import com.phairplay.airplay.StreamStats
import androidx.media3.ui.SubtitleView
import androidx.media3.common.text.Cue
import com.phairplay.util.Logger

/**
 * StreamingScreen — Full-screen view that displays the AirPlay video stream.
 *
 * WHY: The decoded video from MediaCodec must be rendered to a [Surface].
 * A [SurfaceView] provides a dedicated, hardware-accelerated drawing surface
 * that can receive MediaCodec output directly — no intermediate bitmap copies.
 * This is the lowest-latency way to display video on Android.
 *
 * HOW: Add this view to the streaming_container in activity_main.xml.
 * Call [getSurface] to get the Surface to pass to [VideoDecoder.initialize].
 * The Surface is valid as long as this view is attached to the window.
 *
 * IMPORTANT: The Surface becomes available asynchronously after the view is
 * laid out. [getSurface] returns null if called before the Surface is ready.
 * [VideoDecoder.initialize] must not be called until [getSurface] returns non-null.
 *
 * Example:
 *   val streamingScreen = StreamingScreen(context)
 *   container.addView(streamingScreen)
 *   // Later, when surface is ready:
 *   val surface = streamingScreen.getSurface()
 *   videoDecoder.initialize(spsBytes, ppsBytes, width, height)
 */
class StreamingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // The SurfaceView that provides the hardware-accelerated rendering surface
    private val surfaceView: SurfaceView = SurfaceView(context)

    // The Surface is created asynchronously by SurfaceView — stored here when ready
    private var surface: Surface? = null
    var onSurfaceReady: (() -> Unit)? = null

    // Optional debug HUD (Settings → "Debug overlay"), drawn on top of the video.
    private val debugView = TextView(context).apply {
        setTextColor(Color.parseColor("#FF00FF66"))
        setBackgroundColor(Color.parseColor("#A6000000"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(24, 16, 24, 16)
        visibility = GONE
    }
    // tvOS-style transport chrome: a bottom scrim, a thin scrubber with a round thumb, a
    // play/pause glyph, and elapsed/remaining time either side of it — not a single centred line.
    private val playbackPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(90), dp(28), dp(90), dp(56))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.TRANSPARENT, Color.argb(214, 0, 0, 0)
        ))
        visibility = GONE
    }
    private val playbackProgress = Scrubber(context)
    private val playPauseIcon = ImageView(context).apply { imageAlpha = 235 }
    private val elapsedView = timeLabel().apply { setPadding(dp(10), 0, 0, 0) }
    private val remainingView = timeLabel().apply { gravity = Gravity.END }
    private val subtitleView = SubtitleView(context).apply {
        setApplyEmbeddedStyles(true)
        setApplyEmbeddedFontSizes(true)
        setBottomPaddingFraction(0.12f)
    }
    private var playbackInfoProvider: (() -> PlaybackInfo?)? = null
    private var playbackToggle: (() -> Unit)? = null
    private var playbackSeek: ((Double) -> Unit)? = null
    // The pause/position bar is a transient HUD (like Apple TV/Roku's), not a permanent status line:
    // visible only for PANEL_VISIBLE_MS after it first appears or after an actual change (play/pause
    // toggling, a seek), then it fades out of the way of the video. `Long.MIN_VALUE` = never shown yet.
    private var panelVisibleUntilMs = Long.MIN_VALUE
    private var lastRate = Double.NaN
    private var lastDurationSec = Double.NaN
    // Last applied surface size, so we only re-layout on an actual change (rotation/resolution switch).
    private var lastSurfaceW = Int.MIN_VALUE
    private var lastSurfaceH = Int.MIN_VALUE

    // "Connected — waiting for playback" placeholder, shown over the (black) surface until video
    // actually arrives: a sender that has connected but plays nothing yet (tvOS shows the same).
    private val waitingView = AirPlayIdleView(context)

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            applyAspectFit()
            val waiting = !StreamStats.videoShowing
            if (waiting != (waitingView.visibility == VISIBLE)) {
                if (waiting) {
                    waitingView.alpha = 1f
                    waitingView.visibility = VISIBLE
                } else {
                    waitingView.animate().alpha(0f).setDuration(WAITING_FADE_MS)
                        .withEndAction { waitingView.visibility = GONE }.start()
                }
            }
            if (StreamStats.overlayEnabled) {
                debugView.visibility = VISIBLE
                debugView.text = StreamStats.summary()
            } else if (debugView.visibility != GONE) {
                debugView.visibility = GONE
            }
            updatePlaybackControls()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        // Black backing so the letterbox/pillarbox bars (when the video is sized to its aspect ratio
        // and doesn't fill 16:9) are black — without this, those margins are transparent and the home
        // menu shows through behind the streaming overlay. The SurfaceView punches its own hole on top.
        setBackgroundColor(Color.BLACK)

        // SurfaceView is centred; its size is set to the video's aspect ratio by applyAspectFit().
        addView(surfaceView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        addView(waitingView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWaitingSender(null)

        // Debug HUD overlay, top-left, above the video surface.
        addView(debugView, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 48; leftMargin = 48 })
        playbackPanel.addView(playbackProgress, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(20)))
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            addView(playPauseIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(elapsedView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(remainingView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        playbackPanel.addView(timeRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(playbackPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM; bottomMargin = 0
        })
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Register a callback to track when the Surface is created/destroyed
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Surface is now ready — store reference for VideoDecoder
                surface = holder.surface
                Logger.d("StreamingScreen: Surface created")
                onSurfaceReady?.invoke()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Called when the surface size changes (e.g., resolution change)
                // MediaCodec handles this automatically — no action needed here
                Logger.d("StreamingScreen: Surface changed ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Surface is gone (e.g., screen turned off) — VideoDecoder must stop
                surface = null
                Logger.d("StreamingScreen: Surface destroyed")
            }
        })
    }

    /**
     * Returns the [Surface] where decoded video frames will be rendered.
     *
     * Returns null if the Surface has not been created yet (the view hasn't
     * been laid out) or if it has been destroyed. The caller must check for
     * null before passing this to [VideoDecoder.initialize].
     *
     * @return The rendering Surface, or null if not yet available.
     */
    fun getSurface(): Surface? = surface
    fun setSubtitles(cues: List<Cue>?) { subtitleView.setCues(cues) }

    fun setPlaybackControls(info: (() -> PlaybackInfo?)?, toggle: (() -> Unit)?, seek: ((Double) -> Unit)?) {
        playbackInfoProvider = info; playbackToggle = toggle; playbackSeek = seek
    }
    fun togglePlayback() { playbackToggle?.invoke(); showPanelNow() }
    fun seekBy(deltaSec: Double) {
        val p = playbackInfoProvider?.invoke() ?: return
        playbackSeek?.invoke((p.positionSec + deltaSec).coerceIn(0.0, p.durationSec))
        showPanelNow()
    }
    /** Resets the auto-hide timer, e.g. after a remote-key interaction. */
    private fun showPanelNow() { panelVisibleUntilMs = System.currentTimeMillis() + PANEL_VISIBLE_MS }
    private fun updatePlaybackControls() {
        val p = playbackInfoProvider?.invoke()
        val ready = p?.readyToPlay == true && p.durationSec > 0.0
        if (!ready || p == null) {
            playbackPanel.visibility = GONE
            panelVisibleUntilMs = Long.MIN_VALUE
            lastRate = Double.NaN
            lastDurationSec = Double.NaN
            return
        }
        // A genuine state change (first frame, play/pause toggled, or a new item replacing the old
        // one — episode/quality switch) (re)starts the visible window; otherwise let it run out and
        // hide. Duration is the switch signal: episode/quality replacement keeps the same rate (it
        // was playing before and after), so rate alone missed it — the transport bar never came back
        // after a mid-playback switch until the viewer happened to toggle play/pause themselves.
        if (panelVisibleUntilMs == Long.MIN_VALUE || p.rate != lastRate || p.durationSec != lastDurationSec) showPanelNow()
        lastRate = p.rate
        lastDurationSec = p.durationSec
        val visible = System.currentTimeMillis() < panelVisibleUntilMs
        playbackPanel.visibility = if (visible) VISIBLE else GONE
        if (!visible) return
        playbackProgress.fraction = (p.positionSec / p.durationSec).toFloat().coerceIn(0f, 1f)
        playPauseIcon.setImageResource(if (p.rate > 0) R.drawable.ic_np_pause else R.drawable.ic_np_play)
        elapsedView.text = formatTime(p.positionSec)
        remainingView.text = "−" + formatTime(p.durationSec - p.positionSec)
    }
    private fun formatTime(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun timeLabel() = TextView(context).apply {
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter_medium) }.getOrNull() ?: Typeface.DEFAULT
        setTextColor(Color.argb(222, 255, 255, 255))
        textSize = 15f
        fontFeatureSettings = "tnum"
    }

    /** Thin rounded tvOS-style scrubber: dim track, bright fill, small round thumb at the fill's edge. */
    private class Scrubber(context: Context) : View(context) {
        var fraction = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        private val barHeight = 5f * resources.displayMetrics.density
        private val thumbRadius = 7f * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            val top = height / 2f - barHeight / 2f
            val r = barHeight / 2f
            val usableW = width - thumbRadius * 2
            val fillX = thumbRadius + usableW * fraction
            rect.set(thumbRadius, top, width - thumbRadius, top + barHeight)
            canvas.drawRoundRect(rect, r, r, track)
            if (fraction > 0f) {
                rect.set(thumbRadius, top, fillX, top + barHeight)
                canvas.drawRoundRect(rect, r, r, fill)
            }
            canvas.drawCircle(fillX, height / 2f, thumbRadius, fill)
        }
    }

    /** Names the connected sender on the waiting placeholder (generic text when unknown). */
    fun setWaitingSender(senderName: String?) {
        val name = senderName?.takeIf { it.isNotBlank() && it != "AirPlay" }
        waitingView.setText(
            if (name != null) context.getString(R.string.connected_to, name) else context.getString(R.string.connected_title),
            context.getString(R.string.connected_waiting_subtitle)
        )
    }

    /**
     * Sizes the SurfaceView to the decoded video's aspect ratio (letterbox/pillarbox) instead of
     * stretching it to fill 16:9. Without this, a portrait phone stream is squashed horizontally.
     * Falls back to filling the container when the size isn't known yet.
     */
    private fun applyAspectFit() {
        val vw = StreamStats.videoWidth
        val vh = StreamStats.videoHeight
        val cw = width
        val ch = height
        val (targetW, targetH) = if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) {
            LayoutParams.MATCH_PARENT to LayoutParams.MATCH_PARENT
        } else {
            val videoRatio = vw.toFloat() / vh
            val containerRatio = cw.toFloat() / ch
            if (videoRatio > containerRatio) cw to (cw / videoRatio).toInt()   // fit width, bars top/bottom
            else (ch * videoRatio).toInt() to ch                              // fit height, bars left/right
        }
        if (targetW == lastSurfaceW && targetH == lastSurfaceH) return
        lastSurfaceW = targetW
        lastSurfaceH = targetH
        surfaceView.layoutParams = (surfaceView.layoutParams as LayoutParams).apply {
            width = targetW; height = targetH; gravity = Gravity.CENTER
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)                 // drive aspect-fit + debug HUD
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val REFRESH_MS = 200L
        private const val PANEL_VISIBLE_MS = 5000L
        private const val WAITING_FADE_MS = 350L
    }
}
