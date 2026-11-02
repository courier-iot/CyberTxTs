package com.cybertxts.app

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** One shared WebRTC engine for the whole app. */
object Rtc {
    @Volatile private var factory: PeerConnectionFactory? = null

    fun factory(ctx: Context): PeerConnectionFactory = synchronized(this) {
        factory ?: run {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions()
            )
            PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
        }
    }

    /** Free public STUN servers: they only tell each phone its public address. No relay, no data passes through them. */
    val iceServers: List<PeerConnection.IceServer> = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    ).map { PeerConnection.IceServer.builder(it).createIceServer() }
}

/**
 * One direct WebRTC link to another phone: a peer connection with a single ordered data channel.
 * Frames are split into 16 KB fragments (the safe size for data channels) and rebuilt on arrival.
 */
class RtcLink(
    ctx: Context,
    val peerId: String,
    initiator: Boolean,
    private val scope: CoroutineScope,
    private val onOpen: (RtcLink) -> Unit,
    private val onFrame: (RtcLink, ByteArray) -> Unit,
    private val onClosed: (RtcLink) -> Unit
) {
    @Volatile var established = false
    @Volatile var lastRx = System.currentTimeMillis()
    @Volatile private var closed = false
    @Volatile private var channel: DataChannel? = null
    private val gathered = CompletableDeferred<Unit>()
    private val seq = AtomicInteger()
    private val parts = HashMap<Int, Array<ByteArray?>>()   // guarded by synchronized(parts)

    private val pc: PeerConnection? = Rtc.factory(ctx).createPeerConnection(
        PeerConnection.RTCConfiguration(Rtc.iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        },
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) close()
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) { if (channel == null) attach(dc) else dc.close() }
            override fun onRenegotiationNeeded() {}
        }
    )

    init {
        if (pc == null) close()
        else if (initiator) attach(pc.createDataChannel("cybertxts", DataChannel.Init().apply { ordered = true }))
    }

    private fun attach(dc: DataChannel) {
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> scope.launch { onOpen(this@RtcLink) }
                    DataChannel.State.CLOSED -> close()
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bb = buffer.data
                val bytes = ByteArray(bb.remaining()).also { bb.get(it) }
                receiveFragment(bytes)
            }
        })
    }

    // ---- SDP (non-trickle: wait for all candidates, then send one complete description) ----

    private suspend fun create(offer: Boolean): SessionDescription? = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resume(null) }
            override fun onSetFailure(error: String?) {}
        }
        val p = pc ?: run { cont.resume(null); return@suspendCancellableCoroutine }
        if (offer) p.createOffer(obs, MediaConstraints()) else p.createAnswer(obs, MediaConstraints())
    }

    private suspend fun set(local: Boolean, sdp: SessionDescription): Boolean = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(true) }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resume(false) }
        }
        val p = pc ?: run { cont.resume(false); return@suspendCancellableCoroutine }
        if (local) p.setLocalDescription(obs, sdp) else p.setRemoteDescription(obs, sdp)
    }

    private suspend fun gatheredDescription(): String? {
        withTimeoutOrNull(7_000) { gathered.await() }
        return pc?.localDescription?.description
    }

    suspend fun makeOffer(): String? {
        val offer = create(true) ?: return null
        if (!set(true, offer)) return null
        return gatheredDescription()
    }

    suspend fun answerOffer(remote: String): String? {
        if (!set(false, SessionDescription(SessionDescription.Type.OFFER, remote))) return null
        val answer = create(false) ?: return null
        if (!set(true, answer)) return null
        return gatheredDescription()
    }

    suspend fun acceptAnswer(remote: String): Boolean =
        set(false, SessionDescription(SessionDescription.Type.ANSWER, remote))

    // ---- framing ----

    fun send(frame: ByteArray): Boolean {
        val dc = channel ?: return false
        if (closed || dc.state() != DataChannel.State.OPEN) return false
        synchronized(this) {
            val id = seq.incrementAndGet()
            val total = maxOf(1, (frame.size + FRAG - 1) / FRAG)
            for (i in 0 until total) {
                val start = i * FRAG
                val len = minOf(FRAG, frame.size - start).coerceAtLeast(0)
                val bb = ByteBuffer.allocate(8 + len)
                bb.putInt(id); bb.putShort(i.toShort()); bb.putShort(total.toShort())
                bb.put(frame, start, len)
                bb.flip()
                if (!dc.send(DataChannel.Buffer(bb, true))) return false
            }
        }
        return true
    }

    /** Waits while the link's outgoing buffer is full, so big media can't swamp memory. */
    suspend fun sendPaced(frame: ByteArray): Boolean {
        val dc = channel ?: return false
        var waited = 0
        while (!closed && dc.bufferedAmount() > MAX_BUFFERED && waited < 30_000) { delay(20); waited += 20 }
        return send(frame)
    }

    private fun receiveFragment(b: ByteArray) {
        if (b.size < 8) return
        val bb = ByteBuffer.wrap(b)
        val id = bb.int
        val idx = bb.short.toInt() and 0xFFFF
        val total = bb.short.toInt() and 0xFFFF
        if (total == 0 || total > MAX_FRAGS || idx >= total) return
        val payload = b.copyOfRange(8, b.size)
        lastRx = System.currentTimeMillis()
        if (total == 1) { onFrame(this, payload); return }
        val done: ByteArray? = synchronized(parts) {
            if (!parts.containsKey(id) && parts.size >= 16) parts.remove(parts.keys.first())
            val arr = parts.getOrPut(id) { arrayOfNulls(total) }
            if (arr.size != total) return
            arr[idx] = payload
            if (arr.all { it != null }) {
                parts.remove(id)
                val size = arr.sumOf { it!!.size }
                val out = ByteArray(size)
                var pos = 0
                arr.forEach { System.arraycopy(it!!, 0, out, pos, it.size); pos += it.size }
                out
            } else null
        }
        if (done != null) onFrame(this, done)
    }

    /** Safe to call from any thread; the real teardown never runs on a WebRTC callback thread. */
    fun close() {
        if (closed) return
        closed = true
        gathered.complete(Unit)
        scope.launch(Dispatchers.IO) {
            runCatching { channel?.unregisterObserver() }
            runCatching { channel?.close() }
            runCatching { channel?.dispose() }
            runCatching { pc?.close() }
            runCatching { pc?.dispose() }
            onClosed(this@RtcLink)
        }
    }

    val isClosed get() = closed

    companion object {
        private const val FRAG = 16 * 1024
        private const val MAX_FRAGS = 4096
        private const val MAX_BUFFERED = 2L * 1024 * 1024
    }
}

