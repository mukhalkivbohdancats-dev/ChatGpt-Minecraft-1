package dev.mukhalkiv.blockworld

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

class MainActivity : Activity(), WorldRenderer.Listener {
    private val preferences by lazy { getSharedPreferences("blockworld_settings", MODE_PRIVATE) }
    private var gameView: BlockWorldView? = null
    private var gameRoot: FrameLayout? = null
    private var gameOverlay: FrameLayout? = null
    private var renderer: WorldRenderer? = null
    private var inventoryOverlay: View? = null
    private var pauseOverlay: View? = null
    private var latestInventory = IntArray(WorldRenderer.BLOCK_COUNT)
    private var selectedBlock = WorldRenderer.BLOCK_DIRT
    private var sensitivity = 50
    private var renderDistance = 2
    private var inventoryOpen = false
    private var isPaused = false
    private var hotbarViews = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensitivity = preferences.getInt("look_sensitivity", 50)
        renderDistance = preferences.getInt("render_distance", 2).coerceIn(1, 3)
        hideSystemBars()
        showMainMenu()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun menuButton(label: String, primary: Boolean = false, action: () -> Unit): TextView {
        val view = TextView(this)
        view.text = label
        view.gravity = Gravity.CENTER
        view.textSize = 16f
        view.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        view.setTextColor(Color.WHITE)
        view.isClickable = true
        view.isFocusable = true
        view.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (primary) intArrayOf(Color.rgb(56, 151, 91), Color.rgb(32, 111, 73))
            else intArrayOf(Color.rgb(44, 62, 79), Color.rgb(29, 42, 56))
        ).apply {
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), if (primary) 0xAA9EE7B6.toInt() else 0x665F7F96)
        }
        view.setPadding(dp(16), dp(10), dp(16), dp(10))
        view.setOnClickListener { action() }
        return view
    }

    private fun addMenuButton(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) {
        val button = menuButton(label, primary, action)
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
        params.topMargin = dp(6)
        parent.addView(button, params)
    }

    private fun heading(text: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }

    private fun showMainMenu() {
        gameView?.onPause()
        gameView = null
        renderer = null
        gameRoot = null
        gameOverlay = null
        inventoryOverlay = null
        pauseOverlay = null
        inventoryOpen = false
        isPaused = false

        val root = FrameLayout(this)
        root.addView(MenuBackdrop(this), FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(dp(24), dp(16), dp(24), dp(16))
        content.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xD91B2934.toInt(), 0xE6101924.toInt())
        ).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), 0x665D8297)
        }

        val logo = heading("BLOCK\nWORLD", 42f)
        logo.letterSpacing = 0.08f
        logo.setShadowLayer(dp(12).toFloat(), 0f, dp(4).toFloat(), 0xAA000000.toInt())
        content.addView(logo, LinearLayout.LayoutParams(-1, dp(90)))

        val subtitle = heading("ДОСЛІДЖУЙ • БУДУЙ • ВІДКРИВАЙ", 12f, 0xFFB6D2C8.toInt())
        subtitle.letterSpacing = 0.08f
        content.addView(subtitle, LinearLayout.LayoutParams(-1, dp(30)))

        val saved = preferences.contains("world_seed")
        if (saved) {
            addMenuButton(content, "ПРОДОВЖИТИ", true) {
                val seed = preferences.getLong("world_seed", 1L)
                startGame(seed)
            }
        }
        addMenuButton(content, "СТВОРИТИ НОВИЙ СВІТ", !saved) { createNewWorld() }
        addMenuButton(content, "НАЛАШТУВАННЯ") { showSettings() }
        addMenuButton(content, "ЯК ГРАТИ") {
            AlertDialog.Builder(this)
                .setTitle("Керування")
                .setMessage(
                    "Лівий круглий майданчик — рух.\n" +
                    "Проведи пальцем по правій частині екрана — огляд.\n" +
                    "СТРИБОК — перестрибнути перешкоду.\n" +
                    "ЗЛАМАТИ — добути блок під прицілом.\n" +
                    "ПОСТАВИТИ — встановити вибраний блок.\n" +
                    "ІНВЕНТАР — вибрати блоки та переглянути ресурси."
                )
                .setPositiveButton("ЗРОЗУМІЛО", null)
                .show()
        }

        val version = heading("ANDROID • VERSION 0.5.0", 10f, 0xFF8BA6B4.toInt())
        version.letterSpacing = 0.16f
        val versionParams = LinearLayout.LayoutParams(-1, dp(28))
        versionParams.topMargin = dp(10)
        content.addView(version, versionParams)

        val container = FrameLayout.LayoutParams(dp(360), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        container.leftMargin = dp(18)
        container.rightMargin = dp(18)
        root.addView(content, container)
        setContentView(root)
    }

    private fun createNewWorld() {
        val seed = System.currentTimeMillis() xor (System.nanoTime() shl 1)
        preferences.edit()
            .putLong("world_seed", seed)
            .remove("world_changes")
            .remove("world_inventory")
            .apply()
        startGame(seed)
    }

    private fun showSettings() {
        val root = FrameLayout(this)
        root.addView(MenuBackdrop(this), FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(24), dp(24), dp(24), dp(24))
        content.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF51D2934.toInt(), 0xF5121B25.toInt())
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0x775F8396)
        }

        content.addView(heading("НАЛАШТУВАННЯ", 28f), LinearLayout.LayoutParams(-1, dp(58)))
        val label = heading("ЧУТЛИВІСТЬ ОГЛЯДУ: $sensitivity%", 15f, 0xFFD4E4E8.toInt())
        content.addView(label, LinearLayout.LayoutParams(-1, dp(38)))
        val seek = SeekBar(this).apply {
            max = 100
            progress = sensitivity
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                sensitivity = value.coerceIn(10, 100)
                label.text = "ЧУТЛИВІСТЬ ОГЛЯДУ: $sensitivity%"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                preferences.edit().putInt("look_sensitivity", sensitivity).apply()
            }
        })
        content.addView(seek, LinearLayout.LayoutParams(-1, dp(42)))

        val distance = heading("ДАЛЬНІСТЬ ПРОГЛЯДУ: $renderDistance", 15f, 0xFFD4E4E8.toInt())
        content.addView(distance, LinearLayout.LayoutParams(-1, dp(42)))
        addMenuButton(content, "ЗМІНИТИ ДАЛЬНІСТЬ") {
            renderDistance = if (renderDistance >= 3) 1 else renderDistance + 1
            preferences.edit().putInt("render_distance", renderDistance).apply()
            distance.text = "ДАЛЬНІСТЬ ПРОГЛЯДУ: $renderDistance"
            renderer?.setRenderDistance(renderDistance)
        }
        addMenuButton(content, "НАЗАД", true) { showMainMenu() }

        val layout = FrameLayout.LayoutParams(dp(380), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        layout.leftMargin = dp(16)
        layout.rightMargin = dp(16)
        root.addView(content, layout)
        setContentView(root)
    }

    private fun startGame(seed: Long) {
        inventoryOpen = false
        isPaused = false
        latestInventory = IntArray(WorldRenderer.BLOCK_COUNT)
        selectedBlock = WorldRenderer.BLOCK_DIRT

        val root = FrameLayout(this)
        val surface = BlockWorldView(this, seed, this)
        val worldRenderer = surface.worldRenderer
        worldRenderer.setRenderDistance(renderDistance)
        renderer = worldRenderer
        gameView = surface

        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        val controls = buildGameControls(surface, worldRenderer)
        gameOverlay = controls
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        gameRoot = root
        setContentView(root)
        surface.onResume()
    }

    private fun buildGameControls(surface: BlockWorldView, world: WorldRenderer): FrameLayout {
        val layer = FrameLayout(this)
        layer.setMotionEventSplittingEnabled(true)

        val lookPad = CameraLookPad(this, surface, sensitivity)
        layer.addView(lookPad, FrameLayout.LayoutParams(-1, -1))

        val joystick = JoystickPad(this) { x, y ->
            surface.queueEvent { world.setMovement(x, y) }
        }
        val joystickSize = dp(142)
        val joystickParams = FrameLayout.LayoutParams(joystickSize, joystickSize, Gravity.START or Gravity.BOTTOM)
        joystickParams.leftMargin = dp(18)
        joystickParams.bottomMargin = dp(24)
        layer.addView(joystick, joystickParams)

        val info = TextView(this).apply {
            text = "BLOCK WORLD"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(0xB91B2934.toInt(), 0x665D8297)
        }
        val infoParams = FrameLayout.LayoutParams(dp(160), dp(42), Gravity.TOP or Gravity.START)
        infoParams.leftMargin = dp(14)
        infoParams.topMargin = dp(12)
        layer.addView(info, infoParams)

        val inventoryButton = gameButton("ІНВ", 0xCC263D4D.toInt()) { showInventoryOverlay() }
        val inventoryParams = FrameLayout.LayoutParams(dp(64), dp(44), Gravity.TOP or Gravity.END)
        inventoryParams.topMargin = dp(12)
        inventoryParams.rightMargin = dp(86)
        layer.addView(inventoryButton, inventoryParams)

        val pauseButton = gameButton("Ⅱ", 0xCC263D4D.toInt()) { showPauseOverlay() }
        val pauseParams = FrameLayout.LayoutParams(dp(58), dp(44), Gravity.TOP or Gravity.END)
        pauseParams.topMargin = dp(12)
        pauseParams.rightMargin = dp(16)
        layer.addView(pauseButton, pauseParams)

        val hotbar = LinearLayout(this)
        hotbar.orientation = LinearLayout.HORIZONTAL
        hotbar.gravity = Gravity.CENTER
        hotbar.setPadding(dp(5), dp(5), dp(5), dp(5))
        hotbar.background = rounded(0xB8172530.toInt(), 0x669CB3BF)
        hotbarViews.clear()
        val quickSlots = intArrayOf(
            WorldRenderer.BLOCK_DIRT,
            WorldRenderer.BLOCK_STONE,
            WorldRenderer.BLOCK_WOOD,
            WorldRenderer.BLOCK_LEAVES,
            WorldRenderer.BLOCK_SAND
        )
        for (block in quickSlots) {
            val slot = gameButton(WorldRenderer.shortName(block), 0xCC33495A.toInt()) {
                selectedBlock = block
                surface.queueEvent { world.selectBlock(block) }
                updateHotbarSelection()
            }
            hotbarViews.add(slot)
            val params = LinearLayout.LayoutParams(dp(58), dp(44))
            params.leftMargin = dp(3)
            params.rightMargin = dp(3)
            hotbar.addView(slot, params)
        }
        val hotbarParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        hotbarParams.bottomMargin = dp(15)
        layer.addView(hotbar, hotbarParams)
        updateHotbarSelection()

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER
        actions.setPadding(dp(4), dp(4), dp(4), dp(4))
        actions.background = rounded(0xA8172530.toInt(), 0x669CB3BF)

        val jump = gameButton("СТРИБОК", 0xCC3C725D.toInt()) { surface.queueEvent { world.jump() } }
        val breakBlock = gameButton("ЗЛАМАТИ", 0xCC82533D.toInt()) { surface.queueEvent { world.breakBlock() } }
        val place = gameButton("ПОСТАВИТИ", 0xCC3F647E.toInt()) { surface.queueEvent { world.placeBlock() } }
        for (button in listOf(jump, breakBlock, place)) {
            val params = LinearLayout.LayoutParams(dp(76), dp(54))
            params.leftMargin = dp(3)
            params.rightMargin = dp(3)
            actions.addView(button, params)
        }
        val actionParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(64), Gravity.BOTTOM or Gravity.END)
        actionParams.rightMargin = dp(12)
        actionParams.bottomMargin = dp(82)
        layer.addView(actions, actionParams)
        return layer
    }

    private fun rounded(color: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun gameButton(label: String, color: Int, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12f
            maxLines = 2
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(color, 0x99C1D2DA.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun updateHotbarSelection() {
        hotbarViews.forEachIndexed { index, view ->
            val block = intArrayOf(
                WorldRenderer.BLOCK_DIRT,
                WorldRenderer.BLOCK_STONE,
                WorldRenderer.BLOCK_WOOD,
                WorldRenderer.BLOCK_LEAVES,
                WorldRenderer.BLOCK_SAND
            )[index]
            val count = latestInventory.getOrElse(block) { 0 }
            view.text = WorldRenderer.shortName(block) + "\n" + count
            view.background = rounded(
                if (block == selectedBlock) 0xEE477C63.toInt() else 0xCC33495A.toInt(),
                if (block == selectedBlock) 0xFFFFFFFF.toInt() else 0x669CB3BF
            )
        }
    }

    private fun showPauseOverlay() {
        if (isPaused) return
        isPaused = true
        renderer?.setPaused(true)
        val root = gameRoot ?: return
        val shade = FrameLayout(this)
        shade.setBackgroundColor(0xB9000000.toInt())
        shade.isClickable = true
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(dp(24), dp(24), dp(24), dp(24))
        content.background = rounded(0xF51B2934.toInt(), 0xAA8DA7B4.toInt())
        content.addView(heading("ПАУЗА", 30f), LinearLayout.LayoutParams(-1, dp(56)))
        addMenuButton(content, "ПОВЕРНУТИСЯ ДО ГРИ", true) {
            root.removeView(shade)
            pauseOverlay = null
            isPaused = false
            renderer?.setPaused(false)
        }
        addMenuButton(content, "ІНВЕНТАР") { showInventoryOverlay() }
        addMenuButton(content, "ЗБЕРЕГТИ СВІТ") {
            renderer?.saveGame()
            Toast.makeText(this, "Світ збережено", Toast.LENGTH_SHORT).show()
        }
        addMenuButton(content, "ГОЛОВНЕ МЕНЮ") {
            renderer?.saveGame()
            gameView?.onPause()
            showMainMenu()
        }
        val params = FrameLayout.LayoutParams(dp(340), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        params.leftMargin = dp(16)
        params.rightMargin = dp(16)
        shade.addView(content, params)
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        pauseOverlay = shade
    }

    private fun showInventoryOverlay() {
        if (inventoryOpen) return
        inventoryOpen = true
        renderer?.setPaused(true)
        val root = gameRoot ?: return
        val shade = FrameLayout(this)
        shade.setBackgroundColor(0xC9000000.toInt())
        shade.isClickable = true

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(18), dp(18), dp(18), dp(18))
        panel.background = rounded(0xF51B2934.toInt(), 0xAA8DA7B4.toInt())
        panel.addView(heading("ІНВЕНТАР", 25f), LinearLayout.LayoutParams(-1, dp(48)))

        val grid = GridLayout(this)
        grid.columnCount = 4
        val blockIds = (1 until WorldRenderer.BLOCK_COUNT).filter { WorldRenderer.isInventoryItem(it) }
        for (block in blockIds) {
            val count = latestInventory.getOrElse(block) { 0 }
            val slot = gameButton(WorldRenderer.shortName(block) + "\n" + count, 0xCC33495A.toInt()) {
                selectedBlock = block
                renderer?.let { current -> gameView?.queueEvent { current.selectBlock(block) } }
                updateHotbarSelection()
                inventoryOpen = false
                root.removeView(shade)
                inventoryOverlay = null
                renderer?.setPaused(isPaused)
            }
            val cell = GridLayout.LayoutParams()
            cell.width = 0
            cell.height = dp(58)
            cell.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            cell.setMargins(dp(3), dp(3), dp(3), dp(3))
            grid.addView(slot, cell)
        }
        panel.addView(grid, LinearLayout.LayoutParams(-1, dp(220)))
        val close = gameButton("ЗАКРИТИ", 0xCC3C725D.toInt()) {
            root.removeView(shade)
            inventoryOverlay = null
            inventoryOpen = false
            renderer?.setPaused(isPaused)
        }
        val closeParams = LinearLayout.LayoutParams(-1, dp(48))
        closeParams.topMargin = dp(8)
        panel.addView(close, closeParams)

        val panelParams = FrameLayout.LayoutParams(dp(360), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        panelParams.leftMargin = dp(12)
        panelParams.rightMargin = dp(12)
        shade.addView(panel, panelParams)
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))
        inventoryOverlay = shade
    }

    override fun onInventoryChanged(counts: IntArray, selected: Int) {
        runOnUiThread {
            latestInventory = counts.copyOf()
            selectedBlock = selected
            updateHotbarSelection()
        }
    }

    override fun onWorldReady() {
        runOnUiThread { Toast.makeText(this, "Світ готовий", Toast.LENGTH_SHORT).show() }
    }

    override fun onPause() {
        renderer?.saveGame()
        gameView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        gameView?.onResume()
    }
}

private class BlockWorldView(
    activity: Activity,
    seed: Long,
    listener: WorldRenderer.Listener
) : GLSurfaceView(activity) {
    val worldRenderer = WorldRenderer(activity, seed, listener)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(worldRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
    }
}

private class CameraLookPad(
    context: android.content.Context,
    private val surface: BlockWorldView,
    private val sensitivity: Int
) : View(context) {
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val factor = (sensitivity.coerceIn(10, 100) / 50f) * 0.0042f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                if (x < width * 0.40f || y < height * 0.08f || y > height * 0.90f) return true
                if (pointerId == MotionEvent.INVALID_POINTER_ID) {
                    pointerId = event.getPointerId(index)
                    lastX = x
                    lastY = y
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = (0 until event.pointerCount).firstOrNull { event.getPointerId(it) == pointerId }
                if (index != null) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val dx = x - lastX
                    val dy = y - lastY
                    lastX = x
                    lastY = y
                    surface.queueEvent { surface.worldRenderer.rotate(dx * factor, dy * factor) }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                if (event.getPointerId(index) == pointerId) pointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
        }
        return true
    }
}

