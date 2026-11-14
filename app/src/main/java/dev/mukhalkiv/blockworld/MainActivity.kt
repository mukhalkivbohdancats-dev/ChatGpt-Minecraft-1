package dev.mukhalkiv.blockworld

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var game: BlockWorldView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        game = BlockWorldView(this)
        val root = FrameLayout(this)
        root.addView(game, FrameLayout.LayoutParams(-1, -1))
        root.addView(HudView(this), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }
    override fun onPause() { super.onPause(); game.onPause() }
    override fun onResume() { super.onResume(); game.onResume() }
}

private class BlockWorldView(activity: Activity) : GLSurfaceView(activity) {
    private val worldRenderer = WorldRenderer(activity)
    private var lookPointer = -1
    private var movePointer = -1
    private var lastLookX = 0f
    private var lastLookY = 0f
    private var moveX = 0f
    private var moveY = 0f
    init {
        setEGLContextClientVersion(2)
        setRenderer(worldRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i); val y = event.getY(i)
                if (y > h * 0.62f && x < w * 0.42f && movePointer == -1) {
                    movePointer = event.getPointerId(i)
                    updateMove(x, y, w, h)
                } else if (x > w * 0.38f && lookPointer == -1) {
                    lookPointer = event.getPointerId(i)
                    lastLookX = x; lastLookY = y
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id == movePointer) updateMove(event.getX(i), event.getY(i), w, h)
                    if (id == lookPointer) {
                        val x = event.getX(i); val y = event.getY(i)
                        val dx = x - lastLookX; val dy = y - lastLookY
                        lastLookX = x; lastLookY = y
                        queueEvent { worldRenderer.rotate(dx * 0.004f, dy * 0.004f) }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = event.getPointerId(event.actionIndex.coerceAtMost(event.pointerCount - 1))
                if (id == movePointer) {
                    movePointer = -1; moveX = 0f; moveY = 0f
                    queueEvent { worldRenderer.setMovement(0f, 0f) }
                }
                if (id == lookPointer) lookPointer = -1
                val x = event.getX(event.actionIndex.coerceAtMost(event.pointerCount - 1))
                val y = event.getY(event.actionIndex.coerceAtMost(event.pointerCount - 1))
                if (event.actionMasked == MotionEvent.ACTION_UP && x > w * 0.72f && y > h * 0.68f) {
                    if (x > w * 0.88f) queueEvent { worldRenderer.placeBlock() }
                    else queueEvent { worldRenderer.breakBlock() }
                }
                return true
            }
        }
        return true
    }
    private fun updateMove(x: Float, y: Float, w: Float, h: Float) {
        val cx = w * 0.20f; val cy = h * 0.82f
        moveX = ((x - cx) / (w * 0.12f)).coerceIn(-1f, 1f)
        moveY = ((cy - y) / (h * 0.12f)).coerceIn(-1f, 1f)
        queueEvent { worldRenderer.setMovement(moveX, moveY) }
    }
}

private class HudView(context: android.content.Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        p.color = 0x99000000.toInt(); p.style = Paint.Style.FILL
        c.drawRoundRect(18f, 18f, 235f, 66f, 16f, 16f, p)
        p.color = Color.WHITE; p.textSize = 23f; p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        c.drawText("BLOCK WORLD", 34f, 49f, p)
        p.color = 0x55333333; c.drawCircle(w * .20f, h * .82f, h * .105f, p)
        p.color = 0x99FFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        c.drawCircle(w * .20f, h * .82f, h * .105f, p)
        p.style = Paint.Style.FILL; p.color = 0xAAFFFFFF.toInt()
        c.drawCircle(w * .20f, h * .82f, h * .035f, p)
        drawButton(c, w * .78f, h * .83f, "BREAK")
        drawButton(c, w * .91f, h * .83f, "PLACE")
        p.color = Color.WHITE; p.textSize = 19f
        c.drawText("Drag right side to look", w * .42f, 36f, p)
    }
    private fun drawButton(c: Canvas, x: Float, y: Float, label: String) {
        p.style = Paint.Style.FILL; p.color = 0xAA17212B.toInt()
        c.drawCircle(x, y, height * .065f, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        c.drawCircle(x, y, height * .065f, p)
        p.style = Paint.Style.FILL; p.textSize = height * .022f
        p.typeface = Typeface.DEFAULT_BOLD
        val tw = p.measureText(label)
        c.drawText(label, x - tw / 2f, y + height * .008f, p)
    }
}