/**
 * Internet links. Discovery and connection setup go through public Nostr relays (encrypted with the node
 * key); after that each pair of phones talks directly over WebRTC. Same rules as the WiFi transport:
 * encrypted HELLO handshake, older-key members get the key-change bundle, 25-operative limit, bans.
 *
 * [skip] says a peer is already reachable another way (same WiFi), so we don't double-send to them.
 */
class InternetTransport(context: Context, private val skip: (String) -> Boolean) : Transport {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ring = CopyOnWriteArrayList<Pair<String, SecretKey>>()   // (kid, key), newest first
    @Volatile private var graceUntil = 0L
    private val banned = ConcurrentHashMap.newKeySet<String>()
    private val links = ConcurrentHashMap<String, RtcLink>()     // established, by peer id
    private val pending = ConcurrentHashMap<String, RtcLink>()   // being set up, by peer id
    private val pendingSid = ConcurrentHashMap<String, String>()
    private val rng = SecureRandom()

    private lateinit var selfId: String
    private lateinit var onFrame: (ByteArray) -> Unit
    private lateinit var onPeers: (Set<String>) -> Unit
    private lateinit var rescue: (String, String) -> ByteArray?
    private var pool: NostrPool? = null

    private class Match(val kid: String, val key: SecretKey, val plain: ByteArray)

    override fun start(
        selfId: String, roomTag: String, kid: String, key: SecretKey,
        onFrame: (ByteArray) -> Unit, onPeers: (Set<String>) -> Unit,
        rescue: (String, String) -> ByteArray?
    ) {
        this.selfId = selfId; this.onFrame = onFrame; this.onPeers = onPeers; this.rescue = rescue
        ring.add(kid to key)
        val p = NostrPool { content -> onSignal(content) }
        pool = p
        p.start(Schnorr.sha256Hex("cybertxts-net:$roomTag"))

        // Presence: "I'm in this node" every 15 s (encrypted; relays can't read it)
        scope.launch {
            delay(1_500)
            while (isActive) {
                signal(ring[0].second, JSONObject().put("t", "here").put("from", selfId))
                delay(15_000)
            }
        }
        // Heartbeats and dead-link cleanup
        scope.launch {
            while (isActive) {
                delay(10_000)
                val ping = Crypto.encrypt(ring[0].second, PING)
                val now = System.currentTimeMillis()
                links.values.forEach { l ->
                    if (now - l.lastRx > 35_000) l.close() else l.send(ping)
                }
            }
        }
    }

    // ---------- signalling over relays ----------

    private fun signal(key: SecretKey, json: JSONObject) {
        json.put("ts", System.currentTimeMillis())
        val enc = Crypto.encrypt(key, json.toString().toByteArray())
        pool?.publish(Base64.encodeToString(enc, Base64.NO_WRAP))
    }

    private fun decryptWithRing(data: ByteArray): Match? {
        for ((kid, k) in ring) {
            val p = Crypto.decrypt(k, data)
            if (p != null) return Match(kid, k, p)
        }
        return null
    }

