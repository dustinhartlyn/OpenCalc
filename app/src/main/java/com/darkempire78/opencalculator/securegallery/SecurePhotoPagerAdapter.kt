package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.LayoutInflater
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
        
        init {
            // Enable all PhotoView features
            photoView.isZoomable = true
            photoView.maximumScale = 5.0f
            photoView.minimumScale = 0.5f
            photoView.mediumScale = 2.0f
            
            // Use PhotoView's built-in tap listener for zoom functionality
            photoView.setOnPhotoTapListener { view, x, y ->
                // Single tap to zoom in/out by 300%
                Log.d("SecurePhotoPagerAdapter", "Photo tapped at scale: ${photoView.scale}")
                if (photoView.scale > 1.1f) {
                    // If zoomed in, zoom out to fit screen
                    photoView.setScale(1f, true)
                } else {
                    // If at normal size, zoom in to 300%
                    photoView.setScale(3f, true)
                }
            }
            
            // Use PhotoView's built-in view tap listener as backup
            photoView.setOnViewTapListener { view, x, y ->
                // Alternative tap handling if photo tap doesn't work
                Log.d("SecurePhotoPagerAdapter", "View tapped at scale: ${photoView.scale}")
                if (photoView.scale > 1.1f) {
                    photoView.setScale(1f, true)
                } else {
                    photoView.setScale(3f, true)
                }
            }
            
            // Use PhotoView's matrix change listener to detect when user returns to normal scale
            photoView.setOnMatrixChangeListener { matrix ->
                Log.d("SecurePhotoPagerAdapter", "Matrix changed, current scale: ${photoView.scale}")
            }
            
            // Use PhotoView's built-in single fling listener for swipe down to dismiss
            photoView.setOnSingleFlingListener { e1, e2, velocityX, velocityY ->
                Log.d("SecurePhotoPagerAdapter", "Fling detected: scale=${photoView.scale}, velocityY=$velocityY, velocityX=$velocityX")
                // Only handle swipe down to dismiss when at normal zoom level
                if (photoView.scale <= 1.1f) {
                    val deltaY = e2.y - e1.y
                    val deltaX = e2.x - e1.x
                    
                    // Check if it's a downward swipe (and not primarily horizontal)
                    if (deltaY > 200 && abs(deltaY) > abs(deltaX) && velocityY > 1000) {
                        Log.d("SecurePhotoPagerAdapter", "Dismissing photo viewer")
                        onDismiss(adapterPosition)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
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
                    holder.photoView.setImageBitmap(bitmap)
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
