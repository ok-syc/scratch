package com.relay.ssh

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class TunnelService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunProcess: Process? = null
    private var isRunning = false
    private var socksServer: ServerSocket? = null

    private fun log(msg: String) {
        val intent = Intent("TUNNEL_LOG").apply { putExtra("log", msg) }
        sendBroadcast(intent)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        log("Service Created. Initializing engine...")
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
                val payload = prefs.getString("payload", "") ?: ""
                val useSSL = prefs.getBoolean("useSSL", false)
                val sni = prefs.getString("sni", "") ?: ""

                val jsch = JSch()
                val session = jsch.getSession(user, host, port)
                session.setPassword(password)
                session.setConfig("StrictHostKeyChecking", "no")
                
                log("Injecting Payload... SSL=$useSSL, SNI=$sni")
                session.setSocketFactory(CustomSocketFactory(payload, useSSL, sni))

                log("Connecting SSH to $host:$port...")
                session.connect(15000)
                log("SSH Handshake Successful!")

                val socksPort = 10808
                startLocalSocks5(session, socksPort)
                
                setupVpnInterface()
                startTun2Socks(socksPort)

            } catch (e: Exception) {
                log("SSH Error: ${e.message}")
            }
        }
    }

    private fun startLocalSocks5(session: Session, port: Int) {
        thread {
            try {
                socksServer = ServerSocket(port)
                log("Internal SOCKS5 active on port $port")
                while (isRunning) {
                    val client = socksServer?.accept() ?: break
                    thread { handleSocksClient(client, session) }
                }
            } catch (e: Exception) {
                log("SOCKS5 Server died: ${e.message}")
            }
        }
    }

    private fun handleSocksClient(client: Socket, session: Session) {
        try {
            val input = client.inputStream
            val output = client.outputStream
            
            // SOCKS5 Handshake
            input.read(); val nMethods = input.read(); input.skip(nMethods.toLong())
            output.write(byteArrayOf(5, 0))
            
            // SOCKS5 Request
            val req = ByteArray(4)
            input.read(req)
            if (req[1] != 1.toByte()) return 
            
            var targetHost = ""
            val atyp = req[3].toInt()
            if (atyp == 1) { // IPv4
                val ip = ByteArray(4); input.read(ip)
                targetHost = InetAddress.getByAddress(ip).hostAddress
            } else if (atyp == 3) { // Domain
                val len = input.read()
                val dom = ByteArray(len); input.read(dom)
                targetHost = String(dom)
            }
            val targetPort = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)

            val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(targetHost)
            channel.setPort(targetPort)
            channel.connect(5000)

            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // Success
            
            thread { try { channel.inputStream.copyTo(output) } catch (e: Exception) {} }
            try { input.copyTo(channel.outputStream) } catch (e: Exception) {}
            
            channel.disconnect()
            client.close()
        } catch (e: Exception) {}
    }

    private fun setupVpnInterface() {
        val builder = Builder()
        builder.addAddress("10.0.0.2", 32)
        builder.addRoute("0.0.0.0", 0)
        builder.setSession("SSH-Relay")
        builder.setMtu(1500)
        vpnInterface = builder.establish()
    }

    private fun startTun2Socks(socksPort: Int) {
        val fd = vpnInterface?.fd ?: return
        val outFile = File(filesDir, "tun2socks")
        if (!outFile.exists()) {
            assets.open("arm64-v8a/tun2socks").use { input ->
                outFile.outputStream().use { it.write(input.readBytes()) }
            }
            outFile.setExecutable(true)
        }
        
        val cmd = arrayOf(outFile.absolutePath, "-device", "fd://$fd", "-proxy", "socks5://127.0.0.1:$socksPort", "-loglevel", "warning")
        try {
            tunProcess = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            log("Routing engine (tun2socks) injected.")
            
            val reader = BufferedReader(InputStreamReader(tunProcess!!.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null && isRunning) {
                log("tun2socks: $line")
            }
        } catch (e: Exception) {
            log("Routing Error: ${e.message}")
        }
    }

    override fun onDestroy() {
        isRunning = false
        socksServer?.close()
        tunProcess?.destroy()
        vpnInterface?.close()
        super.onDestroy()
    }
}
