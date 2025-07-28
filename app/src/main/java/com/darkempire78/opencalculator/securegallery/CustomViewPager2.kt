package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.util.AttributeSet
import android.util.Log
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

    fun setCurrentItem(item: Int, smoothScroll: Boolean) {
        viewPager2.setCurrentItem(item, smoothScroll)
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
                Log.d("CustomViewPager2", "ACTION_DOWN at (${ev.x}, ${ev.y}) - NOT intercepting")
                // Never intercept on ACTION_DOWN - always let child views get the touch event first
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                // Temporarily disable all custom interception logic to test PhotoView
                Log.d("CustomViewPager2", "ACTION_MOVE - NOT intercepting (testing mode)")
                return false
            }
        }
        Log.d("CustomViewPager2", "Other action: ${ev.action} - NOT intercepting")
        return false // Default to not intercepting
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return viewPager2.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun getCurrentPhotoView(): PhotoView? {
        return try {
            val recyclerView = viewPager2.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            if (recyclerView != null) {
                val currentItem = viewPager2.currentItem
                Log.d("CustomViewPager2", "Looking for PhotoView at position $currentItem")
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentItem)
                if (viewHolder is SecurePhotoPagerAdapter.PhotoViewHolder) {
                    Log.d("CustomViewPager2", "Found PhotoView with scale: ${viewHolder.photoView.scale}")
                    viewHolder.photoView
                } else {
                    Log.d("CustomViewPager2", "ViewHolder not found or wrong type: $viewHolder")
                    null
                }
            } else {
                Log.d("CustomViewPager2", "RecyclerView not found")
                null
            }
        } catch (e: Exception) {
            Log.e("CustomViewPager2", "Error getting current PhotoView", e)
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
