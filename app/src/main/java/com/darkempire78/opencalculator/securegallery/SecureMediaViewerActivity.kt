package com.darkempire78.opencalculator.securegallery

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.darkempire78.opencalculator.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class SecureMediaViewerActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_GALLERY_NAME = "gallery_name"
        const val EXTRA_POSITION = "position"
        const val EXTRA_PIN = "pin"
        const val EXTRA_SALT = "salt"
    }
    
    private lateinit var adapter: SecureMediaPagerAdapter
    private var media: List<SecureMedia> = listOf()
    private var currentPosition = 0
    private lateinit var gestureDetector: GestureDetector
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secure_media_viewer) // Use media viewer layout
        
        // Initialize GalleryManager context
        GalleryManager.setContext(this)
        
        val galleryName = intent.getStringExtra(EXTRA_GALLERY_NAME) ?: ""
        currentPosition = intent.getIntExtra(EXTRA_POSITION, 0)
        val galleryPin = intent.getStringExtra(EXTRA_PIN) ?: ""
        val gallerySalt = intent.getByteArrayExtra(EXTRA_SALT)
        
        Log.d("SecureMediaViewer", "Opening media viewer for gallery: $galleryName, position: $currentPosition")
        
        // Get gallery data
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName }
        if (gallery == null) {
            Log.e("SecureMediaViewer", "Gallery not found: $galleryName")
            finish()
            return
        }
        
        media = gallery.media
        
        if (media.isEmpty()) {
            Log.w("SecureMediaViewer", "No media items in gallery")
            Toast.makeText(this, "No media items found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Ensure position is valid
        if (currentPosition >= media.size) {
            currentPosition = media.size - 1
        }
        if (currentPosition < 0) {
            currentPosition = 0
        }
        
        Log.d("SecureMediaViewer", "Media count: ${media.size}, starting position: $currentPosition")
        
        // Setup ViewPager2 with media adapter
        val mediaViewPager = findViewById<ViewPager2>(R.id.mediaViewPager)
        if (mediaViewPager == null) {
            Log.e("SecureMediaViewer", "Failed to find mediaViewPager in layout")
            Toast.makeText(this, "Layout error: ViewPager not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        adapter = SecureMediaPagerAdapter(this, media, galleryPin, gallerySalt)
        mediaViewPager.adapter = adapter
        mediaViewPager.setCurrentItem(currentPosition, false)
        
        // Add a touch interceptor view on top of ViewPager2 to ensure gesture detection
        val touchInterceptor = findViewById<View>(android.R.id.content)
        touchInterceptor.setOnTouchListener { _, event ->
            // Process touch events for swipe detection before ViewPager2 handles them
            window.decorView.findViewById<View>(android.R.id.content).dispatchTouchEvent(event)
            false // Let other views handle the event normally
        }
        
        // Setup enhanced swipe down gesture to close media viewer (supports both fast and slow swipes)
        var startY = 0f
        var startX = 0f
        var isDownwardSwipe = false
        var swipeStartTime = 0L
        
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                
                val deltaY = e2.y - e1.y
                val deltaX = e2.x - e1.x
                
                // Check for fast downward swipe with more lenient thresholds
                val minSwipeDistance = 60 * resources.displayMetrics.density // Further reduced from 80dp
                val minSwipeVelocity = 300 * resources.displayMetrics.density // Further reduced from 400dp/s
                
                // Ensure it's more vertical than horizontal (prevents conflicts with horizontal swipes)
                if (deltaY > minSwipeDistance && 
                    velocityY > minSwipeVelocity && 
                    abs(deltaY) > abs(deltaX)) { // Simplified: just needs to be more vertical
                    
                    Log.d("SecureMediaViewer", "Fast swipe down detected (deltaY=$deltaY, velocity=$velocityY) - closing media viewer")
                    finish()
                    overridePendingTransition(0, R.anim.slide_down_out)
                    return true
                }
                return false
            }
            
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Handle slower swipes through scroll detection
                if (e1 == null) return false
                
                val deltaY = e2.y - e1.y
                val deltaX = e2.x - e1.x
                
                // Check if this is a significant downward movement with more lenient requirements
                if (deltaY > 40 * resources.displayMetrics.density && // Reduced from 50dp
                    abs(deltaY) > abs(deltaX)) { // Simplified: just needs to be more vertical
                    isDownwardSwipe = true
                    Log.d("SecureMediaViewer", "Slow swipe down in progress: deltaY=$deltaY, ratio=${abs(deltaY)/abs(deltaX)}")
                }
                
                return false
            }
        })
        
        // Override dispatchTouchEvent to intercept touches before ViewPager2 processes them
        window.decorView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    startX = event.x
                    isDownwardSwipe = false
                    swipeStartTime = System.currentTimeMillis()
                    Log.d("SecureMediaViewer", "Touch down at: x=$startX, y=$startY")
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.y - startY
                    val deltaX = event.x - startX
                    
                    // Track progressive downward movement
                    if (deltaY > 30 * resources.displayMetrics.density && 
                        abs(deltaY) > abs(deltaX)) {
                        isDownwardSwipe = true
                        Log.d("SecureMediaViewer", "Tracking downward swipe: deltaY=$deltaY")
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = event.y - startY
                    val deltaX = event.x - startX
                    val swipeDuration = System.currentTimeMillis() - swipeStartTime
                    
                    Log.d("SecureMediaViewer", "Touch up: deltaY=$deltaY, deltaX=$deltaX, duration=${swipeDuration}ms, isDownwardSwipe=$isDownwardSwipe")
                    
                    // Handle slow swipe completion with very lenient requirements
                    if (isDownwardSwipe || 
                        (deltaY > 80 * resources.displayMetrics.density && // Reduced from 120dp
                         abs(deltaY) > abs(deltaX))) { // Just needs to be more vertical
                        
                        Log.d("SecureMediaViewer", "Slow swipe down completed - closing media viewer")
                        finish()
                        overridePendingTransition(0, R.anim.slide_down_out)
                        return@setOnTouchListener true
                    }
                }
            }
            
            // Always pass touch events to the gesture detector
            gestureDetector.onTouchEvent(event)
            false // Don't consume the event, let other views handle it
        }
        
        // Handle page changes
        mediaViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPosition = position
                Log.d("SecureMediaViewer", "Page changed to position: $position")
                
                // Use the new onPageChanged method that handles video transitions properly
                adapter.onPageChanged(position)
            }
            
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                // When user starts swiping, immediately stop all videos to prevent conflicts
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    adapter.pauseAllVideos()
                    Log.d("SecureMediaViewer", "User started swiping, stopping all videos")
                }
            }
        })
        
        // Set up return behavior - return the current position
        setResult(Activity.RESULT_OK, Intent().putExtra("return_position", currentPosition))
    }
    
    // Override dispatchTouchEvent to ensure our gesture detection happens at the activity level
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Process all touch events through our gesture detection system first
        ev?.let { event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d("SecureMediaViewer", "Activity received touch down at: x=${event.x}, y=${event.y}")
                }
                MotionEvent.ACTION_UP -> {
                    Log.d("SecureMediaViewer", "Activity received touch up at: x=${event.x}, y=${event.y}")
                }
                MotionEvent.ACTION_MOVE -> {
                    Log.d("SecureMediaViewer", "Activity received touch move to: x=${event.x}, y=${event.y}")
                }
            }
            
            // Always process through our gesture detector
            gestureDetector.onTouchEvent(event)
            
            // Also process through our window decorView touch listener
            window.decorView.findViewById<View>(android.R.id.content)?.let { content ->
                content.onTouchEvent(event)
            }
        }
        
        // Always pass the event to the normal dispatch chain
        return super.dispatchTouchEvent(ev)
    }
    
    override fun onPause() {
        super.onPause()
        // Pause all videos when activity loses focus
        adapter.pauseAllVideos()
    }
    
    override fun onResume() {
        super.onResume()
        // Videos will automatically resume when they come into view
        // No explicit resume needed with the current implementation
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up any temporary files created by video playback
        adapter.cleanup()
    }
}