    private fun onSignal(content: String) {
        val data = try { Base64.decode(content, Base64.NO_WRAP) } catch (e: Exception) { return }
        val m = decryptWithRing(data) ?: return            // not for this node, or a key we never had
        val j = try { JSONObject(m.plain.toString(Charsets.UTF_8)) } catch (e: Exception) { return }
        val from = j.optString("from")
        if (from.length != 32 || from == selfId || banned.contains(from)) return
        if (Math.abs(System.currentTimeMillis() - j.optLong("ts")) > 5 * 60_000) return   // stale / replayed
        when (j.optString("t")) {
            "here" -> {
                if (skip(from) || links.containsKey(from) || pending.containsKey(from)) return
                if (selfId < from) dial(from, m.key)           // smaller ID makes the offer
            }
            "offer" -> if (j.optString("to") == selfId) {
                answer(from, j.optString("sid"), j.optString("sdp"), m.key)
            }
            "answer" -> if (j.optString("to") == selfId) {
                val l = pending[from] ?: return
                if (pendingSid[from] != j.optString("sid")) return
                scope.launch { if (!l.acceptAnswer(j.optString("sdp"))) l.close() }
            }
        }
    }

    private fun newLink(peer: String, initiator: Boolean) =
        RtcLink(app, peer, initiator, scope, ::onLinkOpen, ::onLinkFrame, ::onLinkClosed)

    private fun dial(peer: String, key: SecretKey) {
        if (links.size >= Limits.MAX_PEERS) return
        val l = newLink(peer, initiator = true)
        if (pending.putIfAbsent(peer, l) != null) { l.close(); return }
        val sid = rng.nextLong().toString(16)
        pendingSid[peer] = sid
        scope.launch {
            val sdp = l.makeOffer()
            if (sdp == null) { l.close(); return@launch }
            signal(key, JSONObject().put("t", "offer").put("from", selfId).put("to", peer).put("sid", sid).put("sdp", sdp))
            expire(peer, l)
        }
    }

    private fun answer(peer: String, sid: String, sdp: String, key: SecretKey) {
        if (sdp.isEmpty() || sdp.length > 20_000 || skip(peer) || links.containsKey(peer)) return
        pending.remove(peer)?.close()          // a newer offer replaces an older half-open one
        val l = newLink(peer, initiator = false)
        pending[peer] = l
        pendingSid[peer] = sid
        scope.launch {
            val ans = l.answerOffer(sdp)
            if (ans == null) { l.close(); return@launch }
            signal(key, JSONObject().put("t", "answer").put("from", selfId).put("to", peer).put("sid", sid).put("sdp", ans))
            expire(peer, l)
        }
    }

    /** Give up on a link that hasn't finished its handshake in 45 s; presence will trigger a retry. */
    private suspend fun expire(peer: String, l: RtcLink) {
        delay(45_000)
        if (!l.established) {
            pending.remove(peer, l)
            l.close()
        }
    }

    // ---------- the link itself (same handshake as WiFi) ----------

    private fun onLinkOpen(l: RtcLink) {
        l.send(Crypto.encrypt(ring[0].second, "HELLO:$selfId".toByteArray()))
    }

    private fun onLinkFrame(l: RtcLink, raw: ByteArray) {
        if (!l.established) {
            val m = decryptWithRing(raw) ?: return
            val text = m.plain.toString(Charsets.UTF_8)
            if (!text.startsWith("HELLO:")) {
                // We are the one behind: the peer sent the key-change bundle sealed with our key.
                onFrame(m.plain)
                return
            }
            val peer = text.removePrefix("HELLO:")
            if (peer != l.peerId || banned.contains(peer)) { l.close(); return }
            if (m.kid != ring[0].first) {
                // Peer is on an older key: hand them the bundle that moves them on, then hang up.
                rescue(peer, m.kid)?.let { l.send(Crypto.encrypt(m.key, it)) }
                scope.launch { delay(3_000); l.close() }
                return
            }
            if (!links.containsKey(peer) && links.size >= Limits.MAX_PEERS) {
                l.send(Crypto.encrypt(m.key, FULL))
                scope.launch { delay(2_000); l.close() }
                return
            }
            if (links.putIfAbsent(peer, l) != null) { l.close(); return }
            l.established = true
            pending.remove(peer, l)
            onPeers(links.keys.toSet())
            return
        }
        val snap = ring.toList()
        var plain = Crypto.decrypt(snap[0].second, raw)
        if (plain == null && snap.size > 1 && System.currentTimeMillis() < graceUntil) plain = Crypto.decrypt(snap[1].second, raw)
        if (plain == null || plain.contentEquals(PING)) return
        onFrame(plain)
    }

