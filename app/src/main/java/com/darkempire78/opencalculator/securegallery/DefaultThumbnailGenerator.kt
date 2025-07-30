package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.graphics.*
import android.util.Log

/**
 * Generator for default thumbnails when actual thumbnail generation fails
 */
object DefaultThumbnailGenerator {
    private const val TAG = "DefaultThumbnailGenerator"
    
    /**
     * Create a default video thumbnail with a play icon
     */
    fun createDefaultVideoThumbnail(
        context: Context,
        width: Int = 320,
        height: Int = 240
    ): Bitmap? {
        return try {
            // Create a bitmap with dark background
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            
            // Fill with dark gray background
            canvas.drawColor(Color.rgb(64, 64, 64))
            
            // Create paint for the play icon
            val paint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            
            // Draw a simple play triangle
            val centerX = width / 2f
            val centerY = height / 2f
            val triangleSize = minOf(width, height) / 6f
            
            val path = Path().apply {
                moveTo(centerX - triangleSize, centerY - triangleSize)
                lineTo(centerX - triangleSize, centerY + triangleSize)
                lineTo(centerX + triangleSize, centerY)
                close()
            }
            
            canvas.drawPath(path, paint)
            
            // Add "VIDEO" text at bottom
            val textPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                textSize = height / 12f
            }
            
            canvas.drawText("VIDEO", centerX, height - height / 8f, textPaint)
            
            Log.d(TAG, "Created default video thumbnail: ${width}x${height}")
            bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating default video thumbnail", e)
            null
        }
    }
    
    /**
     * Create a default photo thumbnail with a photo icon
     */
    fun createDefaultPhotoThumbnail(
        context: Context,
        width: Int = 320,
        height: Int = 240
    ): Bitmap? {
        return try {
            // Create a bitmap with light gray background
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            
            // Fill with light gray background
            canvas.drawColor(Color.rgb(200, 200, 200))
            
            // Create paint for the photo icon
            val paint = Paint().apply {
                color = Color.GRAY
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            
            val centerX = width / 2f
            val centerY = height / 2f
            val iconSize = minOf(width, height) / 4f
            
            // Draw a simple rectangle (photo frame)
            val rect = RectF(
                centerX - iconSize,
                centerY - iconSize * 0.7f,
                centerX + iconSize,
                centerY + iconSize * 0.7f
            )
            canvas.drawRect(rect, paint)
            
            // Draw a simple mountain/photo scene
            val scenePaint = Paint().apply {
                color = Color.GRAY
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            
            // Mountain path
            val mountainPath = Path().apply {
                moveTo(centerX - iconSize * 0.8f, centerY + iconSize * 0.5f)
                lineTo(centerX - iconSize * 0.3f, centerY - iconSize * 0.3f)
                lineTo(centerX, centerY)
                lineTo(centerX + iconSize * 0.5f, centerY - iconSize * 0.5f)
                lineTo(centerX + iconSize * 0.8f, centerY + iconSize * 0.5f)
                close()
            }
            canvas.drawPath(mountainPath, scenePaint)
            
            // Sun circle
            canvas.drawCircle(centerX + iconSize * 0.5f, centerY - iconSize * 0.3f, iconSize * 0.15f, scenePaint)
            
            Log.d(TAG, "Created default photo thumbnail: ${width}x${height}")
            bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating default photo thumbnail", e)
            null
        }
    }
}
