package com.xomel45.naleystogramm.ui

import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val VIEW_OUT = 0
private const val VIEW_IN  = 1

class MessagesAdapter : ListAdapter<MessageEntity, MessagesAdapter.VH>(DIFF) {

    private var mediaPlayer: MediaPlayer? = null
    private var playingId: Long = -1

    abstract inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract val msgText: TextView
        abstract val voiceRow: View
        abstract val btnPlay: ImageButton
        abstract val seekBar: SeekBar
        abstract val voiceDuration: TextView
        abstract val timeView: TextView
    }

    inner class OutVH(v: View) : VH(v) {
        override val msgText      = v.findViewById<TextView>(R.id.message_text)
        override val voiceRow     = v.findViewById<View>(R.id.voice_row)
        override val btnPlay      = v.findViewById<ImageButton>(R.id.btn_play_voice)
        override val seekBar      = v.findViewById<SeekBar>(R.id.voice_seekbar)
        override val voiceDuration = v.findViewById<TextView>(R.id.voice_duration)
        override val timeView     = v.findViewById<TextView>(R.id.message_time)
        val statusView: TextView  = v.findViewById(R.id.message_status)
    }

    inner class InVH(v: View) : VH(v) {
        override val msgText      = v.findViewById<TextView>(R.id.message_text)
        override val voiceRow     = v.findViewById<View>(R.id.voice_row)
        override val btnPlay      = v.findViewById<ImageButton>(R.id.btn_play_voice)
        override val seekBar      = v.findViewById<SeekBar>(R.id.voice_seekbar)
        override val voiceDuration = v.findViewById<TextView>(R.id.voice_duration)
        override val timeView     = v.findViewById<TextView>(R.id.message_time)
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position).isOutgoing) VIEW_OUT else VIEW_IN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_OUT)
            OutVH(inf.inflate(R.layout.item_message_out, parent, false))
        else
            InVH(inf.inflate(R.layout.item_message_in, parent, false))
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val msg = getItem(position)
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        h.timeView.text = fmt.format(Date(msg.timestamp))

        if (h is OutVH) {
            h.statusView.text = if (msg.isRead) "✓✓" else "✓"
        }

        when (msg.type) {
            "audio" -> {
                h.voiceRow.visibility = View.VISIBLE
                h.msgText.visibility  = View.GONE
                h.voiceDuration.text  = "0:00"
                h.btnPlay.setOnClickListener { togglePlay(msg, h) }
            }
            else -> {
                h.voiceRow.visibility = View.GONE
                h.msgText.visibility  = View.VISIBLE
                h.msgText.text = msg.content
            }
        }
    }

    private fun togglePlay(msg: MessageEntity, h: VH) {
        if (playingId == msg.id) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            playingId = -1
            h.btnPlay.setImageResource(android.R.drawable.ic_media_play)
            return
        }
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        playingId = msg.id
        h.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(msg.content)
                prepare()
                start()
                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                    playingId = -1
                    h.btnPlay.setImageResource(android.R.drawable.ic_media_play)
                }
            }
        } catch (e: Exception) {
            mediaPlayer = null
            playingId = -1
            h.btnPlay.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        playingId = -1
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MessageEntity>() {
            override fun areItemsTheSame(a: MessageEntity, b: MessageEntity) = a.id == b.id
            override fun areContentsTheSame(a: MessageEntity, b: MessageEntity) = a == b
        }
    }
}
