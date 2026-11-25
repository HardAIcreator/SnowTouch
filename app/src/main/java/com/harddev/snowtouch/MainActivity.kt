package com.harddev.snowtouch

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val background = Color.rgb(7, 16, 32)
        val white = Color.rgb(238, 245, 255)
        val muted = Color.rgb(170, 190, 215)
        val accent = Color.rgb(45, 126, 205)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(36), dp(28), dp(36))
            setBackgroundColor(background)
        }
        val title = TextView(this).apply {
            text = "❄  SnowTouch"
            textSize = 32f
            setTextColor(white)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "НОВОГОДНИЕ ЖИВЫЕ ОБОИ"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        val description = TextView(this).apply {
            text = "Зимняя ёлка, падающий снег и живые сугробы.\n\n" +
                "Проводи пальцем по снегу, разбрасывай снежинки и встряхни телефон, чтобы поднять снежную метель."
            textSize = 16f
            setTextColor(white)
            gravity = Gravity.CENTER
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        val button = Button(this).apply {
            text = "Установить живые обои"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)
            setOnClickListener { openWallpaperPicker() }
        }
        val note = TextView(this).apply {
            text = "В предварительном просмотре нажми «Установить обои». Их можно заменить в настройках обоев Android."
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        layout.addView(title, matchWrap())
        layout.addView(subtitle, marginParams(top = 8))
        layout.addView(description, marginParams(top = 42))
        layout.addView(button, marginParams(top = 38).apply { height = dp(56) })
        layout.addView(note, marginParams(top = 18))
        setContentView(layout)
    }

    private fun openWallpaperPicker() {
        val component = ComponentName(this, SnowWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun marginParams(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}