private class JoystickPad(
    context: android.content.Context,
    private val onMove: (Float, Float) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var knobX = 0f
    private var knobY = 0f

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.43f
        paint.style = Paint.Style.FILL
        paint.color = 0x66303E4B
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xBBE7F0F3.toInt()
        canvas.drawCircle(if (knobX == 0f) cx else knobX, if (knobY == 0f) cy else knobY, radius * 0.30f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (pointerId == MotionEvent.INVALID_POINTER_ID) {
                    pointerId = event.getPointerId(index)
                    update(event.getX(index), event.getY(index))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = (0 until event.pointerCount).firstOrNull { event.getPointerId(it) == pointerId }
                if (index != null) update(event.getX(index), event.getY(index))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.getPointerId(index) == pointerId) {
                    pointerId = MotionEvent.INVALID_POINTER_ID
                    knobX = 0f
                    knobY = 0f
                    onMove(0f, 0f)
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (min(width, height) * 0.40f).coerceAtLeast(1f)
        var dx = ((x - cx) / radius).coerceIn(-1f, 1f)
        var dy = ((cy - y) / radius).coerceIn(-1f, 1f)
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length > 1f) {
            dx /= length
            dy /= length
        }
        knobX = cx + dx * radius
        knobY = cy - dy * radius
        onMove(dx, dy)
        invalidate()
    }
}

private class MenuBackdrop(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.rgb(15, 31, 43))
        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.rgb(20, 46, 62), Color.rgb(27, 70, 70), Color.rgb(12, 27, 42)),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.color = 0x223FBE8B
        canvas.drawCircle(w * 0.78f, h * 0.30f, h * 0.34f, paint)
        paint.color = 0x223F84BE
        canvas.drawCircle(w * 0.20f, h * 0.82f, h * 0.40f, paint)

        val block = (h * 0.055f).coerceAtLeast(12f)
        for (i in 0..11) {
            val x = (i * 97f + w * 0.08f) % (w + block) - block
            val y = h * 0.68f + (i % 4) * block * 0.72f
            paint.color = when (i % 3) {
                0 -> 0x553F8B60
                1 -> 0x554A6979
                else -> 0x555D7858
            }
            canvas.drawRoundRect(x, y, x + block, y + block, block * 0.10f, block * 0.10f, paint)
        }
        paint.color = 0x335D9B81
        for (i in 0..5) {
            val y = h * 0.58f + i * dpStatic(13f)
            canvas.drawRect(0f, y, w, y + dpStatic(1.5f), paint)
        }
    }

    private fun dpStatic(value: Float) = value * resources.displayMetrics.density
}
