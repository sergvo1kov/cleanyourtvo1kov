package com.phairplay.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.phairplay.R

/**
 * AirPlayIdleView — tvOS-style "AirPlay" placeholder: a dark, slowly shifting ambient backdrop with
 * a softly breathing AirPlay glyph in a frosted circle, a title and a subtitle, centred.
 *
 * Used for the idle screen (no sender: title = this device's name, "Ready for AirPlay") and for a
 * connected sender that isn't playing anything yet ("Connected to iPhone", "Waiting for playback").
 * Sizes scale with the screen height (reference 1080 px). Animations run only while shown.
 */
class AirPlayIdleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val glow = View(context)
    private val glowAlt = View(context)
    private val content = LinearLayout(context)
    private val badge = FrameLayout(context)
    private val glyph = ImageView(context)
    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private val animators = mutableListOf<ValueAnimator>()
    private var appliedHeight = -1

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF0B0C12.toInt(), 0xFF07070A.toInt(), 0xFF000000.toInt())
        )
        clipChildren = false

        // Two large, very soft colour glows that drift and breathe — the ambient backdrop.
        glow.background = radialGlow(0x5A2C4BFF)
        glowAlt.background = radialGlow(0x46A33DFF)
        addView(glow)
        addView(glowAlt)

        glyph.setImageResource(R.drawable.ic_airplay_audio)
        glyph.imageAlpha = 235
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(30, 255, 255, 255))
            setStroke(2, Color.argb(36, 255, 255, 255))
        }
        badge.addView(glyph)

        style(titleView, R.font.inter_semibold, Color.WHITE)
        style(subtitleView, R.font.inter_regular, Color.argb(150, 255, 255, 255))
        subtitleView.maxLines = 2

        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(badge)
        content.addView(titleView)
        content.addView(subtitleView)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Shifts the centred content up by this fraction of the height (room for controls below). */
    var contentLift = 0f
        set(value) { field = value; appliedHeight = -1; requestLayout() }

    fun setText(title: CharSequence, subtitle: CharSequence) {
        titleView.text = title
        subtitleView.text = subtitle
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (h != appliedHeight) applySizes(h)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applySizes(h: Int) {
        appliedHeight = h
        val s = h / 1080f
        fun px(v: Int) = Math.round(v * s)
        val glowSize = px(1300)
        glow.layoutParams = LayoutParams(glowSize, glowSize, Gravity.CENTER).apply {
            leftMargin = -px(420); topMargin = -px(160)
        }
        glowAlt.layoutParams = LayoutParams(glowSize, glowSize, Gravity.CENTER).apply {
            leftMargin = px(520); topMargin = px(260)
        }
        val badgeSize = px(168)
        badge.layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize)
        val glyphSize = px(92)
        glyph.layoutParams = LayoutParams(glyphSize, glyphSize, Gravity.CENTER)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(56).toFloat())
        titleView.setPadding(px(90), px(48), px(90), 0)
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30).toFloat())
        subtitleView.setPadding(px(90), px(16), px(90), 0)
        content.translationY = -h * contentLift
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) start() else stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (animators.isNotEmpty()) return
        animators += loop(badge, 4_200L,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 1f))
        animators += loop(glow, 17_000L,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, 160f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, 90f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.75f, 1f))
        animators += loop(glowAlt, 23_000L,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -190f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -110f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f))
    }

    private fun stop() {
        animators.forEach { it.cancel() }
        animators.clear()
    }

    private fun loop(target: View, ms: Long, vararg values: PropertyValuesHolder) =
        ObjectAnimator.ofPropertyValuesHolder(target, *values).apply {
            duration = ms
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

    private fun radialGlow(color: Int) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(color, color and 0x00FFFFFF)
        gradientRadius = 650f * resources.displayMetrics.heightPixels / 1080f
    }

    private fun style(view: TextView, fontRes: Int, color: Int) {
        view.typeface = runCatching { ResourcesCompat.getFont(context, fontRes) }.getOrNull() ?: Typeface.DEFAULT
        view.setTextColor(color)
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        view.maxLines = 1
        view.ellipsize = TextUtils.TruncateAt.END
        view.letterSpacing = -0.01f
    }
}
