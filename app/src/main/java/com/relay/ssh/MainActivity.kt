package com.relay.ssh

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.widget.*

class MainActivity : Activity() {
    private val VPN_REQUEST_CODE = 1
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("log") ?: return
            runOnUiThread {
                logView.append("$msg\n")
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("SSH_CONFIG", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        
        val hostInput = EditText(this).apply { hint = "Host (e.g. x.xkyun.xyz)"; setText(prefs.getString("host", "")) }
        val portInput = EditText(this).apply { hint = "Port (80 or 443)"; setText(prefs.getString("port", "443")) }
        val userInput = EditText(this).apply { hint = "Username"; setText(prefs.getString("user", "")) }
        val passInput = EditText(this).apply { hint = "Password"; setText(prefs.getString("pass", "")) }
        val sniInput = EditText(this).apply { hint = "SNI (Bug Host)"; setText(prefs.getString("sni", "")) }
        val sslCheck = CheckBox(this).apply { text = "Use SSL/TLS"; isChecked = prefs.getBoolean("useSSL", true) }
        val payloadInput = EditText(this).apply { hint = "Payload"; setText(prefs.getString("payload", "GET / HTTP/1.1\\r\\nHost: bug.com\\r\\nConnection: Upgrade\\r\\nUpgrade: websocket\\r\\n\\r\\n")) }

        val saveBtn = Button(this).apply {
            text = "SAVE CONFIG"
            setOnClickListener {
                prefs.edit().apply {
                    putString("host", hostInput.text.toString())
                    putString("port", portInput.text.toString())
                    putString("user", userInput.text.toString())
                    putString("pass", passInput.text.toString())
                    putString("sni", sniInput.text.toString())
                    putBoolean("useSSL", sslCheck.isChecked)
                    putString("payload", payloadInput.text.toString())
                    apply()
                }
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        }

        val startBtn = Button(this).apply {
            text = "START SSH TUNNEL"
            setOnClickListener {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE)
                else startService(Intent(this@MainActivity, TunnelService::class.java))
            }
        }

        logView = TextView(this).apply { textSize = 12f; setPadding(16, 16, 16, 16) }
        scrollView = ScrollView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(logView) 
        }

        layout.addView(hostInput); layout.addView(portInput); layout.addView(userInput)
        layout.addView(passInput); layout.addView(sslCheck); layout.addView(sniInput)
        layout.addView(payloadInput); layout.addView(saveBtn); layout.addView(startBtn); layout.addView(scrollView)
        setContentView(layout)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("TUNNEL_LOG"), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter("TUNNEL_LOG"))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startService(Intent(this, TunnelService::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
    }
}
