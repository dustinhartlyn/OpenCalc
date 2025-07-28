package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.abs

class CustomViewPager2 @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val viewPager2: ViewPager2 = ViewPager2(context)
    private var initialX = 0f
    private var initialY = 0f

    init {
        addView(viewPager2, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // Delegate properties and methods to ViewPager2
    var adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>?
        get() = viewPager2.adapter
        set(value) { viewPager2.adapter = value }

    var currentItem: Int
        get() = viewPager2.currentItem
        set(value) { viewPager2.currentItem = value }

    var orientation: Int
        get() = viewPager2.orientation
        set(value) { viewPager2.orientation = value }

    fun setPageTransformer(transformer: ViewPager2.PageTransformer?) {
        viewPager2.setPageTransformer(transformer)
    }

    fun registerOnPageChangeCallback(callback: ViewPager2.OnPageChangeCallback) {
        viewPager2.registerOnPageChangeCallback(callback)
    }

    fun unregisterOnPageChangeCallback(callback: ViewPager2.OnPageChangeCallback) {
        viewPager2.unregisterOnPageChangeCallback(callback)
    }

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
                                return true
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
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return viewPager2.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun getCurrentPhotoView(): PhotoView? {
        return try {
            val recyclerView = viewPager2.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            if (recyclerView != null) {
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(viewPager2.currentItem)
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
