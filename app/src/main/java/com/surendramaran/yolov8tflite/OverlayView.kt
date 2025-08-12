package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import yolov8tflite.R

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var bounds = Rect()
    
    // Thêm biến để lưu kích thước model input
    private var modelInputWidth = 640
    private var modelInputHeight = 640
    private var previewWidth = 0
    private var previewHeight = 0

    init {
        initPaints()
    }

    fun clear() {
        results = listOf()
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        previewWidth = w
        previewHeight = h
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        if (previewWidth == 0 || previewHeight == 0) return

        results.forEach {
            // Tính toán scale factor để chuyển đổi từ model coordinates sang preview coordinates
            val scaleX = previewWidth.toFloat() / modelInputWidth
            val scaleY = previewHeight.toFloat() / modelInputHeight
            
            // Chuyển đổi từ normalized coordinates (0-1) sang pixel coordinates
            val left = it.x1 * modelInputWidth * scaleX
            val top = it.y1 * modelInputHeight * scaleY
            val right = it.x2 * modelInputWidth * scaleX
            val bottom = it.y2 * modelInputHeight * scaleY

            canvas.drawRect(left, top, right, bottom, boxPaint)
            val drawableText = it.clsName

            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            val textBottom = top - BOUNDING_RECT_TEXT_PADDING // Đáy của nền chữ nằm ngay trên bbox
            val textTop = textBottom - textHeight - BOUNDING_RECT_TEXT_PADDING // Đỉnh của nền chữ
            canvas.drawRect(
                left,
                textTop,
                left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                textBottom,
                textBackgroundPaint
            )
            canvas.drawText(drawableText, left, textBottom - BOUNDING_RECT_TEXT_PADDING, textPaint)
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }
    
    // Thêm method để set kích thước model input
    fun setModelInputSize(width: Int, height: Int) {
        modelInputWidth = width
        modelInputHeight = height
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}