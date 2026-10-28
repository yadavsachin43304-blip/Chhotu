package com.sachin.chhotu

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

object Ui {
    val BG = Color.parseColor("#0E1121")
    val CARD = Color.parseColor("#181C30")
    val CARD2 = Color.parseColor("#22273F")
    val ACCENT = Color.parseColor("#FF6D28")
    val TEAL = Color.parseColor("#2EC4B6")
    val TEXT = Color.parseColor("#F2F3F7")
    val MUTED = Color.parseColor("#8A90A8")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun round(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokeColor != null) setStroke(strokePx, strokeColor)
    }

    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun text(c: Context, t: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun button(c: Context, t: String, primary: Boolean = true) = Button(c).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) Color.WHITE else TEXT)
        background = round(if (primary) ACCENT else CARD2, dp(c, 14).toFloat())
        stateListAnimator = null
        setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
    }

    fun field(c: Context, value: String, hintText: String) = EditText(c).apply {
        setText(value)
        hint = hintText
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 15f
        background = round(CARD2, dp(c, 12).toFloat())
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        gravity = Gravity.CENTER_VERTICAL
    }
}
