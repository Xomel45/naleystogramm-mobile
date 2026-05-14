package com.xomel45.naleystogramm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.Logger

class ShellFragment : Fragment() {

    private lateinit var sessionId: String
    private lateinit var peerId: String

    private lateinit var terminalOutput: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var editCommand: EditText
    private lateinit var otpView: LinearLayout
    private lateinit var inputRow: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_shell, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionId = arguments?.getString("sessionId") ?: ""
        peerId    = arguments?.getString("peerId") ?: ""

        val toolbar    = view.findViewById<MaterialToolbar>(R.id.toolbar)
        otpView        = view.findViewById(R.id.otp_view)
        val editOtp    = view.findViewById<TextInputEditText>(R.id.edit_otp)
        val btnSubmit  = view.findViewById<MaterialButton>(R.id.btn_submit_otp)
        terminalScroll = view.findViewById(R.id.terminal_scroll)
        terminalOutput = view.findViewById(R.id.terminal_output)
        inputRow       = view.findViewById(R.id.input_row)
        editCommand    = view.findViewById(R.id.edit_command)
        val btnSend    = view.findViewById<ImageButton>(R.id.btn_send_cmd)

        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        toolbar.title = getString(R.string.shell_title)

        val svc = (requireActivity() as? MainActivity)?.getMessagingService()

        if (svc != null) {
            svc.remoteShell.onShellStarted = { sid ->
                if (sid == sessionId) activity?.runOnUiThread { showTerminal() }
            }
            svc.remoteShell.onDataReceived = { sid, data ->
                if (sid == sessionId) appendOutput(String(data))
            }
            svc.remoteShell.onSessionEnded = { sid, reason ->
                if (sid == sessionId) appendOutput("\n[Session ended: $reason]\n")
            }
            svc.remoteShell.onShellRejected = { sid, reason ->
                if (sid == sessionId) {
                    activity?.runOnUiThread {
                        appendOutput("\n[Rejected: $reason]\n")
                        editOtp.isEnabled = false
                        btnSubmit.isEnabled = false
                    }
                }
            }
        }

        btnSubmit.setOnClickListener {
            val otp = editOtp.text?.toString()?.trim() ?: return@setOnClickListener
            if (otp.length != 6) return@setOnClickListener
            svc?.remoteShell?.respondToChallenge(sessionId, otp)
            editOtp.setText("")
            btnSubmit.isEnabled = false
        }

        editCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand(svc)
                true
            } else false
        }

        btnSend.setOnClickListener { sendCommand(svc) }

        if (sessionId.isEmpty()) {
            svc?.remoteShell?.requestShell(peerId)
        }
    }

    private fun sendCommand(svc: com.xomel45.naleystogramm.service.MessagingService?) {
        val cmd = editCommand.text?.toString() ?: return
        if (cmd.isBlank()) return
        editCommand.setText("")
        appendOutput("$ $cmd\n")
        svc?.remoteShell?.sendInput(sessionId, cmd.toByteArray())
    }

    private fun showTerminal() {
        otpView.visibility        = View.GONE
        terminalScroll.visibility = View.VISIBLE
        inputRow.visibility       = View.VISIBLE
    }

    private fun appendOutput(text: String) {
        activity?.runOnUiThread {
            terminalOutput.append(text)
            terminalScroll.post { terminalScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroyView() {
        val svc = (requireActivity() as? MainActivity)?.getMessagingService()
        svc?.remoteShell?.apply {
            onShellStarted = null
            onDataReceived = null
            onSessionEnded = null
            onShellRejected = null
        }
        super.onDestroyView()
    }
}
