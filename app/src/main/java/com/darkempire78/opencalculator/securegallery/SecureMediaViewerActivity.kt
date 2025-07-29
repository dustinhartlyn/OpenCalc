package com.darkempire78.opencalculator.securegallery

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.darkempire78.opencalculator.R
import java.io.File
import java.io.FileOutputStream

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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secure_photo_viewer) // Reuse existing layout
        
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
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        adapter = SecureMediaPagerAdapter(this, media, galleryPin, gallerySalt)
        viewPager.adapter = adapter
        viewPager.setCurrentItem(currentPosition, false)
        
        // Handle page changes
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPosition = position
                Log.d("SecureMediaViewer", "Page changed to position: $position")
            }
        })
        
        // Set up return behavior - return the current position
        setResult(Activity.RESULT_OK, Intent().putExtra("return_position", currentPosition))
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up any temporary files created by video playback
        adapter.cleanup()
    }
}
