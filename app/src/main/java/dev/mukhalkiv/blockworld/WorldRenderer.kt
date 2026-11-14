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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val BIOME_DESERT = 0
private const val BIOME_PLAINS = 1
private const val BIOME_FOREST = 2

private data class ChunkKey(val x: Int, val z: Int)
private data class BlockPos(val x: Int, val y: Int, val z: Int)
private data class RayHit(val block: BlockPos, val previousAir: BlockPos?)

private class VoxelChunk {
    companion object {
        const val SIZE = 16
        const val HEIGHT = 48
    }

    val voxels = ByteArray(SIZE * HEIGHT * SIZE)
    var mesh: FloatBuffer? = null
    var vertexCount = 0
    var dirty = true

    fun get(x: Int, y: Int, z: Int): Int {
        if (x !in 0 until SIZE || y !in 0 until HEIGHT || z !in 0 until SIZE) return WorldRenderer.BLOCK_AIR
        return voxels[index(x, y, z)].toInt() and 0xFF
    }

    fun set(x: Int, y: Int, z: Int, type: Int) {
        if (x !in 0 until SIZE || y !in 0 until HEIGHT || z !in 0 until SIZE) return
        voxels[index(x, y, z)] = type.toByte()
        dirty = true
    }

    private fun index(x: Int, y: Int, z: Int): Int = (y * SIZE + z) * SIZE + x
}

private class MeshWriter(initialCapacity: Int = 4096) {
    private var data = FloatArray(initialCapacity)
    var floatCount = 0
        private set

    fun addVertex(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
        ensure(6)
        data[floatCount++] = x
        data[floatCount++] = y
        data[floatCount++] = z
        data[floatCount++] = r
        data[floatCount++] = g
        data[floatCount++] = b
    }

    private fun ensure(extra: Int) {
        if (floatCount + extra <= data.size) return
        data = data.copyOf(max(data.size * 2, floatCount + extra))
    }

    fun toBuffer(): FloatBuffer {
        val bytes = ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder())
        val result = bytes.asFloatBuffer()
        result.put(data, 0, floatCount)
        result.position(0)
        return result
    }
}

