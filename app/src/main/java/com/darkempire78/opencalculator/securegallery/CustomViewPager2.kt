package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
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
    private var onDismissCallback: ((Int) -> Unit)? = null
    
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            Log.d("CustomViewPager2", "Gesture down detected at (${e.x}, ${e.y})")
            return true
        }
        
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            Log.d("CustomViewPager2", "Fling detected: velocityX=$velocityX, velocityY=$velocityY")
            if (e1 != null) {
                val currentPhotoView = getCurrentPhotoView()
                if (currentPhotoView != null && currentPhotoView.scale <= 1.1f) {
                    val deltaY = e2.y - e1.y
                    val deltaX = e2.x - e1.x
                    
                    Log.d("CustomViewPager2", "Fling details: deltaY=$deltaY, deltaX=$deltaX, scale=${currentPhotoView.scale}")
                    
                    // Check for downward swipe to dismiss
                    if (deltaY > 150 && abs(deltaY) > abs(deltaX) && velocityY > 800) {
                        Log.d("CustomViewPager2", "Swipe down detected - dismissing")
                        onDismissCallback?.invoke(viewPager2.currentItem)
                        return true
                    }
                }
            }
            return false
        }
    })

    init {
        addView(viewPager2, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    
    fun setOnDismissCallback(callback: (Int) -> Unit) {
        onDismissCallback = callback
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
                // Track gestures but don't intercept yet
                gestureDetector.onTouchEvent(ev)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                // Track potential swipe gestures
                gestureDetector.onTouchEvent(ev)
                
                val currentPhotoView = getCurrentPhotoView()
                if (currentPhotoView != null && currentPhotoView.scale <= 1.1f) {
                    val deltaY = ev.y - initialY
                    val deltaX = ev.x - initialX
                    
                    // Only intercept if this looks like a strong downward swipe
                    if (deltaY > 100 && abs(deltaY) > abs(deltaX) * 2) {
                        Log.d("CustomViewPager2", "Intercepting for potential swipe-down dismiss")
                        return true
                    }
                }
                
                Log.d("CustomViewPager2", "ACTION_MOVE - NOT intercepting")
                return false
            }
        }
        Log.d("CustomViewPager2", "Other action: ${ev.action} - NOT intercepting")
        gestureDetector.onTouchEvent(ev)
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // If we intercepted the event, let our gesture detector handle it
        val gestureHandled = gestureDetector.onTouchEvent(event)
        if (gestureHandled) {
            return true
        }
        // Otherwise let ViewPager2 handle it
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
