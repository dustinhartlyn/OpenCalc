package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.abs

class CustomViewPager2 @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewPager2(context, attrs, defStyleAttr) {

    private var initialX = 0f
    private var initialY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ev.x
                initialY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val currentPhotoView = getCurrentPhotoView()
                if (currentPhotoView != null) {
                    val scale = currentPhotoView.scale
                    val deltaX = ev.x - initialX
                    val deltaY = ev.y - initialY
                    
                    // If photo is zoomed in, let PhotoView handle the touch
                    if (scale > 1.1f) {
                        // Check if we're at the edge of the photo when panning horizontally
                        if (abs(deltaX) > abs(deltaY)) {
                            // Horizontal pan - check if we're at photo edge
                            val isAtLeftEdge = currentPhotoView.isAtLeftEdge()
                            val isAtRightEdge = currentPhotoView.isAtRightEdge()
                            
                            if ((deltaX > 0 && isAtLeftEdge) || (deltaX < 0 && isAtRightEdge)) {
                                // At edge and trying to pan further - allow ViewPager2 to handle
                                return super.onInterceptTouchEvent(ev)
                            } else {
                                // Not at edge - let PhotoView handle the pan
                                return false
                            }
                        } else {
                            // Vertical pan while zoomed - let PhotoView handle
                            return false
                        }
                    } else {
                        // Photo is not zoomed in
                        if (abs(deltaY) > abs(deltaX) && abs(deltaY) > 100) {
                            // Primarily vertical swipe - don't intercept, let PhotoView handle for dismiss
                            return false
                        }
                        // Horizontal swipe at normal zoom - let ViewPager2 handle
                        return super.onInterceptTouchEvent(ev)
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun getCurrentPhotoView(): PhotoView? {
        return try {
            val recyclerView = getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            if (recyclerView != null) {
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentItem)
                if (viewHolder is SecurePhotoPagerAdapter.PhotoViewHolder) {
                    viewHolder.photoView
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun PhotoView.isAtLeftEdge(): Boolean {
        return try {
            val matrix = imageMatrix
            val values = FloatArray(9)
            matrix.getValues(values)
            val x = values[2] // Translation X
            x >= -10f // Small tolerance for floating point precision
        } catch (e: Exception) {
            false
        }
    }

    private fun PhotoView.isAtRightEdge(): Boolean {
        return try {
            val matrix = imageMatrix
            val values = FloatArray(9)
            matrix.getValues(values)
            val x = values[2] // Translation X
            val scaleX = values[0] // Scale X
            val drawable = drawable ?: return false
            val imageWidth = drawable.intrinsicWidth
            val scaledWidth = imageWidth * scaleX
            val viewWidth = width
            
            if (scaledWidth <= viewWidth) {
                // Image is smaller than view, always at "edge"
                return true
            }
            
            val rightEdge = -(scaledWidth - viewWidth)
            x <= rightEdge + 10f // Small tolerance
        } catch (e: Exception) {
            false
        }
    }
}
