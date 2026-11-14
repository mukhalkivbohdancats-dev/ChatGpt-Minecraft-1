package dev.mukhalkiv.blockworld

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

private data class BlockPos(val x: Int, val y: Int, val z: Int)
private data class RayHit(val block: BlockPos, val previousAir: BlockPos?)

private class FloatMeshBuilder(initialCapacity: Int = 16_384) {
    private var values = FloatArray(initialCapacity)
    var size: Int = 0
        private set

    fun vertex(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
        ensure(6)
        values[size++] = x
        values[size++] = y
        values[size++] = z
        values[size++] = r
        values[size++] = g
        values[size++] = b
    }

    private fun ensure(extra: Int) {
        if (size + extra <= values.size) return
        values = values.copyOf(max(values.size * 2, size + extra))
    }

    fun toDirectFloatBuffer(): FloatBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
        val result = byteBuffer.asFloatBuffer()
        result.put(values, 0, size)
        result.position(0)
        return result
    }
}

class WorldRenderer(context: Context) : GLSurfaceView.Renderer {
    companion object {
        private const val WORLD_SIZE = 48
        private const val MAX_HEIGHT = 20
        private const val PLAYER_EYE_HEIGHT = 2.65f
    }

    private val preferences = context.getSharedPreferences("blockworld_save", Context.MODE_PRIVATE)
    // All world mutations after initialization happen on the GL thread via queueEvent().
    private val blocks = HashMap<BlockPos, Int>()
    private val changedBlocks = HashMap<BlockPos, Int>()

    private var vertexBuffer: FloatBuffer? = null
    private var vertexCount = 0
    private var shaderProgram = 0
    private var positionAttribute = 0
    private var colorAttribute = 0
    private var mvpUniform = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    private var playerX = 24.5f
    private var playerY = 8f
    private var playerZ = 40.5f
    private var yaw = 0f
    private var pitch = -0.20f
    private var verticalVelocity = 0f
    private var onGround = false
    @Volatile private var forwardInput = 0f
    @Volatile private var strafeInput = 0f
    private var jumpQueued = false
    private var lastFrameNanos = 0L
    private var meshDirty = true
    private val selectedBlockType = 2 // dirt

    init {
        generateTerrain()
        loadSavedChanges()
        playerY = groundEyeHeight(playerX, playerZ)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.53f, 0.78f, 0.98f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DITHER)

