package com.darkempire78.opencalculator.securegallery

import android.app.Dialog
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.darkempire78.opencalculator.R
import androidx.appcompat.widget.PopupMenu

class GalleryActivity : AppCompatActivity() {
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGES_REQUEST && resultCode == RESULT_OK && data != null) {
            val galleryName = intent.getStringExtra("gallery_name") ?: return
            val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
            val pin = TempPinHolder.pin ?: ""
            val salt = gallery.salt
            val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
            val uris = mutableListOf<android.net.Uri>()
            if (data.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    uris.add(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data.data != null) {
                uris.add(data.data!!)
            }
            val encryptedPhotos = mutableListOf<String>()
            val originalPaths = mutableListOf<String>()
            for (uri in uris) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes() ?: continue
                    inputStream.close()
                    if (key != null) {
                        val iv = CryptoUtils.generateIv()
                        val encrypted = CryptoUtils.encrypt(bytes, key, iv)
                        // Store as base64(iv + ciphertext)
                        val combined = iv + encrypted
                        val encoded = android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT)
                        encryptedPhotos.add(encoded)
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
            // Prompt to delete originals
            if (originalPaths.isNotEmpty()) {
                android.app.AlertDialog.Builder(this)
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
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
            // Refresh gallery UI (reload photos)
            recreate()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

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
        findViewById<android.view.View>(android.R.id.content).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        val notes = intent.getSerializableExtra("gallery_notes") as? ArrayList<SecureNote> ?: arrayListOf()
        val photos = intent.getSerializableExtra("gallery_photos") as? ArrayList<String> ?: arrayListOf()

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

        Toast.makeText(this, "Opened $galleryName with ${notes.size} notes and ${photos.size} photos", Toast.LENGTH_SHORT).show()

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
                    intent.putExtra("gallery_notes", ArrayList<SecureNote>())
                    intent.putExtra("gallery_photos", ArrayList<String>())
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
        val menuItems = listOf(
            Pair("Add Pictures", R.id.action_add_pictures),
            Pair("Create Gallery", R.id.action_create_gallery),
            Pair("Rename Gallery", R.id.action_rename_gallery),
            Pair("Delete Gallery", R.id.action_delete_gallery)
        )
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
                }
    // Request code for picking images
    private val PICK_IMAGES_REQUEST = 1001

    // Handler for Add Pictures menu item
    private fun addPicturesToGallery() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        intent.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, PICK_IMAGES_REQUEST)
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
}
