package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.darkempire78.opencalculator.R
import com.github.chrisbanes.photoview.PhotoView

class SecurePhotoPagerAdapter(
    private val context: Context,
    private val photos: List<SecurePhoto>,
    private val pin: String?,
    private val salt: ByteArray?
) : RecyclerView.Adapter<SecurePhotoPagerAdapter.PhotoViewHolder>() {

    inner class PhotoViewHolder(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView) {
        
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
            
            // Remove any custom touch listener to let PhotoView handle all touch events
            // Swipe-down dismiss is now handled in CustomViewPager2
            
            Log.d("SecurePhotoPagerAdapter", "PhotoView touch listeners configured - PhotoView only")
            
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
                val encryptedData = photo.getEncryptedData()
                if (encryptedData.size < 16) {
                    Log.e("SecurePhotoPagerAdapter", "Encrypted data too small for photo: ${photo.name}")
                    holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
                    return
                }
                
                val iv = encryptedData.copyOfRange(0, 16)
                val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
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
                Log.e("SecurePhotoPagerAdapter", "Primary decryption failed for photo: ${photo.name}", e)
                
                // Try legacy decryption with default salt
                try {
                    Log.d("SecurePhotoPagerAdapter", "Attempting legacy decryption for photo: ${photo.name}")
                    val legacySalt = ByteArray(16) // Empty salt for legacy photos
                    val legacyKey = CryptoUtils.deriveKey(pin, legacySalt)
                    val encryptedData = photo.getEncryptedData()
                    val iv = encryptedData.copyOfRange(0, 16)
                    val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
                    val decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, legacyKey)
                    val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                    
                    if (bitmap != null) {
                        Log.d("SecurePhotoPagerAdapter", "Legacy decryption succeeded for photo: ${photo.name}")
                        holder.photoView.setImageBitmap(bitmap)
                    } else {
                        Log.e("SecurePhotoPagerAdapter", "Legacy decryption produced null bitmap for photo: ${photo.name}")
                        holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                } catch (legacyE: Exception) {
                    Log.e("SecurePhotoPagerAdapter", "Both primary and legacy decryption failed for photo: ${photo.name}", legacyE)
                    holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
        } else {
            Log.e("SecurePhotoPagerAdapter", "Missing pin or salt for decryption")
            holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    override fun getItemCount() = photos.size
}
