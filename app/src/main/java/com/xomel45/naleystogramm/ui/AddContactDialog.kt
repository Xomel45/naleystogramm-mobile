package com.xomel45.naleystogramm.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.ContactEntity
import com.xomel45.naleystogramm.core.Storage
import kotlinx.coroutines.launch

class AddContactDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_contact, null)

        val layoutId  = view.findViewById<TextInputLayout>(R.id.layout_peer_id)
        val editId    = view.findViewById<TextInputEditText>(R.id.edit_peer_id)
        val editName  = view.findViewById<TextInputEditText>(R.id.edit_display_name)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_cancel)
        val btnAdd    = view.findViewById<MaterialButton>(R.id.btn_add)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        btnCancel.setOnClickListener { dismiss() }

        btnAdd.setOnClickListener {
            val rawId = editId.text?.toString()?.trim() ?: ""
            val name  = editName.text?.toString()?.trim() ?: ""

            if (rawId.isEmpty()) {
                layoutId.error = getString(R.string.add_contact_id_required)
                return@setOnClickListener
            }
            layoutId.error = null

            val (peerId, ip, port) = parseConnectionString(rawId)
            if (peerId == null) {
                layoutId.error = getString(R.string.add_contact_invalid_format)
                return@setOnClickListener
            }

            val displayName = name.ifEmpty { peerId.take(8) }

            lifecycleScope.launch {
                Storage.contacts.upsert(ContactEntity(
                    peerId      = peerId,
                    displayName = displayName,
                    publicKey   = "",
                    address     = if (ip != null && port != null) "$ip:$port" else "",
                    lastSeen    = System.currentTimeMillis()
                ))
                val svc = (requireActivity() as? MainActivity)?.getMessagingService()
                if (ip != null && port != null) {
                    svc?.network?.connectToPeer(peerId, displayName, ip, port)
                }
            }
            dismiss()
        }

        return dialog
    }

    private fun parseConnectionString(raw: String): Triple<String?, String?, Int?> {
        // Format: UUID@IP:Port  OR  just UUID
        return try {
            if ('@' in raw) {
                val (uuid, hostPort) = raw.split("@", limit = 2)
                if (':' in hostPort) {
                    val (ip, portStr) = hostPort.rsplit(":", 2)
                    Triple(uuid.trim(), ip.trim(), portStr.toIntOrNull())
                } else {
                    Triple(uuid.trim(), null, null)
                }
            } else {
                Triple(raw.trim(), null, null)
            }
        } catch (e: Exception) {
            Triple(null, null, null)
        }
    }

    private fun String.rsplit(delimiter: String, limit: Int): List<String> {
        val idx = lastIndexOf(delimiter)
        return if (idx < 0) listOf(this)
        else listOf(substring(0, idx), substring(idx + delimiter.length))
    }
}
