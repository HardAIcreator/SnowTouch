package com.harddev.snowtouch

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SnowWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = SnowEngine()

    inner class SnowEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val random = Random(System.nanoTime())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val particles = ArrayList<SnowParticle>(MAX_PARTICLES)

        private var screenW = 1
        private var screenH = 1
        private var visible = false
        private var running = false
        private var lastFrame = 0L

        // Compact 2D snow-density field: snow can rest anywhere on the screen.
        private var columns = 1
        private var rows = 1
        private var snow = FloatArray(1)
        private val cellSize = CELL_SIZE.toFloat()

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
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            screenW = width.coerceAtLeast(1)
            screenH = height.coerceAtLeast(1)
            createSnowField()
            createParticles()
            drawFrame()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (visible) startRendering() else stopRendering()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopRendering()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopRendering()
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

        private fun createSnowField() {
            columns = (screenW / CELL_SIZE + 1).coerceAtLeast(1)
            rows = (screenH / CELL_SIZE + 1).coerceAtLeast(1)
            snow = FloatArray(columns * rows) {
                0.025f + random.nextFloat() * 0.035f
            }
        }

        private fun createParticles() {
            particles.clear()
            repeat(PARTICLE_COUNT) { particles.add(newParticle(true)) }
        }

        private fun newParticle(randomY: Boolean): SnowParticle {
            val depth = random.nextFloat()
            return SnowParticle(
                x = random.nextFloat() * screenW,
                y = if (randomY) random.nextFloat() * screenH else -random.nextFloat() * 30f,
                vx = (random.nextFloat() - 0.5f) * 16f,
                vy = 20f + depth * 55f,
                size = 0.8f + depth * 2.4f,
                depth = depth,
                phase = random.nextFloat() * PI.toFloat() * 2f,
                rotation = random.nextFloat() * 360f,
                rotationSpeed = (random.nextFloat() - 0.5f) * 100f,
                depositClock = random.nextFloat() * 0.12f
            )
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
                    touchVX = ((event.x - previousTouchX) / elapsed).coerceIn(-1800f, 1800f)
                    touchVY = ((event.y - previousTouchY) / elapsed).coerceIn(-1800f, 1800f)
                    touchX = event.x
                    touchY = event.y
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

        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    val now = System.nanoTime()
                    val dt = if (lastFrame == 0L) 0.016f
                    else ((now - lastFrame) / 1_000_000_000f).coerceIn(0.001f, 0.04f)
                    lastFrame = now
                    render(canvas, dt)
                }
            } catch (_: Exception) {
                // The wallpaper surface can disappear during screen transitions.
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                        // Ignore a surface destroyed during drawing.
                    }
                }
            }
        }

        private fun render(canvas: Canvas, dt: Float) {
            canvas.drawColor(Color.rgb(5, 12, 27))
            val wind = sin(System.currentTimeMillis() / 1900.0).toFloat() * 9f

            for (p in particles) {
                p.phase += dt * (0.7f + p.depth)
                p.rotation += p.rotationSpeed * dt
                p.depositClock += dt
                p.vx += wind * dt * 0.10f + sin(p.phase) * dt * 2f
                p.vy += (18f + p.depth * 13f) * dt * 0.035f

                // Each flake leaves a little powder along its path.
                if (p.depositClock >= DEPOSIT_INTERVAL) {
                    addSnow(p.x, p.y, DEPOSIT_AMOUNT * (0.7f + p.depth * 0.6f))
                    p.depositClock = 0f
                }

                if (touchActive) applyTouchToParticle(p, dt)
                p.vx *= 0.992f
                p.vy = p.vy.coerceIn(-260f, 180f)
                p.x += p.vx * dt
                p.y += p.vy * dt

                if (p.y > screenH + 10f || p.x < -18f || p.x > screenW + 18f) resetParticle(p)
                drawFlake(canvas, p)
            }

            if (touchActive) disturbSnow()
            drawSnowLayer(canvas)

            if (touchActive) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = Color.argb(65, 205, 235, 255)
                canvas.drawCircle(touchX, touchY, screenW * 0.105f, paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun applyTouchToParticle(p: SnowParticle, dt: Float) {
            val dx = p.x - touchX
            val dy = p.y - touchY
            val radius = screenW * 0.19f
            val distance2 = dx * dx + dy * dy
            if (distance2 >= radius * radius || distance2 < 1f) return
            val distance = sqrt(distance2)
            val force = (1f - distance / radius).coerceIn(0f, 1f)
            p.vx += (dx / distance * 190f + touchVX * 0.22f) * force * dt
            // Upward finger movement gives the flakes an extra upward toss.
            val lift = if (touchVY < -80f) 1.7f else 1f
            p.vy += (dy / distance * 170f - 250f * lift) * force * dt
        }

        private fun addSnow(x: Float, y: Float, amount: Float) {
            val cx = (x / cellSize).toInt().coerceIn(0, columns - 1)
            val cy = (y / cellSize).toInt().coerceIn(0, rows - 1)
            val index = cy * columns + cx
            snow[index] = (snow[index] + amount).coerceAtMost(1f)
        }

        private fun drawSnowLayer(canvas: Canvas) {
            for (index in snow.indices) {
                val density = snow[index]
                if (density < 0.035f) continue
                val cx = index % columns
                val cy = index / columns
                val x = cx * cellSize + cellSize * 0.5f
                val y = cy * cellSize + cellSize * 0.5f
                val alpha = (density * 205f).toInt().coerceIn(8, 205)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(alpha, 225, 239, 250)
                val radius = cellSize * (0.25f + density * 0.30f)
                canvas.drawCircle(x, y, radius, paint)
                if (density > 0.35f) {
                    paint.color = Color.argb((alpha * 0.38f).toInt(), 255, 255, 255)
                    canvas.drawCircle(x - radius * 0.22f, y - radius * 0.25f, radius * 0.32f, paint)
                }
            }
        }

        private fun disturbSnow() {
            val radius = screenW * 0.16f
            val minX = ((touchX - radius) / cellSize).toInt().coerceIn(0, columns - 1)
            val maxX = ((touchX + radius) / cellSize).toInt().coerceIn(0, columns - 1)
            val minY = ((touchY - radius) / cellSize).toInt().coerceIn(0, rows - 1)
            val maxY = ((touchY + radius) / cellSize).toInt().coerceIn(0, rows - 1)

            for (cy in minY..maxY) for (cx in minX..maxX) {
                val x = cx * cellSize + cellSize * 0.5f
                val y = cy * cellSize + cellSize * 0.5f
                val dx = x - touchX
                val dy = y - touchY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance >= radius) continue

                val index = cy * columns + cx
                val amount = snow[index]
                if (amount < 0.045f) continue

                val strength = (1f - distance / radius).coerceIn(0f, 1f)
                val removed = minOf(amount, 0.12f + strength * 0.22f)
                snow[index] -= removed

                if (particles.size < MAX_PARTICLES && random.nextFloat() < removed * 2.8f) {
                    val angle = random.nextFloat() * PI.toFloat() * 2f
                    val fling = 90f + strength * 300f
                    particles.add(
                        SnowParticle(
                            x = x,
                            y = y,
                            vx = cos(angle) * fling + touchVX * 0.25f,
                            vy = -fling - kotlin.math.abs(touchVY).coerceAtMost(900f) * 0.18f,
                            size = 1.2f + random.nextFloat() * 2.4f,
                            depth = random.nextFloat(),
                            phase = random.nextFloat() * PI.toFloat() * 2f,
                            rotation = random.nextFloat() * 360f,
                            rotationSpeed = (random.nextFloat() - 0.5f) * 180f,
                            depositClock = 0f
                        )
                    )
                }
            }
        }

        private fun resetParticle(p: SnowParticle) {
            val replacement = newParticle(false)
            p.x = random.nextFloat() * screenW
            p.y = -random.nextFloat() * 25f
            p.vx = replacement.vx
            p.vy = replacement.vy
            p.size = replacement.size
            p.depth = replacement.depth
            p.phase = replacement.phase
            p.rotation = replacement.rotation
            p.rotationSpeed = replacement.rotationSpeed
            p.depositClock = replacement.depositClock
        }

        private fun drawFlake(canvas: Canvas, p: SnowParticle) {
            val alpha = (105 + p.depth * 145).toInt().coerceIn(0, 250)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, 232, 242, 255)
            canvas.drawCircle(p.x, p.y, p.size, paint)

            if (p.size > 2.1f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.8f + p.depth * 0.5f
                paint.color = Color.argb(alpha / 2, 245, 250, 255)
                val r = p.size * 2f
                val angle = p.rotation * (PI.toFloat() / 180f)
                for (i in 0..2) {
                    val a = angle + i * (PI.toFloat() / 3f)
                    val dx = cos(a) * r
                    val dy = sin(a) * r
                    canvas.drawLine(p.x - dx, p.y - dy, p.x + dx, p.y + dy, paint)
                }
                paint.style = Paint.Style.FILL
            }
        }

        private inner class SnowParticle(
            var x: Float,
            var y: Float,
            var vx: Float,
            var vy: Float,
            var size: Float,
            var depth: Float,
            var phase: Float,
            var rotation: Float,
            var rotationSpeed: Float,
            var depositClock: Float
        )
    }

    companion object {
        private const val PARTICLE_COUNT = 1250
        private const val MAX_PARTICLES = 1850
        private const val CELL_SIZE = 18
        private const val DEPOSIT_INTERVAL = 0.12f
        private const val DEPOSIT_AMOUNT = 0.0035f
        private const val FRAME_DELAY_MS = 33L
    }
}
