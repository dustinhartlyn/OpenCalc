package com.darkempire78.opencalculator.securegallery

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.darkempire78.opencalculator.R

class GalleryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        val notes = intent.getSerializableExtra("gallery_notes") as? ArrayList<SecureNote> ?: arrayListOf()
        val photos = intent.getSerializableExtra("gallery_photos") as? ArrayList<String> ?: arrayListOf()

        // TODO: Setup RecyclerViews for notes and photos
        Toast.makeText(this, "Opened $galleryName with ${notes.size} notes and ${photos.size} photos", Toast.LENGTH_SHORT).show()
    }
}