    private fun onLinkClosed(l: RtcLink) {
        pending.remove(l.peerId, l)
        if (links.remove(l.peerId, l)) onPeers(links.keys.toSet())
    }

    // ---------- Transport ----------

    private fun targets() = links.values.filter { !skip(it.peerId) }

    override fun send(frame: ByteArray) {
        val enc = Crypto.encrypt(ring[0].second, frame)
        scope.launch { targets().forEach { it.send(enc) } }
    }

    override fun sendBlocking(frame: ByteArray) {
        val enc = Crypto.encrypt(ring[0].second, frame)
        kotlinx.coroutines.runBlocking { targets().forEach { it.sendPaced(enc) } }
    }

    override fun rekey(kid: String, key: SecretKey) {
        if (ring.any { it.first == kid }) return
        ring.add(0, kid to key)
        graceUntil = System.currentTimeMillis() + 120_000
    }

    override fun addLegacyKey(kid: String, key: SecretKey) {
        if (ring.none { it.first == kid }) ring.add(kid to key)
    }

    override fun ban(id: String) {
        banned.add(id)
        pending.remove(id)?.close()
        links[id]?.close()
    }

    fun isLinked(id: String) = links.containsKey(id)

    override fun sendTo(ids: Set<String>, frame: ByteArray) {
        val targets = links.values.filter { it.peerId in ids }
        if (targets.isEmpty()) return
        val enc = Crypto.encrypt(ring[0].second, frame)
        scope.launch { targets.forEach { it.sendPaced(enc) } }
    }

    fun relayCount(): Int = pool?.connectedRelays() ?: 0

    override fun stop() {
        pool?.stop()
        links.values.forEach { it.close() }
        pending.values.forEach { it.close() }
        links.clear(); pending.clear()
        scope.launch { delay(3_000); scope.cancel() }   // let link teardown finish first
    }

    companion object {
        private val PING = "PING".toByteArray()
        private val FULL = """{"type":"full"}""".toByteArray()
    }
}

/**
 * WiFi + internet together. A node keeps both running; peers on the same WiFi talk locally,
 * everyone else over the internet. The chat layer sees one combined set of linked operatives.
 */
class MultiTransport(context: Context, internet: Boolean) : Transport {
    private val lan = LanTransport(context)
    private val net: InternetTransport? = if (internet) InternetTransport(context) { lan.isConnected(it) } else null
    @Volatile private var lanPeers: Set<String> = emptySet()
    @Volatile private var netPeers: Set<String> = emptySet()
    private lateinit var onPeers: (Set<String>) -> Unit

    override fun start(
        selfId: String, roomTag: String, kid: String, key: SecretKey,
        onFrame: (ByteArray) -> Unit, onPeers: (Set<String>) -> Unit,
        rescue: (String, String) -> ByteArray?
    ) {
        this.onPeers = onPeers
        lan.start(selfId, roomTag, kid, key, onFrame, { lanPeers = it; emit() }, rescue)
        net?.start(selfId, roomTag, kid, key, onFrame, { netPeers = it; emit() }, rescue)
    }

    @Synchronized private fun emit() = onPeers(lanPeers + netPeers)

    private val tx = java.util.concurrent.atomic.AtomicLong()

    /** Bytes handed to links so far (each copy counted), for the star map. */
    fun txBytes(): Long = tx.get()

    private fun count(frame: ByteArray, copies: Int) { tx.addAndGet(frame.size.toLong() * copies) }

    override fun send(frame: ByteArray) { count(frame, (lanPeers + netPeers).size); lan.send(frame); net?.send(frame) }
    override fun sendBlocking(frame: ByteArray) {
        count(frame, (lanPeers + netPeers).size); lan.sendBlocking(frame); net?.sendBlocking(frame)
    }
    override fun sendTo(ids: Set<String>, frame: ByteArray) {
        val viaLan = ids.filter { lan.isConnected(it) }.toSet()
        val viaNet = ids - viaLan
        count(frame, ids.size)
        if (viaLan.isNotEmpty()) lan.sendTo(viaLan, frame)
        if (viaNet.isNotEmpty()) net?.sendTo(viaNet, frame)
    }
    override fun rekey(kid: String, key: SecretKey) { lan.rekey(kid, key); net?.rekey(kid, key) }
    override fun addLegacyKey(kid: String, key: SecretKey) { lan.addLegacyKey(kid, key); net?.addLegacyKey(kid, key) }
    override fun ban(id: String) { lan.ban(id); net?.ban(id) }
    override fun stop() { lan.stop(); net?.stop() }

    fun relays(): Int = net?.relayCount() ?: -1

    /** Which way each linked operative is reached: "WIFI" or "NET". */
    fun route(id: String): String? = when {
        lan.isConnected(id) -> "WIFI"
        net?.isLinked(id) == true -> "NET"
        else -> null
    }
}
