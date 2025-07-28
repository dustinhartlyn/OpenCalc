package com.darkempire78.opencalculator.securegallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.VideoView
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import java.io.File
import java.io.FileOutputStream

class SecureMediaPagerAdapter(
    private val context: Context,
    private val mediaList: List<SecureMedia>,
    private val pin: String,
    private val salt: ByteArray?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    companion object {
        private const val VIEW_TYPE_PHOTO = 1
        private const val VIEW_TYPE_VIDEO = 2
    }
    
    private val tempFiles = mutableListOf<File>()
    private val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
    
    override fun getItemViewType(position: Int): Int {
        return when (mediaList[position].mediaType) {
            MediaType.PHOTO -> VIEW_TYPE_PHOTO
            MediaType.VIDEO -> VIEW_TYPE_VIDEO
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PHOTO -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_view, parent, false)
                PhotoViewHolder(view)
            }
            VIEW_TYPE_VIDEO -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video_view, parent, false)
                VideoViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }
    
    override fun getItemCount(): Int = mediaList.size
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val media = mediaList[position]
        when (holder) {
            is PhotoViewHolder -> bindPhoto(holder, media)
            is VideoViewHolder -> bindVideo(holder, media)
        }
    }
    
    private fun bindPhoto(holder: PhotoViewHolder, media: SecureMedia) {
        Log.d("SecureMediaPagerAdapter", "Loading photo: ${media.name}")
        
        if (key != null) {
            try {
                if (media.encryptedData.size < 16) {
                    Log.e("SecureMediaPagerAdapter", "Encrypted data too small for photo: ${media.name}")
                    return
                }
                
                val iv = media.encryptedData.copyOfRange(0, 16)
                val ciphertext = media.encryptedData.copyOfRange(16, media.encryptedData.size)
                val decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
                val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                
                if (bitmap != null) {
                    Log.d("SecureMediaPagerAdapter", "Successfully loaded bitmap for photo: ${media.name}, size: ${bitmap.width}x${bitmap.height}")
                    holder.photoView.setImageBitmap(bitmap)
                } else {
                    Log.e("SecureMediaPagerAdapter", "Failed to decode bitmap for photo: ${media.name}")
                    holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } catch (e: Exception) {
                Log.e("SecureMediaPagerAdapter", "Failed to decrypt photo: ${media.name}", e)
                // Try legacy decryption with default salt
                try {
                    val legacySalt = ByteArray(16)
                    val legacyKey = CryptoUtils.deriveKey(pin, legacySalt)
                    val iv = media.encryptedData.copyOfRange(0, 16)
                    val ciphertext = media.encryptedData.copyOfRange(16, media.encryptedData.size)
                    val decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, legacyKey)
                    val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                    
                    if (bitmap != null) {
                        Log.d("SecureMediaPagerAdapter", "Legacy decryption succeeded for photo: ${media.name}")
                        holder.photoView.setImageBitmap(bitmap)
                    } else {
                        holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                } catch (legacyE: Exception) {
                    Log.e("SecureMediaPagerAdapter", "Both primary and legacy decryption failed for photo: ${media.name}", legacyE)
                    holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
        } else {
            Log.w("SecureMediaPagerAdapter", "No decryption key available for photo: ${media.name}")
            holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }
    
    private fun bindVideo(holder: VideoViewHolder, media: SecureMedia) {
        Log.d("SecureMediaPagerAdapter", "Loading video: ${media.name}")
        
        if (key != null) {
            try {
                // Show loading indicator
                holder.loadingIndicator.visibility = View.VISIBLE
                holder.videoView.visibility = View.GONE
                
                // Decrypt video data
                val iv = media.encryptedData.copyOfRange(0, 16)
                val ciphertext = media.encryptedData.copyOfRange(16, media.encryptedData.size)
                val decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
                
                // Create temporary file for video playback
                val tempFile = File.createTempFile("secure_video_", ".mp4", context.cacheDir)
                tempFiles.add(tempFile)
                
                val fos = FileOutputStream(tempFile)
                fos.write(decryptedBytes)
                fos.close()
                
                // Setup video view
                val uri = Uri.fromFile(tempFile)
                holder.videoView.setVideoURI(uri)
                
                // Set up video listeners
                holder.videoView.setOnPreparedListener { mediaPlayer ->
                    Log.d("SecureMediaPagerAdapter", "Video prepared: ${media.name}")
                    
                    // Hide loading indicator and show video
                    holder.loadingIndicator.visibility = View.GONE
                    holder.videoView.visibility = View.VISIBLE
                    
                    // Set video to loop
                    mediaPlayer.isLooping = true
                    
                    // Start playing automatically
                    holder.videoView.start()
                }
                
                holder.videoView.setOnErrorListener { mediaPlayer, what, extra ->
                    Log.e("SecureMediaPagerAdapter", "Video error for ${media.name}: what=$what, extra=$extra")
                    holder.loadingIndicator.visibility = View.GONE
                    true
                }
                
                holder.videoView.setOnCompletionListener { mediaPlayer ->
                    // This shouldn't be called since we set looping to true
                    Log.d("SecureMediaPagerAdapter", "Video completed: ${media.name}")
                }
                
            } catch (e: Exception) {
                Log.e("SecureMediaPagerAdapter", "Failed to decrypt/play video: ${media.name}", e)
                holder.loadingIndicator.visibility = View.GONE
            }
        } else {
            Log.w("SecureMediaPagerAdapter", "No decryption key available for video: ${media.name}")
            holder.loadingIndicator.visibility = View.GONE
        }
    }
    
    fun cleanup() {
        // Clean up temporary video files
        for (file in tempFiles) {
            try {
                file.delete()
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Failed to delete temp file: ${file.name}", e)
            }
        }
        tempFiles.clear()
    }
    
    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val photoView: PhotoView = itemView.findViewById(R.id.photoView)
        
        init {
            // Configure PhotoView for zoom functionality
            photoView.isZoomable = true
            photoView.maximumScale = 5.0f
            photoView.mediumScale = 2.5f
            photoView.minimumScale = 1.0f
            
            // Set up tap-to-zoom functionality
            photoView.setOnPhotoTapListener { view, x, y ->
                Log.d("SecureMediaPagerAdapter", "Photo tapped at scale: ${photoView.scale}, coordinates: ($x, $y)")
                if (photoView.scale > 1.5f) {
                    Log.d("SecureMediaPagerAdapter", "Zooming out to 1x")
                    photoView.setScale(1.0f, x, y, true)
                } else {
                    Log.d("SecureMediaPagerAdapter", "Zooming in to 3x")
                    photoView.setScale(3.0f, x, y, true)
                }
            }
        }
    }
    
    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val videoView: VideoView = itemView.findViewById(R.id.videoView)
        val loadingIndicator: ProgressBar = itemView.findViewById(R.id.loadingIndicator)
    }
}
