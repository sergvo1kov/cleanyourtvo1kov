package com.phairplay.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.phairplay.R
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.airplay.PlaybackProgress
import com.phairplay.util.Logger

/**
 * NowPlayingScreen — full-screen audio-only player in the style of Apple TV's Music "Now Playing"
 * (tvOS): shown while AirPlay audio plays without video (Apple Music, podcasts, Mac system audio).
 *
 *  - Background: the artwork, heavily blurred and saturated, under a dark scrim, drifting slowly
 *    (Ken Burns) like Apple Music's animated backdrop; cross-fades between tracks.
 *  - Left: the artwork — rounded corners, soft shadow; it shrinks with a spring when paused and
 *    springs back on play (as on iOS / tvOS).
 *  - Right: title / artist / album in a tvOS type hierarchy (Inter as the free stand-in for SF Pro),
 *    a thin progress bar with elapsed / remaining time, transport controls that appear briefly
 *    when the remote is used, and an AirPlay source caption.
 *  - No metadata (raw system audio): a neutral card with the AirPlay glyph and a dark backdrop.
 *
 * All geometry is proportional to the screen height (reference 1080 px), so it looks identical on
 * 720p / 1080p / 4K surfaces. Built programmatically like the other overlay views.
 */
class NowPlayingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ─── Views ────────────────────────────────────────────────────────────────
    private val backdrop = FrameLayout(context)
    private val backdropFront = ImageView(context)
    private val backdropBack = ImageView(context)
    private val scrim = View(context)
    private val artwork = ImageView(context)
    private val textBlock = LinearLayout(context)
    private val titleView: TextView
    private val artistView: TextView
    private val albumView: TextView
    private val progressBlock = LinearLayout(context)
    private val progressBar = ProgressLine(context)
    private val elapsedView: TextView
    private val remainingView: TextView
    private val controlsRow = LinearLayout(context)
    private val prevButton = ImageView(context)
    private val playPauseButton = ImageView(context)
    private val nextButton = ImageView(context)
    private val pulse = ImageView(context)
    private val captionRow = LinearLayout(context)
    private val captionIcon = ImageView(context)
    private val captionText: TextView
    private val hintView = TextView(context)

    // ─── State ────────────────────────────────────────────────────────────────
    private var trackKey: String? = null
    private var artworkHash = 0
    private var hasArtwork = false
    private var progress: PlaybackProgress? = null
    private var paused = false
    private var scale = 1f          // screen height / 1080
    private var appliedScale = -1f
    private var kenBurns: ValueAnimator? = null

    // Geometry computed in onMeasure, consumed in onLayout.
    private var artSide = 0
    private var artLeft = 0
    private var artTop = 0
    private var textLeft = 0
    private var textTop = 0

    private val ticker = object : Runnable {
        override fun run() {
            renderProgress()
            if (progress != null && !paused) postDelayed(this, TICK_MS)
        }
    }

    private val hideControls = Runnable {
        controlsRow.animate().alpha(0f).setDuration(CONTROLS_FADE_MS).start()
    }

    init {
        setBackgroundColor(Color.BLACK)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        clipChildren = false

        // Backdrop: two stacked image layers for cross-fades, blurred bitmaps scaled to cover.
        listOf(backdropBack, backdropFront).forEach { layer ->
            layer.scaleType = ImageView.ScaleType.CENTER_CROP
            layer.colorFilter = ColorMatrixColorFilter(backdropMatrix())
            backdrop.addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        backdrop.background = placeholderBackdrop()
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hintView.visibility = View.GONE

        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x4D000000, 0x66000000, 0x99000000.toInt())
        )
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        artwork.clipToOutline = true
        artwork.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * ART_CORNER_FRACTION)
            }
        }
        addView(artwork, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        // Flick AirPlay: the play/pause/skip sign that pops over the cover when the remote is pressed
        pulse.alpha = 0f
        pulse.scaleType = ImageView.ScaleType.FIT_CENTER
        pulse.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x8C000000.toInt())
        }
        addView(pulse, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        titleView = text(R.font.inter_semibold, Color.WHITE, maxLines = 2)
        artistView = text(R.font.inter_regular, withAlpha(0.62f), maxLines = 1)
        albumView = text(R.font.inter_regular, withAlpha(0.42f), maxLines = 1)
        elapsedView = text(R.font.inter_medium, withAlpha(0.55f), maxLines = 1).apply { fontFeatureSettings = "tnum" }
        remainingView = text(R.font.inter_medium, withAlpha(0.55f), maxLines = 1).apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
        }
        captionText = text(R.font.inter_medium, withAlpha(0.45f), maxLines = 1)

        // Progress: bar, then elapsed (left) / remaining (right).
        progressBlock.orientation = LinearLayout.VERTICAL
        progressBlock.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(elapsedView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(remainingView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        progressBlock.addView(times)

        // Transport controls — hidden until the remote is used (tvOS shows them on interaction).
        controlsRow.orientation = LinearLayout.HORIZONTAL
        controlsRow.gravity = Gravity.CENTER_VERTICAL
        controlsRow.alpha = 0f
        prevButton.setImageResource(R.drawable.ic_np_prev)
        nextButton.setImageResource(R.drawable.ic_np_next)
        playPauseButton.setImageResource(R.drawable.ic_np_pause)
        listOf(prevButton, playPauseButton, nextButton).forEach {
            it.imageAlpha = 235
            controlsRow.addView(it)
        }

        captionIcon.setImageResource(R.drawable.ic_airplay_audio)
        captionIcon.imageAlpha = 115
        captionRow.orientation = LinearLayout.HORIZONTAL
        captionRow.gravity = Gravity.CENTER_VERTICAL
        captionRow.addView(captionIcon)
        captionRow.addView(captionText)

        textBlock.orientation = LinearLayout.VERTICAL
        listOf(titleView, artistView, albumView, progressBlock, controlsRow, captionRow).forEach {
            textBlock.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(textBlock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    // ─── Layout ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val metrics = resources.displayMetrics
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: metrics.widthPixels
        val h = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: metrics.heightPixels
        setMeasuredDimension(w, h)
        applyScale(h / REFERENCE_HEIGHT)

        val full = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY) to MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        backdrop.measure(full.first, full.second)
        scrim.measure(full.first, full.second)

        // Artwork + text column form one row, centred on screen (and never wider than it).
        artSide = px(ART_SIDE)
        val gap = px(ART_TEXT_GAP)
        val textW = minOf(px(TEXT_WIDTH), w - artSide - gap - 2 * px(SAFE_X)).coerceAtLeast(1)
        artLeft = ((w - (artSide + gap + textW)) / 2).coerceAtLeast(px(SAFE_X))
        artTop = (h - artSide) / 2
        artwork.measure(
            MeasureSpec.makeMeasureSpec(artSide, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(artSide, MeasureSpec.EXACTLY)
        )
        textLeft = artLeft + artSide + gap
        textBlock.measure(
            MeasureSpec.makeMeasureSpec(textW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h - 2 * px(SAFE_Y), MeasureSpec.AT_MOST)
        )
        textTop = artTop + (artSide - textBlock.measuredHeight) / 2
    }

    private val hideHint = Runnable {
        hintView.animate().alpha(0f).setDuration(250).withEndAction { hintView.visibility = View.GONE }.start()
    }

    /** Transient pill at the bottom ("Audio delay: +150 ms (←/→)"), auto-hides after 2 s. */
    fun showHint(text: String) {
        if (hintView.parent == null) {
            hintView.setTextColor(Color.WHITE)
            hintView.gravity = Gravity.CENTER
            hintView.typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            hintView.background = GradientDrawable().apply { setColor(0xB0000000.toInt()); cornerRadius = 1000f }
            addView(hintView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(CAPTION_PX + 6).toFloat())
        hintView.setPadding(px(34), px(16), px(34), px(16))
        hintView.text = text
        hintView.animate().cancel()
        hintView.alpha = 1f
        hintView.visibility = View.VISIBLE
        requestLayout()
        removeCallbacks(hideHint)
        postDelayed(hideHint, 2000)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (hintView.parent != null && hintView.visibility == View.VISIBLE) {
            hintView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            val hx = (width - hintView.measuredWidth) / 2
            val hy = height - px(96) - hintView.measuredHeight
            hintView.layout(hx, hy, hx + hintView.measuredWidth, hy + hintView.measuredHeight)
        }
        backdrop.layout(0, 0, width, height)
        scrim.layout(0, 0, width, height)
        artwork.layout(artLeft, artTop, artLeft + artSide, artTop + artSide)
        artwork.pivotX = artSide / 2f
        artwork.pivotY = artSide / 2f
        val ps = artSide * 30 / 100
        val pp = ps * 22 / 100
        val pl = artLeft + (artSide - ps) / 2
        val pt = artTop + (artSide - ps) / 2
        pulse.setPadding(pp, pp, pp, pp)
        pulse.layout(pl, pt, pl + ps, pt + ps)
        pulse.pivotX = ps / 2f
        pulse.pivotY = ps / 2f
        textBlock.layout(textLeft, textTop, textLeft + textBlock.measuredWidth, textTop + textBlock.measuredHeight)
        backdrop.pivotX = width / 2f
        backdrop.pivotY = height / 2f
    }

    /** Applies all sizes for the current screen scale (no-op if unchanged). */
    private fun applyScale(newScale: Float) {
        if (newScale == appliedScale) return
        appliedScale = newScale
        scale = newScale
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(TITLE_PX).toFloat())
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(ARTIST_PX).toFloat())
        albumView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(ALBUM_PX).toFloat())
        elapsedView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(TIME_PX).toFloat())
        remainingView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(TIME_PX).toFloat())
        captionText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(CAPTION_PX).toFloat())
        titleView.setLineSpacing(0f, 1.05f)

        artistView.setPadding(0, px(10), 0, 0)
        albumView.setPadding(0, px(6), 0, 0)
        (progressBar.layoutParams as LinearLayout.LayoutParams).height = px(PROGRESS_TOUCH_HEIGHT)
        progressBar.barHeight = px(PROGRESS_BAR_HEIGHT).toFloat()
        progressBlock.setPadding(0, px(52), 0, 0)
        elapsedView.setPadding(0, px(10), 0, 0)
        remainingView.setPadding(0, px(10), 0, 0)
        controlsRow.setPadding(0, px(36), 0, 0)
        listOf(prevButton, playPauseButton, nextButton).forEachIndexed { i, button ->
            val size = px(if (button === playPauseButton) 56 else 44)
            button.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                if (i > 0) marginStart = px(64)
            }
        }
        captionRow.setPadding(0, px(40), 0, 0)
        captionIcon.layoutParams = LinearLayout.LayoutParams(px(30), px(30)).apply { marginEnd = px(12) }
        artwork.elevation = px(40).toFloat()
    }

    // ─── Content ──────────────────────────────────────────────────────────────

    /** Shows [info]. Cheap to call repeatedly: artwork is only re-decoded when its bytes change. */
    fun update(info: NowPlayingInfo) {
        val key = listOf(info.title, info.artist, info.album).joinToString("\u0000")
        val trackChanged = key != trackKey
        trackKey = key

        val bytes = info.artwork
        val hash = bytes?.contentHashCode() ?: 0
        if (hash != artworkHash || (bytes == null && hasArtwork)) {
            artworkHash = hash
            setArtwork(bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                .also { if (it == null && bytes != null) Logger.w("NowPlayingScreen: artwork (${bytes.size}B) failed to decode") })
        }

        if (trackChanged) {
            textBlock.alpha = 0f
            textBlock.animate().alpha(1f).setDuration(TEXT_FADE_MS).setInterpolator(DecelerateInterpolator()).start()
        }
        titleView.text = info.title ?: context.getString(R.string.now_playing_audio)
        artistView.setTextOrGone(info.artist)
        albumView.setTextOrGone(info.album)
        captionText.text = info.senderName

        progress = info.progress
        progressBlock.visibility = if (info.progress != null) View.VISIBLE else View.GONE
        setPaused(info.paused)
        removeCallbacks(ticker)
        ticker.run()
    }

    /**
     * Briefly reveals the transport controls, highlighting the one the remote just triggered
     * ("playpause", "nextitem", "previtem" …, see DacpClient) — like tvOS on remote interaction.
     */
    /**
     * Flick AirPlay: the sign of what the remote just did pops over the cover — small, it grows and
     * fades away, as on Apple TV. Play/pause shows the state the press leads to.
     */
    fun pop(command: String?) {
        val icon = when (command) {
            "playpause" -> if (paused) R.drawable.ic_np_play else R.drawable.ic_np_pause
            "nextitem", "beginff" -> R.drawable.ic_np_next
            "previtem", "beginrew" -> R.drawable.ic_np_prev
            else -> return
        }
        pulse.setImageResource(icon)
        pulse.animate().cancel()
        pulse.alpha = 1f
        pulse.scaleX = 0.6f
        pulse.scaleY = 0.6f
        pulse.animate().scaleX(1.7f).scaleY(1.7f).alpha(0f).setDuration(650)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        // the cover gives a small nudge too
        if (command == "playpause") setPaused(!paused)
    }

    fun showControls(command: String?) {
        removeCallbacks(hideControls)
        controlsRow.animate().alpha(1f).setDuration(CONTROLS_FADE_MS).start()
        val target = when (command) {
            "nextitem", "beginff" -> nextButton
            "previtem", "beginrew" -> prevButton
            "playpause" -> playPauseButton
            else -> null
        }
        target?.let {
            it.animate().cancel()
            it.scaleX = 1.25f; it.scaleY = 1.25f
            it.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(DecelerateInterpolator()).start()
        }
        postDelayed(hideControls, CONTROLS_VISIBLE_MS)
    }

    /** Releases bitmaps and stops animations when the player is hidden. */
    fun clear() {
        removeCallbacks(ticker)
        removeCallbacks(hideControls)
        stopKenBurns()
        artwork.setImageDrawable(null)
        backdropFront.setImageDrawable(null)
        backdropBack.setImageDrawable(null)
        trackKey = null
        artworkHash = 0
        hasArtwork = false
        progress = null
        paused = false
        artwork.scaleX = 1f; artwork.scaleY = 1f
        controlsRow.alpha = 0f
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE && isShown) startKenBurns() else stopKenBurns()
    }

    override fun onDetachedFromWindow() {
        stopKenBurns()
        removeCallbacks(ticker)
        removeCallbacks(hideControls)
        super.onDetachedFromWindow()
    }

    private fun setArtwork(bitmap: Bitmap?) {
        hasArtwork = bitmap != null
        artwork.animate().cancel()
        if (bitmap != null) {
            artwork.background = null
            artwork.scaleType = ImageView.ScaleType.CENTER_CROP
            artwork.setPadding(0, 0, 0, 0)
            artwork.imageAlpha = 255
            artwork.setImageBitmap(bitmap)
            crossfadeBackdrop(blur(bitmap))
        } else {
            // No artwork (typical for raw system audio): neutral card with the AirPlay glyph.
            artwork.background = placeholderCard()
            artwork.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (px(ART_SIDE) * 0.32f).toInt()
            artwork.setPadding(pad, pad, pad, pad)
            artwork.setImageResource(R.drawable.ic_airplay_audio)
            artwork.imageAlpha = 150
            crossfadeBackdrop(null)
        }
        artwork.alpha = 0f
        artwork.animate().alpha(1f).setDuration(ART_FADE_MS).start()
    }

    /** New backdrop fades in over the old one (the old stays underneath until replaced). */
    private fun crossfadeBackdrop(blurred: Bitmap?) {
        backdropBack.setImageDrawable(backdropFront.drawable)
        backdropBack.alpha = 1f
        backdropFront.setImageBitmap(blurred)
        backdropFront.alpha = 0f
        backdropFront.animate().alpha(1f).setDuration(BACKDROP_FADE_MS).withEndAction {
            backdropBack.setImageDrawable(null)
        }.start()
    }

    private fun setPaused(value: Boolean) {
        if (value == paused && artwork.scaleX == (if (value) PAUSED_ART_SCALE else 1f)) return
        paused = value
        playPauseButton.setImageResource(if (value) R.drawable.ic_np_play else R.drawable.ic_np_pause)
        val target = if (value) PAUSED_ART_SCALE else 1f
        artwork.animate().scaleX(target).scaleY(target)
            .setDuration(if (value) 420 else 520)
            .setInterpolator(if (value) DecelerateInterpolator(1.6f) else OvershootInterpolator(1.1f))
            .start()
    }

    private fun renderProgress() {
        val p = progress ?: return
        val pos = p.positionAt(SystemClock.elapsedRealtime(), paused)
        progressBar.fraction = if (p.durationMs > 0) pos.toFloat() / p.durationMs else 0f
        elapsedView.text = formatTime(pos)
        remainingView.text = "−" + formatTime(p.durationMs - pos)
    }

    private fun startKenBurns() {
        if (kenBurns != null) return
        // Slow drift + zoom of the blurred backdrop, back and forth (Apple Music-style ambient motion).
        kenBurns = ObjectAnimator.ofPropertyValuesHolder(
            backdrop,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.18f, 1.38f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.18f, 1.38f),
            PropertyValuesHolder.ofFloat(View.ROTATION, -2f, 3f),
        ).apply {
            duration = KEN_BURNS_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopKenBurns() {
        kenBurns?.cancel()
        kenBurns = null
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun text(fontRes: Int, color: Int, maxLines: Int) = TextView(context).apply {
        typeface = runCatching { ResourcesCompat.getFont(context, fontRes) }.getOrNull() ?: Typeface.DEFAULT
        setTextColor(color)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        letterSpacing = -0.01f
    }

    private fun TextView.setTextOrGone(value: String?) {
        if (value.isNullOrBlank()) visibility = View.GONE else { text = value; visibility = View.VISIBLE }
    }

    private fun px(reference: Int): Int = Math.round(reference * scale)

    private fun withAlpha(alpha: Float): Int = Color.argb((alpha * 255).toInt(), 255, 255, 255)

    private fun placeholderCard() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF3A3A3C.toInt(), 0xFF1C1C1E.toInt())
    )

    private fun placeholderBackdrop() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1E2230.toInt(), 0xFF0E0E12.toInt(), 0xFF1A1420.toInt())
    )

    /** Saturation boost + slight darkening, so the blurred art reads as colour, not as a picture. */
    private fun backdropMatrix() = ColorMatrix().apply {
        setSaturation(1.5f)
        postConcat(ColorMatrix().apply { setScale(0.82f, 0.82f, 0.82f, 1f) })
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Heavy, cheap blur: shrink to [BLUR_SIZE] px, box-blur a few passes; the view upscales it smoothly. */
    private fun blur(src: Bitmap): Bitmap {
        val n = BLUR_SIZE
        val small = Bitmap.createScaledBitmap(src, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        repeat(BLUR_PASSES) {
            boxBlur(px, n, horizontal = true)
            boxBlur(px, n, horizontal = false)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(px: IntArray, n: Int, horizontal: Boolean) {
        val r = BLUR_RADIUS
        val out = IntArray(n)
        for (line in 0 until n) {
            for (i in 0 until n) {
                var a = 0; var rr = 0; var g = 0; var b = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, n - 1)
                    val c = if (horizontal) px[line * n + j] else px[j * n + line]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                val d = 2 * r + 1
                out[i] = ((a / d) shl 24) or ((rr / d) shl 16) or ((g / d) shl 8) or (b / d)
            }
            for (i in 0 until n) if (horizontal) px[line * n + i] = out[i] else px[i * n + line] = out[i]
        }
    }

    /** Thin rounded progress line: dim track + bright fill (tvOS scrubber look). */
    private class ProgressLine(context: Context) : View(context) {
        var fraction = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        var barHeight = 8f
            set(value) { field = value; invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56, 255, 255, 255) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(222, 255, 255, 255) }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val top = (height - barHeight) / 2f
            val r = barHeight / 2f
            rect.set(0f, top, width.toFloat(), top + barHeight)
            canvas.drawRoundRect(rect, r, r, track)
            if (fraction > 0f) {
                rect.right = maxOf(barHeight, width * fraction)
                canvas.drawRoundRect(rect, r, r, fill)
            }
        }
    }

    companion object {
        /** All sizes below are in px at a 1080 px tall screen and scale with the height. */
        private const val REFERENCE_HEIGHT = 1080f
        private const val SAFE_X = 90            // tvOS safe area: 90 px sides …
        private const val SAFE_Y = 60            // … 60 px top/bottom
        private const val ART_SIDE = 600
        private const val ART_TEXT_GAP = 88
        private const val TEXT_WIDTH = 760
        private const val ART_CORNER_FRACTION = 0.022f

        private const val TITLE_PX = 58
        private const val ARTIST_PX = 40
        private const val ALBUM_PX = 30
        private const val TIME_PX = 24
        private const val CAPTION_PX = 24
        private const val PROGRESS_BAR_HEIGHT = 8
        private const val PROGRESS_TOUCH_HEIGHT = 14

        private const val PAUSED_ART_SCALE = 0.86f

        private const val BLUR_SIZE = 36
        private const val BLUR_RADIUS = 3
        private const val BLUR_PASSES = 3

        private const val TICK_MS = 250L
        private const val TEXT_FADE_MS = 420L
        private const val ART_FADE_MS = 380L
        private const val BACKDROP_FADE_MS = 900L
        private const val CONTROLS_FADE_MS = 260L
        private const val CONTROLS_VISIBLE_MS = 3_500L
        private const val KEN_BURNS_MS = 26_000L
    }
}
