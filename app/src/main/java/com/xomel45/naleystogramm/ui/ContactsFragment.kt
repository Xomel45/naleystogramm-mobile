package com.xomel45.naleystogramm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.ContactEntity
import com.xomel45.naleystogramm.core.DemoMode
import com.xomel45.naleystogramm.core.Storage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ContactsFragment : Fragment() {

    private lateinit var adapter: ContactsAdapter
    private var allRows = listOf<ContactRow>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_contacts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler     = view.findViewById<RecyclerView>(R.id.contacts_list)
        val emptyView    = view.findViewById<TextView>(R.id.empty_view)
        val fab          = view.findViewById<FloatingActionButton>(R.id.fab_add)
        val searchField  = view.findViewById<TextInputEditText>(R.id.search_field)

        adapter = ContactsAdapter(
            onClick      = { contact -> openChat(contact) },
            onLongClick  = { contact -> showProfile(contact) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        fab.setOnClickListener {
            AddContactDialog().show(childFragmentManager, "add_contact")
        }

        searchField.addTextChangedListener { text ->
            filterContacts(text?.toString() ?: "")
        }

        observeContacts(recycler, emptyView)
    }

    private fun observeContacts(recycler: RecyclerView, emptyView: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            Storage.contacts.all().collectLatest { contacts ->
                val rows = contacts.map { c ->
                    val lastMsg = Storage.messages.lastFor(c.peerId)
                    val name    = if (DemoMode.enabled) DemoMode.maskDisplayName(c.displayName)
                                  else c.displayName
                    ContactRow(
                        entity      = c.copy(displayName = name),
                        lastMessage = lastMsg?.content?.take(60) ?: ""
                    )
                }
                allRows = rows
                val q = view?.findViewById<TextInputEditText>(R.id.search_field)?.text?.toString() ?: ""
                filterContacts(q)

                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility  = if (rows.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun filterContacts(query: String) {
        val filtered = if (query.isBlank()) allRows
        else allRows.filter {
            it.entity.displayName.contains(query, ignoreCase = true) ||
            it.entity.peerId.contains(query, ignoreCase = true)
        }
        adapter.submitList(filtered)
    }

    private fun openChat(contact: ContactEntity) {
        val bundle = Bundle().apply {
            putString("peerId", contact.peerId)
            putString("peerName", contact.displayName)
        }
        findNavController().navigate(R.id.action_contacts_to_chat, bundle)
    }

    private fun showProfile(contact: ContactEntity) {
        ContactProfileDialog.newInstance(contact.peerId)
            .show(childFragmentManager, "profile")
    }
}
