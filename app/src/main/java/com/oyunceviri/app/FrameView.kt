package com.oyunceviri.app

import android.content.Context
import android.graphics.*
import android.view.View

class FrameView(context: Context) : View(context) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(232, 14, 14, 14)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }

    private val statusBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 18, 18, 18)
        style = Paint.Style.FILL
    }

    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FF99")
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    var lines: List<SubtitleLine> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }

    var statusMessage: String = ""
        set(value) {
            field = value
            postInvalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (statusMessage.isNotBlank()) {
            val w = (statusTextPaint.measureText(statusMessage) + 32f).coerceAtMost(width - 32f)
            val statusRect = RectF(20f, 24f, 20f + w, 68f)
            canvas.drawRoundRect(statusRect, 10f, 10f, statusBgPaint)
            canvas.drawText(statusMessage, 36f, 54f, statusTextPaint)
        }

        if (lines.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        val sorted = lines.sortedBy { it.rect.top }
        val combinedText = sorted.joinToString(" ") { it.translatedText }.trim()
        if (combinedText.isEmpty()) return

        val anchorRect = sorted.last().rect
        val targetBottom = (anchorRect.bottom + 10f).coerceAtMost(viewH - 28f)
        val lineHeight = (anchorRect.height().coerceIn(28, 68) * 0.72f).coerceIn(24f, 44f)
        subtitlePaint.textSize = lineHeight

        val maxBoxWidth = (viewW * 0.84f).coerceAtLeast(240f)
        val wrapped = wrapText(combinedText, subtitlePaint, maxBoxWidth - 36f)
        val textBlockHeight = wrapped.size * (lineHeight * 1.25f) + 20f

        val boxTop = (targetBottom - textBlockHeight).coerceAtLeast(viewH * 0.55f)
        val boxBottom = (boxTop + textBlockHeight).coerceAtMost(viewH - 20f)

        val widestLine = wrapped.maxOfOrNull { subtitlePaint.measureText(it) } ?: 200f
        val boxWidth = (widestLine + 36f).coerceIn(180f, maxBoxWidth)
        val centerX = if (anchorRect.centerX() > 0) anchorRect.centerX().toFloat() else viewW / 2f
        val boxLeft = (centerX - boxWidth / 2f).coerceIn(20f, viewW - boxWidth - 20f)
        val boxRight = boxLeft + boxWidth

        val rectF = RectF(boxLeft, boxTop, boxRight, boxBottom)
        canvas.drawRoundRect(rectF, 12f, 12f, boxPaint)
        canvas.drawRoundRect(rectF, 12f, 12f, borderPaint)

        wrapped.forEachIndexed { idx, rowText ->
            val rowWidth = subtitlePaint.measureText(rowText)
            val drawX = boxLeft + ((boxWidth - rowWidth) / 2f).coerceAtLeast(14f)
            val drawY = boxTop + 14f + (idx + 1) * (lineHeight * 1.22f) - (lineHeight * 0.2f)
            canvas.drawText(rowText, drawX, drawY, subtitlePaint)
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var current = StringBuilder()

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) result.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result.take(3)
    }
}
