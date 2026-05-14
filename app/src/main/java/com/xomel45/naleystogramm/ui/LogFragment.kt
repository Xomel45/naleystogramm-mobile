package com.xomel45.naleystogramm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LogFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val logText  = view.findViewById<TextView>(R.id.log_text)
        val scroll   = view.findViewById<ScrollView>(R.id.log_scroll)
        val btnClear = view.findViewById<MaterialButton>(R.id.btn_clear_log)

        btnClear.setOnClickListener {
            Logger.clear()
            logText.text = ""
        }

        viewLifecycleOwner.lifecycleScope.launch {
            var lastCount = -1
            while (isActive) {
                val entries = Logger.entries()
                if (entries.size != lastCount) {
                    lastCount = entries.size
                    val text = entries.joinToString("\n") { e ->
                        "${e.level.name} [${e.tag}] ${e.message}"
                    }
                    logText.text = text
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
                delay(1500)
            }
        }
    }
}
