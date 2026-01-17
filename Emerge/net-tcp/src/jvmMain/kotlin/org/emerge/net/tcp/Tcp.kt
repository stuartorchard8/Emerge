package org.emerge.net.tcp

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.emerge.net.api.Pipe

object Tcp {
    fun connect(host: String, port: Int, timeoutMs: Int = 3000): Pipe {
        require(port in 1..65535)
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        return TcpPipe(s)
    }

    fun listen(port: Int, backlog: Int = 1): Listener {
        require(port in 1..65535)
        val ss = ServerSocket(port, backlog)
        return Listener(ss)
    }

    class Listener internal constructor(private val ss: ServerSocket) : AutoCloseable {
        fun accept(): Pipe {
            val s = ss.accept()
            s.tcpNoDelay = true
            return TcpPipe(s)
        }

        override fun close() {
            runCatching { ss.close() }
        }
    }
}

