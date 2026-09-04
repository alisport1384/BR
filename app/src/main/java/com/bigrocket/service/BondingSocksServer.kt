package com.bigrocket.service

import android.net.Network
import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local SOCKS5 server that [HevTunnel] (hev-socks5-tunnel, a real userspace TCP/IP stack)
 * forwards every TUN packet to as plain SOCKS5, in place of BigRocket's own hand-rolled
 * IP-packet parser/relay ([TunPacketRouter]/[TcpRelayEngine]/[UdpRelayEngine]).
 *
 * hev already terminates the device's TCP connections properly (retransmission, ordering,
 * congestion control - the actual point of switching to it); this class only needs to do
 * what BigRocket's bonding has always done - pick Wi-Fi vs Cellular per new connection by
 * weight, protect() the socket, and relay real bytes - once per SOCKS5 request rather than
 * once per raw IP flow. That's also why this doesn't reuse TcpRelayEngine/UdpRelayEngine:
 * their code is built around parsing raw IP/TCP/UDP headers and hand-building reply packets
 * (RST, etc.), none of which applies here - hev owns that layer now.
 *
 * Kept as a self-contained alternate path (TunPacketRouter is untouched) specifically so
 * this can be A/B compared and trivially reverted - see BigRocketVpnService.USE_HEV_TUNNEL.
 */
class BondingSocksServer(private val vpnService: VpnService) {

    companion object {
        /** 127.0.0.1-only; picked to avoid AetherUpstream's own 1819 and any other local port. */
        const val PORT = 12347
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val UDP_IDLE_TIMEOUT_MS = 60_000L
        private const val UDP_RECEIVE_TIMEOUT_MS = 1000
    }

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellularNetwork: Network? = null
    @Volatile private var wifiWeight = 50
    @Volatile private var cellularWeight = 50
    @Volatile private var upstreamMode = UpstreamMode.NONE

    // Randomized starting phase, not 0 - identical reasoning/fix to
    // TunPacketRouter.packetCounter: starting at a fixed 0 made the very first new
    // connection after every weight change/session start deterministically land on
    // Wi-Fi whenever wifiWeight > 0, regardless of how low the configured share was
    // (a single download is exactly one connection, so it always ran at Wi-Fi's speed).
    private val packetCounter = AtomicInteger(kotlin.random.Random.nextInt(100))
    private val relayIdCounter = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** Every open relay (TCP or UDP), tagged with which physical Network it's using, so a
     * soft-failure eviction (see [notifySoftFailure]) can close exactly the ones pinned to a
     * path that just went bad - mirrors NetworkSessionTracker's role for the old router. */
    private val activeRelays = ConcurrentHashMap<Int, ActiveRelay>()

    private class ActiveRelay(@Volatile var network: Network?, val close: () -> Unit)

