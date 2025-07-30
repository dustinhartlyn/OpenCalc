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
        
        // Setup swipe down gesture to close media viewer (supports both fast and slow swipes)
        var startY = 0f
        var startX = 0f
        var isDownwardSwipe = false
        
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
                
                // Check for fast downward swipe
                val minSwipeDistance = 80 * resources.displayMetrics.density // Reduced from 100dp
                val minSwipeVelocity = 400 * resources.displayMetrics.density // Reduced from 600dp/s
                
                // Ensure it's more vertical than horizontal (prevents conflicts with horizontal swipes)
                if (deltaY > minSwipeDistance && 
                    velocityY > minSwipeVelocity && 
                    abs(deltaY) > abs(deltaX) * 1.2) { // Reduced ratio from 1.5 to 1.2
                    
                    Log.d("SecureMediaViewer", "Fast swipe down detected - closing media viewer")
                    finish()
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
                
                // Check if this is a significant downward movement
                if (deltaY > 50 * resources.displayMetrics.density && // Minimum 50dp movement
                    abs(deltaY) > abs(deltaX) * 1.2) { // More vertical than horizontal
                    isDownwardSwipe = true
                    Log.d("SecureMediaViewer", "Slow swipe down in progress: deltaY=$deltaY")
                }
                
                return false
            }
        })
        
        // Apply gesture detection with custom touch handling for slow swipes
        mediaViewPager.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    startX = event.x
                    isDownwardSwipe = false
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = event.y - startY
                    val deltaX = event.x - startX
                    
                    // Handle slow swipe completion
                    if (isDownwardSwipe || 
                        (deltaY > 120 * resources.displayMetrics.density && // 120dp minimum for slow swipes
                         abs(deltaY) > abs(deltaX) * 1.2)) {
                        
                        Log.d("SecureMediaViewer", "Slow swipe down completed - closing media viewer")
                        finish()
                        return@setOnTouchListener true
                    }
                }
            }
            
            gestureDetector.onTouchEvent(event)
            false // Don't consume the event, let ViewPager handle normal swipes
        }
        
        // Handle page changes
        mediaViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPosition = position
                Log.d("SecureMediaViewer", "Page changed to position: $position")
                
                // Pause all videos and update the current video holder
                adapter.pauseAllVideos()
                
                // If the new page is a video, set it as current and resume if needed
                val currentMedia = media.getOrNull(position)
                if (currentMedia?.mediaType == MediaType.VIDEO) {
                    // The adapter will handle setting the new current video holder in bindVideo
                    // and resume playback automatically
                }
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
    
    override fun onPause() {
        super.onPause()
        // Pause all videos when activity loses focus
        adapter.pauseAllVideos()
    }
    
    override fun onResume() {
        super.onResume()
        // Resume current video when activity gains focus
        adapter.resumeCurrentVideo()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up any temporary files created by video playback
        adapter.cleanup()
    }
}
