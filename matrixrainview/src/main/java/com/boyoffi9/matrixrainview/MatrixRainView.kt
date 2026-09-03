package com.boyoffi9.matrixrainview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * MatrixRainView — a configurable "digital rain" effect.
 *
 * Drop it into a FrameLayout (or any ViewGroup that allows overlapping
 * children) as the bottom-most child, and stack your real UI on top of it
 * for a Matrix-style background. It also works full-screen on its own.
 *
 * Note: a plain LinearLayout arranges children edge-to-edge and will NOT
 * let this view sit *behind* siblings — use FrameLayout (or ConstraintLayout)
 * for the "background effect" use case.
 *
 * Handles its own animation lifecycle: starts on attach, stops on detach,
 * so it won't leak or burn battery when off-screen.
 */
class MatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class CharSet { KATAKANA, BINARY, ALNUM, CUSTOM }

    // ---------------- Public configuration ----------------

    /** Glyph color. Defaults to the classic Matrix green. */
    var rainColor: Int = Color.parseColor("#00FF41")
        set(value) {
            field = value
            headPaint.color = value
            invalidate()
        }

    /** Fall speed multiplier. 1.0 = default. */
    var speed: Float = 1f

    /** Column density multiplier, clamped to [0.1, 3.0]. */
    var density: Float = 1f
        set(value) {
            field = value.coerceIn(0.1f, 3f)
            recalculateColumns()
        }

    /** Whether the head glyph of each column renders with a soft glow. */
    var glowEnabled: Boolean = true

    /** Trail fade strength, clamped to [4, 255]. Lower = longer-lingering trails. */
    var fadeStrength: Int = 32
        set(value) {
            field = value.coerceIn(4, 255)
        }

    /** Which glyph pool to draw from. */
    var charSet: CharSet = CharSet.KATAKANA
        set(value) {
            field = value
            rebuildGlyphPool()
        }

    /** Used only when [charSet] == CUSTOM. Falls back to katakana if null/empty. */
    var customChars: String? = null
        set(value) {
            field = value
            if (charSet == CharSet.CUSTOM) rebuildGlyphPool()
        }

    /** Glyph size in raw pixels. */
    var textSizePx: Float = spToPx(16f)
        set(value) {
            field = value
            headPaint.textSize = value
            trailPaint.textSize = value
            recalculateColumns()
        }

    // ---------------- Internal state ----------------

    private var glyphPool: CharArray = katakanaPool()

    private var columnCount = 0
    private var columnY = FloatArray(0)          // head row position, fractional for smooth motion
    private var columnSpeed = FloatArray(0)      // per-column speed variance
    private var columnLength = IntArray(0)       // trail length in rows, per column
    private var columnGlyphs: Array<CharArray> = arrayOf()
    private var rowHeight = 0f
    private var colWidth = 0f
    private var rows = 0

    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = rainColor
        typeface = Typeface.MONOSPACE
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val fadePaint = Paint().apply {
        color = Color.BLACK
    }

    private var animator: ValueAnimator? = null
    private var lastFrameTimeNanos = 0L

    init {
        // Shadow layers (used for the glow) require a software or hardware
        // layer depending on API level; hardware layer is fine here since
        // we don't use blur radii large enough to need software fallback.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        attrs?.let { readAttrs(context, it) }
        headPaint.textSize = textSizePx
        trailPaint.textSize = textSizePx
    }

    private fun readAttrs(context: Context, attrs: AttributeSet) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.MatrixRainView)
        try {
            rainColor = a.getColor(R.styleable.MatrixRainView_rainColor, rainColor)
            speed = a.getFloat(R.styleable.MatrixRainView_rainSpeed, speed)
            density = a.getFloat(R.styleable.MatrixRainView_rainDensity, density)
            glowEnabled = a.getBoolean(R.styleable.MatrixRainView_rainGlow, glowEnabled)
            fadeStrength = a.getInt(R.styleable.MatrixRainView_rainFadeStrength, fadeStrength)
            textSizePx = a.getDimension(R.styleable.MatrixRainView_rainTextSize, textSizePx)
            customChars = a.getString(R.styleable.MatrixRainView_rainCustomChars)
            charSet = when (a.getInt(R.styleable.MatrixRainView_rainCharSet, 0)) {
                1 -> CharSet.BINARY
                2 -> CharSet.ALNUM
                3 -> CharSet.CUSTOM
                else -> CharSet.KATAKANA
            }
        } finally {
            a.recycle()
        }
    }

    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    // ---------------- Glyph pools ----------------

    private fun katakanaPool(): CharArray = (0x30A0..0x30FF).map { it.toChar() }.toCharArray()
    private fun binaryPool(): CharArray = charArrayOf('0', '1')
    private fun alnumPool(): CharArray = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()

    private fun rebuildGlyphPool() {
        glyphPool = when (charSet) {
            CharSet.KATAKANA -> katakanaPool()
            CharSet.BINARY -> binaryPool()
            CharSet.ALNUM -> alnumPool()
            CharSet.CUSTOM -> customChars?.takeIf { it.isNotEmpty() }?.toCharArray() ?: katakanaPool()
        }
        recalculateColumns()
    }

    private fun randomGlyph(): Char = glyphPool[Random.nextInt(glyphPool.size)]

    // ---------------- Layout ----------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateColumns()
    }

    private fun recalculateColumns() {
        if (width == 0 || height == 0) return

        colWidth = textSizePx * 0.85f
        rowHeight = textSizePx * 1.1f
        val baseColumns = (width / colWidth).toInt().coerceAtLeast(1)
        columnCount = (baseColumns * density).toInt().coerceIn(1, baseColumns * 3)
        rows = (height / rowHeight).toInt().coerceAtLeast(1)

        columnY = FloatArray(columnCount) { Random.nextInt(-rows, rows).toFloat() }
        columnSpeed = FloatArray(columnCount) { 0.6f + Random.nextFloat() * 0.8f }
        columnLength = IntArray(columnCount) { Random.nextInt(8, 24) }
        columnGlyphs = Array(columnCount) { CharArray(rows) { randomGlyph() } }
    }

    // ---------------- Animation lifecycle ----------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        lastFrameTimeNanos = 0L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L // ~60fps tick; actual motion is delta-time based, so this is just a poll rate
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { advanceFrame() }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    /** Pause the rain (e.g. when the host screen goes off-screen but the view stays attached). */
    fun pause() = stopAnimation()

    /** Resume a paused rain. */
    fun resume() = startAnimation()

    private fun advanceFrame() {
        val now = System.nanoTime()
        val delta = if (lastFrameTimeNanos == 0L) 0f else (now - lastFrameTimeNanos) / 1_000_000_000f
        lastFrameTimeNanos = now

        if (rows > 0) {
            for (c in 0 until columnCount) {
                columnY[c] += delta * 12f * speed * columnSpeed[c]

                if (columnY[c] - columnLength[c] > rows) {
                    columnY[c] = Random.nextInt(-rows / 2, 0).toFloat()
                    columnSpeed[c] = 0.6f + Random.nextFloat() * 0.8f
                    columnLength[c] = Random.nextInt(8, 24)
                }

                // Occasional glyph mutation for the "flicker" look real Matrix rain has.
                if (Random.nextFloat() < 0.05f) {
                    val glyphs = columnGlyphs[c]
                    if (glyphs.isNotEmpty()) {
                        glyphs[Random.nextInt(glyphs.size)] = randomGlyph()
                    }
                }
            }
        }
        invalidate()
    }

    // ---------------- Drawing ----------------

    override fun onDraw(canvas: Canvas) {
        // Rather than clearing each frame, paint a translucent black rect over
        // the previous frame. This is what produces the fading trail behind
        // each column's head glyph — lower fadeStrength = slower fade = longer trails.
        fadePaint.alpha = fadeStrength
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)

        for (c in 0 until columnCount) {
            val headRow = columnY[c].toInt()
            val x = c * colWidth
            val glyphs = columnGlyphs[c]
            val trailLen = columnLength[c]

            for (i in 0..trailLen) {
                val row = headRow - i
                if (row < 0 || row >= rows) continue
                val y = row * rowHeight + rowHeight
                val glyph = glyphs.getOrElse(row) { ' ' }

                if (i == 0) {
                    headPaint.alpha = 255
                    if (glowEnabled) {
                        headPaint.setShadowLayer(8f, 0f, 0f, rainColor)
                    } else {
                        headPaint.clearShadowLayer()
                    }
                    canvas.drawText(glyph.toString(), x, y, headPaint)
                } else {
                    val fade = (1f - i.toFloat() / trailLen).coerceIn(0f, 1f)
                    trailPaint.color = rainColor
                    trailPaint.alpha = (fade * 200).toInt()
                    canvas.drawText(glyph.toString(), x, y, trailPaint)
                }
            }
        }
    }
}
