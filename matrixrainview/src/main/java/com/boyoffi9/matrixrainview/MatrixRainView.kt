package com.boyoffi9.matrixrainview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A configurable "digital rain" effect.
 *
 * Use it full-screen, or as the bottom-most child of a FrameLayout or
 * ConstraintLayout to run behind your UI (a LinearLayout can't overlap
 * children). Animation starts on attach and stops on detach.
 *
 * Touch effects ([touchEffects]), glitch ([glitchIntensity]) and transparent
 * mode ([transparentBackground]) are all off by default.
 */
class MatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class CharSet { KATAKANA, BINARY, ALNUM, CUSTOM }

    companion object {
        /** Streams bend around fingers, like water around a stone. */
        const val TOUCH_AVOID = 1

        /** Glyphs near a finger mutate rapidly and brighten. */
        const val TOUCH_FLICKER = 2

        /** A quick tap sends an expanding ring that scatters and brightens glyphs. */
        const val TOUCH_SHOCKWAVE = 4

        /** Holding a finger down boosts the glitch effect. */
        const val TOUCH_GLITCH = 8

        private const val MAX_TOUCHES = 5
        private const val MAX_WAVES = 4
        private const val MAX_SLICES = 8
        private const val TAP_TIMEOUT_MS = 250L
        private const val WAVE_LIFETIME = 0.9f
    }

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

    /**
     * Bitmask of [TOUCH_AVOID], [TOUCH_FLICKER], [TOUCH_SHOCKWAVE] and
     * [TOUCH_GLITCH]. 0 (the default) turns touch handling off entirely, and
     * the view then never consumes touch events.
     */
    var touchEffects: Int = 0
        set(value) {
            if (field == value) return
            field = value
            if (value == 0) clearTouchState()
        }

    /** Radius of a finger's influence, in pixels. Defaults to 96dp. */
    var touchRadiusPx: Float = dpToPx(96f)
        set(value) {
            field = value.coerceAtLeast(1f)
        }

    /** How far streams part around a finger, 0 to 1. Only used with [TOUCH_AVOID]. */
    var touchPush: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /** Glitch intensity, 0 (off) to 1. Produces short bursts of horizontal slice shifts. */
    var glitchIntensity: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /**
     * When true the view draws no backdrop and trails fade to transparent, so the
     * rain can sit over images or other content. When false (the default) the rain
     * paints its own black backdrop.
     */
    var transparentBackground: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            fadePaint.xfermode = if (value) dstOutMode else null
            trailBitmap?.eraseColor(Color.TRANSPARENT)
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
    private val backdropPaint = Paint().apply {
        color = Color.BLACK
    }
    private val echoPaint = Paint().apply {
        alpha = 90
    }
    private val dstOutMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    private var animator: ValueAnimator? = null
    private var lastFrameTimeNanos = 0L

    // Offscreen buffer that persists between frames; onDraw's own canvas
    // isn't guaranteed to under hardware acceleration.
    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null

    // Touch state: fixed-size slots, so tracking fingers never allocates.
    private val touchId = IntArray(MAX_TOUCHES)
    private val touchX = FloatArray(MAX_TOUCHES)
    private val touchY = FloatArray(MAX_TOUCHES)
    private val touchAmount = FloatArray(MAX_TOUCHES)   // eased 0..1, fades in on press and out on release
    private val touchHeld = BooleanArray(MAX_TOUCHES)
    private val touchUsed = BooleanArray(MAX_TOUCHES)
    private var activeTouches = 0
    private var touchGlitchBoost = 0f

    private val waveX = FloatArray(MAX_WAVES)
    private val waveY = FloatArray(MAX_WAVES)
    private val waveAge = FloatArray(MAX_WAVES)
    private val waveUsed = BooleanArray(MAX_WAVES)
    private var activeWaves = 0

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f

    // Per-glyph result of computeFx(): displacement and extra brightness.
    private var fxDx = 0f
    private var fxDy = 0f
    private var fxBoost = 0f

    // Glitch state: while a burst is active, the bitmap is drawn in horizontal
    // bands and the band at sliceTop..sliceBottom is shifted sideways.
    private var glitchBurstLeft = 0f
    private var glitchReshuffleIn = 0f
    private val sliceTop = IntArray(MAX_SLICES)
    private val sliceBottom = IntArray(MAX_SLICES)
    private val sliceShift = IntArray(MAX_SLICES)
    private var sliceCount = 0
    private val srcRect = Rect()
    private val dstRect = Rect()

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
            touchEffects = a.getInt(R.styleable.MatrixRainView_rainTouchEffects, touchEffects)
            touchRadiusPx = a.getDimension(R.styleable.MatrixRainView_rainTouchRadius, touchRadiusPx)
            touchPush = a.getFloat(R.styleable.MatrixRainView_rainTouchPush, touchPush)
            glitchIntensity = a.getFloat(R.styleable.MatrixRainView_rainGlitch, glitchIntensity)
            transparentBackground =
                a.getBoolean(R.styleable.MatrixRainView_rainTransparent, transparentBackground)
        } finally {
            a.recycle()
        }
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

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

    // ---------------- Touch ----------------

    private fun has(flag: Int): Boolean = (touchEffects and flag) != 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (touchEffects == 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                downX = event.x
                downY = event.y
                pressPointer(event.getPointerId(0), event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                pressPointer(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    movePointer(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> releasePointer(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP -> {
                releasePointer(event.getPointerId(event.actionIndex))
                if (has(TOUCH_SHOCKWAVE) && isTap(event)) {
                    spawnWave(event.x, event.y)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseAllPointers()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun isTap(event: MotionEvent): Boolean {
        val dx = event.x - downX
        val dy = event.y - downY
        return event.eventTime - downTime < TAP_TIMEOUT_MS &&
            dx * dx + dy * dy < touchSlop.toFloat() * touchSlop
    }

    private fun findSlot(id: Int): Int {
        for (i in 0 until MAX_TOUCHES) if (touchUsed[i] && touchId[i] == id) return i
        return -1
    }

    private fun pressPointer(id: Int, x: Float, y: Float) {
        var slot = findSlot(id)
        if (slot < 0) {
            for (i in 0 until MAX_TOUCHES) if (!touchUsed[i]) { slot = i; break }
        }
        if (slot < 0) {
            // All slots busy: reuse the faintest released one, if any.
            var faintest = 2f
            for (i in 0 until MAX_TOUCHES) {
                if (!touchHeld[i] && touchAmount[i] < faintest) { faintest = touchAmount[i]; slot = i }
            }
        }
        if (slot < 0) return
        if (!touchUsed[slot]) touchAmount[slot] = 0f
        touchUsed[slot] = true
        touchHeld[slot] = true
        touchId[slot] = id
        touchX[slot] = x
        touchY[slot] = y
        recountTouches()
    }

    private fun movePointer(id: Int, x: Float, y: Float) {
        val slot = findSlot(id)
        if (slot < 0) return
        touchX[slot] = x
        touchY[slot] = y
    }

    private fun releasePointer(id: Int) {
        val slot = findSlot(id)
        if (slot >= 0) touchHeld[slot] = false
    }

    private fun releaseAllPointers() {
        for (i in 0 until MAX_TOUCHES) touchHeld[i] = false
    }

    private fun recountTouches() {
        var count = 0
        for (i in 0 until MAX_TOUCHES) if (touchUsed[i]) count++
        activeTouches = count
    }

    private fun clearTouchState() {
        for (i in 0 until MAX_TOUCHES) {
            touchUsed[i] = false
            touchHeld[i] = false
            touchAmount[i] = 0f
        }
        for (i in 0 until MAX_WAVES) waveUsed[i] = false
        activeTouches = 0
        activeWaves = 0
        touchGlitchBoost = 0f
    }

    private fun spawnWave(x: Float, y: Float) {
        var slot = -1
        var oldest = -1f
        for (i in 0 until MAX_WAVES) {
            if (!waveUsed[i]) { slot = i; break }
            if (waveAge[i] > oldest) { oldest = waveAge[i]; slot = i }
        }
        waveUsed[slot] = true
        waveX[slot] = x
        waveY[slot] = y
        waveAge[slot] = 0f
        var count = 0
        for (i in 0 until MAX_WAVES) if (waveUsed[i]) count++
        activeWaves = count
    }

    /** Eases fingers in and out and ages shockwaves. */
    private fun updateInteraction(delta: Float) {
        var strongest = 0f
        if (activeTouches > 0) {
            for (i in 0 until MAX_TOUCHES) {
                if (!touchUsed[i]) continue
                touchAmount[i] = if (touchHeld[i]) {
                    (touchAmount[i] + delta * 8f).coerceAtMost(1f)
                } else {
                    touchAmount[i] - delta * 5f
                }
                if (!touchHeld[i] && touchAmount[i] <= 0f) {
                    touchUsed[i] = false
                    touchAmount[i] = 0f
                } else {
                    strongest = max(strongest, touchAmount[i])
                }
            }
            recountTouches()
        }
        if (activeWaves > 0) {
            var count = 0
            for (i in 0 until MAX_WAVES) {
                if (!waveUsed[i]) continue
                waveAge[i] += delta
                if (waveAge[i] >= WAVE_LIFETIME) waveUsed[i] = false else count++
            }
            activeWaves = count
        }
        touchGlitchBoost = if (has(TOUCH_GLITCH)) strongest else 0f
    }

    /**
     * Computes how much a glyph centered at ([cx], [cy]) is pushed and
     * brightened by fingers and shockwaves. Writes [fxDx], [fxDy], [fxBoost].
     */
    private fun computeFx(cx: Float, cy: Float) {
        fxDx = 0f
        fxDy = 0f
        fxBoost = 0f
        val r = touchRadiusPx
        val r2 = r * r

        if (activeTouches > 0) {
            val avoid = has(TOUCH_AVOID)
            val flicker = has(TOUCH_FLICKER)
            if (avoid || flicker) {
                for (i in 0 until MAX_TOUCHES) {
                    if (!touchUsed[i]) continue
                    val dx = cx - touchX[i]
                    val dy = cy - touchY[i]
                    val d2 = dx * dx + dy * dy
                    if (d2 >= r2) continue
                    val amount = touchAmount[i]
                    if (avoid) {
                        // Radial "parting": distance d maps to d + hole * (1 - d/r)^2.
                        // With hole <= r/2 that mapping stays monotonic, so glyphs
                        // bunch up at the edge but never cross over each other.
                        val d = sqrt(d2)
                        val t = 1f - d / r
                        val push = 0.5f * r * touchPush * amount * t * t
                        if (d > 0.001f) {
                            fxDx += dx / d * push
                            fxDy += dy / d * push
                        } else {
                            fxDx += push
                        }
                    }
                    if (flicker) {
                        val w = 1f - d2 / r2
                        fxBoost = max(fxBoost, w * w * amount)
                    }
                }
            }
        }

        if (activeWaves > 0 && has(TOUCH_SHOCKWAVE)) {
            val waveSpeed = r * 3f
            val band = r * 0.35f
            for (i in 0 until MAX_WAVES) {
                if (!waveUsed[i]) continue
                val dx = cx - waveX[i]
                val dy = cy - waveY[i]
                val d = sqrt(dx * dx + dy * dy)
                val off = (d - waveAge[i] * waveSpeed) / band
                if (off <= -1f || off >= 1f) continue
                val shape = 1f - off * off
                val w = shape * shape * (1f - waveAge[i] / WAVE_LIFETIME)
                val push = w * textSizePx * 1.2f
                if (d > 0.001f) {
                    fxDx += dx / d * push
                    fxDy += dy / d * push
                }
                fxBoost = max(fxBoost, w)
            }
        }
    }

    /** Makes glyphs near each finger mutate rapidly. */
    private fun flickerNearTouches() {
        if (!has(TOUCH_FLICKER) || activeTouches == 0 || columnCount == 0 || rows == 0) return
        val r = touchRadiusPx
        val r2 = r * r
        val glyphHalf = textSizePx * 0.3f
        for (i in 0 until MAX_TOUCHES) {
            if (!touchUsed[i]) continue
            val amount = touchAmount[i]
            val c0 = ((touchX[i] - r) / colStep).toInt().coerceIn(0, columnCount - 1)
            val c1 = ((touchX[i] + r) / colStep).toInt().coerceIn(0, columnCount - 1)
            val r0 = ((touchY[i] - r) / rowHeight).toInt().coerceIn(0, rows - 1)
            val r1 = ((touchY[i] + r) / rowHeight).toInt().coerceIn(0, rows - 1)
            for (c in c0..c1) {
                val glyphs = columnGlyphs[c]
                val dx = c * colStep + glyphHalf - touchX[i]
                for (row in r0..r1) {
                    if (row >= glyphs.size) break
                    val dy = (row + 0.5f) * rowHeight - touchY[i]
                    val d2 = dx * dx + dy * dy
                    if (d2 >= r2) continue
                    val w = 1f - d2 / r2
                    if (Random.nextFloat() < 0.5f * w * w * amount) glyphs[row] = randomGlyph()
                }
            }
        }
    }

    // ---------------- Glitch ----------------

    private fun updateGlitch(delta: Float) {
        val intensity = (glitchIntensity + touchGlitchBoost * 0.7f).coerceAtMost(1f)
        if (intensity <= 0.001f) {
            glitchBurstLeft = 0f
            sliceCount = 0
            return
        }
        if (glitchBurstLeft > 0f) {
            glitchBurstLeft -= delta
            glitchReshuffleIn -= delta
            if (glitchBurstLeft <= 0f) {
                sliceCount = 0
            } else if (glitchReshuffleIn <= 0f) {
                buildSlices(intensity)
                glitchReshuffleIn = 0.04f
            }
        } else if (Random.nextFloat() < intensity * 3f * delta) {
            glitchBurstLeft = 0.08f + Random.nextFloat() * 0.2f * (0.5f + intensity)
            buildSlices(intensity)
            glitchReshuffleIn = 0.04f
        }
    }

    /** Picks non-overlapping bands (top to bottom) and a sideways shift for each. */
    private fun buildSlices(intensity: Float) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            sliceCount = 0
            return
        }
        val wanted = (2 + intensity * 5f).toInt().coerceAtMost(MAX_SLICES)
        val maxShift = (w * (0.02f + 0.13f * intensity)).toInt().coerceAtLeast(1)
        var y = Random.nextInt(0, (h / 4).coerceAtLeast(1))
        var n = 0
        while (n < wanted && y < h) {
            val sliceHeight = (h * (0.02f + Random.nextFloat() * 0.12f)).toInt().coerceAtLeast(2)
            val bottom = (y + sliceHeight).coerceAtMost(h)
            sliceTop[n] = y
            sliceBottom[n] = bottom
            sliceShift[n] = Random.nextInt(-maxShift, maxShift + 1)
            n++
            y = bottom + Random.nextInt(h / 20 + 1, h / 4 + 2)
        }
        sliceCount = n
    }

    private fun drawBand(canvas: Canvas, bitmap: Bitmap, top: Int, bottom: Int, shift: Int, paint: Paint?) {
        srcRect.set(0, top, bitmap.width, bottom)
        dstRect.set(shift, top, bitmap.width + shift, bottom)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    private fun blit(canvas: Canvas, bitmap: Bitmap) {
        if (sliceCount == 0) {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            return
        }
        val h = bitmap.height
        var cursor = 0
        for (i in 0 until sliceCount) {
            val top = sliceTop[i].coerceIn(cursor, h)
            val bottom = sliceBottom[i].coerceIn(top, h)
            if (top > cursor) drawBand(canvas, bitmap, cursor, top, 0, null)
            if (bottom > top) {
                if (!transparentBackground) {
                    // The shift exposes a strip at one edge; keep it black.
                    canvas.drawRect(0f, top.toFloat(), bitmap.width.toFloat(), bottom.toFloat(), backdropPaint)
                }
                drawBand(canvas, bitmap, top, bottom, sliceShift[i], null)
                drawBand(canvas, bitmap, top, bottom, -sliceShift[i] / 2, echoPaint)
            }
            cursor = bottom
        }
        if (cursor < h) drawBand(canvas, bitmap, cursor, h, 0, null)
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
        clearTouchState()
        glitchBurstLeft = 0f
        sliceCount = 0
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

        updateInteraction(delta)
        updateGlitch(delta)

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
            flickerNearTouches()
        }
        invalidate()
    }

    // ---------------- Drawing ----------------

    override fun onDraw(canvas: Canvas) {
        val buffer = trailCanvas ?: return
        val bitmap = trailBitmap ?: return

        // Paint translucent black over the previous frame instead of clearing
        // it; that is what makes the trails fade. In transparent mode the same
        // paint uses DST_OUT, which lowers alpha instead of darkening.
        fadePaint.alpha = fadeStrength
        buffer.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)

        val interactive = activeTouches > 0 || activeWaves > 0
        val glyphHalf = textSizePx * 0.3f

        for (c in 0 until columnCount) {
            val headRow = columnY[c].toInt()
            val baseX = c * colStep
            val glyphs = columnGlyphs[c]
            val trailLen = columnLength[c]

            for (i in 0..trailLen) {
                val row = headRow - i
                if (row < 0 || row >= rows || row >= glyphs.size) continue
                val baseY = row * rowHeight + rowHeight

                var x = baseX
                var y = baseY
                var boost = 0f
                if (interactive) {
                    computeFx(baseX + glyphHalf, baseY - rowHeight * 0.4f)
                    x += fxDx
                    y += fxDy
                    boost = fxBoost
                }

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
                    trailPaint.alpha = (fade * 200 + boost * 120).toInt().coerceAtMost(255)
                    buffer.drawText(glyphs, row, 1, x, y, trailPaint)
                }
            }
        }

        blit(canvas, bitmap)
    }
}
