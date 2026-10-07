package com.sriox.vasateysec.utils

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Window
import android.widget.ImageView
import com.bumptech.glide.Glide
import java.io.File

/**
 * Tap any evidence thumbnail → fullscreen uncropped view with pinch-zoom + drag.
 * Accepts local File, local path String, or remote http(s) URL String.
 * Thumbnails stay centerCrop (tidy); this dialog always shows the FULL image.
 */
object PhotoFullscreenViewer {

    fun resolveSource(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val f = File(raw)
        return if (f.exists()) f else null
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, source: Any?) {
        if (source == null) return
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context)
            .inflate(com.sriox.vasateysec.R.layout.dialog_fullscreen_photo, null)
        dialog.setContentView(view)

        val imageView = view.findViewById<ImageView>(com.sriox.vasateysec.R.id.fullPhotoView)
        Glide.with(context).load(source).fitCenter().into(imageView)

        // --- pinch-zoom + drag (matrix mode, image never cropped) ---
        val matrix = Matrix()
        val savedMatrix = Matrix()
        var mode = 0 // 0 none, 1 drag, 2 zoom
        val start = PointF()
        val mid = PointF()
        var oldDist = 1f

        imageView.scaleType = ImageView.ScaleType.MATRIX
        // Fit whole image on first layout
        imageView.post {
            val d = imageView.drawable ?: return@post
            val vw = imageView.width.toFloat()
            val vh = imageView.height.toFloat()
            if (vw == 0f || vh == 0f) return@post
            val dw = d.intrinsicWidth.toFloat()
            val dh = d.intrinsicHeight.toFloat()
            if (dw <= 0 || dh <= 0) return@post
            val scale = minOf(vw / dw, vh / dh)
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate((vw - dw * scale) / 2f, (vh - dh * scale) / 2f)
            imageView.imageMatrix = matrix
        }

        val scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    matrix.postScale(
                        detector.scaleFactor, detector.scaleFactor,
                        detector.focusX, detector.focusY
                    )
                    // Clamp zoom between fit-scale and 6x of it
                    imageView.imageMatrix = matrix
                    return true
                }
            })

        imageView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    start.set(event.x, event.y)
                    mode = 1
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        midPoint(mid, event)
                        mode = 2
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1 && !scaleDetector.isInProgress) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(event.x - start.x, event.y - start.y)
                        imageView.imageMatrix = matrix
                    } else if (mode == 2) {
                        val newDist = spacing(event)
                        if (newDist > 10f) {
                            matrix.set(savedMatrix)
                            val scale = (newDist / oldDist).coerceIn(0.5f, 6f)
                            matrix.postScale(scale, scale, mid.x, mid.y)
                            imageView.imageMatrix = matrix
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> mode = 0
            }
            v.performClick()
            true
        }

        view.findViewById<ImageView>(com.sriox.vasateysec.R.id.btnClosePhoto)
            .setOnClickListener { dialog.dismiss() }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun spacing(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun midPoint(p: PointF, e: MotionEvent) {
        if (e.pointerCount < 2) return
        p.set((e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f)
    }
}
