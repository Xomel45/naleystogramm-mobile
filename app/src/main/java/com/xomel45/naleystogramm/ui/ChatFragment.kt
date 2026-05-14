package com.xomel45.naleystogramm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.AudioRecorder
import com.xomel45.naleystogramm.core.Storage
import com.xomel45.naleystogramm.service.MessagingService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private lateinit var peerId: String
    private lateinit var peerName: String

    private lateinit var adapter: MessagesAdapter
    private var audioRecorder: AudioRecorder? = null
    private var isRecording = false

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { sendFile(it) }
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        peerId   = arguments?.getString("peerId") ?: ""
        peerName = arguments?.getString("peerName") ?: peerId

        val toolbar   = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val statusTv  = view.findViewById<TextView>(R.id.peer_status)
        val recycler  = view.findViewById<RecyclerView>(R.id.messages_list)
        val inputEt   = view.findViewById<TextInputEditText>(R.id.input_message)
        val btnSend   = view.findViewById<ImageButton>(R.id.btn_send)
        val btnVoice  = view.findViewById<ImageButton>(R.id.btn_voice)
        val btnAttach = view.findViewById<ImageButton>(R.id.btn_attach)

        toolbar.title = peerName
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        adapter = MessagesAdapter()
        recycler.layoutManager = LinearLayoutManager(requireContext()).also { it.stackFromEnd = true }
        recycler.adapter = adapter

        inputEt.addTextChangedListener { text ->
            val hasText = !text.isNullOrBlank()
            btnSend.visibility  = if (hasText) View.VISIBLE else View.GONE
            btnVoice.visibility = if (hasText) View.GONE else View.VISIBLE
        }

        btnSend.setOnClickListener {
            val text = inputEt.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isEmpty()) return@setOnClickListener
            inputEt.setText("")
            sendText(text)
        }

        btnVoice.setOnLongClickListener {
            checkAndStartRecording()
            true
        }
        btnVoice.setOnClickListener {
            if (isRecording) stopRecording()
        }

        btnAttach.setOnClickListener {
            pickFile.launch("*/*")
        }

        observeMessages(recycler, statusTv)

        viewLifecycleOwner.lifecycleScope.launch {
            Storage.messages.markRead(peerId)
        }
    }

    private fun observeMessages(recycler: RecyclerView, statusTv: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            Storage.contacts.byId(peerId)?.let { c ->
                statusTv.text = if (c.isOnline)
                    getString(R.string.status_online)
                else
                    getString(R.string.status_offline)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            Storage.messages.forPeer(peerId).collectLatest { msgs ->
                adapter.submitList(msgs)
                if (msgs.isNotEmpty()) recycler.scrollToPosition(msgs.size - 1)
            }
        }
    }

    private fun sendText(text: String) {
        val svc = (requireActivity() as? MainActivity)?.getMessagingService() ?: return
        svc.sendMessage(peerId, text)
    }

    private fun sendFile(uri: Uri) {
        val ctx = requireContext()
        val path = uri.path ?: return
        val svc = (requireActivity() as? MainActivity)?.getMessagingService() ?: return
        svc.sendFile(peerId, path)
    }

    private fun checkAndStartRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        audioRecorder = AudioRecorder(requireContext()).apply {
            onRecorded = { filePath, durationMs ->
                val svc = (activity as? MainActivity)?.getMessagingService() ?: return@apply
                svc.sendFile(peerId, filePath, durationMs)
            }
        }
        audioRecorder?.start()
        isRecording = true
    }

    private fun stopRecording() {
        audioRecorder?.stop()
        audioRecorder = null
        isRecording = false
    }

    override fun onDestroyView() {
        adapter.releasePlayer()
        stopRecording()
        super.onDestroyView()
    }
}
