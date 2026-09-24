package com.relay.ssh

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.jcraft.jsch.JSch
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

class TunnelService : VpnService() {
    private val TAG = "SSHTunnel"
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created. Initializing SSH connection...")
        startSshTunnel()
    }

    private fun startSshTunnel() {
        thread {
            try {
                val prefs = getSharedPreferences("SSH_CONFIG", Context.MODE_PRIVATE)
                val user = prefs.getString("user", "") ?: ""
                val host = prefs.getString("host", "") ?: ""
                val port = prefs.getString("port", "80")?.toIntOrNull() ?: 80
                val password = prefs.getString("pass", "") ?: ""
                
                // Parse literal \r\n from UI input to actual carriage returns for the socket
                val rawPayload = prefs.getString("payload", "") ?: ""
                val payload = rawPayload.replace("\\r", "\r").replace("\\n", "\n")

                val jsch = JSch()
                val session = jsch.getSession(user, host, port)
                session.setPassword(password)
                session.setConfig("StrictHostKeyChecking", "no")
                
                session.setSocketFactory(CustomSocketFactory(payload))

                Log.d(TAG, "Connecting SSH to $host:$port via Custom Payload Injector...")
                session.connect(15000)
                Log.d(TAG, "SSH Handshake Successful!")

                val localPort = 10808
                session.setPortForwardingL(localPort, "127.0.0.1", 8080)
                
                setupVpnInterface()
                startTun2Socks(localPort)

            } catch (e: Exception) {
                Log.e(TAG, "SSH Connection Failed: ${e.message}")
            }
        }
    }

    private fun setupVpnInterface() {
        val builder = Builder()
        builder.addAddress("10.0.0.2", 32)
        builder.addRoute("0.0.0.0", 0)
        builder.setSession("SSH-Relay")
        builder.setMtu(1500)
        
        vpnInterface = builder.establish()
    }

    private fun extractTun2Socks(): String {
        val binaryName = "tun2socks"
        val outFile = File(filesDir, binaryName)
        if (!outFile.exists()) {
            assets.open("arm64-v8a/$binaryName").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outFile.setExecutable(true)
        }
        return outFile.absolutePath
    }

    private fun startTun2Socks(localPort: Int) {
        val fd = vpnInterface?.fd ?: return
        val binaryPath = extractTun2Socks()
        
        val cmd = arrayOf(
            binaryPath,
            "-device", "fd://$fd",
            "-proxy", "http://127.0.0.1:$localPort",
            "-loglevel", "info"
        )

        try {
            tunProcess = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val reader = BufferedReader(InputStreamReader(tunProcess!!.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "tun2socks: $line")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute tun2socks: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tunProcess?.destroy()
        vpnInterface?.close()
    }
}
