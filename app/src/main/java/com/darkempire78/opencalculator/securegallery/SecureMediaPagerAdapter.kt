package com.darkempire78.opencalculator.securegallery

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.VideoView
import androidx.recyclerview.widget.RecyclerView
import com.darkempire78.opencalculator.R
import com.github.chrisbanes.photoview.PhotoView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference

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
    private val activityRef = WeakReference(context as? Activity)
    
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
                val encryptedData = media.getEncryptedData()
                if (encryptedData.size < 16) {
                    Log.e("SecureMediaPagerAdapter", "Encrypted data too small for photo: ${media.name}")
                    return
                }
                
                val iv = encryptedData.copyOfRange(0, 16)
                val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
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
                    val encryptedData = media.getEncryptedData()
                    val iv = encryptedData.copyOfRange(0, 16)
                    val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
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
            // Show loading indicator
            holder.loadingIndicator.visibility = View.VISIBLE
            holder.videoView.visibility = View.GONE
            
            // Decrypt and prepare video in background thread
            Thread {
                try {
                    Log.d("SecureMediaPagerAdapter", "Starting video decryption in background for: ${media.name}")
                    
                    // Create temporary file for video playback
                    val tempFile = File.createTempFile("secure_video_", ".mp4", context.cacheDir)
                    tempFiles.add(tempFile)
                    
                    if (media.usesExternalStorage()) {
                        // For file-based storage, decrypt file using streaming
                        val encryptedFile = File(media.filePath!!)
                        val inputStream = FileInputStream(encryptedFile)
                        val outputStream = FileOutputStream(tempFile)
                        
                        Log.d("SecureMediaPagerAdapter", "Decrypting video file: ${encryptedFile.length()} bytes")
                        
                        // Read IV from file
                        val iv = ByteArray(16)
                        inputStream.read(iv)
                        
                        // Decrypt the rest using streaming
                        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
                        
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            val decrypted = cipher.update(buffer, 0, bytesRead)
                            if (decrypted != null) {
                                outputStream.write(decrypted)
                            }
                        }
                        
                        val finalData = cipher.doFinal()
                        if (finalData.isNotEmpty()) {
                            outputStream.write(finalData)
                        }
                        
                        inputStream.close()
                        outputStream.close()
                    } else {
                        // For in-memory storage, decrypt normally
                        val encryptedData = media.getEncryptedData()
                        val iv = encryptedData.copyOfRange(0, 16)
                        val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
                        val decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
                        
                        val fos = FileOutputStream(tempFile)
                        fos.write(decryptedBytes)
                        fos.close()
                    }
                    
                    Log.d("SecureMediaPagerAdapter", "Video decryption completed: ${tempFile.length()} bytes")
                    
                    // Force garbage collection to free up memory before video setup
                    System.gc()
                    
                    // Small delay to allow GC to complete
                    Thread.sleep(100)
                    
                    // Log memory usage
                    val runtime = Runtime.getRuntime()
                    val totalMemory = runtime.totalMemory()
                    val freeMemory = runtime.freeMemory()
                    val usedMemory = totalMemory - freeMemory
                    Log.d("SecureMediaPagerAdapter", "Memory after decryption - Used: ${usedMemory / 1024 / 1024}MB, Free: ${freeMemory / 1024 / 1024}MB, Total: ${totalMemory / 1024 / 1024}MB")
                    
                    // Setup video view on main thread with safety checks
                    val activity = activityRef.get()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        activity.runOnUiThread {
                            try {
                                // Double-check activity state in UI thread
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    Log.d("SecureMediaPagerAdapter", "About to setup video view for ${media.name}")
                                    
                                    // Add a small delay to ensure the activity is fully ready
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        try {
                                            if (!activity.isFinishing && !activity.isDestroyed) {
                                                setupVideoView(holder, tempFile, media.name)
                                                Log.d("SecureMediaPagerAdapter", "Video view setup initiated for ${media.name}")
                                            } else {
                                                Log.w("SecureMediaPagerAdapter", "Activity destroyed during delay, skipping video setup for ${media.name}")
                                                holder.loadingIndicator.visibility = View.GONE
                                            }
                                        } catch (e: Exception) {
                                            Log.e("SecureMediaPagerAdapter", "Failed to setup video view (delayed) for ${media.name}", e)
                                            holder.loadingIndicator.visibility = View.GONE
                                        }
                                    }, 100) // 100ms delay
                                    
                                } else {
                                    Log.w("SecureMediaPagerAdapter", "Activity finishing/destroyed, skipping video setup for ${media.name}")
                                    holder.loadingIndicator.visibility = View.GONE
                                }
                            } catch (e: Exception) {
                                Log.e("SecureMediaPagerAdapter", "Failed to setup video view for ${media.name}", e)
                                holder.loadingIndicator.visibility = View.GONE
                            }
                        }
                    } else {
                        Log.w("SecureMediaPagerAdapter", "Activity reference lost, skipping video setup for ${media.name}")
                    }
                    
                } catch (e: Exception) {
                    Log.e("SecureMediaPagerAdapter", "Failed to decrypt/play video: ${media.name}", e)
                    val activity = activityRef.get()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        activity.runOnUiThread {
                            holder.loadingIndicator.visibility = View.GONE
                        }
                    }
                }
            }.start()
        } else {
            Log.w("SecureMediaPagerAdapter", "No decryption key available for video: ${media.name}")
            holder.loadingIndicator.visibility = View.GONE
        }
    }
    
    private fun setupVideoView(holder: VideoViewHolder, tempFile: File, videoName: String) {
        try {
            Log.d("SecureMediaPagerAdapter", "Setting up video view for: $videoName, file exists: ${tempFile.exists()}, file size: ${tempFile.length()}")
            
            // First, let's try to get video information to verify the file is valid
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                Log.d("SecureMediaPagerAdapter", "Video metadata - Width: $width, Height: $height, Duration: $duration, MimeType: $mimeType")
                retriever.release()
            } catch (e: Exception) {
                Log.e("SecureMediaPagerAdapter", "Failed to read video metadata for $videoName", e)
                retriever.release()
                holder.loadingIndicator.visibility = View.GONE
                return
            }
            
            val uri = Uri.fromFile(tempFile)
            Log.d("SecureMediaPagerAdapter", "Created URI: $uri")
            
            // Set video URI first
            holder.videoView.setVideoURI(uri)
            Log.d("SecureMediaPagerAdapter", "Video URI set successfully")
            
            // Set up video listeners
            holder.videoView.setOnPreparedListener { mediaPlayer ->
                Log.d("SecureMediaPagerAdapter", "Video prepared: $videoName")
                
                try {
                    // Hide loading indicator and show video
                    holder.loadingIndicator.visibility = View.GONE
                    holder.videoView.visibility = View.VISIBLE
                    
                    // Set video to loop
                    mediaPlayer.isLooping = true
                    
                    // Start playing automatically
                    holder.videoView.start()
                    Log.d("SecureMediaPagerAdapter", "Video started playing: $videoName")
                } catch (e: Exception) {
                    Log.e("SecureMediaPagerAdapter", "Error in onPrepared for $videoName", e)
                }
            }
            
            holder.videoView.setOnErrorListener { mediaPlayer, what, extra ->
                Log.e("SecureMediaPagerAdapter", "Video error for $videoName: what=$what, extra=$extra")
                holder.loadingIndicator.visibility = View.GONE
                
                // Try to provide more specific error information
                val errorMsg = when (what) {
                    android.media.MediaPlayer.MEDIA_ERROR_IO -> "IO Error"
                    android.media.MediaPlayer.MEDIA_ERROR_MALFORMED -> "Malformed media"
                    android.media.MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "Unsupported media"
                    android.media.MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "Timed out"
                    android.media.MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Server died"
                    android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN -> "Unknown error"
                    else -> "Error code $what"
                }
                Log.e("SecureMediaPagerAdapter", "Video error details: $errorMsg, extra: $extra")
                true
            }
            
            holder.videoView.setOnInfoListener { mediaPlayer, what, extra ->
                val infoMsg = when (what) {
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START -> "Buffering started"
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END -> "Buffering ended"
                    android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> "Video rendering started"
                    else -> "Info: what=$what, extra=$extra"
                }
                Log.d("SecureMediaPagerAdapter", "Video info for $videoName: $infoMsg")
                false
            }
            
            holder.videoView.setOnCompletionListener { mediaPlayer ->
                // This shouldn't be called since we set looping to true
                Log.d("SecureMediaPagerAdapter", "Video completed: $videoName")
            }
            
            Log.d("SecureMediaPagerAdapter", "Video view setup completed for: $videoName")
            
            // Add a shorter timeout to detect if VideoView never calls onPrepared
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (holder.loadingIndicator.visibility == View.VISIBLE) {
                    Log.w("SecureMediaPagerAdapter", "Video preparation timeout for $videoName - onPrepared never called")
                    Log.w("SecureMediaPagerAdapter", "Attempting to force video preparation...")
                    
                    // Try to manually start the video preparation
                    try {
                        holder.videoView.requestFocus()
                        holder.videoView.start()
                    } catch (e: Exception) {
                        Log.e("SecureMediaPagerAdapter", "Failed to force video start", e)
                        holder.loadingIndicator.visibility = View.GONE
                    }
                }
            }, 5000) // Reduced to 5 second timeout
            
        } catch (e: Exception) {
            Log.e("SecureMediaPagerAdapter", "Exception in setupVideoView for $videoName", e)
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
