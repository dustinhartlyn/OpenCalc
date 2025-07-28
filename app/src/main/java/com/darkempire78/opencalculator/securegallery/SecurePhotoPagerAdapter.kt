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
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Single tap to zoom in/out by 300%
                if (photoView.scale > 1f) {
                    // If zoomed in, zoom out to fit screen
                    photoView.setScale(1f, e.x, e.y, true)
                } else {
                    // If at normal size, zoom in to 300%
                    photoView.setScale(3f, e.x, e.y, true)
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                // Only handle swipe down to dismiss when at normal zoom level
                if (photoView.scale <= 1.1f && e1 != null) {
                    val deltaY = e2.y - e1.y
                    val deltaX = e2.x - e1.x
                    
                    // Check if it's a downward swipe (and not primarily horizontal)
                    if (deltaY > 200 && abs(deltaY) > abs(deltaX) && velocityY > 1000) {
                        onDismiss(adapterPosition)
                        return true
                    }
                }
                return false
            }
        })

        init {
            photoView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false // Return false to allow PhotoView to handle zoom/pan
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
