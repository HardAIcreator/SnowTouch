package com.harddev.snowtouch

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SnowWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = SnowEngine()

    inner class SnowEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val random = Random(System.nanoTime())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val snowPath = Path()
        private val particles = ArrayList<SnowParticle>(MAX_PARTICLES)

        private var screenW = 1
        private var screenH = 1
        private var visible = false
        private var running = false
        private var lastFrame = 0L

        // One height value per horizontal sample: snow exists only at the bottom.
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

        private fun createSnowProfile() {
            sampleStep = 5f
            baseY = screenH - 2f
            maxSnowHeight = screenH * 0.29f
            val count = (screenW / sampleStep).toInt() + 2
            profile = FloatArray(count)

            // A low, uneven blanket of snow with several soft drifts.
            for (i in profile.indices) {
                val x = i * sampleStep / screenW.toFloat()
                val broad = 0.48f + 0.19f * sin(x * 2.2f * Math.PI).toFloat() +
                    0.12f * sin(x * 5.7f * Math.PI + 0.8f).toFloat()
                val small = (random.nextFloat() - 0.5f) * 0.035f
                profile[i] = (screenH * (0.105f + broad * 0.095f + small))
                    .coerceIn(screenH * 0.075f, maxSnowHeight * 0.88f)
            }
            smoothProfile(2)
        }

        private fun createParticles() {
            particles.clear()
            repeat(PARTICLE_COUNT) { particles.add(newParticle(true)) }
        }

        private fun newParticle(randomY: Boolean): SnowParticle {
            val depth = random.nextFloat()
            return SnowParticle(
                x = random.nextFloat() * screenW,
                y = if (randomY) random.nextFloat() * (screenH * 0.78f) else -random.nextFloat() * 35f,
                vx = (random.nextFloat() - 0.5f) * 13f,
                vy = 22f + depth * 48f,
                size = 0.7f + depth * 1.8f,
                depth = depth,
                phase = random.nextFloat() * 6.28f
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
                    val dx = event.x - previousTouchX
                    val dy = event.y - previousTouchY
                    touchVX = (dx / elapsed).coerceIn(-1800f, 1800f)
                    touchVY = (dy / elapsed).coerceIn(-1800f, 1800f)
                    touchX = event.x
                    touchY = event.y

                    // Only interact when the finger is close to the snowbank.
                    if (isNearSnow(event.x, event.y)) {
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
            val wind = sin(System.currentTimeMillis() / 2100.0).toFloat() * 7f

            for (p in particles) {
                p.phase += dt
                p.vx += wind * dt * 0.08f + sin(p.phase * 1.7f) * dt * 1.8f
                p.vx *= 0.995f
                p.vy = (p.vy + 5f * dt).coerceAtMost(100f)
                p.x += p.vx * dt
                p.y += p.vy * dt

                val surfaceY = snowSurfaceAt(p.x)
                if (p.y >= surfaceY) {
                    addSnow(p.x, 0.45f + p.depth * 0.55f)
                    resetParticle(p)
                } else if (p.y > screenH * 0.76f && p.y < surfaceY) {
                    // A subtle drift slows flakes as they approach the snow.
                    p.vy *= 0.999f
                }

                if (p.x < -12f || p.x > screenW + 12f || p.y > screenH) resetParticle(p)
                drawFlake(canvas, p)
            }

            drawSnowbank(canvas)
        }

        private fun snowSurfaceAt(x: Float): Float {
            val index = (x / sampleStep).toInt().coerceIn(0, profile.lastIndex)
            return baseY - profile[index]
        }

        private fun isNearSnow(x: Float, y: Float): Boolean {
            val surface = snowSurfaceAt(x)
            return y >= surface - screenH * 0.11f && y <= baseY + screenH * 0.04f
        }

        private fun moveSnowWithFinger(oldX: Float, oldY: Float, dx: Float, dy: Float) {
            val radius = screenW * 0.105f
            val start = ((min(oldX, oldX + dx) - radius) / sampleStep).toInt().coerceIn(0, profile.lastIndex)
            val end = ((max(oldX, oldX + dx) + radius) / sampleStep).toInt().coerceIn(0, profile.lastIndex)
            val swipeStrength = (abs(dx) + abs(dy) * 0.65f).coerceAtMost(screenW * 0.32f)
            if (swipeStrength < 1f) return

            for (i in start..end) {
                val x = i * sampleStep
                val distance = abs(x - (oldX + dx * 0.5f))
                if (distance > radius) continue
                val influence = 1f - distance / radius
                val scoop = min(profile[i], swipeStrength * 0.12f * influence)
                profile[i] -= scoop

                // Push displaced snow sideways, forming raised ridges beside the finger trail.
                val direction = if (dx >= 0f) 1f else -1f
                val spread = (scoop * 0.72f * influence)
                val left = (i - (2 + (influence * 5f).toInt())).coerceAtLeast(0)
                val right = (i + (2 + (influence * 5f).toInt())).coerceAtMost(profile.lastIndex)
                if (direction > 0f) {
                    profile[left] = min(maxSnowHeight, profile[left] + spread * 0.65f)
                    profile[right] = min(maxSnowHeight, profile[right] + spread * 0.35f)
                } else {
                    profile[left] = min(maxSnowHeight, profile[left] + spread * 0.35f)
                    profile[right] = min(maxSnowHeight, profile[right] + spread * 0.65f)
                }
            }

            // A quick upward swipe throws a small spray of loose powder.
            if (dy < -screenH * 0.012f && particles.size < MAX_PARTICLES) {
                val count = (abs(dy) / 12f).toInt().coerceIn(2, 18)
                repeat(count) {
                    val spread = (random.nextFloat() - 0.5f) * radius
                    particles.add(
                        SnowParticle(
                            x = (oldX + dx * 0.5f + spread).coerceIn(0f, screenW.toFloat()),
                            y = (oldY + dy * 0.5f).coerceIn(0f, screenH.toFloat()),
                            vx = dx * 1.8f + (random.nextFloat() - 0.5f) * 110f,
                            vy = -abs(dy) * (1.4f + random.nextFloat()) - 35f,
                            size = 1f + random.nextFloat() * 2.2f,
                            depth = random.nextFloat(),
                            phase = random.nextFloat() * 6.28f
                        )
                    )
                }
            }
            smoothProfile(1)
        }

        private fun addSnow(x: Float, amount: Float) {
            val index = (x / sampleStep).toInt().coerceIn(0, profile.lastIndex)
            val spread = 2
            for (offset in -spread..spread) {
                val target = (index + offset).coerceIn(0, profile.lastIndex)
                val weight = 1f - abs(offset) / 3.5f
                profile[target] = min(maxSnowHeight, profile[target] + amount * weight)
            }
        }

        private fun smoothProfile(passes: Int) {
            repeat(passes) {
                if (profile.size < 3) return
                val copy = profile.copyOf()
                for (i in 1 until profile.lastIndex) {
                    profile[i] = copy[i - 1] * 0.22f + copy[i] * 0.56f + copy[i + 1] * 0.22f
                }
            }
        }

        private fun drawSnowbank(canvas: Canvas) {
            if (profile.isEmpty()) return
            snowPath.reset()
            snowPath.moveTo(0f, baseY - profile[0])
            for (i in 1 until profile.size) {
                val x = i * sampleStep
                val y = baseY - profile[i]
                val previousX = (i - 1) * sampleStep
                val midX = (previousX + x) * 0.5f
                val previousY = baseY - profile[i - 1]
                snowPath.quadTo(previousX, previousY, midX, (previousY + y) * 0.5f)
            }
            snowPath.lineTo(screenW.toFloat(), baseY - profile.last())
            snowPath.lineTo(screenW.toFloat(), screenH.toFloat())
            snowPath.lineTo(0f, screenH.toFloat())
            snowPath.close()

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(207, 224, 240)
            canvas.drawPath(snowPath, paint)

            // Soft blue shadow near the lower edge gives the bank depth.
            paint.color = Color.argb(95, 105, 139, 174)
            canvas.drawRect(0f, baseY - screenH * 0.035f, screenW.toFloat(), screenH.toFloat(), paint)

            // Fine highlights follow the irregular top edge; no dotted grid or circles.
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = max(1.2f, screenW * 0.0022f)
            paint.color = Color.argb(125, 255, 255, 255)
            val highlight = Path()
            for (i in profile.indices step 3) {
                val x = i * sampleStep
                val y = baseY - profile[i]
                if (i == 0) highlight.moveTo(x, y + 2f) else highlight.lineTo(x, y + 2f)
            }
            canvas.drawPath(highlight, paint)
            paint.style = Paint.Style.FILL
        }

        private fun resetParticle(p: SnowParticle) {
            p.x = random.nextFloat() * screenW
            p.y = -random.nextFloat() * (screenH * 0.35f)
            p.vx = (random.nextFloat() - 0.5f) * 13f
            p.vy = 22f + random.nextFloat() * 48f
            p.size = 0.7f + random.nextFloat() * 1.8f
            p.depth = random.nextFloat()
            p.phase = random.nextFloat() * 6.28f
        }

        private fun drawFlake(canvas: Canvas, p: SnowParticle) {
            val alpha = (90 + p.depth * 145).toInt().coerceIn(0, 235)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, 232, 242, 255)
            canvas.drawCircle(p.x, p.y, p.size, paint)
        }

        private inner class SnowParticle(
            var x: Float,
            var y: Float,
            var vx: Float,
            var vy: Float,
            var size: Float,
            var depth: Float,
            var phase: Float
        )
    }

    companion object {
        private const val PARTICLE_COUNT = 260
        private const val MAX_PARTICLES = 420
        private const val FRAME_DELAY_MS = 33L
    }
}
