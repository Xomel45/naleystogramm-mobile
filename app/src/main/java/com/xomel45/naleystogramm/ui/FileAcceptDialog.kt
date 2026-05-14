package com.xomel45.naleystogramm.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.xomel45.naleystogramm.R

class FileAcceptDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val fromId   = arguments?.getString(ARG_FROM_ID) ?: ""
        val offerId  = arguments?.getString(ARG_OFFER_ID) ?: ""
        val fileName = arguments?.getString(ARG_FILE_NAME) ?: ""
        val fileSize = arguments?.getLong(ARG_FILE_SIZE) ?: 0L
        val fromName = arguments?.getString(ARG_FROM_NAME) ?: fromId

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_file_accept, null)
        view.findViewById<TextView>(R.id.file_name).text = fileName
        view.findViewById<TextView>(R.id.file_size).text = formatSize(fileSize)
        view.findViewById<TextView>(R.id.sender_name).text =
            getString(R.string.file_accept_from, fromName)

        val btnAccept = view.findViewById<MaterialButton>(R.id.btn_accept)
        val btnReject = view.findViewById<MaterialButton>(R.id.btn_reject)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()

        btnAccept.setOnClickListener {
            val svc = (requireActivity() as? MainActivity)?.getMessagingService()
            svc?.fileTransfer?.acceptOffer(fromId, offerId)
            dismiss()
        }
        btnReject.setOnClickListener {
            val svc = (requireActivity() as? MainActivity)?.getMessagingService()
            svc?.fileTransfer?.rejectOffer(fromId, offerId)
            dismiss()
        }

        return dialog
    }

    private fun formatSize(bytes: Long) = when {
        bytes < 1024        -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else                -> "%.1f MB".format(bytes / (1024.0 * 1024))
    }

    companion object {
        private const val ARG_FROM_ID   = "fromId"
        private const val ARG_OFFER_ID  = "offerId"
        private const val ARG_FILE_NAME = "fileName"
        private const val ARG_FILE_SIZE = "fileSize"
        private const val ARG_FROM_NAME = "fromName"

        fun newInstance(fromId: String, offerId: String, fileName: String, fileSize: Long, fromName: String) =
            FileAcceptDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_FROM_ID,   fromId)
                    putString(ARG_OFFER_ID,  offerId)
                    putString(ARG_FILE_NAME, fileName)
                    putLong(ARG_FILE_SIZE,   fileSize)
                    putString(ARG_FROM_NAME, fromName)
                }
            }
    }
}
