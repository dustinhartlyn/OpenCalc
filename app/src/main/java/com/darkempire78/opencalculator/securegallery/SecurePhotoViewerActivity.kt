package com.darkempire78.opencalculator.securegallery

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.darkempire78.opencalculator.R

class SecurePhotoViewerActivity : AppCompatActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: SecurePhotoPagerAdapter
    private var originalPhotos: ArrayList<SecurePhoto> = arrayListOf()
    private var startPosition: Int = 0
    private var galleryPin: String? = null
    private var gallerySalt: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Hide system UI for immersive full-screen experience
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        
        // Hide the action bar
        supportActionBar?.hide()
        
        // Hide navigation bar and status bar
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        
        setContentView(R.layout.activity_secure_photo_viewer)

        // Get data from intent
        @Suppress("DEPRECATION")
        originalPhotos = intent.getSerializableExtra("photos") as? ArrayList<SecurePhoto> ?: arrayListOf()
        startPosition = intent.getIntExtra("position", 0)
        galleryPin = intent.getStringExtra("pin")
        gallerySalt = intent.getByteArrayExtra("salt")

        viewPager = findViewById(R.id.photoViewPager)
        adapter = SecurePhotoPagerAdapter(this, originalPhotos, galleryPin, gallerySalt) { position ->
            // Callback when swipe down to dismiss
            setResult(RESULT_OK, intent.putExtra("return_position", position))
            finish()
        }
        
        viewPager.adapter = adapter
        viewPager.setCurrentItem(startPosition, false)
    }

    override fun onBackPressed() {
        // Return the current position when back button is pressed
        setResult(RESULT_OK, intent.putExtra("return_position", viewPager.currentItem))
        super.onBackPressed()
    }
}
