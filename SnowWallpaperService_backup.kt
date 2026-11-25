package com.harddev.snowtouch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
        private val snowPath = Path()
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

        // A height profile keeps the settled snow only along the bottom edge.
        private var profile = FloatArray(1)
        private var sampleStep = 5f
        private var baseY = 1f
        private var maxSnowHeight = 1f

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
            createSnowProfile()
            createParticles()
            drawFrame()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (visible) {
                startRendering()
                accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
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

        private fun createSnowProfile() {
            sampleStep = 5f
            baseY = screenH - 1f
            maxSnowHeight = screenH * 0.30f
            profile = FloatArray((screenW / sampleStep).toInt() + 2)
            for (i in profile.indices) {
                val x = i * sampleStep / screenW.toFloat()
                val broad = 0.52f + 0.20f * kotlin.math.sin(x * 2.1f * Math.PI).toFloat() +
                    0.11f * kotlin.math.sin(x * 5.4f * Math.PI + 0.7f).toFloat()
                profile[i] = (screenH * (0.105f + broad * 0.085f + (random.nextFloat() - 0.5f) * 0.025f))
                    .coerceIn(screenH * 0.075f, maxSnowHeight * 0.88f)
            }
            softenInitialProfile()
        }

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
            // Remove gravity with a low-pass estimate; trigger only on a deliberate shake.
            gravityX = gravityX * 0.82f + x * 0.18f
            gravityY = gravityY * 0.82f + y * 0.18f
            val linearX = x - gravityX
            val linearY = y - gravityY
            val linearZ = z - 9.8f
            val magnitude = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)
            val now = System.currentTimeMillis()
            if (magnitude > SHAKE_THRESHOLD && now - lastShakeAt > SHAKE_COOLDOWN_MS) {
                lastShakeAt = now
                scatterSnowbank(linearX, linearY)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun scatterSnowbank(ax: Float, ay: Float) {
            // Break loose a portion of the top layer and turn it into airborne snow particles.
            val impulseX = (ax * 22f).coerceIn(-260f, 260f)
            val impulseY = (-abs(ay) * 18f - 95f).coerceIn(-360f, -120f)
            var emitted = 0
            for (i in profile.indices step 2) {
                if (emitted >= SHAKE_PARTICLE_LIMIT) break
                val available = profile[i]
                if (available < screenH * 0.045f) continue
                val take = min(available * 0.24f, screenH * 0.045f + random.nextFloat() * screenH * 0.025f)
                profile[i] -= take
                val x = (i * sampleStep).coerceIn(0f, screenW.toFloat())
                val y = baseY - profile[i] - random.nextFloat() * take
                val count = (1 + (take / 9f).toInt()).coerceIn(1, 4)
                repeat(count) {
                    if (particles.size >= MAX_PARTICLES || emitted >= SHAKE_PARTICLE_LIMIT) return@repeat
                    val angle = (random.nextFloat() - 0.5f) * 2f
                    particles.add(SnowParticle(
                        x = (x + angle * 9f).coerceIn(0f, screenW.toFloat()),
                        y = y,
                        vx = impulseX * 0.35f + angle * 95f + (random.nextFloat() - 0.5f) * 70f,
                        vy = impulseY * (0.45f + random.nextFloat() * 0.65f),
                        size = 1.2f + random.nextFloat() * 2.8f,
                        depth = random.nextFloat(),
                        phase = random.nextFloat() * 6.28f,
                        loose = true
                    ))
                    emitted++
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
                    if (event.y > screenH * 0.66f && event.y < screenH * 0.89f) {
                        moveSnowWithFinger(previousTouchX, previousTouchY, dx, dy)
                    }
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
                    val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0.001f, 0.04f)
                    lastFrame = now
                    render(canvas, dt)
                }
            } catch (_: Exception) {
                // Android may recreate the wallpaper surface during transitions.
            } finally {
                if (canvas != null) try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Exception) { }
            }
        }

        private fun render(canvas: Canvas, dt: Float) {
            drawBackground(canvas)
            val wind = kotlin.math.sin(System.currentTimeMillis() / 2100.0).toFloat() * 8f

            for (p in particles) {
                p.phase += dt
                p.vx += wind * dt * 0.07f + kotlin.math.sin(p.phase * 1.7f) * dt * 2f
                p.vx *= if (p.loose) 0.992f else 0.996f
                p.vy = (p.vy + (if (p.loose) 155f else 9f) * dt).coerceAtMost(if (p.loose) 520f else 95f)
                p.x += p.vx * dt
                p.y += p.vy * dt

                // Finger pushes flakes in a broad, forgiving radius; no long-press mode.
                if (touchActive && touchY < screenH * 0.90f) {
                    val dx = p.x - touchX
                    val dy = p.y - touchY
                    val radius = screenW * 0.19f
                    val d2 = dx * dx + dy * dy
                    if (d2 < radius * radius && d2 > 1f) {
                        val d = sqrt(d2)
                        val force = (1f - d / radius).coerceAtLeast(0f)
                        val speed = (abs(touchVX) + abs(touchVY)).coerceAtMost(1500f)
                        p.vx += (dx / d) * (95f + speed * 0.34f) * force * dt * 7f + touchVX * force * 0.018f
                        p.vy += (dy / d) * (75f + speed * 0.28f) * force * dt * 7f + touchVY * force * 0.012f
                        p.loose = true
                    }
                }

                val surface = snowSurfaceAt(p.x)
                if (p.y >= surface) {
                    addSnow(p.x, if (p.loose) 1.1f else 0.38f + p.depth * 0.42f)
                    resetParticle(p)
                }
                if (p.x < -18f || p.x > screenW + 18f || p.y > screenH + 15f) resetParticle(p)
            }

            resolveFlakeCollisions()
            for (p in particles) drawFlake(canvas, p)
            drawSnowbank(canvas)
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
            canvas.drawBitmap(bitmap, null, Rect(left.toInt(), top.toInt(), (left + width).toInt(), (top + height).toInt()), paint)
            paint.color = Color.argb(28, 2, 8, 20)
            canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), paint)
        }

        private fun resolveFlakeCollisions() {
            // Small bounded particle count keeps this simple pair solver mobile-friendly.
            for (i in 0 until particles.size) {
                val a = particles[i]
                for (j in i + 1 until particles.size) {
                    val b = particles[j]
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val minDistance = (a.size + b.size) * 1.45f
                    val d2 = dx * dx + dy * dy
                    if (d2 <= 0.01f || d2 >= minDistance * minDistance) continue
                    val d = sqrt(d2)
                    val nx = dx / d
                    val ny = dy / d
                    val overlap = minDistance - d
                    val push = overlap * 0.34f
                    a.x -= nx * push
                    a.y -= ny * push
                    b.x += nx * push
                    b.y += ny * push
                    val relative = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                    if (relative < 0f) {
                        val impulse = -relative * 0.28f
                        a.vx -= nx * impulse
                        a.vy -= ny * impulse
                        b.vx += nx * impulse
                        b.vy += ny * impulse
                    }
                    if (a.loose || b.loose) {
                        a.loose = true
                        b.loose = true
                    }
                }
            }
        }

        private fun snowSurfaceAt(x: Float): Float = baseY - profile[(x / sampleStep).toInt().coerceIn(0, profile.lastIndex)]

        private fun moveSnowWithFinger(oldX: Float, oldY: Float, dx: Float, dy: Float) {
            val centerX = oldX + dx * 0.5f
            val radius = screenW * 0.14f
            val start = ((centerX - radius) / sampleStep).toInt().coerceIn(0, profile.lastIndex)
            val end = ((centerX + radius) / sampleStep).toInt().coerceIn(0, profile.lastIndex)
            val strength = (abs(dx) + abs(dy) * 0.55f).coerceAtMost(screenW * 0.45f)
            if (strength < 0.5f) return
            val direction = if (dx >= 0f) 1f else -1f
            for (i in start..end) {
                val distance = abs(i * sampleStep - centerX)
                if (distance > radius) continue
                val influence = 1f - distance / radius
                val scoop = min(profile[i], strength * 0.20f * influence)
                profile[i] -= scoop
                // Preserve the displaced snow as two soft side ridges; do not smooth the trail away.
                val offset = (3 + influence * 8f).toInt()
                val left = (i - offset).coerceAtLeast(0)
                val right = (i + offset).coerceAtMost(profile.lastIndex)
                profile[if (direction > 0f) right else left] = min(maxSnowHeight, profile[if (direction > 0f) right else left] + scoop * 0.68f)
                profile[if (direction > 0f) left else right] = min(maxSnowHeight, profile[if (direction > 0f) left else right] + scoop * 0.32f)
            }
            // A quick upward flick lifts a little powder from the touched area.
            if (dy < -screenH * 0.008f) {
                val count = (abs(dy) / 7f).toInt().coerceIn(2, 22)
                repeat(count) {
                    if (particles.size >= MAX_PARTICLES) return@repeat
                    particles.add(SnowParticle(
                        x = (centerX + (random.nextFloat() - 0.5f) * radius).coerceIn(0f, screenW.toFloat()),
                        y = (oldY + dy * 0.5f).coerceIn(0f, screenH.toFloat()),
                        vx = dx * 2.2f + (random.nextFloat() - 0.5f) * 145f,
                        vy = -abs(dy) * (1.0f + random.nextFloat()) - 55f,
                        size = 1.2f + random.nextFloat() * 2.6f,
                        depth = random.nextFloat(), phase = random.nextFloat() * 6.28f, loose = true
                    ))
                }
            }
        }

        private fun addSnow(x: Float, amount: Float) {
            val index = (x / sampleStep).toInt().coerceIn(0, profile.lastIndex)
            for (offset in -2..2) {
                val target = (index + offset).coerceIn(0, profile.lastIndex)
                val weight = 1f - abs(offset) / 3.5f
                profile[target] = min(maxSnowHeight, profile[target] + amount * weight)
            }
        }

        private fun softenInitialProfile() {
            if (profile.size < 3) return
            val copy = profile.copyOf()
            for (i in 1 until profile.lastIndex) profile[i] = copy[i - 1] * 0.22f + copy[i] * 0.56f + copy[i + 1] * 0.22f
        }

        private fun drawSnowbank(canvas: Canvas) {
            snowPath.reset()
            snowPath.moveTo(0f, baseY - profile[0])
            for (i in 1 until profile.size) {
                val x = i * sampleStep
                snowPath.lineTo(x, baseY - profile[i])
            }
            snowPath.lineTo(screenW.toFloat(), screenH.toFloat())
            snowPath.lineTo(0f, screenH.toFloat())
            snowPath.close()
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(215, 231, 246)
            canvas.drawPath(snowPath, paint)
            paint.color = Color.argb(75, 112, 151, 190)
            canvas.drawRect(0f, baseY - screenH * 0.025f, screenW.toFloat(), screenH.toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = max(1.2f, screenW * 0.002f)
            paint.color = Color.argb(150, 255, 255, 255)
            val edge = Path()
            for (i in profile.indices step 2) {
                val x = i * sampleStep
                val y = baseY - profile[i]
                if (i == 0) edge.moveTo(x, y) else edge.lineTo(x, y)
            }
            canvas.drawPath(edge, paint)
            paint.style = Paint.Style.FILL
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
        private const val PARTICLE_COUNT = 260
        private const val MAX_PARTICLES = 420
        private const val SHAKE_PARTICLE_LIMIT = 145
        private const val SHAKE_THRESHOLD = 13.5f
        private const val SHAKE_COOLDOWN_MS = 850L
        private const val FRAME_DELAY_MS = 33L
    }
}
