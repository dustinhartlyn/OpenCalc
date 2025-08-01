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
        Log.d("SecureMediaViewer", "=== ACTIVITY ONCREATE START ===")
        
        try {
            setContentView(R.layout.activity_secure_media_viewer) // Use media viewer layout
            
            // Initialize GalleryManager context
            GalleryManager.setContext(this)
            
            val galleryName = intent.getStringExtra(EXTRA_GALLERY_NAME) ?: ""
            currentPosition = intent.getIntExtra(EXTRA_POSITION, 0)
            val galleryPin = intent.getStringExtra(EXTRA_PIN) ?: ""
            val gallerySalt = intent.getByteArrayExtra(EXTRA_SALT)
            
            Log.d("SecureMediaViewer", "Intent data - Gallery: $galleryName, Position: $currentPosition, PIN: ${galleryPin.isNotEmpty()}, Salt: ${gallerySalt != null}")
            
            // Get gallery data
            val gallery = GalleryManager.getGalleries().find { it.name == galleryName }
            if (gallery == null) {
                Log.e("SecureMediaViewer", "Gallery not found: $galleryName")
                Log.e("SecureMediaViewer", "Available galleries: ${GalleryManager.getGalleries().map { it.name }}")
                finish()
                return
            }
            
            media = gallery.media
            Log.d("SecureMediaViewer", "Gallery loaded - Media count: ${media.size}")
            
            if (media.isEmpty()) {
                Log.w("SecureMediaViewer", "No media items in gallery")
                Toast.makeText(this, "No media items found", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            // Log media details
            media.forEachIndexed { index, item ->
                Log.d("SecureMediaViewer", "  [$index] ${item.name} (${item.mediaType})")
            }
            
            // Ensure position is valid
            if (currentPosition >= media.size) {
                Log.w("SecureMediaViewer", "Position $currentPosition >= media size ${media.size}, adjusting to ${media.size - 1}")
                currentPosition = media.size - 1
            }
            if (currentPosition < 0) {
                Log.w("SecureMediaViewer", "Position $currentPosition < 0, adjusting to 0")
                currentPosition = 0
            }
            
            Log.d("SecureMediaViewer", "Final position: $currentPosition, Media: ${media[currentPosition].name}")
            
            // Setup ViewPager2 with media adapter
            val mediaViewPager = findViewById<ViewPager2>(R.id.mediaViewPager)
            if (mediaViewPager == null) {
                Log.e("SecureMediaViewer", "Failed to find mediaViewPager in layout")
                Toast.makeText(this, "Layout error: ViewPager not found", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            Log.d("SecureMediaViewer", "Creating adapter with ${media.size} media items")
            adapter = SecureMediaPagerAdapter(this, media, galleryPin, gallerySalt)
            mediaViewPager.adapter = adapter
            mediaViewPager.setCurrentItem(currentPosition, false)
            Log.d("SecureMediaViewer", "ViewPager setup complete")
            
            // Handle page changes - setup callbacks while mediaViewPager is in scope
            mediaViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    Log.d("SecureMediaViewer", "=== PAGE SELECTED EVENT ===")
                    Log.d("SecureMediaViewer", "Page changed from $currentPosition to $position")
                    
                    if (position >= 0 && position < media.size) {
                        val newMedia = media[position]
                        Log.d("SecureMediaViewer", "New media: ${newMedia.name} (${newMedia.mediaType})")
                    } else {
                        Log.e("SecureMediaViewer", "Invalid position $position for media size ${media.size}")
                        return
                    }
                    
                    currentPosition = position
                    
                    // Use the new onPageChanged method that handles video transitions properly
                    try {
                        adapter.onPageChanged(position)
                        Log.d("SecureMediaViewer", "Adapter page change notification sent successfully")
                    } catch (e: Exception) {
                        Log.e("SecureMediaViewer", "Error in adapter page change", e)
                    }
                }
                
                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    val stateString = when (state) {
                        ViewPager2.SCROLL_STATE_IDLE -> "IDLE"
                        ViewPager2.SCROLL_STATE_DRAGGING -> "DRAGGING"
                        ViewPager2.SCROLL_STATE_SETTLING -> "SETTLING"
                        else -> "UNKNOWN($state)"
                    }
                    Log.d("SecureMediaViewer", "Page scroll state changed to: $stateString")
                    
                    // When user starts swiping, immediately stop all videos to prevent conflicts
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                        Log.d("SecureMediaViewer", "User started swiping, stopping all videos")
                        try {
                            adapter.pauseAllVideos()
                            Log.d("SecureMediaViewer", "Videos paused successfully")
                        } catch (e: Exception) {
                            Log.e("SecureMediaViewer", "Error pausing videos during drag", e)
                        }
                    }
                }
            })
            
            // Continue with rest of onCreate...
        } catch (e: Exception) {
            Log.e("SecureMediaViewer", "Critical error in onCreate", e)
            Toast.makeText(this, "Error opening media viewer: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
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
        Log.d("SecureMediaViewer", "=== ACTIVITY ONPAUSE ===")
        // Pause all videos when activity loses focus
        try {
            adapter.pauseAllVideos()
            Log.d("SecureMediaViewer", "Videos paused successfully")
        } catch (e: Exception) {
            Log.e("SecureMediaViewer", "Error pausing videos in onPause", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d("SecureMediaViewer", "=== ACTIVITY ONRESUME ===")
        // Videos will automatically resume when they come into view
        // No explicit resume needed with the current implementation
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d("SecureMediaViewer", "=== ACTIVITY ONDESTROY ===")
        // Clean up any temporary files created by video playback
        try {
            adapter.cleanup()
            Log.d("SecureMediaViewer", "Adapter cleanup completed")
        } catch (e: Exception) {
            Log.e("SecureMediaViewer", "Error during adapter cleanup", e)
        }
    }
    
    override fun onStop() {
        super.onStop()
        Log.d("SecureMediaViewer", "=== ACTIVITY ONSTOP ===")
    }
    
    override fun onStart() {
        super.onStart()
        Log.d("SecureMediaViewer", "=== ACTIVITY ONSTART ===")
    }
}
