package com.boyoffi9.matrixrainview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

/**
 * A configurable "digital rain" effect.
 *
 * Use it full-screen, or as the bottom-most child of a FrameLayout or
 * ConstraintLayout to run behind your UI (a LinearLayout can't overlap
 * children). Animation starts on attach and stops on detach.
 */
class MatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class CharSet { KATAKANA, BINARY, ALNUM, CUSTOM }

    // ---------------- Public configuration ----------------
    // Setters ignore unchanged values: Compose re-assigns every property on
    // each recomposition, and that must not rebuild the columns.

    /** Glyph color. Defaults to the classic Matrix green. */
    var rainColor: Int = Color.parseColor("#00FF41")
        set(value) {
            if (field == value) return
            field = value
            headPaint.color = value
            invalidate()
        }

    /** Fall speed multiplier. 1.0 = default. */
    var speed: Float = 1f

    /** Column density multiplier, clamped to [0.1, 3.0]. */
    var density: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0.1f, 3f)
            if (field == clamped) return
            field = clamped
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
            if (field == value) return
            field = value
            rebuildGlyphPool()
        }

    /** Used only when [charSet] == CUSTOM. Falls back to katakana if null/empty. */
    var customChars: String? = null
        set(value) {
            if (field == value) return
            field = value
            if (charSet == CharSet.CUSTOM) rebuildGlyphPool()
        }

    /** Glyph size in raw pixels. */
    var textSizePx: Float = spToPx(16f)
        set(value) {
            if (field == value) return
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
    private var colWidth = 0f                    // nominal glyph width, sets the base column count
    private var colStep = 0f                     // actual spacing: width / columnCount
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

    // Offscreen buffer that persists between frames; onDraw's own canvas
    // isn't guaranteed to under hardware acceleration.
    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null

    init {
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

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

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

    /** Creates (or resizes) the offscreen trail buffer if it's missing or the wrong size. */
    private fun ensureTrailBuffer() {
        if (width == 0 || height == 0) return
        val existing = trailBitmap
        if (existing == null || existing.width != width || existing.height != height) {
            existing?.recycle()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            trailBitmap = bitmap
            trailCanvas = Canvas(bitmap)
        }
    }

    private fun recalculateColumns() {
        if (width == 0 || height == 0) return

        ensureTrailBuffer()

        colWidth = textSizePx * 0.85f
        rowHeight = textSizePx * 1.1f
        val baseColumns = (width / colWidth).toInt().coerceAtLeast(1)
        columnCount = (baseColumns * density).toInt().coerceIn(1, baseColumns * 3)
        // Spread across the full width; a fixed step would leave gaps (density < 1)
        // or push columns off-screen (density > 1).
        colStep = width.toFloat() / columnCount
        rows = (height / rowHeight).toInt().coerceAtLeast(1)

        columnY = FloatArray(columnCount) { Random.nextInt(-rows, rows).toFloat() }
        columnSpeed = FloatArray(columnCount) { 0.6f + Random.nextFloat() * 0.8f }
        columnLength = IntArray(columnCount) { Random.nextInt(8, 24) }
        columnGlyphs = Array(columnCount) { CharArray(rows) { randomGlyph() } }
    }

    // ---------------- Animation lifecycle ----------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The bitmap is released on detach and onSizeChanged won't fire again
        // on re-attach, so rebuild it here.
        ensureTrailBuffer()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
        super.onDetachedFromWindow()
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        lastFrameTimeNanos = 0L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L // poll rate only; motion is delta-time based
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
                    val resetSpread = (rows / 2).coerceAtLeast(1)
                    columnY[c] = Random.nextInt(-resetSpread, 0).toFloat()
                    columnSpeed[c] = 0.6f + Random.nextFloat() * 0.8f
                    columnLength[c] = Random.nextInt(8, 24)
                }

                // Random glyph swaps give the flicker.
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
        val buffer = trailCanvas ?: return
        val bitmap = trailBitmap ?: return

        // Paint translucent black over the previous frame instead of clearing
        // it; that is what makes the trails fade.
        fadePaint.alpha = fadeStrength
        buffer.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)

        for (c in 0 until columnCount) {
            val headRow = columnY[c].toInt()
            val x = c * colStep
            val glyphs = columnGlyphs[c]
            val trailLen = columnLength[c]

            for (i in 0..trailLen) {
                val row = headRow - i
                if (row < 0 || row >= rows || row >= glyphs.size) continue
                val y = row * rowHeight + rowHeight

                if (i == 0) {
                    headPaint.alpha = 255
                    if (glowEnabled) {
                        headPaint.setShadowLayer(8f, 0f, 0f, rainColor)
                    } else {
                        headPaint.clearShadowLayer()
                    }
                    buffer.drawText(glyphs, row, 1, x, y, headPaint)
                } else {
                    val fade = (1f - i.toFloat() / trailLen).coerceIn(0f, 1f)
                    trailPaint.color = rainColor
                    trailPaint.alpha = (fade * 200).toInt()
                    buffer.drawText(glyphs, row, 1, x, y, trailPaint)
                }
            }
        }

        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
