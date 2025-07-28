package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.darkempire78.opencalculator.R
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.abs

class SecurePhotoPagerAdapter(
    private val context: Context,
    private val photos: List<SecurePhoto>,
    private val pin: String?,
    private val salt: ByteArray?,
    private val onDismiss: (Int) -> Unit
) : RecyclerView.Adapter<SecurePhotoPagerAdapter.PhotoViewHolder>() {

    inner class PhotoViewHolder(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView) {
        
        private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && photoView.scale <= 1.1f) {
                    val deltaY = e2.y - e1.y
                    val deltaX = e2.x - e1.x
                    
                    // Check for downward swipe to dismiss
                    if (deltaY > 150 && abs(deltaY) > abs(deltaX) && velocityY > 800) {
                        Log.d("SecurePhotoPagerAdapter", "Swipe down detected - dismissing")
                        onDismiss(adapterPosition)
                        return true
                    }
                }
                return false
            }
        })
        
        init {
            // Enable all PhotoView features
            photoView.isZoomable = true
            photoView.maximumScale = 5.0f
            photoView.minimumScale = 0.5f
            photoView.mediumScale = 2.0f
            
            Log.d("SecurePhotoPagerAdapter", "PhotoView configured: zoomable=${photoView.isZoomable}, maxScale=${photoView.maximumScale}")
            
            // Use PhotoView's built-in tap listener for zoom functionality
            photoView.setOnPhotoTapListener { view, x, y ->
                Log.d("SecurePhotoPagerAdapter", "Photo tapped at scale: ${photoView.scale}, coordinates: ($x, $y)")
                if (photoView.scale > 1.1f) {
                    Log.d("SecurePhotoPagerAdapter", "Zooming out to 1x")
                    photoView.setScale(1f, true)
                } else {
                    Log.d("SecurePhotoPagerAdapter", "Zooming in to 3x")
                    photoView.setScale(3f, true)
                }
            }
            
            // Use PhotoView's built-in view tap listener as backup
            photoView.setOnViewTapListener { view, x, y ->
                Log.d("SecurePhotoPagerAdapter", "View tapped at scale: ${photoView.scale}, coordinates: ($x, $y)")
                if (photoView.scale > 1.1f) {
                    Log.d("SecurePhotoPagerAdapter", "Zooming out to 1x (view tap)")
                    photoView.setScale(1f, true)
                } else {
                    Log.d("SecurePhotoPagerAdapter", "Zooming in to 3x (view tap)")
                    photoView.setScale(3f, true)
                }
            }
            
            // Temporarily disable custom touch listener to test PhotoView
            /*
            // Simplified touch handling - only for swipe down to dismiss
            // Let PhotoView handle all other gestures naturally
            photoView.setOnTouchListener { v, event ->
                // Only track gestures for dismiss functionality
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        // Let gesture detector track for dismiss, but don't consume the event
                        gestureDetector.onTouchEvent(event)
                    }
                }
                // Always return false to let PhotoView handle all gestures
                false
            }
            */
            
            Log.d("SecurePhotoPagerAdapter", "PhotoView touch listeners configured, testing without custom touch listener")
            
            // Use PhotoView's matrix change listener to detect scale changes
            photoView.setOnMatrixChangeListener { matrix ->
                Log.d("SecurePhotoPagerAdapter", "Matrix changed, current scale: ${photoView.scale}")
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val photoView = LayoutInflater.from(context)
            .inflate(R.layout.item_photo_view, parent, false) as PhotoView
        return PhotoViewHolder(photoView)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = photos[position]
        
        // Decrypt and load image
        if (pin != null && salt != null) {
            try {
                val key = CryptoUtils.deriveKey(pin, salt)
                
                // Ensure we have enough data for IV and ciphertext
                if (photo.encryptedData.size < 16) {
                    Log.e("SecurePhotoPagerAdapter", "Encrypted data too small for photo: ${photo.name}")
                    holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
                    return
                }
                
                val iv = photo.encryptedData.copyOfRange(0, 16)
                val ciphertext = photo.encryptedData.copyOfRange(16, photo.encryptedData.size)
                val decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
                val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                
                if (bitmap != null) {
                    Log.d("SecurePhotoPagerAdapter", "Successfully loaded bitmap for photo: ${photo.name}, size: ${bitmap.width}x${bitmap.height}")
                    holder.photoView.setImageBitmap(bitmap)
                    Log.d("SecurePhotoPagerAdapter", "PhotoView image set, isZoomable: ${holder.photoView.isZoomable}")
                } else {
                    Log.e("SecurePhotoPagerAdapter", "Failed to decode bitmap for photo: ${photo.name}")
                    holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } catch (e: Exception) {
                Log.e("SecurePhotoPagerAdapter", "Failed to decrypt photo: ${photo.name}", e)
                holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        } else {
            Log.e("SecurePhotoPagerAdapter", "Missing pin or salt for decryption")
            holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    override fun getItemCount() = photos.size
}
