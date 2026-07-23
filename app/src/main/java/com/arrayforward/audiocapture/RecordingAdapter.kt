package com.arrayforward.audiocapture

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class RecordingAdapter(
    private val onPlay: (File) -> Unit,
    private val onRename: (File) -> Unit,
    private val onTrim: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.VH>() {

    private val items = mutableListOf<File>()

    fun submitList(files: List<File>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    fun isEmpty() = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.fileName)
        private val info: TextView = view.findViewById(R.id.fileInfo)
        private val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)
        private val btnRename: ImageButton = view.findViewById(R.id.btnRename)
        private val btnTrim: ImageButton = view.findViewById(R.id.btnTrim)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)

        fun bind(file: File) {
            name.text = file.name
            info.text = FileUtils.formatSize(file.length())
            btnPlay.setOnClickListener { onPlay(file) }
            btnRename.setOnClickListener { onRename(file) }
            btnTrim.setOnClickListener { onTrim(file) }
            btnDelete.setOnClickListener { onDelete(file) }
        }
    }
}
