package com.xomel45.naleystogramm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.FileTransferEntity

class TransfersAdapter(
    private val onAction: (FileTransferEntity) -> Unit
) : ListAdapter<FileTransferEntity, TransfersAdapter.VH>(DIFF) {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val fileName: TextView    = v.findViewById(R.id.file_name)
        val peer: TextView        = v.findViewById(R.id.transfer_peer)
        val progress: ProgressBar = v.findViewById(R.id.transfer_progress)
        val stats: TextView       = v.findViewById(R.id.transfer_stats)
        val btnAction: ImageButton = v.findViewById(R.id.btn_action)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_transfer, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = getItem(position)
        h.fileName.text = t.filename
        val dir = if (t.isOutgoing)
            h.itemView.context.getString(R.string.transfer_to, t.peerId)
        else
            h.itemView.context.getString(R.string.transfer_from, t.peerId)
        h.peer.text = dir

        val ctx = h.itemView.context
        when (t.status) {
            "active" -> {
                h.progress.visibility = View.VISIBLE
                h.stats.text = ctx.getString(R.string.transfer_in_progress)
                h.btnAction.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            }
            "done" -> {
                h.progress.progress = 100
                h.stats.text = formatSize(t.size)
                h.btnAction.setImageResource(android.R.drawable.ic_menu_view)
            }
            "failed" -> {
                h.progress.visibility = View.INVISIBLE
                h.stats.text = ctx.getString(R.string.transfer_failed)
                h.btnAction.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            }
            "cancelled" -> {
                h.progress.visibility = View.INVISIBLE
                h.stats.text = ctx.getString(R.string.transfer_cancelled)
                h.btnAction.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            }
            else -> {
                h.stats.text = formatSize(t.size)
                h.btnAction.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            }
        }
        h.btnAction.setOnClickListener { onAction(t) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else                -> "%.1f MB".format(bytes / (1024.0 * 1024))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FileTransferEntity>() {
            override fun areItemsTheSame(a: FileTransferEntity, b: FileTransferEntity) = a.id == b.id
            override fun areContentsTheSame(a: FileTransferEntity, b: FileTransferEntity) = a == b
        }
    }
}
