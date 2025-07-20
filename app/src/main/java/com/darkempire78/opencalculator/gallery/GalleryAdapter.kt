package com.darkempire78.opencalculator.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.darkempire78.opencalculator.R
import com.darkempire78.opencalculator.gallery.MediaItem
import com.bumptech.glide.Glide

class GalleryAdapter(private var mediaItems: MutableList<MediaItem>) :
    RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    fun sortByName() {
        mediaItems.sortBy { it.file.name }
        notifyDataSetChanged()
    }

    fun sortByDate() {
        mediaItems.sortBy { it.file.lastModified() }
        notifyDataSetChanged()
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        val movedItem = mediaItems.removeAt(fromPosition)
        mediaItems.add(toPosition, movedItem)
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.gallery_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val mediaItem = mediaItems[position]
        when (mediaItem) {
            is MediaItem.Photo -> {
                holder.noteTitle.visibility = View.GONE
                holder.noteContent.visibility = View.GONE
                holder.photoView.visibility = View.VISIBLE
                Glide.with(holder.itemView.context)
                    .load(mediaItem.file)
                    .into(holder.photoView)
            }
            is MediaItem.Note -> {
                holder.photoView.visibility = View.GONE
                holder.noteTitle.visibility = View.VISIBLE
                holder.noteContent.visibility = View.VISIBLE
                holder.noteTitle.text = mediaItem.title
                holder.noteContent.text = mediaItem.content
            }
        }
    }

    override fun getItemCount(): Int {
        return mediaItems.size
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val photoView: ImageView = itemView.findViewById(R.id.photo_view)
        val noteTitle: TextView = itemView.findViewById(R.id.note_title)
        val noteContent: TextView = itemView.findViewById(R.id.note_content)
    }
}
