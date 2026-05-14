package com.xomel45.naleystogramm.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.FileTransferEntity
import com.xomel45.naleystogramm.core.Storage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class FileTransferFragment : Fragment() {

    private lateinit var adapter: TransfersAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_file_transfer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar   = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val recycler  = view.findViewById<RecyclerView>(R.id.transfers_list)
        val emptyView = view.findViewById<TextView>(R.id.empty_view)

        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        adapter = TransfersAdapter { transfer -> onActionClick(transfer) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            Storage.fileTransfers.all().collectLatest { transfers ->
                adapter.submitList(transfers)
                emptyView.visibility = if (transfers.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility  = if (transfers.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun onActionClick(transfer: FileTransferEntity) {
        when (transfer.status) {
            "done" -> openFile(transfer)
            else   -> cancelTransfer(transfer)
        }
    }

    private fun openFile(transfer: FileTransferEntity) {
        if (transfer.localPath.isEmpty()) return
        val file = File(transfer.localPath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, requireContext().contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    private fun cancelTransfer(transfer: FileTransferEntity) {
        val svc = (requireActivity() as? MainActivity)?.getMessagingService() ?: return
        svc.fileTransfer.cancelTransfer(transfer.id.toString())
    }
}
