package com.xomel45.naleystogramm.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.ContactEntity
import com.xomel45.naleystogramm.core.Storage
import kotlinx.coroutines.launch

class IncomingDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val peerId   = arguments?.getString(ARG_PEER_ID) ?: ""
        val peerName = arguments?.getString(ARG_PEER_NAME) ?: peerId
        val peerIp   = arguments?.getString(ARG_PEER_IP) ?: ""

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_incoming, null)
        view.findViewById<TextView>(R.id.caller_name).text = peerName
        view.findViewById<TextView>(R.id.caller_id).text = peerId

        val btnAccept = view.findViewById<MaterialButton>(R.id.btn_accept)
        val btnReject = view.findViewById<MaterialButton>(R.id.btn_reject)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()

        btnAccept.setOnClickListener {
            lifecycleScope.launch {
                Storage.contacts.upsert(ContactEntity(
                    peerId      = peerId,
                    displayName = peerName,
                    publicKey   = "",
                    address     = peerIp,
                    lastSeen    = System.currentTimeMillis()
                ))
            }
            val svc = (requireActivity() as? MainActivity)?.getMessagingService()
            svc?.network?.acceptIncoming(peerId)
            dismiss()
        }

        btnReject.setOnClickListener {
            val svc = (requireActivity() as? MainActivity)?.getMessagingService()
            svc?.network?.rejectIncoming(peerId)
            dismiss()
        }

        return dialog
    }

    companion object {
        private const val ARG_PEER_ID   = "peerId"
        private const val ARG_PEER_NAME = "peerName"
        private const val ARG_PEER_IP   = "peerIp"

        fun newInstance(peerId: String, peerName: String, peerIp: String) =
            IncomingDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PEER_ID,   peerId)
                    putString(ARG_PEER_NAME, peerName)
                    putString(ARG_PEER_IP,   peerIp)
                }
            }
    }
}
