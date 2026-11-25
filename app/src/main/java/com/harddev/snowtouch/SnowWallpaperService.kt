package com.harddev.snowtouch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class SnowWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = SnowEngine()

    inner class SnowEngine : Engine(), SensorEventListener {
        private val handler = Handler(Looper.getMainLooper())
        private val random = Random(System.nanoTime())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val particles = ArrayList<SnowParticle>(MAX_PARTICLES)
        private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
        private var accelerometer: Sensor? = null
        private var background: Bitmap? = null

        private var screenW = 1
        private var screenH = 1
        private var visible = false
        private var running = false
        private var lastFrame = 0L
        private var lastShakeAt = 0L
        private var gravityX = 0f
        private var gravityY = 0f
        private var gravityZ = 9.8f

        // Two-dimensional settled-snow mass grid.
        private var cell = 8f
        private var cols = 1
        private var rows = 1
        private var snow = FloatArray(1)
        private var nextSnow = FloatArray(1)
        private var physicsCounter = 0

        private var touchActive = false
        private var touchX = 0f
        private var touchY = 0f
        private var previousTouchX = 0f
        private var previousTouchY = 0f
        private var touchVX = 0f
        private var touchVY = 0f
        private var lastTouchAt = 0L

        private val frame = object : Runnable {
            override fun run() {
                if (!visible || !running) return
                drawFrame()
                handler.postDelayed(this, FRAME_DELAY_MS)
            }
        }

        init {
            setTouchEventsEnabled(true)
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            background = BitmapFactory.decodeResource(resources, R.drawable.winter_scene)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            screenW = width.coerceAtLeast(1)
            screenH = height.coerceAtLeast(1)
            createSnowGrid()
            createParticles()
            drawFrame()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (visible) {
                startRendering()
                accelerometer?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
            } else {
                stopRendering()
                sensorManager.unregisterListener(this)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopRendering()
            sensorManager.unregisterListener(this)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopRendering()
            sensorManager.unregisterListener(this)
            background?.recycle()
            background = null
            super.onDestroy()
        }

        private fun startRendering() {
            if (running) return
            running = true
            lastFrame = System.nanoTime()
            handler.removeCallbacks(frame)
            handler.post(frame)
        }

        private fun stopRendering() {
            running = false
            handler.removeCallbacks(frame)
        }

        private fun createSnowGrid() {
            cell = 8f
            cols = (screenW / cell).toInt().coerceAtLeast(1)
            rows = (screenH / cell).toInt().coerceAtLeast(1)
            snow = FloatArray(cols * rows)
            nextSnow = FloatArray(cols * rows)

            // A low, softly varied initial blanket along the bottom.
            for (x in 0 until cols) {
                val wave = 0.55f +
                    0.18f * kotlin.math.sin(x * 0.075f).toFloat() +
                    0.10f * kotlin.math.sin(x * 0.19f + 1.2f).toFloat()
                val depth = (rows * (0.035f + wave * 0.045f)).toInt()
                    .coerceIn(1, (rows * 0.13f).toInt().coerceAtLeast(1))
                for (offset in 0 until depth) {
                    val y = rows - 1 - offset
                    snow[index(x, y)] = (0.72f + random.nextFloat() * 0.25f)
                }
            }
            repeat(5) { simulateSnow() }
        }

        private fun index(x: Int, y: Int) = y * cols + x

        private fun createParticles() {
            particles.clear()
            repeat(PARTICLE_COUNT) { particles.add(newParticle(true)) }
        }

        private fun newParticle(randomY: Boolean): SnowParticle {
            val depth = random.nextFloat()
            return SnowParticle(
                x = random.nextFloat() * screenW,
                y = if (randomY) random.nextFloat() * screenH * 0.78f else -random.nextFloat() * 45f,
                vx = (random.nextFloat() - 0.5f) * 14f,
                vy = 20f + depth * 42f,
                size = 0.8f + depth * 2.0f,
                depth = depth,
                phase = random.nextFloat() * 6.28f,
                loose = false
            )
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            gravityX = gravityX * 0.82f + x * 0.18f
            gravityY = gravityY * 0.82f + y * 0.18f
            gravityZ = gravityZ * 0.82f + z * 0.18f
            val lx = x - gravityX
            val ly = y - gravityY
            val lz = z - gravityZ
            val magnitude = sqrt(lx * lx + ly * ly + lz * lz)
            val now = System.currentTimeMillis()
            if (magnitude > SHAKE_THRESHOLD && now - lastShakeAt > SHAKE_COOLDOWN_MS) {
                lastShakeAt = now
                scatterSnow(lx, ly)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun scatterSnow(ax: Float, ay: Float) {
            val impulseX = (ax * 24f).coerceIn(-280f, 280f)
            val impulseY = (-abs(ay) * 20f - 105f).coerceIn(-380f, -130f)
            var emitted = 0
            for (y in rows - 1 downTo 0 step 2) {
                for (x in 0 until cols step 2) {
                    if (emitted >= SHAKE_PARTICLE_LIMIT) break
                    val i = index(x, y)
                    val mass = snow[i]
                    if (mass < 0.55f) continue
                    val take = min(mass * 0.38f, 0.75f)
                    snow[i] -= take
                    val count = (1 + (take * 4f).toInt()).coerceIn(1, 3)
                    repeat(count) {
                        if (particles.size >= MAX_PARTICLES || emitted >= SHAKE_PARTICLE_LIMIT) return@repeat
                        particles.add(
                            SnowParticle(
                                x = (x * cell + cell / 2f).coerceIn(0f, screenW.toFloat()),
                                y = (y * cell + cell / 2f).coerceIn(0f, screenH.toFloat()),
                                vx = impulseX * 0.35f + (random.nextFloat() - 0.5f) * 120f,
                                vy = impulseY * (0.45f + random.nextFloat() * 0.65f),
                                size = 1.3f + random.nextFloat() * 2.8f,
                                depth = random.nextFloat(),
                                phase = random.nextFloat() * 6.28f,
                                loose = true
                            )
                        )
                        emitted++
                    }
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            val now = System.currentTimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchActive = true
                    touchX = event.x
                    touchY = event.y
                    previousTouchX = event.x
                    previousTouchY = event.y
                    touchVX = 0f
                    touchVY = 0f
                    lastTouchAt = now
                }
                MotionEvent.ACTION_MOVE -> if (touchActive) {
                    val elapsed = ((now - lastTouchAt).coerceAtLeast(1L)) / 1000f
                    val dx = event.x - previousTouchX
                    val dy = event.y - previousTouchY
                    touchVX = (dx / elapsed).coerceIn(-2200f, 2200f)
                    touchVY = (dy / elapsed).coerceIn(-2200f, 2200f)
                    touchX = event.x
                    touchY = event.y
                    moveSnowWithFinger(previousTouchX, previousTouchY, dx, dy)
                    previousTouchX = event.x
                    previousTouchY = event.y
                    lastTouchAt = now
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchX = event.x
                    touchY = event.y
                    touchActive = false
                }
            }
            super.onTouchEvent(event)
        }

        private fun moveSnowWithFinger(oldX: Float, oldY: Float, dx: Float, dy: Float) {
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < 0.5f) return
            val radius = max(22f, screenW * 0.075f)
            val steps = (distance / (cell * 0.65f)).toInt().coerceIn(1, 18)
            val dirX = dx / distance
            val dirY = dy / distance
            val shiftX = (dirX * cell * 1.35f / cell).toInt()
            val shiftY = (dirY * cell * 1.35f / cell).toInt()

            for (step in 0..steps) {
                val t = step.toFloat() / steps
                val cx = oldX + dx * t
                val cy = oldY + dy * t
                val gx = (cx / cell).toInt()
                val gy = (cy / cell).toInt()
                val r = (radius / cell).toInt().coerceAtLeast(2)

                // Process from the destination side backwards to avoid moving the same
                // cell repeatedly during a single brush pass.
                val yRange = if (shiftY >= 0) (gy + r downTo gy - r) else (gy - r..gy + r)
                for (yy in yRange) {
                    for (xx in gx - r..gx + r) {
                        if (xx !in 0 until cols || yy !in 0 until rows) continue
                        val px = xx * cell + cell * 0.5f - cx
                        val py = yy * cell + cell * 0.5f - cy
                        val d2 = px * px + py * py
                        if (d2 > radius * radius) continue
                        val from = index(xx, yy)
                        val amount = min(snow[from], 0.52f * (1f - sqrt(d2) / radius))
                        if (amount < 0.035f) continue
                        val tx = (xx + shiftX).coerceIn(0, cols - 1)
                        val ty = (yy + shiftY).coerceIn(0, rows - 1)
                        val to = index(tx, ty)
                        if (to == from) continue
                        val accepted = min(amount, 1.35f - snow[to])
                        if (accepted <= 0f) continue
                        snow[from] -= accepted
                        snow[to] += accepted
                    }
                }

                // Fast upward flicks release a little loose powder.
                if (dy < -screenH * 0.006f && particles.size < MAX_PARTICLES) {
                    val count = (abs(dy) / 9f).toInt().coerceIn(1, 5)
                    repeat(count) {
                        if (particles.size >= MAX_PARTICLES) return@repeat
                        particles.add(
                            SnowParticle(
                                x = cx + (random.nextFloat() - 0.5f) * radius,
                                y = cy,
                                vx = dx * 1.8f + (random.nextFloat() - 0.5f) * 130f,
                                vy = -abs(dy) * (0.8f + random.nextFloat()) - 45f,
                                size = 1.2f + random.nextFloat() * 2.5f,
                                depth = random.nextFloat(),
                                phase = random.nextFloat() * 6.28f,
                                loose = true
                            )
                        )
                    }
                }
            }
        }

        private fun simulateSnow() {
            // Gravity: each cell transfers mass down, or diagonally if blocked.
            // The small relaxation step creates rounded heaps instead of sharp spikes.
            nextSnow.fill(0f)
            for (y in rows - 1 downTo 0) {
                for (x in 0 until cols) {
                    val amount = snow[index(x, y)]
                    if (amount <= 0.015f) continue
                    var remaining = amount

                    if (y < rows - 1) {
                        val below = index(x, y + 1)
                        val capacity = 1.15f - nextSnow[below]
                        if (capacity > 0.02f) {
                            val moved = min(remaining * 0.78f, capacity)
                            nextSnow[below] += moved
                            remaining -= moved
                        }

                        if (remaining > 0.03f) {
                            val firstLeft = random.nextBoolean()
                            val dirs = if (firstLeft) intArrayOf(-1, 1) else intArrayOf(1, -1)
                            for (dir in dirs) {
                                val nx = x + dir
                                if (nx !in 0 until cols || remaining <= 0.03f) continue
                                val diagonal = index(nx, y + 1)
                                val cap = 0.98f - nextSnow[diagonal]
                                if (cap <= 0.02f) continue
                                val moved = min(remaining * 0.48f, cap)
                                nextSnow[diagonal] += moved
                                remaining -= moved
                            }
                        }
                    }

                    nextSnow[index(x, y)] += remaining
                }
            }

            // Lateral relaxation: gently spread tall local peaks into nearby empty space.
            for (y in rows - 1 downTo 0) {
                for (x in 0 until cols - 1) {
                    val a = index(x, y)
                    val b = index(x + 1, y)
                    val diff = nextSnow[a] - nextSnow[b]
                    if (abs(diff) > 0.48f) {
                        val move = min(abs(diff) * 0.12f, 0.16f)
                        if (diff > 0f) {
                            nextSnow[a] -= move
                            nextSnow[b] += move
                        } else {
                            nextSnow[b] -= move
                            nextSnow[a] += move
                        }
                    }
                }
            }
            val old = snow
            snow = nextSnow
            nextSnow = old
        }

        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    val now = System.nanoTime()
                    val dt = if (lastFrame == 0L) 0.016f else
                        ((now - lastFrame) / 1_000_000_000f).coerceIn(0.001f, 0.04f)
                    lastFrame = now
                    render(canvas, dt)
                }
            } catch (_: Exception) {
                // Android may recreate the wallpaper surface during transitions.
            } finally {
                if (canvas != null) {
                    try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Exception) { }
                }
            }
        }

        private fun render(canvas: Canvas, dt: Float) {
            drawBackground(canvas)
            physicsCounter++
            if (physicsCounter % 2 == 0) simulateSnow()

            val wind = kotlin.math.sin(System.currentTimeMillis() / 2100.0).toFloat() * 8f
            val iterator = particles.iterator()
            while (iterator.hasNext()) {
                val p = iterator.next()
                p.phase += dt
                p.vx += wind * dt * 0.07f + kotlin.math.sin(p.phase * 1.7f) * dt * 2f
                p.vx *= if (p.loose) 0.992f else 0.996f
                p.vy = (p.vy + (if (p.loose) 155f else 9f) * dt)
                    .coerceAtMost(if (p.loose) 520f else 95f)
                p.x += p.vx * dt
                p.y += p.vy * dt

                if (touchActive) {
                    val dx = p.x - touchX
                    val dy = p.y - touchY
                    val radius = screenW * 0.17f
                    val d2 = dx * dx + dy * dy
                    if (d2 < radius * radius && d2 > 1f) {
                        val d = sqrt(d2)
                        val force = 1f - d / radius
                        p.vx += dx / d * 95f * force * dt * 7f + touchVX * force * 0.018f
                        p.vy += dy / d * 75f * force * dt * 7f + touchVY * force * 0.012f
                        p.loose = true
                    }
                }

                if (p.y >= screenH - cell || p.x < -20f ||
                    p.x > screenW + 20f || p.y > screenH + 20f) {
                    if (p.y >= screenH - cell && p.loose) addSnow(p.x, 0.18f)
                    if (p.loose) iterator.remove() else resetParticle(p)
                }
            }

            resolveFlakeCollisions()
            for (p in particles) drawFlake(canvas, p)
            drawSnowGrid(canvas)
        }

        private fun drawBackground(canvas: Canvas) {
            val bitmap = background
            if (bitmap == null || bitmap.isRecycled) {
                canvas.drawColor(Color.rgb(5, 12, 27))
                return
            }
            val scale = max(screenW.toFloat() / bitmap.width, screenH.toFloat() / bitmap.height)
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val left = (screenW - width) * 0.5f
            val top = (screenH - height) * 0.5f
            paint.style = Paint.Style.FILL
            canvas.drawBitmap(
                bitmap, null,
                Rect(left.toInt(), top.toInt(), (left + width).toInt(), (top + height).toInt()),
                paint
            )
            paint.color = Color.argb(28, 2, 8, 20)
            canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), paint)
        }

        private fun resolveFlakeCollisions() {
            for (i in 0 until particles.size) {
                val a = particles[i]
                for (j in i + 1 until particles.size) {
                    val b = particles[j]
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val minDistance = (a.size + b.size) * 1.35f
                    val d2 = dx * dx + dy * dy
                    if (d2 <= 0.01f || d2 >= minDistance * minDistance) continue
                    val d = sqrt(d2)
                    val nx = dx / d
                    val ny = dy / d
                    val push = (minDistance - d) * 0.28f
                    a.x -= nx * push
                    a.y -= ny * push
                    b.x += nx * push
                    b.y += ny * push
                }
            }
        }

        private fun addSnow(x: Float, amount: Float) {
            val gx = (x / cell).toInt().coerceIn(0, cols - 1)
            val gy = rows - 1
            for (offset in -1..1) {
                val xx = (gx + offset).coerceIn(0, cols - 1)
                val i = index(xx, gy)
                snow[i] = min(1.35f, snow[i] + amount * (1f - abs(offset) * 0.28f))
            }
        }

        private fun drawSnowGrid(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val mass = snow[index(x, y)]
                    if (mass < 0.035f) continue
                    val cx = x * cell + cell * 0.5f
                    val cy = y * cell + cell * 0.5f
                    val radius = cell * (0.43f + min(mass, 1.2f) * 0.12f)
                    val alpha = (205 + min(mass, 1f) * 45f).toInt().coerceIn(205, 250)
                    paint.color = Color.argb(alpha, 220, 235, 248)
                    canvas.drawCircle(cx, cy, radius, paint)
                    if (mass > 0.72f) {
                        paint.color = Color.argb(38, 255, 255, 255)
                        canvas.drawCircle(cx - cell * 0.12f, cy - cell * 0.15f, radius * 0.58f, paint)
                    }
                }
            }
        }

        private fun resetParticle(p: SnowParticle) {
            p.x = random.nextFloat() * screenW
            p.y = -random.nextFloat() * screenH * 0.25f
            p.vx = (random.nextFloat() - 0.5f) * 14f
            p.vy = 20f + random.nextFloat() * 42f
            p.size = 0.8f + random.nextFloat() * 2f
            p.depth = random.nextFloat()
            p.phase = random.nextFloat() * 6.28f
            p.loose = false
        }

        private fun drawFlake(canvas: Canvas, p: SnowParticle) {
            val alpha = (100 + p.depth * 145).toInt().coerceIn(0, 245)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, 235, 245, 255)
            canvas.drawCircle(p.x, p.y, p.size, paint)
            if (p.size > 2.3f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.7f + p.depth * 0.5f
                paint.color = Color.argb(alpha / 2, 255, 255, 255)
                canvas.drawLine(p.x - p.size * 1.7f, p.y, p.x + p.size * 1.7f, p.y, paint)
                canvas.drawLine(p.x, p.y - p.size * 1.7f, p.x, p.y + p.size * 1.7f, paint)
                paint.style = Paint.Style.FILL
            }
        }

        private inner class SnowParticle(
            var x: Float, var y: Float, var vx: Float, var vy: Float,
            var size: Float, var depth: Float, var phase: Float, var loose: Boolean
        )
    }

    companion object {
        private const val PARTICLE_COUNT = 220
        private const val MAX_PARTICLES = 420
        private const val SHAKE_PARTICLE_LIMIT = 130
        private const val SHAKE_THRESHOLD = 13.5f
        private const val SHAKE_COOLDOWN_MS = 850L
        private const val FRAME_DELAY_MS = 33L
    }
}
