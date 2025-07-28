package com.darkempire78.opencalculator.securegallery

import android.app.Dialog
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.darkempire78.opencalculator.R
import androidx.appcompat.widget.PopupMenu

class GalleryActivity : AppCompatActivity() {
    companion object {
        const val PHOTO_VIEWER_REQUEST = 1002
    }

    private var deleteDialog: android.app.AlertDialog? = null
    private var isDeleteMode = false
    private val selectedPhotosForDeletion = mutableSetOf<Int>()
    private var photosAdapter: RecyclerView.Adapter<PhotoThumbnailViewHolder>? = null
    
    // Activity result launcher for photo viewer
    private val photoViewerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val returnPosition = result.data?.getIntExtra("return_position", -1) ?: -1
            if (returnPosition >= 0) {
                // Optionally scroll to the position in the gallery that was being viewed
                val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
                photosRecyclerView.scrollToPosition(returnPosition)
            }
        }
    }

    // Activity result launcher for adding multiple pictures
    private val addPicturesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            handleSelectedImages(uris)
        }
    }

    // Handler for Add Pictures menu item
    private fun addPicturesToGallery() {
        addPicturesLauncher.launch("image/*")
    }

    private fun handleSelectedImages(uris: List<android.net.Uri>) {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
        
        val encryptedPhotos = mutableListOf<SecurePhoto>()
        val originalPaths = mutableListOf<String>()
        
        for (uri in uris) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: continue
                inputStream.close()
                if (key != null) {
                    val (iv, encrypted) = CryptoUtils.encrypt(bytes, key)
                    val combined = iv + encrypted
                    encryptedPhotos.add(SecurePhoto(encryptedData = combined, name = "photo_${System.currentTimeMillis()}.jpg", date = System.currentTimeMillis()))
                    // Try to get original file path for deletion prompt
                    val path = uri.path ?: ""
                    originalPaths.add(path)
                }
            } catch (e: Exception) {
                android.util.Log.e("SecureGallery", "Failed to encrypt photo: $uri", e)
            }
        }
        
        // Add encrypted photos to gallery
        gallery.photos.addAll(encryptedPhotos)
        // Save gallery data (this will persist the added photos)
        GalleryManager.setContext(this)
        GalleryManager.saveGalleries()
        
        // Prompt to delete originals
        if (originalPaths.isNotEmpty()) {
            deleteDialog = android.app.AlertDialog.Builder(this)
                .setTitle("Delete Original Photos?")
                .setMessage("Do you want to delete the original photos from your device?")
                .setPositiveButton("Delete") { _, _ ->
                    for (uri in uris) {
                        try {
                            contentResolver.delete(uri, null, null)
                        } catch (e: Exception) {
                            android.util.Log.e("SecureGallery", "Failed to delete original photo: $uri", e)
                        }
                    }
                    Toast.makeText(this, "Original photos deleted", Toast.LENGTH_SHORT).show()
                    deleteDialog = null
                    // Refresh gallery UI (reload photos)
                    recreate()
                }
                .setNegativeButton("Keep") { _, _ ->
                    deleteDialog = null
                    // Refresh gallery UI (reload photos)
                    recreate()
                }
                .create()
            deleteDialog?.show()
        } else {
            // Refresh gallery UI (reload photos) if no deletion prompt
            recreate()
        }
    }

    private fun enterDeleteMode() {
        isDeleteMode = true
        selectedPhotosForDeletion.clear()
        
        // Update the action bar
        supportActionBar?.title = "Select photos to delete"
        
        // Refresh the adapter to show checkboxes
        photosAdapter?.notifyDataSetChanged()
    }

    private fun exitDeleteMode() {
        isDeleteMode = false
        selectedPhotosForDeletion.clear()
        
        // Restore the original action bar title
        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        supportActionBar?.title = galleryName
        
        // Refresh the adapter to hide checkboxes
        photosAdapter?.notifyDataSetChanged()
    }

    private fun deleteSelectedPhotos() {
        if (selectedPhotosForDeletion.isEmpty()) return
        
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        
        // Sort indices in descending order to avoid index shifting issues
        val sortedIndices = selectedPhotosForDeletion.sortedDescending()
        
        for (index in sortedIndices) {
            if (index < gallery.photos.size) {
                gallery.photos.removeAt(index)
            }
        }
        
        // Save gallery data
        GalleryManager.saveGalleries()
        
        // Exit delete mode and refresh UI
        exitDeleteMode()
        recreate()
    }

    override fun onDestroy() {
        deleteDialog?.dismiss()
        deleteDialog = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        // Initialize GalleryManager context
        GalleryManager.setContext(this)

        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        
        // Get gallery data from GalleryManager instead of Intent
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName }
        val notes = gallery?.notes ?: mutableListOf()
        val photos = gallery?.photos ?: mutableListOf()

        findViewById<android.widget.TextView>(R.id.galleryTitle).text = galleryName

        // Decrypt notes using pin and salt
        val pin = TempPinHolder.pin ?: ""
        val salt = GalleryManager.getGalleries().find { it.name == galleryName }?.salt
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null

        val decryptedNotes = notes.map { note ->
            var title = "(Encrypted)"
            var body = "(Encrypted)"
            if (key != null) {
                try {
                    val ivTitle = note.encryptedTitle.copyOfRange(0, 16)
                    val ctTitle = note.encryptedTitle.copyOfRange(16, note.encryptedTitle.size)
                    val ivBody = note.encryptedBody.copyOfRange(0, 16)
                    val ctBody = note.encryptedBody.copyOfRange(16, note.encryptedBody.size)
                    title = String(CryptoUtils.decrypt(ivTitle, ctTitle, key), Charsets.UTF_8)
                    body = String(CryptoUtils.decrypt(ivBody, ctBody, key), Charsets.UTF_8)
                } catch (e: Exception) {
                    title = "(Decryption failed)"
                    body = "(Decryption failed)"
                }
            }
            Pair(title, body)
        }

        val notesRecyclerView = findViewById<RecyclerView>(R.id.notesRecyclerView)
        notesRecyclerView.layoutManager = LinearLayoutManager(this)
        notesRecyclerView.adapter = object : RecyclerView.Adapter<NoteViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): NoteViewHolder {
                val v = android.view.LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
                return NoteViewHolder(v)
            }
            override fun getItemCount() = decryptedNotes.size
            override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
                holder.bind(decryptedNotes[position].first, decryptedNotes[position].second)
            }
        }

        // Setup photos RecyclerView with two-column grid
        val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
        photosRecyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        
        // Decrypt and display photos as thumbnails
        val decryptedPhotos = photos.mapNotNull { photo ->
            if (key != null) {
                try {
                    val iv = photo.encryptedData.copyOfRange(0, 16)
                    val ct = photo.encryptedData.copyOfRange(16, photo.encryptedData.size)
                    val decryptedBytes = CryptoUtils.decrypt(iv, ct, key)
                    android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Failed to decrypt photo: ${photo.name}", e)
                    null
                }
            } else {
                null
            }
        }

        photosAdapter = object : RecyclerView.Adapter<PhotoThumbnailViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoThumbnailViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_thumbnail, parent, false)
                return PhotoThumbnailViewHolder(v)
            }
            override fun getItemCount() = decryptedPhotos.size
            override fun onBindViewHolder(holder: PhotoThumbnailViewHolder, position: Int) {
                holder.bind(decryptedPhotos[position], isDeleteMode, selectedPhotosForDeletion.contains(position))
                
                if (isDeleteMode) {
                    // In delete mode, clicking toggles selection
                    holder.itemView.setOnClickListener {
                        if (selectedPhotosForDeletion.contains(position)) {
                            selectedPhotosForDeletion.remove(position)
                        } else {
                            selectedPhotosForDeletion.add(position)
                        }
                        notifyItemChanged(position)
                    }
                    
                    // No long press needed in delete mode
                    holder.itemView.setOnLongClickListener(null)
                } else {
                    // Normal mode - click to view photo
                    holder.itemView.setOnClickListener {
                        val intent = android.content.Intent(this@GalleryActivity, SecurePhotoViewerActivity::class.java)
                        intent.putExtra("gallery_name", galleryName)
                        intent.putExtra("position", position)
                        intent.putExtra("pin", pin)
                        intent.putExtra("salt", salt)
                        photoViewerLauncher.launch(intent)
                    }
                    
                    // Long press to enter delete mode
                    holder.itemView.setOnLongClickListener {
                        enterDeleteMode()
                        selectedPhotosForDeletion.add(position)
                        notifyDataSetChanged()
                        true
                    }
                }
            }
        }
        
        photosRecyclerView.adapter = photosAdapter

        // Removed toast: Opened $galleryName with ... notes and ... photos

        // Enable swipe-to-go-back gesture
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null) {
                    val deltaX = e2.x - e1.x
                    if (deltaX > 300 && Math.abs(deltaX) > Math.abs(e2.y - e1.y)) {
                        finish()
                        return true
                    }
                }
                return false
            }
        })

        findViewById<android.view.View>(R.id.galleryTitle).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // Setup hamburger menu
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.galleryToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_menu_black_background)
        toolbar.setNavigationOnClickListener {
            showCustomGalleryMenu()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.gallery_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        return when (item.itemId) {
            R.id.action_create_gallery -> {
                showCreateGalleryDialog()
                true
            }
            R.id.action_rename_gallery -> {
                showRenameGalleryDialog(galleryName)
                true
            }
            R.id.action_delete_gallery -> {
                showDeleteGalleryDialog(galleryName)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Dialog for creating a gallery
    private fun showCreateGalleryDialog() {
        val pinInput = android.widget.EditText(this)
        pinInput.hint = "Enter PIN"
        val nameInput = android.widget.EditText(this)
        nameInput.hint = "Enter Gallery Name"
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.addView(pinInput)
        layout.addView(nameInput)
        android.app.AlertDialog.Builder(this)
            .setTitle("Create Gallery")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val pin = pinInput.text.toString()
                val name = nameInput.text.toString()
                val result = com.darkempire78.opencalculator.securegallery.GalleryManager.createGallery(pin, name)
                android.util.Log.d("SecureGallery", "CreateGalleryDialog: pin=$pin name=$name result=$result")
                if (result) {
                    Toast.makeText(this, "Gallery created", Toast.LENGTH_SHORT).show()
                    // Close current and open new gallery
                    val intent = intent
                    intent.putExtra("gallery_name", name)
                    finish()
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Failed to create gallery", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Dialog for renaming a gallery
    private fun showRenameGalleryDialog(oldName: String) {
        val nameInput = android.widget.EditText(this)
        nameInput.hint = "Enter New Gallery Name"
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename Gallery")
            .setView(nameInput)
            .setPositiveButton("Rename") { _, _ ->
                val newName = nameInput.text.toString()
                val gallery = com.darkempire78.opencalculator.securegallery.GalleryManager.getGalleries().find { it.name == oldName }
                val result = if (gallery != null) com.darkempire78.opencalculator.securegallery.GalleryManager.renameGallery(gallery.id, newName) else false
                android.util.Log.d("SecureGallery", "RenameGalleryDialog: oldName=$oldName newName=$newName result=$result")
                Toast.makeText(this, if (result) "Gallery renamed" else "Failed to rename gallery", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Dialog for deleting a gallery
    private fun showDeleteGalleryDialog(name: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete Gallery")
            .setMessage("Are you sure you want to delete $name?")
            .setPositiveButton("Delete") { _, _ ->
                val gallery = com.darkempire78.opencalculator.securegallery.GalleryManager.getGalleries().find { it.name == name }
                val result = if (gallery != null) com.darkempire78.opencalculator.securegallery.GalleryManager.deleteGallery(gallery.id) else false
                android.util.Log.d("SecureGallery", "DeleteGalleryDialog: name=$name result=$result")
                if (result) {
                    Toast.makeText(this, "Gallery deleted", Toast.LENGTH_SHORT).show()
                    finish() // Close gallery and return to calculator
                } else {
                    Toast.makeText(this, "Failed to delete gallery", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomGalleryMenu() {
        val dialog = Dialog(this)
        
        val menuItems = if (isDeleteMode) {
            // Delete mode menu
            listOf(
                Pair("Delete Selected", R.id.action_delete_selected),
                Pair("Cancel", R.id.action_cancel_delete)
            )
        } else {
            // Normal mode menu
            listOf(
                Pair("Add Pictures", R.id.action_add_pictures),
                Pair("Create Gallery", R.id.action_create_gallery),
                Pair("Rename Gallery", R.id.action_rename_gallery),
                Pair("Delete Gallery", R.id.action_delete_gallery)
            )
        }
        
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setBackgroundColor(android.graphics.Color.WHITE)
        
        for ((title, id) in menuItems) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.custom_popup_menu_item, container, false)
            val textView = itemView.findViewById<TextView>(R.id.menuItemText)
            textView.text = title
            textView.setOnClickListener {
                val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
                when (id) {
                    R.id.action_add_pictures -> addPicturesToGallery()
                    R.id.action_create_gallery -> showCreateGalleryDialog()
                    R.id.action_rename_gallery -> showRenameGalleryDialog(galleryName)
                    R.id.action_delete_gallery -> showDeleteGalleryDialog(galleryName)
                    R.id.action_delete_selected -> deleteSelectedPhotos()
                    R.id.action_cancel_delete -> exitDeleteMode()
                }
                dialog.dismiss()
            }
            container.addView(itemView)
        }
        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    class NoteViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        fun bind(title: String, body: String) {
            val titleView = itemView.findViewById<android.widget.TextView>(android.R.id.text1)
            val bodyView = itemView.findViewById<android.widget.TextView>(android.R.id.text2)
            titleView.text = title
            bodyView.text = body
        }
    }
    
    class PhotoThumbnailViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        fun bind(bitmap: android.graphics.Bitmap?, isDeleteMode: Boolean = false, isSelected: Boolean = false) {
            val imageView = itemView.findViewById<android.widget.ImageView>(R.id.photoThumbnail)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            
            // Handle selection overlay
            if (isDeleteMode) {
                // Add a semi-transparent overlay for delete mode
                if (isSelected) {
                    itemView.alpha = 0.6f
                    itemView.setBackgroundColor(0x880000FF.toInt()) // Semi-transparent blue
                } else {
                    itemView.alpha = 1.0f
                    itemView.setBackgroundColor(0x00000000.toInt()) // Transparent
                }
            } else {
                // Normal mode - remove any overlays
                itemView.alpha = 1.0f
                itemView.setBackgroundColor(0x00000000.toInt()) // Transparent
            }
        }
    }
}
