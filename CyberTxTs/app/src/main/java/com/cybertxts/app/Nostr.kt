package com.cybertxts.app

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * BIP-340 Schnorr signatures on secp256k1, which Nostr relays require on every event.
 * Small and dependency-free; checked against the official BIP-340 test vectors.
 * Only used to sign throwaway relay events (a fresh random key per node session), never your identity.
 */
object Schnorr {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Pt(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    )
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)

    private data class Pt(val x: BigInteger, val y: BigInteger)

    private fun add(a: Pt?, b: Pt?): Pt? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x && a.y != b.y) return null
        val lam = if (a == b) {
            THREE.multiply(a.x).multiply(a.x).multiply(TWO.multiply(a.y).modInverse(P)).mod(P)
        } else {
            b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
        }
        val x = lam.multiply(lam).subtract(a.x).subtract(b.x).mod(P)
        val y = lam.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Pt(x, y)
    }

    private fun mul(pt: Pt, k: BigInteger): Pt? {
        var r: Pt? = null
        for (i in k.bitLength() - 1 downTo 0) {
            r = add(r, r)
            if (k.testBit(i)) r = add(r, pt)
        }
        return r
    }

    private fun b32(x: BigInteger): ByteArray {
        val raw = x.toByteArray()
        val out = ByteArray(32)
        val n = minOf(32, raw.size)
        System.arraycopy(raw, raw.size - n, out, 32 - n, n)
        return out
    }

    private fun int(b: ByteArray) = BigInteger(1, b)

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        parts.forEach { d.update(it) }
        return d.digest()
    }

    private fun tagged(tag: String, vararg parts: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray())
        return sha256(t, t, *parts)
    }

    private fun isEven(y: BigInteger) = !y.testBit(0)

    /** A new random secret key (32 bytes) and its x-only public key (32 bytes). */
    fun newKey(): Pair<ByteArray, ByteArray> {
        val rng = SecureRandom()
        while (true) {
            val sk = ByteArray(32).also { rng.nextBytes(it) }
            val d = int(sk)
            if (d.signum() == 0 || d >= N) continue
            val pub = mul(G, d) ?: continue
            return sk to b32(pub.x)
        }
    }

    fun sign(sk: ByteArray, msg: ByteArray): ByteArray {
        val aux = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val d0 = int(sk)
        val pub = mul(G, d0)!!
        val d = if (isEven(pub.y)) d0 else N.subtract(d0)
        val t = b32(d.xor(int(tagged("BIP0340/aux", aux))))
        val k0 = int(tagged("BIP0340/nonce", t, b32(pub.x), msg)).mod(N)
        require(k0.signum() != 0)
        val r = mul(G, k0)!!
        val k = if (isEven(r.y)) k0 else N.subtract(k0)
        val e = int(tagged("BIP0340/challenge", b32(r.x), b32(pub.x), msg)).mod(N)
        return b32(r.x) + b32(k.add(e.multiply(d)).mod(N))
    }

    /** x-only public key (32 bytes) for a secret key. */
    fun pubOf(sk: ByteArray): ByteArray = b32(mul(G, int(sk))!!.x)

    /** A valid secret key derived from a seed (used for the recovery phrase). Same seed, same key. */
    fun keyFromSeed(seed: ByteArray, label: String): ByteArray {
        var i = 0
        while (true) {
            val sk = sha256(label.toByteArray(), seed, byteArrayOf(i.toByte()))
            val d = int(sk)
            if (d.signum() != 0 && d < N) return sk
            i++
        }
    }

    private fun liftX(x: BigInteger): Pt? {
        if (x >= P) return null
        val c = x.modPow(THREE, P).add(BigInteger.valueOf(7)).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(TWO, P) != c) return null
        return Pt(x, if (isEven(y)) y else P.subtract(y))
    }

    /** BIP-340 verification: is [sig] a valid signature of [msg] by x-only public key [pub]? */
    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = try {
        if (pub.size != 32 || sig.size != 64) false else {
            val pt = liftX(int(pub))
            val r = int(sig.copyOfRange(0, 32)); val s = int(sig.copyOfRange(32, 64))
            if (pt == null || r >= P || s >= N) false else {
                val e = int(tagged("BIP0340/challenge", sig.copyOfRange(0, 32), pub, msg)).mod(N)
                val rr = add(mul(G, s), mul(pt, N.subtract(e)))
                rr != null && isEven(rr.y) && rr.x == r
            }
        }
    } catch (e: Exception) { false }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray? =
        if (s.length % 2 != 0 || !s.all { it in "0123456789abcdefABCDEF" }) null
        else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun sha256Hex(s: String): String = hex(sha256(s.toByteArray()))
    fun sha256Bytes(b: ByteArray): ByteArray = sha256(b)
}