class WorldRenderer(
    context: Context,
    private val worldSeed: Long,
    private val listener: Listener
) : GLSurfaceView.Renderer {

    interface Listener {
        fun onInventoryChanged(counts: IntArray, selected: Int)
        fun onWorldReady()
    }

    companion object {
        const val BLOCK_COUNT = 17
        const val BLOCK_AIR = 0
        const val BLOCK_GRASS = 1
        const val BLOCK_DIRT = 2
        const val BLOCK_STONE = 3
        const val BLOCK_WOOD = 4
        const val BLOCK_LEAVES = 5
        const val BLOCK_COAL = 6
        const val BLOCK_COPPER = 7
        const val BLOCK_IRON = 8
        const val BLOCK_GOLD = 9
        const val BLOCK_REDSTONE = 10
        const val BLOCK_LAPIS = 11
        const val BLOCK_DIAMOND = 12
        const val BLOCK_EMERALD = 13
        const val BLOCK_SAND = 14
        const val BLOCK_WATER = 15
        const val BLOCK_BEDROCK = 16
        private const val SEA_LEVEL = 18
        private const val PLAYER_EYE_HEIGHT = 1.72f
        private const val PLAYER_RADIUS = 0.27f

        fun shortName(type: Int): String = when (type) {
            BLOCK_GRASS -> "ТРАВА"
            BLOCK_DIRT -> "ЗЕМЛЯ"
            BLOCK_STONE -> "КАМІНЬ"
            BLOCK_WOOD -> "ДЕРЕВО"
            BLOCK_LEAVES -> "ЛИСТЯ"
            BLOCK_COAL -> "ВУГІЛЛЯ"
            BLOCK_COPPER -> "МІДЬ"
            BLOCK_IRON -> "ЗАЛІЗО"
            BLOCK_GOLD -> "ЗОЛОТО"
            BLOCK_REDSTONE -> "РУДНИК"
            BLOCK_LAPIS -> "ЛАЗУРИТ"
            BLOCK_DIAMOND -> "ДІАМАНТ"
            BLOCK_EMERALD -> "СМАРАГД"
            BLOCK_SAND -> "ПІСОК"
            BLOCK_WATER -> "ВОДА"
            BLOCK_BEDROCK -> "ОСНОВА"
            else -> "ПОВІТРЯ"
        }

        fun isInventoryItem(type: Int): Boolean =
            type in BLOCK_GRASS..BLOCK_SAND && type != BLOCK_WATER
    }

    private val preferences = context.getSharedPreferences("blockworld_settings", Context.MODE_PRIVATE)
    private val chunks = HashMap<ChunkKey, VoxelChunk>()
    private val savedEdits = HashMap<BlockPos, Int>()
    private val saveLock = Any()
    private val inventoryLock = Any()
    private val inventory = IntArray(BLOCK_COUNT)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    private var shaderProgram = 0
    private var positionAttribute = 0
    private var colorAttribute = 0
    private var mvpUniform = 0
    private var playerX = 0.5f
    private var playerY = 28f
    private var playerZ = 0.5f
    private var yaw = 0f
    private var pitch = -0.18f
    private var verticalVelocity = 0f
    private var onGround = false
    private var lastFrameNanos = 0L
    private var initializedWorld = false
    private var jumpQueued = false
    private var selectedBlock = BLOCK_DIRT
    private var currentChunkX = Int.MIN_VALUE
    private var currentChunkZ = Int.MIN_VALUE

    @Volatile private var forwardInput = 0f
    @Volatile private var strafeInput = 0f
    @Volatile private var paused = false
    @Volatile private var renderDistance = 2
    @Volatile private var distanceChanged = false

    init {
        loadSavedEdits()
        loadInventory()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.54f, 0.78f, 0.98f, 1f)
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
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, safeWidth, safeHeight)
        Matrix.perspectiveM(projection, 0, 72f, safeWidth.toFloat() / safeHeight, 0.08f, 180f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!initializedWorld || distanceChanged) {
            ensureChunksAroundPlayer()
            if (!initializedWorld) {
                playerY = highestSolidAt(floor(playerX).toInt(), floor(playerZ).toInt()) + PLAYER_EYE_HEIGHT
                onGround = true
                initializedWorld = true
                listener.onInventoryChanged(inventorySnapshot(), selectedBlock)
                listener.onWorldReady()
            }
            distanceChanged = false
        }

        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f
        else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = now

        if (!paused && initializedWorld) updatePlayer(dt)
        val cx = Math.floorDiv(floor(playerX).toInt(), VoxelChunk.SIZE)
        val cz = floorDiv(floor(playerZ).toInt(), VoxelChunk.SIZE)
        if (cx != currentChunkX || cz != currentChunkZ) ensureChunksAroundPlayer()

        for ((key, chunk) in chunks) {
            if (chunk.dirty) rebuildChunkMesh(key, chunk)
        }

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

        if (shaderProgram == 0 || chunks.isEmpty()) return
        GLES20.glUseProgram(shaderProgram)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glEnableVertexAttribArray(colorAttribute)

        for (chunk in chunks.values) {
            val buffer = chunk.mesh ?: continue
            if (chunk.vertexCount <= 0) continue
            buffer.position(0)
            GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 24, buffer)
            buffer.position(3)
            GLES20.glVertexAttribPointer(colorAttribute, 3, GLES20.GL_FLOAT, false, 24, buffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, chunk.vertexCount)
        }

        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(colorAttribute)
    }

    fun rotate(deltaX: Float, deltaY: Float) {
        yaw += deltaX
        if (yaw > Math.PI) yaw = (yaw - Math.PI * 2.0).toFloat()
        if (yaw < -Math.PI) yaw = (yaw + Math.PI * 2.0).toFloat()
        pitch = (pitch - deltaY).coerceIn(-1.28f, 1.18f)
    }

    fun setMovement(strafe: Float, forward: Float) {
        strafeInput = strafe.coerceIn(-1f, 1f)
        forwardInput = forward.coerceIn(-1f, 1f)
    }

    fun jump() {
        jumpQueued = true
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            forwardInput = 0f
            strafeInput = 0f
        }
    }

    fun setRenderDistance(value: Int) {
        renderDistance = value.coerceIn(1, 3)
        distanceChanged = true
    }

    fun selectBlock(type: Int) {
        if (isInventoryItem(type)) {
            selectedBlock = type
            listener.onInventoryChanged(inventorySnapshot(), selectedBlock)
            saveGame()
        }
    }

    fun breakBlock() {
        if (paused) return
        val hit = raycast() ?: return
        val type = getBlock(hit.block.x, hit.block.y, hit.block.z)
        if (type == BLOCK_AIR || type == BLOCK_BEDROCK || type == BLOCK_WATER) return
        if (setBlock(hit.block.x, hit.block.y, hit.block.z, BLOCK_AIR)) {
            synchronized(inventoryLock) {
                inventory[type] = (inventory[type] + 1).coerceAtMost(999)
            }
            listener.onInventoryChanged(inventorySnapshot(), selectedBlock)
            saveGame()
        }
    }

    fun placeBlock() {
        if (paused) return
        val hit = raycast() ?: return
        val target = hit.previousAir ?: return
        if (target.y !in 1 until VoxelChunk.HEIGHT || getBlock(target.x, target.y, target.z) != BLOCK_AIR) return
        if (!isInventoryItem(selectedBlock)) return

        synchronized(inventoryLock) {
            if (inventory[selectedBlock] <= 0) return
            if (intersectsPlayer(target)) return
            if (setBlock(target.x, target.y, target.z, selectedBlock)) {
                inventory[selectedBlock]--
            } else {
                return
            }
        }
        listener.onInventoryChanged(inventorySnapshot(), selectedBlock)
        saveGame()
    }

    private fun intersectsPlayer(pos: BlockPos): Boolean {
        val bx = pos.x + 0.5f
        val by = pos.y + 0.5f
        val bz = pos.z + 0.5f
        return abs(bx - playerX) < PLAYER_RADIUS + 0.5f &&
            abs(bz - playerZ) < PLAYER_RADIUS + 0.5f &&
            by > playerY - 1.65f && by < playerY + 0.12f
    }

    private fun updatePlayer(dt: Float) {
        val inputLength = sqrt(strafeInput * strafeInput + forwardInput * forwardInput)
        val scale = if (inputLength > 1f) 1f / inputLength else 1f
        val forward = forwardInput * scale
        val strafe = strafeInput * scale
        val speed = 4.6f * dt

        val forwardX = sin(yaw)
        val forwardZ = -cos(yaw)
        val rightX = cos(yaw)
        val rightZ = sin(yaw)
        val dx = (forwardX * forward + rightX * strafe) * speed
        val dz = (forwardZ * forward + rightZ * strafe) * speed

        val nextX = (playerX + dx).coerceIn(-1_000_000f, 1_000_000f)
        val nextZ = (playerZ + dz).coerceIn(-1_000_000f, 1_000_000f)
        if (canOccupy(nextX, playerY, playerZ)) playerX = nextX
        if (canOccupy(playerX, playerY, nextZ)) playerZ = nextZ

        if (jumpQueued && onGround) {
            verticalVelocity = 6.0f
            onGround = false
        }
        jumpQueued = false
        verticalVelocity -= 17.5f * dt
        val proposedY = playerY + verticalVelocity * dt

        if (verticalVelocity <= 0f && hasFloorAt(playerX, proposedY, playerZ)) {
            playerY = floor(proposedY - 1.72f).toInt() + 1f + PLAYER_EYE_HEIGHT
            verticalVelocity = 0f
            onGround = true
        } else if (verticalVelocity > 0f && hasCeilingAt(playerX, proposedY, playerZ)) {
            verticalVelocity = 0f
            onGround = false
        } else {
            playerY = proposedY.coerceIn(3f, VoxelChunk.HEIGHT + 30f)
            onGround = false
        }
    }

    private fun canOccupy(x: Float, eyeY: Float, z: Float): Boolean {
        val feet = floor(eyeY - 1.62f).toInt()
        val head = floor(eyeY - 0.12f).toInt()
        val minX = floor(x - PLAYER_RADIUS).toInt()
        val maxX = floor(x + PLAYER_RADIUS).toInt()
        val minZ = floor(z - PLAYER_RADIUS).toInt()
        val maxZ = floor(z + PLAYER_RADIUS).toInt()
        for (bx in minX..maxX) for (bz in minZ..maxZ) {
            for (by in feet..head) {
                if (isSolid(getBlock(bx, by, bz))) return false
            }
        }
        return true
    }

    private fun hasFloorAt(x: Float, eyeY: Float, z: Float): Boolean {
        val feetY = floor(eyeY - 1.72f).toInt()
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        return isSolid(getBlock(bx, feetY, bz))
    }

    private fun hasCeilingAt(x: Float, eyeY: Float, z: Float): Boolean {
        val headY = floor(eyeY + 0.08f).toInt()
        return isSolid(getBlock(floor(x).toInt(), headY, floor(z).toInt()))
    }

    private fun isSolid(type: Int): Boolean = type != BLOCK_AIR && type != BLOCK_WATER

    private fun raycast(): RayHit? {
        val cosPitch = cos(pitch)
        val dx = sin(yaw) * cosPitch
        val dy = sin(pitch)
        val dz = -cos(yaw) * cosPitch
        var previous: BlockPos? = null
        var distance = 0.12f
        var last = BlockPos(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        while (distance <= 6.2f) {
            val cell = BlockPos(
                floor(playerX + dx * distance).toInt(),
                floor(playerY + dy * distance).toInt(),
                floor(playerZ + dz * distance).toInt()
            )
            if (cell != last) {
                if (getBlock(cell.x, cell.y, cell.z) != BLOCK_AIR &&
                    getBlock(cell.x, cell.y, cell.z) != BLOCK_WATER) {
                    return RayHit(cell, previous)
                }
                previous = cell
                last = cell
            }
            distance += 0.035f
        }
        return null
    }

    private fun ensureChunksAroundPlayer() {
        val centerX = floorDiv(floor(playerX).toInt(), VoxelChunk.SIZE)
        val centerZ = floorDiv(floor(playerZ).toInt(), VoxelChunk.SIZE)
        currentChunkX = centerX
        currentChunkZ = centerZ
        val radius = renderDistance.coerceIn(1, 3)
        var added = false

        for (cx in centerX - radius..centerX + radius) {
            for (cz in centerZ - radius..centerZ + radius) {
                val key = ChunkKey(cx, cz)
                if (!chunks.containsKey(key)) {
                    chunks[key] = generateChunk(key)
                    added = true
                }
            }
        }

        val maxKeep = radius + 1
        val iterator = chunks.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (abs(key.x - centerX) > maxKeep || abs(key.z - centerZ) > maxKeep) {
                iterator.remove()
            }
        }

        if (added) chunks.values.forEach { it.dirty = true }
    }

    private fun generateChunk(key: ChunkKey): VoxelChunk {
        val chunk = VoxelChunk()
        val originX = key.x * VoxelChunk.SIZE
        val originZ = key.z * VoxelChunk.SIZE

        for (localX in 0 until VoxelChunk.SIZE) {
            val worldX = originX + localX
            for (localZ in 0 until VoxelChunk.SIZE) {
                val worldZ = originZ + localZ
                val height = terrainHeight(worldX, worldZ)
                val biome = biomeAt(worldX, worldZ)
                for (y in 0 until VoxelChunk.HEIGHT) {
                    val type = when {
                        y == 0 -> BLOCK_BEDROCK
                        y > height && y <= SEA_LEVEL -> BLOCK_WATER
                        y > height -> BLOCK_AIR
                        y == height -> if (biome == BIOME_DESERT) BLOCK_SAND else BLOCK_GRASS
                        y >= height - 3 -> if (biome == BIOME_DESERT) BLOCK_SAND else BLOCK_DIRT
                        y > 2 && isCave(worldX, y, worldZ) -> BLOCK_AIR
                        else -> oreAt(worldX, y, worldZ) ?: BLOCK_STONE
                    }
                    chunk.set(localX, y, localZ, type)
                }
            }
        }

        // Trees are placed deterministically and kept inside the owning chunk.
        for (localX in 2 until VoxelChunk.SIZE - 2) {
            val worldX = originX + localX
            for (localZ in 2 until VoxelChunk.SIZE - 2) {
                val worldZ = originZ + localZ
                if (biomeAt(worldX, worldZ) != BIOME_FOREST) continue
                if (random01(worldX, worldZ, worldSeed + 771L) < 0.9975f) continue
                val baseY = terrainHeight(worldX, worldZ) + 1
                if (baseY + 5 >= VoxelChunk.HEIGHT) continue
                for (dy in 0..3) chunk.set(localX, baseY + dy, localZ, BLOCK_WOOD)
                for (lx in -2..2) for (lz in -2..2) for (ly in 2..5) {
                    if (abs(lx) + abs(lz) + abs(ly - 3) <= 4) {
                        val tx = localX + lx
                        val tz = localZ + lz
                        if (chunk.get(tx, baseY + ly - 1, tz) == BLOCK_AIR) {
                            chunk.set(tx, baseY + ly - 1, tz, BLOCK_LEAVES)
                        }
                    }
                }
            }
        }

        synchronized(saveLock) {
            for ((pos, type) in savedEdits) {
                if (floorDiv(pos.x, VoxelChunk.SIZE) == key.x &&
                    floorDiv(pos.z, VoxelChunk.SIZE) == key.z &&
                    pos.y in 0 until VoxelChunk.HEIGHT) {
                    chunk.set(Math.floorMod(pos.x, VoxelChunk.SIZE), pos.y, floorMod(pos.z, VoxelChunk.SIZE), type)
                }
            }
        }
        return chunk
    }

    private fun getBlock(worldX: Int, y: Int, worldZ: Int): Int {
        if (y !in 0 until VoxelChunk.HEIGHT) return BLOCK_AIR
        val key = ChunkKey(floorDiv(worldX, VoxelChunk.SIZE), floorDiv(worldZ, VoxelChunk.SIZE))
        val chunk = chunks[key] ?: return BLOCK_AIR
        return chunk.get(floorMod(worldX, VoxelChunk.SIZE), y, floorMod(worldZ, VoxelChunk.SIZE))
    }

    private fun setBlock(worldX: Int, y: Int, worldZ: Int, type: Int): Boolean {
        if (y !in 0 until VoxelChunk.HEIGHT) return false
        val key = ChunkKey(floorDiv(worldX, VoxelChunk.SIZE), floorDiv(worldZ, VoxelChunk.SIZE))
        val chunk = chunks[key] ?: return false
        val localX = floorMod(worldX, VoxelChunk.SIZE)
        val localZ = floorMod(worldZ, VoxelChunk.SIZE)
        if (chunk.get(localX, y, localZ) == type) return false
        chunk.set(localX, y, localZ, type)
        synchronized(saveLock) { savedEdits[BlockPos(worldX, y, worldZ)] = type }
        if (localX == 0) chunks[ChunkKey(key.x - 1, key.z)]?.dirty = true
        if (localX == VoxelChunk.SIZE - 1) chunks[ChunkKey(key.x + 1, key.z)]?.dirty = true
        if (localZ == 0) chunks[ChunkKey(key.x, key.z - 1)]?.dirty = true
        if (localZ == VoxelChunk.SIZE - 1) chunks[ChunkKey(key.x, key.z + 1)]?.dirty = true
        return true
    }

    private fun rebuildChunkMesh(key: ChunkKey, chunk: VoxelChunk) {
        val writer = MeshWriter()
        val corners = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f),
            floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 1f),
            floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 1f, 1f)
        )
        val faceIndices = arrayOf(
            intArrayOf(0, 2, 1, 0, 3, 2), // -Z
            intArrayOf(4, 5, 6, 4, 6, 7), // +Z
            intArrayOf(0, 4, 7, 0, 7, 3), // -X
            intArrayOf(1, 2, 6, 1, 6, 5), // +X
            intArrayOf(3, 7, 6, 3, 6, 2), // +Y
            intArrayOf(0, 1, 5, 0, 5, 4)  // -Y
        )
        val offsets = arrayOf(
            intArrayOf(0, 0, -1), intArrayOf(0, 0, 1),
            intArrayOf(-1, 0, 0), intArrayOf(1, 0, 0),
            intArrayOf(0, 1, 0), intArrayOf(0, -1, 0)
        )
        val shade = floatArrayOf(0.78f, 0.90f, 0.82f, 0.86f, 1.0f, 0.56f)
        // Mesh coordinates are absolute world coordinates.
        val baseX = key.x * VoxelChunk.SIZE
        val baseZ = key.z * VoxelChunk.SIZE

        for (x in 0 until VoxelChunk.SIZE) {
            for (z in 0 until VoxelChunk.SIZE) {
                for (y in 0 until VoxelChunk.HEIGHT) {
                    val type = chunk.get(x, y, z)
                    if (type == BLOCK_AIR) continue
                    val color = blockColor(type)
                    for (face in faceIndices.indices) {
                        val off = offsets[face]
                        val neighbourType = getBlock(baseX + x + off[0], y + off[1], baseZ + z + off[2])
                        if (!isFaceVisible(type, neighbourType)) continue
                        val brightness = shade[face]
                        for (cornerIndex in faceIndices[face]) {
                            val c = corners[cornerIndex]
                            writer.addVertex(
                                baseX + x + c[0],
                                y + c[1],
                                baseZ + z + c[2],
                                min(1f, color[0] * brightness),
                                min(1f, color[1] * brightness),
                                min(1f, color[2] * brightness)
                            )
                        }
                    }
                }
            }
        }
        chunk.mesh = writer.toBuffer()
        chunk.vertexCount = writer.floatCount / 6
        chunk.dirty = false
    }

    private fun isFaceVisible(block: Int, neighbour: Int): Boolean {
        if (neighbour == BLOCK_AIR) return true
        if (block == BLOCK_WATER && neighbour != BLOCK_WATER) return true
        return neighbour == BLOCK_WATER && block != BLOCK_WATER
    }

    private fun highestSolidAt(x: Int, z: Int): Float {
        for (y in VoxelChunk.HEIGHT - 1 downTo 0) {
            if (isSolid(getBlock(x, y, z))) return y + 1f
        }
        return SEA_LEVEL + 1f
    }

    private fun terrainHeight(x: Int, z: Int): Int {
        val broad = valueNoise2(x * 0.021f, z * 0.021f, worldSeed + 11L)
        val detail = valueNoise2(x * 0.067f, z * 0.067f, worldSeed + 29L)
        val ridge = 1f - abs(valueNoise2(x * 0.010f, z * 0.010f, worldSeed + 61L))
        return (20f + broad * 8f + detail * 3.2f + ridge * 2.5f).roundToInt().coerceIn(9, 34)
    }

    private fun biomeAt(x: Int, z: Int): Int {
        val noise = valueNoise2(x * 0.006f, z * 0.006f, worldSeed + 501L)
        return when {
            noise < -0.24f -> BIOME_DESERT
            noise > 0.25f -> BIOME_FOREST
            else -> BIOME_PLAINS
        }
    }

    private fun isCave(x: Int, y: Int, z: Int): Boolean {
        val phase = (worldSeed and 1023L).toFloat() * 0.013f
        val a = sin(x * 0.19f + y * 0.13f + phase)
        val b = cos(z * 0.17f - y * 0.15f + phase * 1.7f)
        val c = sin((x + z) * 0.10f + y * 0.21f - phase)
        return a + b + c > 2.34f && y < 27
    }

    private fun oreAt(x: Int, y: Int, z: Int): Int? {
        fun vein(type: Int, minY: Int, maxY: Int, chance: Float): Boolean {
            if (y !in minY..maxY) return false
            val cellX = floorDiv(x, 4)
            val cellY = floorDiv(y, 3)
            val cellZ = floorDiv(z, 4)
            val seedSalt = worldSeed + type * 9_731L
            val cluster = random01(cellX + cellY * 17, cellZ - cellY * 11, seedSalt)
            val shape = random01(x + y * 31, z - y * 19, seedSalt + 1L)
            return cluster > 1f - chance && shape > 0.24f
        }

        return when {
            vein(BLOCK_COAL, 5, 39, 0.065f) -> BLOCK_COAL
            vein(BLOCK_COPPER, 5, 31, 0.035f) -> BLOCK_COPPER
            vein(BLOCK_IRON, 4, 29, 0.045f) -> BLOCK_IRON
            vein(BLOCK_GOLD, 4, 19, 0.014f) -> BLOCK_GOLD
            vein(BLOCK_REDSTONE, 3, 13, 0.018f) -> BLOCK_REDSTONE
            vein(BLOCK_LAPIS, 5, 25, 0.014f) -> BLOCK_LAPIS
            vein(BLOCK_DIAMOND, 3, 10, 0.006f) -> BLOCK_DIAMOND
            vein(BLOCK_EMERALD, 8, 24, 0.004f) -> BLOCK_EMERALD
            else -> null
        }
    }

    private fun valueNoise2(x: Float, z: Float, salt: Long): Float {
        val ix = floor(x).toInt()
        val iz = floor(z).toInt()
        val fx = smooth(x - ix)
        val fz = smooth(z - iz)
        val a = randomSigned(ix, iz, salt)
        val b = randomSigned(ix + 1, iz, salt)
        val c = randomSigned(ix, iz + 1, salt)
        val d = randomSigned(ix + 1, iz + 1, salt)
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fz
    }

    private fun smooth(value: Float): Float = value * value * (3f - 2f * value)

    private fun randomSigned(x: Int, z: Int, salt: Long): Float = random01(x, z, salt) * 2f - 1f

    private fun random01(x: Int, z: Int, salt: Long): Float {
        var value = salt xor (x.toLong() * -7046029254386353131L) xor (z.toLong() * -4658895280553007687L)
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        value = value xor (value ushr 31)
        return ((value ushr 40) and 0xFFFFFFL).toFloat() / 16_777_215f
    }

    private fun blockColor(type: Int): FloatArray = when (type) {
        BLOCK_GRASS -> floatArrayOf(0.30f, 0.68f, 0.23f)
        BLOCK_DIRT -> floatArrayOf(0.48f, 0.30f, 0.17f)
        BLOCK_STONE -> floatArrayOf(0.49f, 0.51f, 0.55f)
        BLOCK_WOOD -> floatArrayOf(0.43f, 0.27f, 0.14f)
        BLOCK_LEAVES -> floatArrayOf(0.16f, 0.49f, 0.21f)
        BLOCK_COAL -> floatArrayOf(0.22f, 0.23f, 0.25f)
        BLOCK_COPPER -> floatArrayOf(0.70f, 0.37f, 0.22f)
        BLOCK_IRON -> floatArrayOf(0.72f, 0.60f, 0.51f)
        BLOCK_GOLD -> floatArrayOf(0.92f, 0.72f, 0.19f)
        BLOCK_REDSTONE -> floatArrayOf(0.73f, 0.16f, 0.15f)
        BLOCK_LAPIS -> floatArrayOf(0.19f, 0.36f, 0.79f)
        BLOCK_DIAMOND -> floatArrayOf(0.19f, 0.82f, 0.85f)
        BLOCK_EMERALD -> floatArrayOf(0.13f, 0.72f, 0.42f)
        BLOCK_SAND -> floatArrayOf(0.80f, 0.72f, 0.48f)
        BLOCK_WATER -> floatArrayOf(0.23f, 0.48f, 0.78f)
        BLOCK_BEDROCK -> floatArrayOf(0.18f, 0.19f, 0.21f)
        else -> floatArrayOf(0.70f, 0.70f, 0.70f)
    }

    private fun loadSavedEdits() {
        val data = preferences.getString("world_changes", "") ?: ""
        data.split(';').forEach { entry ->
            val fields = entry.split(',')
            if (fields.size != 4) return@forEach
            runCatching {
                val pos = BlockPos(fields[0].toInt(), fields[1].toInt(), fields[2].toInt())
                val type = fields[3].toInt()
                if (pos.y in 0 until VoxelChunk.HEIGHT && type in 0 until BLOCK_COUNT) {
                    savedEdits[pos] = type
                }
            }
        }
    }

    private fun loadInventory() {
        val data = preferences.getString("world_inventory", null)
        if (data != null) {
            val values = data.split(',').mapNotNull { it.toIntOrNull() }
            for (i in 0 until min(values.size, BLOCK_COUNT)) inventory[i] = values[i].coerceIn(0, 999)
        } else {
            inventory[BLOCK_DIRT] = 64
            inventory[BLOCK_STONE] = 32
            inventory[BLOCK_WOOD] = 16
            inventory[BLOCK_LEAVES] = 16
            inventory[BLOCK_SAND] = 16
        }
        selectedBlock = preferences.getInt("world_selected_block", BLOCK_DIRT)
            .takeIf { isInventoryItem(it) } ?: BLOCK_DIRT
    }

    private fun inventorySnapshot(): IntArray = synchronized(inventoryLock) { inventory.copyOf() }

    fun saveGame() {
        val editText = synchronized(saveLock) {
            savedEdits.entries.joinToString(";") { item ->
                item.key.x.toString() + "," + item.key.y + "," + item.key.z + "," + item.value
            }
        }
        val inventoryText = synchronized(inventoryLock) { inventory.joinToString(",") }
        preferences.edit()
            .putLong("world_seed", worldSeed)
            .putString("world_changes", editText)
            .putString("world_inventory", inventoryText)
            .putInt("world_selected_block", selectedBlock)
            .apply()
    }


    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "Cannot create OpenGL shader" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Shader compilation failed: $log")
            }
            return shader
        }

        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = try {
            compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
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
            throw IllegalStateException("Shader linking failed: $log")
        }
        return program
    }

}
