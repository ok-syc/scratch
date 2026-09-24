package com.relay.ssh

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {
    private val VPN_REQUEST_CODE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("SSH_CONFIG", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        val hostInput = EditText(this).apply { hint = "Host (e.g. 192.168.1.1)"; setText(prefs.getString("host", "")) }
        val portInput = EditText(this).apply { hint = "Port (e.g. 80)"; setText(prefs.getString("port", "80")) }
        val userInput = EditText(this).apply { hint = "Username"; setText(prefs.getString("user", "")) }
        val passInput = EditText(this).apply { hint = "Password"; setText(prefs.getString("pass", "")) }
        val payloadInput = EditText(this).apply { hint = "Payload"; setText(prefs.getString("payload", "GET / HTTP/1.1\\r\\nHost: bug.com\\r\\nConnection: Upgrade\\r\\nUpgrade: websocket\\r\\n\\r\\n")) }

        val saveBtn = Button(this).apply {
            text = "SAVE CONFIG"
            setOnClickListener {
                prefs.edit().apply {
                    putString("host", hostInput.text.toString())
                    putString("port", portInput.text.toString())
                    putString("user", userInput.text.toString())
                    putString("pass", passInput.text.toString())
                    putString("payload", payloadInput.text.toString())
                    apply()
                }
                Toast.makeText(this@MainActivity, "Config Saved", Toast.LENGTH_SHORT).show()
            }
        }

        val startBtn = Button(this).apply {
            text = "START SSH TUNNEL"
            setOnClickListener { startVpn() }
        }

        layout.addView(hostInput)
        layout.addView(portInput)
        layout.addView(userInput)
        layout.addView(passInput)
        layout.addView(payloadInput)
        layout.addView(saveBtn)
        layout.addView(startBtn)
        setContentView(layout)
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startService(Intent(this, TunnelService::class.java))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startService(Intent(this, TunnelService::class.java))
        }
    }
}