/**
 * Meeting point on public Nostr relays. Phones in the same node publish short "ephemeral" events
 * (relays pass them on but don't store them) under a topic derived from the node code.
 * The event content is always encrypted with the node key, so relays only ever see opaque data.
 */
class NostrPool(
    private val relays: List<String> = DEFAULT_RELAYS,
    private val onContent: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val seen = object : LinkedHashMap<String, Boolean>(512, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 2000
    }
    private val key = Schnorr.newKey()
    private val subId = "c" + Schnorr.sha256Hex(System.nanoTime().toString()).take(12)
    @Volatile private var topic = ""
    @Volatile private var stopped = false

    fun start(topic: String) {
        this.topic = topic
        relays.forEach { url -> scope.launch { connectLoop(url) } }
    }

    private suspend fun connectLoop(url: String) {
        var backoff = 3_000L
        while (!stopped) {
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets[url] = webSocket
                    val filter = JSONObject().put("kinds", JSONArray().put(KIND)).put("#t", JSONArray().put(topic))
                        .put("since", System.currentTimeMillis() / 1000 - 60)
                    webSocket.send(JSONArray().put("REQ").put(subId).put(filter).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
            })
            closed.await()
            sockets.remove(url)
            runCatching { ws.cancel() }
            if (stopped) break
            delay(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun handle(text: String) {
        try {
            val arr = JSONArray(text)
            if (arr.optString(0) != "EVENT") return
            val ev = arr.getJSONObject(2)
            val id = ev.optString("id")
            synchronized(seen) {
                if (seen.containsKey(id)) return
                seen[id] = true
            }
            if (ev.optInt("kind") != KIND) return
            val content = ev.optString("content")
            if (content.isEmpty() || content.length > 400_000) return
            onContent(content)
        } catch (_: Exception) {}
    }

    /** Publish one event (content must be plain base64 text) to every connected relay. */
    fun publish(content: String) {
        if (stopped || topic.isEmpty()) return
        scope.launch {
            try {
                val pub = Schnorr.hex(key.second)
                val created = System.currentTimeMillis() / 1000
                // NIP-01 id: sha256 of [0,pubkey,created_at,kind,tags,content] with no spaces
                val serial = "[0,\"$pub\",$created,$KIND,[[\"t\",\"$topic\"]],\"$content\"]"
                val idBytes = MessageDigest.getInstance("SHA-256").digest(serial.toByteArray())
                val sig = Schnorr.sign(key.first, idBytes)
                val ev = JSONObject()
                    .put("id", Schnorr.hex(idBytes)).put("pubkey", pub).put("created_at", created)
                    .put("kind", KIND).put("tags", JSONArray().put(JSONArray().put("t").put(topic)))
                    .put("content", content).put("sig", Schnorr.hex(sig))
                val msg = JSONArray().put("EVENT").put(ev).toString()
                sockets.values.forEach { runCatching { it.send(msg) } }
            } catch (_: Exception) {}
        }
    }

    fun connectedRelays(): Int = sockets.size

    fun stop() {
        stopped = true
        sockets.values.forEach { runCatching { it.close(1000, null) } }
        sockets.clear()
        scope.cancel()
    }

    companion object {
        /** Ephemeral kind (20000-29999): relays forward these but never store them. */
        const val KIND = 25051
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.nostr.band"
        )
    }
}
