package dev.mukhalkiv.blockworld

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

private data class BlockPos(val x: Int, val y: Int, val z: Int)

class WorldRenderer(context: Context) : GLSurfaceView.Renderer {
    private val prefs = context.getSharedPreferences("blockworld_save", Context.MODE_PRIVATE)
    private val blocks = ConcurrentHashMap<BlockPos, Int>()
    private val changed = ConcurrentHashMap<BlockPos, Int>()
    private var vertexBuffer: FloatBuffer? = null
    private var vertexCount = 0
    private var program = 0
    private var aPosition = 0
    private var aColor = 0
    private var uMvp = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private var width = 1
    private var height = 1
    private var px = 16.0f
    private var py = 12.0f
    private var pz = 25.0f
    private var yaw = 0.0f
    private var pitch = -0.20f
    @Volatile private var forward = 0f
    @Volatile private var sideways = 0f
    private var lastFrame = 0L
    private var dirty = true
    private var selectedBlock = 2

    init {
        generateTerrain()
        loadChanges()
        dirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.52f, 0.76f, 0.96f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        val vertex = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vColor = aColor;
            }
        """.trimIndent()
        val fragment = """
            precision mediump float;
            varying vec3 vColor;
            void main() { gl_FragColor = vec4(vColor, 1.0); }
        """.trimIndent()
        program = linkProgram(vertex, fragment)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        dirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 70f, width.toFloat() / height, 0.08f, 180f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrame = now
        updatePlayer(dt)
        if (dirty) rebuildMesh()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val cp = cos(pitch)
        val fx = sin(yaw) * cp
        val fy = sin(pitch)
        val fz = -cos(yaw) * cp
        Matrix.setLookAtM(view, 0, px, py, pz, px + fx, py + fy, pz + fz, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        if (program == 0 || vertexCount == 0) return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        val buffer = vertexBuffer ?: return
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aColor)
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
        buffer.position(3)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aColor)
    }

    fun rotate(dx: Float, dy: Float) {
        yaw = (yaw + dx).let { if (it > Math.PI) (it - Math.PI * 2).toFloat() else if (it < -Math.PI) (it + Math.PI * 2).toFloat() else it }
        pitch = (pitch - dy).coerceIn(-1.35f, 1.15f)
    }

    fun setMovement(x: Float, y: Float) { sideways = x; forward = y }

    private fun updatePlayer(dt: Float) {
        val speed = 5.2f * dt
        val fx = sin(yaw); val fz = -cos(yaw)
        val rx = cos(yaw); val rz = sin(yaw)
        val dx = (fx * forward + rx * sideways) * speed
        val dz = (fz * forward + rz * sideways) * speed
        val nx = (px + dx).coerceIn(1.2f, 30.8f)
        val nz = (pz + dz).coerceIn(1.2f, 30.8f)
        val floor = terrainHeight(nx.roundToInt(), nz.roundToInt()).toFloat() + 2.65f
        px = nx; pz = nz
        py += ((floor - py) * (dt * 5f).coerceAtMost(1f))
    }

    fun breakBlock() {
        val hit = raycast() ?: return
        blocks.remove(hit)
        changed[hit] = 0
        dirty = true
        saveChanges()
    }

    fun placeBlock() {
        val hit = raycast() ?: return
        val dir = direction()
        val target = BlockPos(hit.x + dir[0], hit.y + dir[1], hit.z + dir[2])
        if (target.y !in 0..30 || target.x !in 0..31 || target.z !in 0..31) return
        if (abs(target.x + 0.5f - px) < 0.8f && abs(target.z + 0.5f - pz) < 0.8f && abs(target.y + 0.5f - py) < 1.8f) return
        blocks[target] = selectedBlock
        changed[target] = selectedBlock
        dirty = true
        saveChanges()
    }

    private fun direction(): IntArray {
        val cp = cos(pitch)
        val dx = sin(yaw) * cp
        val dy = sin(pitch)
        val dz = -cos(yaw) * cp
        return intArrayOf(dx.roundToInt(), dy.roundToInt(), dz.roundToInt())
    }

    private fun raycast(): BlockPos? {
        val cp = cos(pitch)
        val dx = sin(yaw) * cp; val dy = sin(pitch); val dz = -cos(yaw) * cp
        var previous: BlockPos? = null
        var t = 0.2f
        while (t < 6.0f) {
            val x = floor(px + dx * t).toInt()
            val y = floor(py + dy * t).toInt()
            val z = floor(pz + dz * t).toInt()
            val pos = BlockPos(x, y, z)
            if (blocks.containsKey(pos)) return previous ?: pos
            previous = pos
            t += 0.12f
        }
        return null
    }

    private fun generateTerrain() {
        for (x in 0..31) for (z in 0..31) {
            val h = terrainHeight(x, z)
            for (y in 0..h) {
                val type = when {
                    y == h -> 1
                    y >= h - 2 -> 2
                    else -> 3
                }
                blocks[BlockPos(x, y, z)] = type
            }
        }
        // A small spawn clearing and a simple tree landmark.
        for (x in 13..18) for (z in 22..27) {
            val h = terrainHeight(x, z)
            for (y in h + 1..h + 3) blocks.remove(BlockPos(x, y, z))
        }
        val tx = 21; val tz = 19; val base = terrainHeight(tx, tz) + 1
        for (y in base..base + 3) blocks[BlockPos(tx, y, tz)] = 4
        for (x in tx - 2..tx + 2) for (z in tz - 2..tz + 2) for (y in base + 2..base + 4) {
            if (abs(x - tx) + abs(z - tz) + abs(y - (base + 3)) < 5) blocks.putIfAbsent(BlockPos(x, y, z), 5)
        }
    }

    private fun terrainHeight(x: Int, z: Int): Int {
        val wave = sin(x * 0.31) * 1.4 + cos(z * 0.23) * 1.1 + sin((x + z) * 0.16) * 1.0
        return (4 + wave.roundToInt()).coerceIn(2, 8)
    }

    private fun loadChanges() {
        val raw = prefs.getString("changes", "") ?: return
        raw.split(';').forEach { item ->
            val v = item.split(',')
            if (v.size == 4) runCatching {
                val p = BlockPos(v[0].toInt(), v[1].toInt(), v[2].toInt())
                val type = v[3].toInt()
                if (type == 0) blocks.remove(p) else blocks[p] = type
            }
        }
    }

    private fun saveChanges() {
        val raw = changed.entries.joinToString(";") { "${it.key.x},${it.key.y},${it.key.z},${it.value}" }
        prefs.edit().putString("changes", raw).apply()
    }

    private fun rebuildMesh() {
        val data = ArrayList<Float>(blocks.size * 36)
        val faces = arrayOf(
            Face(intArrayOf(0,1,2, 0,2,3), floatArrayOf(0f,0f,1f), 0.78f),
            Face(intArrayOf(4,7,6, 4,6,5), floatArrayOf(0f,0f,-1f), 0.72f),
            Face(intArrayOf(0,4,5, 0,5,1), floatArrayOf(-1f,0f,0f), 0.82f),
            Face(intArrayOf(3,2,6, 3,6,7), floatArrayOf(1f,0f,0f), 0.86f),
            Face(intArrayOf(1,5,6, 1,6,2), floatArrayOf(0f,1f,0f), 1.0f),
            Face(intArrayOf(0,3,7, 0,7,4), floatArrayOf(0f,-1f,0f), 0.55f)
        )
        val corners = arrayOf(
            floatArrayOf(0f,0f,0f), floatArrayOf(1f,0f,0f),
            floatArrayOf(1f,1f,0f), floatArrayOf(0f,1f,0f),
            floatArrayOf(0f,0f,1f), floatArrayOf(1f,0f,1f),
            floatArrayOf(1f,1f,1f), floatArrayOf(0f,1f,1f)
        )
        val offsets = arrayOf(intArrayOf(0,0,1), intArrayOf(0,0,-1), intArrayOf(-1,0,0), intArrayOf(1,0,0), intArrayOf(0,1,0), intArrayOf(0,-1,0))
        for ((pos, type) in blocks.entries) {
            val base = color(type)
            for (f in faces.indices) {
                val off = offsets[f]
                if (blocks.containsKey(BlockPos(pos.x + off[0], pos.y + off[1], pos.z + off[2]))) continue
                val face = faces[f]
                val shade = face.shade
                for (index in face.indices) {
                    val c = corners[index]
                    data.add(pos.x + c[0]); data.add(pos.y + c[1]); data.add(pos.z + c[2])
                    data.add((base[0] * shade).coerceAtMost(1f))
                    data.add((base[1] * shade).coerceAtMost(1f))
                    data.add((base[2] * shade).coerceAtMost(1f))
                }
            }
        }
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        data.forEach { fb.put(it) }
        fb.position(0)
        vertexBuffer = fb
        vertexCount = data.size / 6
        dirty = false
    }

    private fun color(type: Int): FloatArray = when (type) {
        1 -> floatArrayOf(0.28f, 0.68f, 0.22f) // grass
        2 -> floatArrayOf(0.48f, 0.30f, 0.16f) // dirt
        3 -> floatArrayOf(0.48f, 0.49f, 0.52f) // stone
        4 -> floatArrayOf(0.42f, 0.25f, 0.12f) // wood
        5 -> floatArrayOf(0.16f, 0.48f, 0.18f) // leaves
        else -> floatArrayOf(0.75f, 0.75f, 0.75f)
    }

    private data class Face(val indices: IntArray, val normal: FloatArray, val shade: Float)

    private fun linkProgram(v: String, f: String): Int {
        fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) throw IllegalStateException("Shader compile failed: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val vs = shader(GLES20.GL_VERTEX_SHADER, v)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, f)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw IllegalStateException("Program link failed: " + GLES20.glGetProgramInfoLog(p))
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return p
    }
}
