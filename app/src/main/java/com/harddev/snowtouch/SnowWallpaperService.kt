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
import kotlin.random.Random

class SnowWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SnowEngine()

    inner class SnowEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val random = Random(System.nanoTime())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val particles = ArrayList<SnowParticle>(PARTICLE_COUNT)

        private var screenW = 1
        private var screenH = 1
        private var visible = false
        private var running = false
        private var lastFrame = 0L

        private var touchActive = false
        private var touchX = 0f
        private var touchY = 0f
        private var touchDownAt = 0L
        private var previousTouchX = 0f
        private var previousTouchY = 0f
        private var swirlDirection = 1f

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

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            screenW = width.coerceAtLeast(1)
            screenH = height.coerceAtLeast(1)
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

        private fun createParticles() {
            particles.clear()
            repeat(PARTICLE_COUNT) {
                particles.add(newParticle(randomY = true))
            }
        }

        private fun newParticle(randomY: Boolean): SnowParticle {
            val depth = random.nextFloat()
            val size = 0.7f + depth * 2.5f
            return SnowParticle(
                x = random.nextFloat() * screenW,
                y = if (randomY) random.nextFloat() * screenH else -random.nextFloat() * 80f,
                vx = (random.nextFloat() - 0.5f) * 12f,
                vy = 18f + depth * 48f,
                size = size,
                depth = depth,
                phase = random.nextFloat() * (PI.toFloat() * 2f),
                rotation = random.nextFloat() * 360f,
                rotationSpeed = (random.nextFloat() - 0.5f) * 90f
            )
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchActive = true
                    touchX = event.x
                    touchY = event.y
                    previousTouchX = event.x
                    previousTouchY = event.y
                    touchDownAt = System.currentTimeMillis()
                    swirlDirection = 1f
                }

                MotionEvent.ACTION_MOVE -> {
                    if (touchActive) {
                        val dx = event.x - previousTouchX
                        val dy = event.y - previousTouchY

                        // Circular movement changes the vortex direction.
                        if (dx * dy < 0f) swirlDirection = -swirlDirection

                        touchX = event.x
                        touchY = event.y
                        previousTouchX = event.x
                        previousTouchY = event.y
                    }
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
            val holder = surfaceHolder
            var canvas: Canvas? = null

            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val now = System.nanoTime()
                    val dt = if (lastFrame == 0L) 0.016f
                    else ((now - lastFrame) / 1_000_000_000f).coerceIn(0.001f, 0.04f)
                    lastFrame = now

                    render(canvas, dt)
                }
            } catch (_: Exception) {
                // Surface may temporarily be unavailable while Android changes screens.
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                        // Ignore a surface that was destroyed during drawing.
                    }
                }
            }
        }

        private fun render(canvas: Canvas, dt: Float) {
            canvas.drawColor(Color.rgb(5, 12, 27))

            val now = System.currentTimeMillis()
            val holdAttract = touchActive && now - touchDownAt > HOLD_TO_ATTRACT_MS

            // Subtle depth haze near the bottom.
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(16, 80, 125, 170)
            canvas.drawRect(0f, screenH * 0.72f, screenW.toFloat(), screenH.toFloat(), paint)

            val wind = sin(now / 1900.0).toFloat() * 10f

            for (p in particles) {
                p.phase += dt * (0.7f + p.depth)
                p.rotation += p.rotationSpeed * dt

                // Natural falling motion and gentle wind.
                p.vx += wind * dt * 0.10f
                p.vx += sin(p.phase) * dt * 2.2f
                p.vy += (22f + p.depth * 15f) * dt * 0.035f

                if (touchActive) {
                    val dx = touchX - p.x
                    val dy = touchY - p.y
                    val distance2 = dx * dx + dy * dy
                    val radius = screenW * 0.23f

                    if (distance2 < radius * radius && distance2 > 1f) {
                        val distance = kotlin.math.sqrt(distance2)
                        val nx = dx / distance
                        val ny = dy / distance
                        val force = (1f - distance / radius).coerceIn(0f, 1f)

                        if (holdAttract) {
                            // Hold finger: pull particles toward the touch point.
                            p.vx += nx * force * 95f * dt
                            p.vy += ny * force * 95f * dt
                        } else {
                            // Swipe: scatter particles away from the finger.
                            p.vx -= nx * force * 260f * dt
                            p.vy -= ny * force * 260f * dt
                        }

                        // Tangential force gives a swirling effect.
                        p.vx += (-ny) * force * 55f * swirlDirection * dt
                        p.vy += nx * force * 55f * swirlDirection * dt
                    }
                }

                // Damping prevents particles from accelerating forever.
                p.vx *= 0.992f
                p.vy = p.vy.coerceIn(-100f, 180f)

                p.x += p.vx * dt
                p.y += p.vy * dt

                if (p.y > screenH + 8f || p.x < -15f || p.x > screenW + 15f) {
                    val replacement = newParticle(randomY = false)
                    p.x = if (p.x < 0f || p.x > screenW) {
                        random.nextFloat() * screenW
                    } else {
                        p.x.coerceIn(0f, screenW.toFloat())
                    }
                    p.y = -random.nextFloat() * 30f
                    p.vx = replacement.vx
                    p.vy = replacement.vy
                    p.size = replacement.size
                    p.depth = replacement.depth
                    p.phase = replacement.phase
                    p.rotation = replacement.rotation
                    p.rotationSpeed = replacement.rotationSpeed
                }

                drawSnowflake(canvas, p)
            }

            // Small glow around the finger while interacting.
            if (touchActive) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = if (holdAttract) {
                    Color.argb(75, 110, 190, 255)
                } else {
                    Color.argb(55, 210, 235, 255)
                }
                canvas.drawCircle(touchX, touchY, screenW * 0.12f, paint)
                paint.style = Paint.Style.FILL
            }
        }

        private fun drawSnowflake(canvas: Canvas, p: SnowParticle) {
            val alpha = (105 + p.depth * 145).toInt().coerceIn(0, 250)
            val blueTint = (235 + p.depth * 20).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, 225, 238, blueTint)

            // Tiny distant flakes are dots; closer flakes have a crystal-like cross.
            canvas.drawCircle(p.x, p.y, p.size, paint)

            if (p.size > 2.1f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.8f + p.depth * 0.5f
                paint.color = Color.argb(alpha / 2, 245, 250, 255)

                val r = p.size * 2.0f
                val a = p.rotation * (PI.toFloat() / 180f)
                for (i in 0..2) {
                    val angle = a + i * (PI.toFloat() / 3f)
                    val dx = cos(angle) * r
                    val dy = sin(angle) * r
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
            var rotationSpeed: Float
        )
    }

    companion object {
        private const val PARTICLE_COUNT = 1500
        private const val FRAME_DELAY_MS = 33L
        private const val HOLD_TO_ATTRACT_MS = 450L
    }
}
