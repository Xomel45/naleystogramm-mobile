package com.xomel45.naleystogramm.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.Storage
import kotlinx.coroutines.launch

class ContactProfileDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val peerId = arguments?.getString(ARG_PEER_ID) ?: ""

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_contact_profile, null)

        val avatarTv  = view.findViewById<TextView>(R.id.avatar)
        val nameTv    = view.findViewById<TextView>(R.id.contact_name)
        val statusTv  = view.findViewById<TextView>(R.id.contact_status)
        val peerIdTv  = view.findViewById<TextView>(R.id.peer_id)
        val latencyTv = view.findViewById<TextView>(R.id.latency)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btn_delete)
        val btnChat   = view.findViewById<MaterialButton>(R.id.btn_chat)

        peerIdTv.text = peerId

        lifecycleScope.launch {
            val contact = Storage.contacts.byId(peerId)
            if (contact != null) {
                val initial = contact.displayName.firstOrNull()?.uppercaseChar() ?: '?'
                avatarTv.text  = initial.toString()
                nameTv.text    = contact.displayName
                statusTv.text  = if (contact.isOnline)
                    getString(R.string.status_online) else getString(R.string.status_offline)
            }
            val svc = (requireActivity() as? MainActivity)?.getMessagingService()
            val latency = svc?.network?.getLatency(peerId) ?: -1L
            latencyTv.text = if (latency >= 0) "$latency ms"
                             else getString(R.string.profile_latency_unknown)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        btnDelete.setOnClickListener {
            lifecycleScope.launch {
                Storage.contacts.delete(peerId)
            }
            dismiss()
        }

        btnChat.setOnClickListener {
            dismiss()
            val bundle = Bundle().apply {
                putString("peerId", peerId)
                putString("peerName", nameTv.text.toString())
            }
            parentFragment?.findNavController()?.navigate(R.id.action_contacts_to_chat, bundle)
        }

        return dialog
    }

    companion object {
        private const val ARG_PEER_ID = "peerId"

        fun newInstance(peerId: String) = ContactProfileDialog().apply {
            arguments = Bundle().apply { putString(ARG_PEER_ID, peerId) }
        }
    }
}