    fun start() {
        if (serverSocket != null) return
        val server = ServerSocket(PORT, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        acceptJob = scope.launch {
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeRelays.values.toList().forEach { runCatching { it.close() } }
        activeRelays.clear()
    }

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        wifiNetwork = wifi
        cellularNetwork = cellular
    }

    fun updateWeights(wifiW: Int, cellularW: Int) {
        val changed = wifiWeight != wifiW || cellularWeight != cellularW
        wifiWeight = wifiW
        cellularWeight = cellularW
        if (changed) packetCounter.set(kotlin.random.Random.nextInt(100))
    }

    fun setUpstreamMode(mode: UpstreamMode) {
        upstreamMode = mode
    }

    /** Mirrors TunPacketRouter.notifySoftFailure: evict every relay pinned to [deadNetwork]
     * immediately instead of leaving it to fail silently - see the extended reasoning on
     * TunPacketRouter.notifySoftFailure itself, which applies identically here. */
    fun notifySoftFailure(deadNetwork: Network) {
        activeRelays.entries.toList().forEach { (id, relay) ->
            if (relay.network == deadNetwork) {
                runCatching { relay.close() }
                activeRelays.remove(id)
            }
        }
    }

    private fun pickNetwork(): Network? {
        val wifi = wifiNetwork
        val cellular = cellularNetwork
        if (wifi != null && cellular != null) {
            // Real traffic ALWAYS uses the weighted split, unconditionally - never the identity
            // policy. See the extended reasoning on TunPacketRouter.selectNetworkForPacket,
            // which applies identically here: routing every new connection through identity for
            // a time window pinned real downloads/uploads opened in roughly the first 5 seconds
            // after connecting to Wi-Fi (identity's null-until-monitored fallback), regardless
            // of score. Identity must never decide which physical network real traffic uses.
            val count = packetCounter.getAndIncrement()
            val slot = Math.floorMod(count, 100)
            return if (slot < wifiWeight) wifi else cellular
        }
        return wifi ?: cellular
    }

    // --- SOCKS5 server handshake ------------------------------------------------------

    private suspend fun handleClient(client: Socket) {
        val relayId = relayIdCounter.getAndIncrement()
        try {
            client.tcpNoDelay = true
            client.soTimeout = CONNECT_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val greeting = ByteArray(2)
            if (!readFully(input, greeting)) return closeQuietly(client)
            if (greeting[0].toInt() != 0x05) return closeQuietly(client)
            val methodCount = greeting[1].toInt() and 0xFF
            if (methodCount > 0 && !readFully(input, ByteArray(methodCount))) return closeQuietly(client)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val head = ByteArray(4)
            if (!readFully(input, head)) return closeQuietly(client)
            val cmd = head[1].toInt() and 0xFF
            val destination = readAddress(input, head[3].toInt() and 0xFF) ?: return closeQuietly(client)

            client.soTimeout = 0
            when (cmd) {
                0x01 -> handleConnect(relayId, client, input, output, destination.first, destination.second)
                0x03 -> handleUdpAssociate(relayId, client, output)
                else -> {
                    output.write(socksReply(0x07))
                    output.flush()
                    closeQuietly(client)
                }
            }
        } catch (_: Exception) {
            activeRelays.remove(relayId)
            closeQuietly(client)
        }
    }

    private fun readAddress(input: InputStream, atyp: Int): Pair<String, Int>? {
        val host = when (atyp) {
            0x01 -> {
                val addr = ByteArray(4)
                if (!readFully(input, addr)) return null
                InetAddress.getByAddress(addr).hostAddress
            }
            0x03 -> {
                val lenByte = ByteArray(1)
                if (!readFully(input, lenByte)) return null
                val len = lenByte[0].toInt() and 0xFF
                val domain = ByteArray(len)
                if (len > 0 && !readFully(input, domain)) return null
                String(domain, Charsets.US_ASCII)
            }
            0x04 -> {
                val addr = ByteArray(16)
                if (!readFully(input, addr)) return null
                InetAddress.getByAddress(addr).hostAddress
            }
            else -> return null
        }
        val portBytes = ByteArray(2)
        if (!readFully(input, portBytes)) return null
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return host to port
    }

    // --- TCP CONNECT -------------------------------------------------------------------

    private suspend fun handleConnect(
        relayId: Int,
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        destHost: String,
        destPort: Int,
    ) {
        val mode = upstreamMode
        val remote: Socket
        val network: Network?
        try {
            if (mode == UpstreamMode.AETHER) {
                network = null
                remote = AetherUpstream.openTcp(vpnService, destHost, destPort)
            } else {
                val picked = pickNetwork() ?: throw IOException("No usable network")
                network = picked
                // Protecting a socket only prevents VPN recursion; it does NOT select the
                // physical uplink. The selected Network must create/bind the socket, otherwise
                // Android is free to use the default network (typically Wi-Fi), defeating
                // BigRocket's path selection.
                val socket = networkSocket(picked)
                if (!vpnService.protect(socket)) throw IOException("Unable to protect TCP socket from VPN")
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(destHost, destPort), CONNECT_TIMEOUT_MS)
                remote = socket
            }
        } catch (_: Exception) {
            runCatching { clientOut.write(socksReply(0x01)); clientOut.flush() }
            closeQuietly(client)
            return
        }

        activeRelays[relayId] = ActiveRelay(network) {
            runCatching { client.close() }
            runCatching { remote.close() }
        }

        try {
            clientOut.write(socksReply(0x00))
            clientOut.flush()
        } catch (_: Exception) {
            activeRelays.remove(relayId)
            runCatching { remote.close() }
            closeQuietly(client)
            return
        }

        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()

        val upload = scope.launch { pipe(clientIn, remoteOut) }
        val download = scope.launch { pipe(remoteIn, clientOut) }
        upload.join()
        download.join()

        activeRelays.remove(relayId)
        runCatching { remote.close() }
        closeQuietly(client)
    }

    private fun networkSocket(network: Network): Socket =
        network.socketFactory.createSocket()

