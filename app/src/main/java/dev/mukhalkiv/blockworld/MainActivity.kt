package dev.mukhalkiv.blockworld

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var gameView: BlockWorldView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        gameView = BlockWorldView(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(gameView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val hud = HudView(this)
        hud.isClickable = false
        hud.isFocusable = false
        root.addView(hud, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        gameView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        gameView.onResume()
    }
}

private class BlockWorldView(activity: Activity) : GLSurfaceView(activity) {
    private val worldRenderer = WorldRenderer(activity)
    private var lookPointerId = MotionEvent.INVALID_POINTER_ID
    private var movePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastLookX = 0f
    private var lastLookY = 0f
    private var joystickCenterX = 0f
    private var joystickCenterY = 0f

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(worldRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return true
        val w = width.toFloat()
        val h = height.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                if (x < w * 0.43f && y > h * 0.58f && movePointerId == MotionEvent.INVALID_POINTER_ID) {
                    movePointerId = id
                    joystickCenterX = x
                    joystickCenterY = y
                    updateJoystick(x, y)
                } else if (x >= w * 0.38f && lookPointerId == MotionEvent.INVALID_POINTER_ID) {
                    lookPointerId = id
                    lastLookX = x
                    lastLookY = y
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (id == movePointerId) updateJoystick(x, y)
                    if (id == lookPointerId) {
                        val dx = x - lastLookX
                        val dy = y - lastLookY
                        lastLookX = x
                        lastLookY = y
                        queueEvent { worldRenderer.rotate(dx * 0.0045f, dy * 0.0045f) }
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                if (id == movePointerId) {
                    movePointerId = MotionEvent.INVALID_POINTER_ID
                    queueEvent { worldRenderer.setMovement(0f, 0f) }
                }
                if (id == lookPointerId) lookPointerId = MotionEvent.INVALID_POINTER_ID

                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    handleTap(x, y, w, h)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                movePointerId = MotionEvent.INVALID_POINTER_ID
                lookPointerId = MotionEvent.INVALID_POINTER_ID
                queueEvent { worldRenderer.setMovement(0f, 0f) }
                return true
            }
        }
        return true
    }

    private fun updateJoystick(x: Float, y: Float) {
        val radiusX = (width * 0.13f).coerceAtLeast(1f)
        val radiusY = (height * 0.13f).coerceAtLeast(1f)
        val dx = ((x - joystickCenterX) / radiusX).coerceIn(-1f, 1f)
        val dy = ((joystickCenterY - y) / radiusY).coerceIn(-1f, 1f)
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        val scale = if (length > 1f) 1f / length else 1f
        val moveX = dx * scale
        val moveY = dy * scale
        queueEvent { worldRenderer.setMovement(moveX, moveY) }
    }

    private fun handleTap(x: Float, y: Float, w: Float, h: Float) {
        if (y < h * 0.60f || x < w * 0.60f) return
        when {
            x > w * 0.86f -> queueEvent { worldRenderer.placeBlock() }
            x > w * 0.74f -> queueEvent { worldRenderer.breakBlock() }
            x > w * 0.60f -> queueEvent { worldRenderer.jump() }
        }
    }
}

private class HudView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = h.coerceAtLeast(1f)

        paint.style = Paint.Style.FILL
        paint.color = 0xA51B2732.toInt()
        canvas.drawRoundRect(16f, 16f, 202f, 62f, 14f, 14f, paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        paint.textSize = (unit * 0.026f).coerceIn(14f, 23f)
        canvas.drawText("BLOCK WORLD", 29f, 46f, paint)

        paint.color = 0xDFFFFFFF.toInt()
        paint.strokeWidth = 3f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(w * 0.5f - 9f, h * 0.5f, w * 0.5f + 9f, h * 0.5f, paint)
        canvas.drawLine(w * 0.5f, h * 0.5f - 9f, w * 0.5f, h * 0.5f + 9f, paint)

        val joystickX = w * 0.19f
        val joystickY = h * 0.82f
        val joystickRadius = h * 0.105f
        paint.style = Paint.Style.FILL
        paint.color = 0x55303B46
        canvas.drawCircle(joystickX, joystickY, joystickRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.color = 0xBBFFFFFF.toInt()
        paint.strokeWidth = 3f
        canvas.drawCircle(joystickX, joystickY, joystickRadius, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xAAFFFFFF.toInt()
        canvas.drawCircle(joystickX, joystickY, joystickRadius * 0.30f, paint)

        drawControl(canvas, w * 0.67f, h * 0.82f, "JUMP", h)
        drawControl(canvas, w * 0.79f, h * 0.82f, "BREAK", h)
        drawControl(canvas, w * 0.92f, h * 0.82f, "PLACE", h)

        paint.style = Paint.Style.FILL
        paint.color = 0xDDFFFFFF.toInt()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.textSize = (unit * 0.018f).coerceIn(11f, 17f)
        val hint = "ЛІВО: рух   ПРАВО: огляд"
        val hintWidth = paint.measureText(hint)
        canvas.drawText(hint, max(215f, w - hintWidth - 22f), 35f, paint)
    }

    private fun drawControl(canvas: Canvas, x: Float, y: Float, label: String, height: Float) {
        val radius = height * 0.060f
        paint.style = Paint.Style.FILL
        paint.color = 0xB31B2732.toInt()
        canvas.drawCircle(x, y, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0xDDFFFFFF.toInt()
        canvas.drawCircle(x, y, radius, paint)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = (height * 0.018f).coerceIn(10f, 15f)
        paint.color = Color.WHITE
        val textWidth = paint.measureText(label)
        canvas.drawText(label, x - textWidth / 2f, y + paint.textSize * 0.35f, paint)
    }
}
