package com.xomel45.naleystogramm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.DemoMode
import com.xomel45.naleystogramm.core.Identity
import com.xomel45.naleystogramm.core.SessionManager
import com.xomel45.naleystogramm.core.UpdateChecker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val editName    = view.findViewById<TextInputEditText>(R.id.edit_display_name)
        val txtMyId     = view.findViewById<TextView>(R.id.txt_my_id)
        val btnSaveName = view.findViewById<MaterialButton>(R.id.btn_save_name)
        val editPort    = view.findViewById<TextInputEditText>(R.id.edit_port)
        val switchUpnp  = view.findViewById<SwitchMaterial>(R.id.switch_upnp)
        val btnSaveNet  = view.findViewById<MaterialButton>(R.id.btn_save_network)
        val switchLog   = view.findViewById<SwitchMaterial>(R.id.switch_verbose)
        val switchDemo  = view.findViewById<SwitchMaterial>(R.id.switch_demo)
        val txtVersion  = view.findViewById<TextView>(R.id.txt_version)
        val btnUpdate   = view.findViewById<MaterialButton>(R.id.btn_check_update)

        txtMyId.text = Identity.uuid

        viewLifecycleOwner.lifecycleScope.launch {
            editName.setText(Identity.displayName)
            val port = SessionManager.port.first()
            editPort.setText(port.toString())
            switchLog.isChecked  = SessionManager.verboseLogging.first()
            switchDemo.isChecked = SessionManager.demoMode.first()
        }

        txtVersion.text = getString(R.string.settings_version, "0.7.3")

        btnSaveName.setOnClickListener {
            val name = editName.text?.toString()?.trim() ?: return@setOnClickListener
            if (name.isNotEmpty()) {
                Identity.setDisplayName(name)
            }
        }

        btnSaveNet.setOnClickListener {
            val portStr = editPort.text?.toString()?.trim() ?: return@setOnClickListener
            val port = portStr.toIntOrNull() ?: return@setOnClickListener
            if (port in 1024..65535) {
                viewLifecycleOwner.lifecycleScope.launch {
                    SessionManager.setPort(port)
                }
            }
        }

        switchLog.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch { SessionManager.setVerboseLogging(checked) }
        }

        switchDemo.setOnCheckedChangeListener { _, checked ->
            viewLifecycleOwner.lifecycleScope.launch {
                SessionManager.setDemoMode(checked)
                DemoMode.enabled = checked
            }
        }

        btnUpdate.setOnClickListener {
            val checker = UpdateChecker()
            checker.onUpdateAvailable = { info ->
                activity?.runOnUiThread {
                    txtVersion.text = getString(R.string.settings_update_available, info.version)
                }
            }
            checker.onNoUpdate = { _ ->
                activity?.runOnUiThread {
                    txtVersion.text = getString(R.string.settings_up_to_date)
                }
            }
            checker.checkNow()
        }
    }
}
