package com.xomel45.naleystogramm.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.ContactEntity

private val AVATAR_COLORS = intArrayOf(
    0xFF6200EE.toInt(), 0xFF3700B3.toInt(), 0xFF018786.toInt(),
    0xFFB00020.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(),
    0xFF6A1B9A.toInt(), 0xFF4E342E.toInt()
)

data class ContactRow(
    val entity: ContactEntity,
    val lastMessage: String = "",
    val latencyMs: Long = -1,
    val unreadCount: Int = 0
)

class ContactsAdapter(
    private val onClick: (ContactEntity) -> Unit,
    private val onLongClick: (ContactEntity) -> Unit
) : ListAdapter<ContactRow, ContactsAdapter.VH>(DIFF) {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: TextView    = itemView.findViewById(R.id.avatar)
        val statusDot: View     = itemView.findViewById(R.id.status_dot)
        val name: TextView      = itemView.findViewById(R.id.contact_name)
        val lastMsg: TextView   = itemView.findViewById(R.id.last_message)
        val latency: TextView   = itemView.findViewById(R.id.latency)
        val badge: TextView     = itemView.findViewById(R.id.unread_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = getItem(position)
        val c   = row.entity

        val initial = c.displayName.firstOrNull()?.uppercaseChar() ?: '?'
        h.avatar.text = initial.toString()
        h.avatar.background.mutate().also {
            (it as? android.graphics.drawable.GradientDrawable)
                ?.setColor(AVATAR_COLORS[c.peerId.hashCode().and(0x7FFFFFFF) % AVATAR_COLORS.size])
        }

        val ctx = h.itemView.context
        val onlineColor  = ctx.getColor(R.color.color_online)
        val offlineColor = ctx.getColor(R.color.color_offline)
        (h.statusDot.background.mutate() as? android.graphics.drawable.GradientDrawable)
            ?.setColor(if (c.isOnline) onlineColor else offlineColor)

        h.name.text    = c.displayName
        h.lastMsg.text = row.lastMessage

        h.latency.text = when {
            !c.isOnline       -> ""
            row.latencyMs < 0 -> "? ms"
            else              -> "${row.latencyMs} ms"
        }

        if (row.unreadCount > 0) {
            h.badge.visibility = View.VISIBLE
            h.badge.text = if (row.unreadCount > 99) "99+" else row.unreadCount.toString()
        } else {
            h.badge.visibility = View.GONE
        }

        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.setOnLongClickListener { onLongClick(c); true }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ContactRow>() {
            override fun areItemsTheSame(a: ContactRow, b: ContactRow) = a.entity.peerId == b.entity.peerId
            override fun areContentsTheSame(a: ContactRow, b: ContactRow) = a == b
        }
    }
}