        val vertexShader = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vColor = aColor;
            }
        """.trimIndent()
        val fragmentShader = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """.trimIndent()

        shaderProgram = linkProgram(vertexShader, fragmentShader)
        positionAttribute = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        colorAttribute = GLES20.glGetAttribLocation(shaderProgram, "aColor")
        mvpUniform = GLES20.glGetUniformLocation(shaderProgram, "uMvp")
        meshDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, safeWidth, safeHeight)
        Matrix.perspectiveM(projection, 0, 72f, safeWidth.toFloat() / safeHeight, 0.08f, 150f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        }
        lastFrameNanos = now

        updatePlayer(deltaSeconds)
        if (meshDirty) rebuildWorldMesh()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val cosPitch = cos(pitch)
        val lookX = sin(yaw) * cosPitch
        val lookY = sin(pitch)
        val lookZ = -cos(yaw) * cosPitch
        Matrix.setLookAtM(
            view, 0,
            playerX, playerY, playerZ,
            playerX + lookX, playerY + lookY, playerZ + lookZ,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        val buffer = vertexBuffer
        if (shaderProgram == 0 || vertexCount <= 0 || buffer == null) return

        GLES20.glUseProgram(shaderProgram)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glEnableVertexAttribArray(colorAttribute)
        GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 24, buffer)
        buffer.position(3)
        GLES20.glVertexAttribPointer(colorAttribute, 3, GLES20.GL_FLOAT, false, 24, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(colorAttribute)
    }

    fun rotate(deltaX: Float, deltaY: Float) {
        yaw = (yaw + deltaX).let { angle ->
            when {
                angle > Math.PI -> (angle - Math.PI * 2.0).toFloat()
                angle < -Math.PI -> (angle + Math.PI * 2.0).toFloat()
                else -> angle
            }
        }
        pitch = (pitch - deltaY).coerceIn(-1.25f, 1.15f)
    }

    fun setMovement(strafe: Float, forward: Float) {
        strafeInput = strafe.coerceIn(-1f, 1f)
        forwardInput = forward.coerceIn(-1f, 1f)
    }

    fun jump() {
        jumpQueued = true
    }

    private fun updatePlayer(dt: Float) {
        val inputLength = sqrt(strafeInput * strafeInput + forwardInput * forwardInput)
        val normalization = if (inputLength > 1f) 1f / inputLength else 1f
        val forward = forwardInput * normalization
        val strafe = strafeInput * normalization
        val speed = 4.8f * dt

        val forwardX = sin(yaw)
        val forwardZ = -cos(yaw)
        val rightX = cos(yaw)
        val rightZ = sin(yaw)
        val dx = (forwardX * forward + rightX * strafe) * speed
        val dz = (forwardZ * forward + rightZ * strafe) * speed

        val nextX = (playerX + dx).coerceIn(1.2f, WORLD_SIZE - 1.2f)
        val nextZ = (playerZ + dz).coerceIn(1.2f, WORLD_SIZE - 1.2f)
        val nextGroundEye = groundEyeHeight(nextX, nextZ)

        // Do not walk through a wall higher than one block.
        if (nextGroundEye <= playerY + 0.58f || !onGround) {
            playerX = nextX
            playerZ = nextZ
        }

        val groundEye = groundEyeHeight(playerX, playerZ)
        if (jumpQueued && onGround) {
            verticalVelocity = 6.2f
            onGround = false
        }
        jumpQueued = false

        verticalVelocity -= 17.5f * dt
        playerY += verticalVelocity * dt
        if (playerY <= groundEye) {
            playerY = groundEye
            verticalVelocity = 0f
            onGround = true
        } else {
            onGround = false
        }
    }

    fun breakBlock() {
        val hit = raycast() ?: return
        if (blocks.remove(hit.block) != null) {
            changedBlocks[hit.block] = 0
            meshDirty = true
            saveChanges()
        }
    }

    fun placeBlock() {
        val hit = raycast() ?: return
        val target = hit.previousAir ?: return
        if (target.x !in 0 until WORLD_SIZE || target.z !in 0 until WORLD_SIZE ||
            target.y !in 0..MAX_HEIGHT || blocks.containsKey(target)) return

        // Keep the player from placing a block inside their own position.
        val centerX = target.x + 0.5f
        val centerY = target.y + 0.5f
        val centerZ = target.z + 0.5f
        if (abs(centerX - playerX) < 0.78f &&
            abs(centerZ - playerZ) < 0.78f &&
            abs(centerY - playerY) < 1.65f) return

        blocks[target] = selectedBlockType
        changedBlocks[target] = selectedBlockType
        meshDirty = true
        saveChanges()
    }

    private fun raycast(): RayHit? {
        val cosPitch = cos(pitch)
        val dx = sin(yaw) * cosPitch
        val dy = sin(pitch)
        val dz = -cos(yaw) * cosPitch
        var previousCell: BlockPos? = null
        var lastAir: BlockPos? = null
        var distance = 0.10f

        // Small fixed steps avoid skipping thin block cells at normal view distances.
        while (distance <= 6.0f) {
            val position = BlockPos(
                floor(playerX + dx * distance).toInt(),
                floor(playerY + dy * distance).toInt(),
                floor(playerZ + dz * distance).toInt()
            )
            if (position != previousCell) {
                if (blocks.containsKey(position)) {
                    return RayHit(position, lastAir)
                }
                lastAir = position
                previousCell = position
            }
            distance += 0.035f
        }
        return null
    }

    private fun generateTerrain() {
        blocks.clear()
        for (x in 0 until WORLD_SIZE) {
            for (z in 0 until WORLD_SIZE) {
                val height = terrainHeight(x, z)
                for (y in 0..height) {
                    val type = when {
                        y == height -> 1 // grass
                        y >= height - 2 -> 2 // dirt
                        else -> 3 // stone
                    }
                    blocks[BlockPos(x, y, z)] = type
                }
            }
        }

        // Create a clear starting area.
        for (x in 21..27) {
            for (z in 37..44) {
                val surface = terrainHeight(x, z)
                for (y in surface + 1..surface + 4) blocks.remove(BlockPos(x, y, z))
            }
        }

        // A small tree that acts as a visible landmark.
        val treeX = 30
        val treeZ = 29
        val treeBase = terrainHeight(treeX, treeZ) + 1
        for (y in treeBase..treeBase + 3) blocks[BlockPos(treeX, y, treeZ)] = 4
        for (x in treeX - 2..treeX + 2) {
            for (z in treeZ - 2..treeZ + 2) {
                for (y in treeBase + 2..treeBase + 4) {
                    val distance = abs(x - treeX) + abs(z - treeZ) + abs(y - (treeBase + 3))
                    if (distance <= 4) blocks.putIfAbsent(BlockPos(x, y, z), 5)
                }
            }
        }
    }

    private fun terrainHeight(x: Int, z: Int): Int {
        val broad = sin(x * 0.19) * 1.8 + cos(z * 0.17) * 1.5
        val detail = sin((x + z) * 0.31) * 0.9 + cos((x - z) * 0.23) * 0.6
        return (5 + broad + detail).roundToInt().coerceIn(2, 9)
    }

    private fun highestSolidBlock(x: Int, z: Int): Int {
        for (y in MAX_HEIGHT downTo 0) {
            if (blocks.containsKey(BlockPos(x, y, z))) return y
        }
        return 0
    }

    private fun groundEyeHeight(x: Float, z: Float): Float {
        val blockX = floor(x).toInt().coerceIn(0, WORLD_SIZE - 1)
        val blockZ = floor(z).toInt().coerceIn(0, WORLD_SIZE - 1)
        return highestSolidBlock(blockX, blockZ) + PLAYER_EYE_HEIGHT
    }

    private fun loadSavedChanges() {
        val raw = preferences.getString("changes_v2", null)
            ?: preferences.getString("changes", "")
            ?: return
        raw.split(';').forEach { entry ->
            val fields = entry.split(',')
            if (fields.size != 4) return@forEach
            runCatching {
                val pos = BlockPos(fields[0].toInt(), fields[1].toInt(), fields[2].toInt())
                val type = fields[3].toInt()
                if (pos.x !in 0 until WORLD_SIZE || pos.z !in 0 until WORLD_SIZE ||
                    pos.y !in 0..MAX_HEIGHT || type !in 0..5) return@runCatching
                if (type == 0) blocks.remove(pos) else blocks[pos] = type
                // Keep loaded edits in the change map so saving a later edit cannot erase them.
                changedBlocks[pos] = type
            }
        }
    }

    private fun saveChanges() {
        val serialized = changedBlocks.entries.joinToString(";") { entry ->
            "${entry.key.x},${entry.key.y},${entry.key.z},${entry.value}"
        }
        preferences.edit().putString("changes_v2", serialized).apply()
    }

    private fun rebuildWorldMesh() {
        val mesh = FloatMeshBuilder()
        val corners = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f),
            floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 1f),
            floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 1f, 1f)
        )

        // Each face is emitted only when the neighbouring cell is empty.
        val faces = arrayOf(
            intArrayOf(0, 3, 2, 0, 2, 1), // -Z
            intArrayOf(4, 5, 6, 4, 6, 7), // +Z
            intArrayOf(0, 4, 7, 0, 7, 3), // -X
            intArrayOf(1, 2, 6, 1, 6, 5), // +X
            intArrayOf(3, 7, 6, 3, 6, 2), // +Y
            intArrayOf(0, 1, 5, 0, 5, 4)  // -Y
        )
        val neighbourOffsets = arrayOf(
            intArrayOf(0, 0, -1), intArrayOf(0, 0, 1),
            intArrayOf(-1, 0, 0), intArrayOf(1, 0, 0),
            intArrayOf(0, 1, 0), intArrayOf(0, -1, 0)
        )
        val brightness = floatArrayOf(0.78f, 0.90f, 0.82f, 0.86f, 1.0f, 0.58f)

        for ((position, blockType) in blocks) {
            val baseColor = blockColor(blockType)
            for (faceIndex in faces.indices) {
                val offset = neighbourOffsets[faceIndex]
                val neighbour = BlockPos(
                    position.x + offset[0],
                    position.y + offset[1],
                    position.z + offset[2]
                )
                if (blocks.containsKey(neighbour)) continue

                val shade = brightness[faceIndex]
                for (cornerIndex in faces[faceIndex]) {
                    val corner = corners[cornerIndex]
                    mesh.vertex(
                        position.x + corner[0],
                        position.y + corner[1],
                        position.z + corner[2],
                        (baseColor[0] * shade).coerceAtMost(1f),
                        (baseColor[1] * shade).coerceAtMost(1f),
                        (baseColor[2] * shade).coerceAtMost(1f)
                    )
                }
            }
        }

        vertexBuffer = mesh.toDirectFloatBuffer()
        vertexCount = mesh.size / 6
        meshDirty = false
    }

    private fun blockColor(type: Int): FloatArray = when (type) {
        1 -> floatArrayOf(0.30f, 0.70f, 0.24f) // grass
        2 -> floatArrayOf(0.52f, 0.34f, 0.20f) // dirt
        3 -> floatArrayOf(0.52f, 0.54f, 0.58f) // stone
        4 -> floatArrayOf(0.43f, 0.27f, 0.15f) // wood
        5 -> floatArrayOf(0.17f, 0.52f, 0.20f) // leaves
        else -> floatArrayOf(0.72f, 0.72f, 0.72f)
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "Unable to create OpenGL shader" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("OpenGL shader compilation failed: $log")
            }
            return shader
        }

        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = try {
            compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        } catch (error: Throwable) {
            GLES20.glDeleteShader(vertex)
            throw error
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("OpenGL program link failed: $log")
        }
        return program
    }
}
