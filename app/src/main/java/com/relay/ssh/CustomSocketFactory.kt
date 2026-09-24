package com.relay.ssh

import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

class CustomSocketFactory(private val payload: String) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket(host, port)
        val input: InputStream = socket.getInputStream()
        val output: OutputStream = socket.getOutputStream()

        // Inject the custom HTTP payload to the proxy/bughost
        output.write(payload.toByteArray())
        output.flush()

        // Read initial proxy response (e.g., HTTP/1.1 101 Switching Protocols or 200 OK)
        val buffer = ByteArray(1024)
        val read = input.read(buffer)
        if (read > 0) {
            val response = String(buffer, 0, read)
            if (!response.contains("HTTP/1.")) {
                throw Exception("Payload rejected. Server responded with: $response")
            }
        } else {
            throw Exception("Connection closed by server before payload response.")
        }

        // Return the injected socket to JSch for the actual SSH handshake
        return socket
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}
