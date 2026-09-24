package com.relay.ssh

import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class CustomSocketFactory(private val payload: String, private val useSSL: Boolean, private val sni: String) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        var socket = Socket(host, port)
        
        if (useSSL) {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            socket = sslFactory.createSocket(socket, host, port, true)
            val sslSocket = socket as SSLSocket
            
            if (sni.isNotEmpty()) {
                val params: SSLParameters = sslSocket.sslParameters
                params.serverNames = listOf(SNIHostName(sni))
                sslSocket.sslParameters = params
            }
            sslSocket.startHandshake()
        }

        val input: InputStream = socket.getInputStream()
        val output: OutputStream = socket.getOutputStream()

        if (payload.isNotEmpty()) {
            val parsedPayload = payload.replace("\\r", "\r").replace("\\n", "\n")
            output.write(parsedPayload.toByteArray())
            output.flush()

            val buffer = ByteArray(2048)
            val read = input.read(buffer)
            if (read > 0) {
                val response = String(buffer, 0, read)
                if (!response.contains("HTTP/1.")) {
                    throw Exception("Payload rejected: $response")
                }
            }
        }

        return socket
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}