    private fun pipe(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
                TrafficStats.recordBytes(n)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { to.flush() }
        }
    }

    // --- UDP ASSOCIATE -------------------------------------------------------------------

    private suspend fun handleUdpAssociate(relayId: Int, client: Socket, clientOut: OutputStream) {
        val localUdp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val mode = upstreamMode
        val aetherAssociation = if (mode == UpstreamMode.AETHER) {
            try {
                AetherUpstream.openUdp(vpnService)
            } catch (_: Exception) {
                null
            }
        } else null

        val reply = socksReply(0x00, InetAddress.getByName("127.0.0.1"), localUdp.localPort)
        try {
            clientOut.write(reply)
            clientOut.flush()
        } catch (_: Exception) {
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            closeQuietly(client)
            return
        }

        // Dual-path state for the real-dial (NONE-mode, aetherAssociation == null) branch: two
        // persistent raw sockets targeting this association's single remote destination - one
        // per physical path - instead of one path chosen once and cached for the whole session.
        //
        // This is safe SPECIFICALLY because this branch carries Aether's own WireGuard traffic
        // (BondingSocksServer is Aether's configured upstreamProxy - see EmbeddedAetherRuntime).
        // WireGuard identifies a peer by decrypting with its session key, not by source IP -
        // that is exactly what lets a phone roam between Wi-Fi and Cellular mid-tunnel without
        // dropping the connection. So genuinely interleaving this one tunnel's packets across
        // both physical source IPs is safe here. This does NOT generalize to the directTargets
        // logic used for ordinary destinations elsewhere in this file: a normal TCP/UDP peer
        // identifies a flow by source IP, so splitting its packets across two source IPs would
        // break it - only this WireGuard destination tolerates it.
        var wifiSocket: DatagramSocket? = null
        var wifiBoundTo: Network? = null
        var cellularSocket: DatagramSocket? = null
        var cellularBoundTo: Network? = null
        var destHost: String? = null
        var destPort = 0
        val lastClientAddr = java.util.concurrent.atomic.AtomicReference<InetSocketAddress?>(null)
        val receiverJobs = mutableListOf<Job>()

        fun bindDualSocket(network: Network): DatagramSocket? = runCatching {
            val s = DatagramSocket()
            if (!vpnService.protect(s)) { s.close(); return@runCatching null }
            network.bindSocket(s)
            s.soTimeout = UDP_RECEIVE_TIMEOUT_MS
            s
        }.getOrNull()

        fun startDualReceiver(socket: DatagramSocket, host: String, port: Int): Job = scope.launch {
            val respBuf = ByteArray(64 * 1024)
            try {
                while (isActive && !socket.isClosed) {
                    val resp = DatagramPacket(respBuf, respBuf.size)
                    try {
                        socket.receive(resp)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    // The server replies to whichever source IP it most recently saw a valid
                    // packet from, so a reply can legitimately arrive on either socket - both
                    // receivers forward everything back to Aether the same way.
                    val addr = lastClientAddr.get() ?: continue
                    val encoded = encodeSocksUdp(host, port, resp.data.copyOf(resp.length))
                    runCatching { localUdp.send(DatagramPacket(encoded, encoded.size, addr)) }
                    TrafficStats.recordBytes(resp.length)
                }
            } catch (_: Exception) {
            }
        }

        fun ensureDualSockets() {
            val wifi = wifiNetwork
            val cellular = cellularNetwork
            if (wifi != wifiBoundTo) {
                runCatching { wifiSocket?.close() }
                wifiSocket = wifi?.let { bindDualSocket(it) }
                wifiBoundTo = wifi
                val host = destHost
                if (wifiSocket != null && host != null) {
                    receiverJobs += startDualReceiver(wifiSocket!!, host, destPort)
                }
            }
            if (cellular != cellularBoundTo) {
                runCatching { cellularSocket?.close() }
                cellularSocket = cellular?.let { bindDualSocket(it) }
                cellularBoundTo = cellular
                val host = destHost
                if (cellularSocket != null && host != null) {
                    receiverJobs += startDualReceiver(cellularSocket!!, host, destPort)
                }
            }
        }

        // This relay's ActiveRelay.network is deliberately left null (never a single path) so
        // notifySoftFailure - built for single-path relays - never tears down this association
        // just because ONE of its two paths died. ensureDualSockets() above already reacts to
        // either path individually and keeps the association alive on whichever path survives.
        activeRelays[relayId] = ActiveRelay(null) {
            runCatching { client.close() }
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            runCatching { wifiSocket?.close() }
            runCatching { cellularSocket?.close() }
            receiverJobs.forEach { it.cancel() }
        }

        // TUN ASSOCIATE lives for as long as the SOCKS5 control TCP connection stays open -
        // this small watcher just closes the UDP side (and unblocks the receive loop below)
        // the moment hev drops it, matching standard SOCKS5 UDP ASSOCIATE semantics.
        val controlWatcher = scope.launch {
            try {
                val buf = ByteArray(1)
                while (client.getInputStream().read(buf) >= 0) { /* control channel stays open */ }
            } catch (_: Exception) {
            } finally {
                runCatching { localUdp.close() }
            }
        }

        val buffer = ByteArray(64 * 1024)
        var lastActivity = System.currentTimeMillis()
        localUdp.soTimeout = UDP_RECEIVE_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() - lastActivity < UDP_IDLE_TIMEOUT_MS) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    localUdp.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                lastActivity = System.currentTimeMillis()
                val decoded = decodeSocksUdp(packet.data, packet.length) ?: continue
                val fromAddr = packet.socketAddress as? InetSocketAddress ?: continue
                lastClientAddr.set(fromAddr)

                if (aetherAssociation != null) {
                    runCatching { aetherAssociation.send(decoded.host, decoded.port, decoded.payload) }
                    val received = runCatching { aetherAssociation.receive(buffer) }.getOrNull()
                    if (received != null) {
                        val encoded = encodeSocksUdp(decoded.host, decoded.port, received.payload)
                        runCatching { localUdp.send(DatagramPacket(encoded, encoded.size, fromAddr)) }
                    }
                    continue
                }

                if (destHost == null) {
                    destHost = decoded.host
                    destPort = decoded.port
                }
                ensureDualSockets()

                // Real per-packet bonding: a fresh weighted pick for THIS packet, not a choice
                // cached once for the whole session (see the class doc above this block).
                val chosen = pickBondedSocket(wifiSocket, cellularSocket)
                if (chosen == null) continue // both paths currently down - drop, same as before
                runCatching {
                    val dest = InetSocketAddress(InetAddress.getByName(decoded.host), decoded.port)
                    chosen.send(DatagramPacket(decoded.payload, decoded.payload.size, dest))
                }
                TrafficStats.recordBytes(decoded.payload.size)
            }
        } finally {
            controlWatcher.cancel()
            activeRelays.remove(relayId)
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            runCatching { wifiSocket?.close() }
            runCatching { cellularSocket?.close() }
            receiverJobs.forEach { it.cancel() }
            closeQuietly(client)
        }
    }

    /** Weighted per-call pick between two already-bound sockets for [handleUdpAssociate]'s
     *  dual-path branch - same weighting as [pickNetwork], just returning a live socket
     *  instead of a Network since the caller already owns both bound sockets. */
    private fun pickBondedSocket(wifi: DatagramSocket?, cellular: DatagramSocket?): DatagramSocket? {
        if (wifi != null && cellular != null) {
            val count = packetCounter.getAndIncrement()
            val slot = Math.floorMod(count, 100)
            return if (slot < wifiWeight) wifi else cellular
        }
        return wifi ?: cellular
    }

    private data class DecodedUdp(val host: String, val port: Int, val payload: ByteArray)

    private fun decodeSocksUdp(data: ByteArray, length: Int): DecodedUdp? {
        if (length < 4) return null
        if (data[0].toInt() != 0 || data[1].toInt() != 0) return null
        val atyp = data[3].toInt() and 0xFF
        var offset = 4
        val host: String
        when (atyp) {
            0x01 -> {
                if (offset + 4 > length) return null
                host = InetAddress.getByAddress(data.copyOfRange(offset, offset + 4)).hostAddress
                offset += 4
            }
            0x03 -> {
                if (offset >= length) return null
                val len = data[offset].toInt() and 0xFF
                offset += 1
                if (offset + len > length) return null
                host = String(data, offset, len, Charsets.US_ASCII)
                offset += len
            }
            0x04 -> {
                if (offset + 16 > length) return null
                host = InetAddress.getByAddress(data.copyOfRange(offset, offset + 16)).hostAddress
                offset += 16
            }
            else -> return null
        }
        if (offset + 2 > length) return null
        val port = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        return DecodedUdp(host, port, data.copyOfRange(offset, length))
    }

    private fun encodeSocksUdp(host: String, port: Int, payload: ByteArray): ByteArray {
        val addr = InetAddress.getByName(host)
        val addrBytes = addr.address
        val out = java.io.ByteArrayOutputStream()
        out.write(0); out.write(0); out.write(0)
        out.write(if (addrBytes.size == 16) 0x04 else 0x01)
        out.write(addrBytes)
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    // --- helpers -------------------------------------------------------------------------

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = try {
                input.read(buffer, read, buffer.size - read)
            } catch (_: Exception) {
                return false
            }
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun socksReply(rep: Int, boundAddr: InetAddress = InetAddress.getByName("0.0.0.0"), boundPort: Int = 0): ByteArray {
        val addrBytes = boundAddr.address
        val out = java.io.ByteArrayOutputStream()
        out.write(0x05); out.write(rep); out.write(0x00)
        out.write(if (addrBytes.size == 16) 0x04 else 0x01)
        out.write(addrBytes)
        out.write((boundPort ushr 8) and 0xFF)
        out.write(boundPort and 0xFF)
        return out.toByteArray()
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }
}
