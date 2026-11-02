package com.cybertxts.app

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

data class Msg(
    val id: String, val senderId: String, val senderName: String,
    val text: String, val ts: Long, val system: Boolean = false,
    val image: String? = null, val imgW: Int = 0, val imgH: Int = 0,
    val voice: String? = null, val durMs: Long = 0,
    val video: String? = null,
    val replyTo: String? = null,     // id of the message this one answers
    val dm: String? = null,          // private thread: the other operative's ID (null = whole node)
    val reactions: Map<String, Set<String>> = emptyMap(),   // emoji -> who reacted
    val edited: Boolean = false,
    val deleted: Boolean = false
)

/** created = true when this phone built the node (the creator is its host). */
data class RoomInfo(val code: String, val password: String, val name: String, val created: Boolean = false)

data class Member(val id: String, val name: String, val isHost: Boolean, val isMe: Boolean)

data class VoteInfo(val target: String, val yes: Set<String>, val no: Set<String>, val startedAt: Long)

object RoomCodes {
    private val words = listOf(
        "ORION", "NEBULA", "PULSAR", "QUASAR", "NOVA", "COMET", "VEGA", "LYRA", "SIRIUS", "ANDROMEDA",
        "CYGNUS", "DRACO", "HELIX", "ZENITH", "ECLIPSE", "PHOTON", "TACHYON", "IONIC",
        "VECTOR", "NEXUS", "CIPHER", "SPECTRA", "ORBITAL", "SOLARIS", "KEPLER", "POLARIS"
    )
    private val rng = SecureRandom()

    /** A space word plus 7 random digits, e.g. NEBULA-0482917 */
    fun generate(): String =
        "${words[rng.nextInt(words.size)]}-${rng.nextInt(10_000_000).toString().padStart(7, '0')}"

    /** Random passphrase with 80 bits of entropy, e.g. K7QM-2XWD-9PLR-HT4B. Used after a removal. */
    fun newPassphrase(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (0 until 4).joinToString("-") {
            (0 until 4).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}

/**
 * One node (chat). Owns its own connections, keys, members, votes and history.
 * The phone can be aboard several nodes at once; ChatViewModel manages the list.
 */
class NodeSession(
    private val app: Application,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val store: HistoryStore,
    private val vault: PhotoVault,
    val people: PeopleBook,
    val myId: String,
    private val myPub: ByteArray,
    private val ecdh: Pair<PrivateKey, ByteArray>,
    val settings: StateFlow<AppSettings>,
    initial: RoomInfo,
    private val onChanged: () -> Unit,              // password or member count changed: manager saves / refreshes
    private val onNotice: (String) -> Unit,
    private val onLeft: (NodeSession) -> Unit,
    private val viewing: (String) -> Boolean        // is this node the one on screen?
) {
    val tag: String = Crypto.roomTag(initial.code)
    private val myEk: ByteArray = ecdh.second

    val room = MutableStateFlow(initial)
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val peers = MutableStateFlow(0)
    val connected = MutableStateFlow<Set<String>>(emptySet())
    val known = MutableStateFlow<Map<String, String>>(emptyMap())   // id -> display name
    val hostId = MutableStateFlow<String?>(null)
    val votes = MutableStateFlow<Map<String, VoteInfo>>(emptyMap())
    val unread = MutableStateFlow(0)
    /** Your own private name for this node. Blank means "show the code". Others never see it. */
    val nickname = MutableStateFlow(prefs.getString("nick_$tag", "") ?: "")

    private var transport: Transport? = null
    private val seen = HashSet<String>()
    private val banned = HashSet<String>()
    private var saveJob: Job? = null
    private var bgJob: Job? = null
    @Volatile private var closed = false

    // ---- key rotation state ----
    private val eks = ConcurrentHashMap<String, ByteArray>()                           // member id -> key-exchange public key
    private val seenAt = ConcurrentHashMap<String, Long>()                             // member id -> first seen (ms)
    private val evidence = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()   // target -> signed kick/vote envelopes
    private val bundles = CopyOnWriteArrayList<JSONObject>()                           // key-change bundles we have applied
    private val excluded = ConcurrentHashMap.newKeySet<String>()                       // removed members the current key already locks out
    private val prevPasses = ArrayList<String>()                                       // recent older passphrases
    private val rekeyLock = Mutex()
    @Volatile private var curPass = ""
    @Volatile private var curKid = ""

    // ---------- Start / leave ----------

    suspend fun start() {
        val r = room.value
        val key = withContext(Dispatchers.Default) { Crypto.deriveKey(r.password, r.code) }
        curPass = r.password
        curKid = Crypto.kid(key)

        synchronized(banned) {
            banned.clear()
            banned.addAll(prefs.getStringSet("bans_$tag", emptySet()) ?: emptySet())
        }
        hostId.value = if (r.created) myId else prefs.getString("host_$tag", null)
        if (r.created) prefs.edit().putString("host_$tag", myId).apply()

        eks.clear(); seenAt.clear(); evidence.clear(); bundles.clear(); excluded.clear(); prevPasses.clear()
        excluded.addAll(prefs.getStringSet("excl_$tag", emptySet()) ?: emptySet())
        readJsonStrings(prefs.getString("bundles_$tag", null)).forEach { s ->
            runCatching { bundles.add(JSONObject(s)) }
        }
        prevPasses.addAll(readJsonStrings(prefs.getString("prev_$tag", null)))

        val s = settings.value
        val history = if (s.keepHistory) withContext(Dispatchers.IO) { store.load(tag, s.retentionDays) } else emptyList()
        synchronized(seen) { seen.clear(); history.forEach { seen.add(it.id) } }
        messages.value = history
        // Photos: honour the "keep photos" and history settings
        withContext(Dispatchers.IO) {
            if (!s.keepHistory) vault.delete(tag) else vault.prune(tag, keepKeys(history, s))
            app.cacheDir.listFiles { _, nm -> nm.startsWith("vin_") || nm.startsWith("vout_") || nm.startsWith("play_") }
                ?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        }

        // WiFi always; internet too unless switched off in Settings
        val t = MultiTransport(app, settings.value.internet)
        synchronized(banned) { banned.forEach { t.ban(it) } }
        transport = t
        t.start(myId, tag, curKid, key, ::onFrame, ::onPeers, ::rescueFor)

        // Older keys let us help members who missed a key change (derived in the background)
        val old = prevPasses.toList()
        scope.launch(Dispatchers.Default) {
            old.forEach { p ->
                val k = Crypto.deriveKey(p, r.code)
                transport?.addLegacyKey(Crypto.kid(k), k)
            }
        }

        // Delivery / read receipts go out in small batches
        scope.launch(Dispatchers.Default) {
            while (isActive && !closed) { delay(1_500); runCatching { flushReceipts() } }
        }

        bgJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(20_000)
                announce()
                sendPing()
                purgeVotes()
                sweepTransfers()
                val pending = synchronized(banned) {
                    banned.any { it !in excluded && evidence[it]?.isNotEmpty() == true }
                }
                if (pending) scheduleRekey()
            }
        }
    }

    fun leave(eraseHistory: Boolean = false) {
        if (closed) return
        closed = true
        val t = tag
        val snapshot = saveable()
        val s = settings.value
        bgJob?.cancel(); saveJob?.cancel()
        transport?.stop(); transport = null
        synchronized(voices) { voices.clear() }
        dropAssemblies()
        prefs.edit().remove("excl_$t").remove("bundles_$t").remove("prev_$t").remove("nick_$t").remove("mute_$t").apply()
        scope.launch(Dispatchers.IO) {
            if (eraseHistory || !s.keepHistory) store.delete(t) else store.save(t, snapshot, s.retentionDays)
            if (eraseHistory || !s.keepHistory) vault.delete(t) else vault.prune(t, keepKeys(snapshot, s))
        }
        onLeft(this)
    }

    /** Stop without forgetting anything (app is going away). */
    fun close() {
        bgJob?.cancel(); saveJob?.cancel()
        if (settings.value.keepHistory) runCatching { store.save(tag, saveable(), settings.value.retentionDays) }
        transport?.stop(); transport = null
        synchronized(voices) { voices.clear() }
        dropAssemblies()
    }

    // ---------- Sending ----------

    fun send(text: String, replyTo: String? = null, dmTo: String? = null) {
        val r = room.value
        val t = text.trim().ifEmpty { return }
        if (dmTo != null) { sendDm(dmTo, t, replyTo); return }
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        val re = replyTo ?: ""
        add(Msg(id, myId, r.name, t, ts, replyTo = replyTo))
        scope.launch(Dispatchers.Default) {
            val sig = Identity.sign("msg|$tag|$id|$myId|$ts|$t|$re".toByteArray())
            transport?.send(JSONObject().put("type", "msg").put("id", id).put("sid", myId)
                .put("n", r.name).put("text", t).put("ts", ts).put("re", re)
                .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
        }
    }

    private fun announce() {
        val r = room.value
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val host = hostId.value == myId
            val ek = b64(myEk)
            val lk = myLinks().entries.sortedBy { it.key }.joinToString(",") { it.key + ":" + it.value }
            val rl = settings.value.relay
            val sig = Identity.sign("hi|$tag|$myId|${r.name}|$host|$ts|$ek|$lk|$rl".toByteArray())
            transport?.send(JSONObject().put("type", "hi").put("sid", myId).put("n", r.name)
                .put("h", host).put("ts", ts).put("ek", ek).put("lk", lk).put("rl", rl)
                .put("pk", b64(myPub)).put("sig", b64(sig))
                .toString().toByteArray())
        }
    }

    // ---------- Receiving ----------

    private fun onPeers(ids: Set<String>) {
        if (closed) return
        val before = connected.value
        val now = System.currentTimeMillis()
        ids.forEach { seenAt.putIfAbsent(it, now); lastSeen[it] = now; linkedSince.putIfAbsent(it, now) }
        (before - ids).forEach { linkedSince.remove(it); rtt.remove(it); lastSeen[it] = now }
        connected.value = ids
        peers.value = ids.size
        if (ids != before) announce()
        onChanged()
    }

    private fun onFrame(bytes: ByteArray) {
        if (closed) return
        try {
            val j = JSONObject(bytes.toString(Charsets.UTF_8))
            val type = j.getString("type")
            rxBytes.addAndGet(bytes.size.toLong())
            val origin = when (type) {
                "mc" -> mediaOrigin[j.optString("id")]
                "ping", "pong", "full" -> null
                else -> j.optString("sid").takeIf { it.length == 32 }
            }
            if (origin != null && origin != myId) noteSeen(origin, bytes.size)
            when (type) {
                "ping" -> handlePing(j)
                "pong" -> handlePong(j)
                "dm" -> handleDm(j)
                "rcpt" -> handleRcpt(j)
                "typing" -> handleTyping(j)
                "react" -> handleReact(j)
                "edit" -> handleEdit(j)
                "full" -> if (connected.value.isEmpty()) {
                    onNotice("That node is full (${Limits.MAX_ROOM} operatives max).")
                    leave(false)
                }
                "msg" -> handleMsg(j)
                "media" -> handleMediaHeader(j)
                "mc" -> handleMediaChunk(j)
                "hi" -> handleHi(j)
                "kick" -> handleKick(j)
                "kv" -> handleVote(j)
                "rekey" -> scope.launch(Dispatchers.Default) { acceptBundle(j) }
            }
            if (origin != null) maybeRelay(origin, bytes)
        } catch (_: Exception) {}
    }

    private fun verified(j: JSONObject, data: String): Boolean = try {
        val pk = Base64.decode(j.getString("pk"), Base64.NO_WRAP)
        val sig = Base64.decode(j.getString("sig"), Base64.NO_WRAP)
        j.getString("sid") == Identity.idFromPublicKey(pk) && Identity.verify(pk, data.toByteArray(), sig)
    } catch (e: Exception) { false }

    private fun isBanned(id: String) = synchronized(banned) { banned.contains(id) }

    private fun handleMsg(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid)) return
        val id = j.getString("id"); val n = j.getString("n")
        val text = j.getString("text"); val ts = j.getLong("ts")
        if (text.length > 4000) return
        val re = j.optString("re", "")
        if (re.length > 64) return
        if (!verified(j, "msg|$tag|$id|$sid|$ts|$text|$re")) return
        people.seen(sid, n)
        val shown = people.alias(sid, n)
        known.update { it + (sid to shown) }
        add(Msg(id, sid, shown, text, ts, replyTo = re.ifEmpty { null }))
        if (sid != myId) pendingDelivered.add(id)
        if (sid != myId) {
            if (!(AppState.foreground && viewing(tag))) unread.update { it + 1 }
            if (!AppState.foreground && !muted.value) Notifier.message(app, displayName(), people.label(sid, n), text)
        }
    }

    private fun handleHi(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid)) return
        val n = j.getString("n"); val h = j.getBoolean("h"); val ts = j.getLong("ts")
        val ek = j.optString("ek")
        val lk = j.optString("lk")
        val rl = j.optBoolean("rl", false)
        if (lk.length > 2_000) return
        if (!verified(j, "hi|$tag|$sid|$n|$h|$ts|$ek|$lk|$rl")) return
        if (Math.abs(System.currentTimeMillis() - ts) < REPORT_TTL) {
            val links = lk.split(",").mapNotNull { e ->
                val p = e.split(":")
                if (p.size == 2 && p[0].length == 32 && (p[1] == "WIFI" || p[1] == "NET")) p[0] to p[1] else null
            }.toMap()
            reports[sid] = Report(links, rl, System.currentTimeMillis())
            seenAt.putIfAbsent(sid, System.currentTimeMillis())
        }
        people.seen(sid, n)
        known.update { it + (sid to people.alias(sid, n)) }
        if (ek.isNotEmpty()) eks[sid] = Base64.decode(ek, Base64.NO_WRAP)
        // Host claim: trust the first one we see (see TODO: verify via invite).
        if (h && hostId.value == null && sid != myId) {
            hostId.value = sid
            prefs.edit().putString("host_$tag", sid).apply()
        }
    }

    // ---------- Star map: link reports, delay, activity ----------
    //
    // Every operative's signed "hi" now carries its list of direct links (id:WIFI/NET) and whether it relays.
    // From those reports each phone can draw the whole mesh, spot pairs that can't link directly, and pick
    // exactly one operative to forward frames between them.

    private class Report(val links: Map<String, String>, val relay: Boolean, val at: Long)

    data class MapPeer(
        val id: String, val name: String, val route: String?, val linked: Boolean, val viaRelay: String?,
        val lastSeen: Long, val rttMs: Long?, val since: Long?, val rx: Long, val active: Boolean, val host: Boolean
    )
    /** route == null means the two have no direct link; relayVia names who forwards between them (if anyone). */
    data class MeshEdge(val a: String, val b: String, val route: String?, val relayVia: String?)
    data class MapSnapshot(val peers: List<MapPeer>, val edges: List<MeshEdge>, val tx: Long, val rx: Long, val relays: Int)

    private val reports = ConcurrentHashMap<String, Report>()
    private val linkedSince = ConcurrentHashMap<String, Long>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val lastActive = ConcurrentHashMap<String, Long>()
    private val rtt = ConcurrentHashMap<String, Long>()
    private val rxFrom = ConcurrentHashMap<String, Long>()
    private val rxBytes = java.util.concurrent.atomic.AtomicLong()
    private val mediaOrigin = ConcurrentHashMap<String, String>()
    private val relayed = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 4000
    }

    private fun fresh(r: Report?) = r != null && System.currentTimeMillis() - r.at < REPORT_TTL

    /** Everyone reachable right now: direct links plus operatives others report as linked (reached via a relay). */
    fun reachable(): Set<String> {
        val banSnap = synchronized(banned) { HashSet(banned) }
        val out = HashSet(connected.value)
        reports.forEach { (id, r) -> if (fresh(r)) out.add(id) }
        return out - banSnap - myId
    }

    private fun myLinks(): Map<String, String> = connected.value.associateWith { route(it) ?: "WIFI" }

    /** true / false if we know whether a and b are directly linked, null if nobody has reported on them. */
    private fun linkedPair(a: String, b: String): Boolean? {
        if (a == myId) return b in connected.value
        if (b == myId) return a in connected.value
        val ra = reports[a]?.takeIf { fresh(it) }
        val rb = reports[b]?.takeIf { fresh(it) }
        if (ra == null && rb == null) return null
        return (ra?.links?.containsKey(b) == true) || (rb?.links?.containsKey(a) == true)
    }

    private fun relays(id: String) = if (id == myId) settings.value.relay else reports[id]?.relay == true

    /** The single operative who forwards between a and b: smallest ID that relays and is linked to both. */
    private fun chosenRelay(a: String, b: String): String? =
        (reachable() + myId).filter {
            it != a && it != b && relays(it) && linkedPair(it, a) == true && linkedPair(it, b) == true
        }.minOrNull()

    /** Forward a frame from [origin] to my direct links that can't reach origin themselves (one hop). */
    private fun maybeRelay(origin: String, bytes: ByteArray) {
        if (!settings.value.relay || origin == myId) return
        val targets = connected.value.filter { p ->
            p != origin && linkedPair(p, origin) == false && chosenRelay(origin, p) == myId
        }.toSet()
        if (targets.isEmpty()) return
        val key = Crypto.sha256Hex(bytes)
        synchronized(relayed) {
            if (relayed.containsKey(key)) return
            relayed[key] = true
        }
        transport?.sendTo(targets, bytes)
    }

    private fun noteSeen(sid: String, size: Int) {
        val now = System.currentTimeMillis()
        lastSeen[sid] = now
        lastActive[sid] = now
        rxFrom.merge(sid, size.toLong()) { a, b -> a + b }
    }

    private fun sendPing() {
        transport?.send(JSONObject().put("type", "ping").put("sid", myId)
            .put("ts", System.currentTimeMillis()).toString().toByteArray())
    }

    private fun handlePing(j: JSONObject) {
        val sid = j.optString("sid")
        if (sid == myId || sid !in connected.value) return
        transport?.sendTo(setOf(sid), JSONObject().put("type", "pong").put("sid", myId).put("to", sid)
            .put("ts", j.optLong("ts")).toString().toByteArray())
    }

    private fun handlePong(j: JSONObject) {
        if (j.optString("to") != myId) return
        val d = System.currentTimeMillis() - j.optLong("ts")
        if (d in 0..60_000) rtt[j.optString("sid")] = d
    }

    fun mapSnapshot(): MapSnapshot {
        val now = System.currentTimeMillis()
        val conn = connected.value
        val reach = reachable()
        val names = known.value
        val host = hostId.value
        val banSnap = synchronized(banned) { HashSet(banned) }
        val ids = (conn + reach + lastSeen.filter { now - it.value < SEEN_TTL }.keys) - myId - banSnap
        val list = ids.map { id ->
            val direct = id in conn
            MapPeer(
                id = id, name = people.label(id, names[id] ?: "Unknown"),
                route = if (direct) route(id) else null, linked = direct || id in reach,
                viaRelay = if (!direct && id in reach) chosenRelay(id, myId) else null,
                lastSeen = lastSeen[id] ?: 0L, rttMs = if (direct) rtt[id] else null, since = linkedSince[id],
                rx = rxFrom[id] ?: 0L, active = now - (lastActive[id] ?: 0L) < 2_500, host = id == host
            )
        }.sortedBy { it.name.lowercase() }
        val online = (reach + myId).sorted()
        val edges = ArrayList<MeshEdge>()
        for (i in online.indices) for (k in i + 1 until online.size) {
            val a = online[i]; val b = online[k]
            val lp = linkedPair(a, b) ?: continue
            if (lp) {
                val r = when {
                    a == myId -> route(b)
                    b == myId -> route(a)
                    else -> reports[a]?.links?.get(b) ?: reports[b]?.links?.get(a)
                }
                edges.add(MeshEdge(a, b, r ?: "NET", null))
            } else {
                edges.add(MeshEdge(a, b, null, chosenRelay(a, b)))
            }
        }
        val mt = transport as? MultiTransport
        return MapSnapshot(list, edges, mt?.txBytes() ?: 0L, rxBytes.get(), relayCount())
    }

    /** Operative IDs this node knows about (for the constellation view). */
    fun memberIds(): Set<String> = (known.value.keys + reachable()) - myId - synchronized(banned) { HashSet(banned) }

    // ---------- Everyday: private messages, receipts, typing, reactions, edit/delete, mute, cancel/retry ----------

    /** Private thread counts by operative ID, and which thread is on screen right now. */
    val dmUnread = MutableStateFlow<Map<String, Int>>(emptyMap())
    @Volatile var viewingDm: String? = null

    /** For each of MY messages: (delivered to N operatives, read by M). */
    val receipts = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    private val deliveredBy = ConcurrentHashMap<String, MutableSet<String>>()
    private val readBy = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingDelivered = ConcurrentHashMap.newKeySet<String>()
    private val pendingRead = ConcurrentHashMap.newKeySet<String>()
    private val readReported = ConcurrentHashMap.newKeySet<String>()

    /** Who is typing: key = operative ID (node chat) or "dm:ID" (to me privately); value = expiry time. */
    val typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    @Volatile private var lastTypingSent = 0L

    val muted = MutableStateFlow(prefs.getBoolean("mute_$tag", false))
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun setMuted(on: Boolean) {
        muted.value = on
        prefs.edit().putBoolean("mute_$tag", on).apply()
        onChanged()
    }

    fun openDm(peer: String?) {
        viewingDm = peer
        if (peer != null) dmUnread.update { it - peer }
    }

    // ---- private messages: sealed to the recipient's key, so other operatives (and relays) can't read them ----

    fun canDm(peer: String) = eks.containsKey(peer)

    private fun sendDm(to: String, t: String, replyTo: String?) {
        val r = room.value
        val pub = eks[to]
        if (pub == null) {
            onNotice("Can't message them privately yet: waiting for their key. Try again in a few seconds.")
            return
        }
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        add(Msg(id, myId, r.name, t, ts, replyTo = replyTo, dm = to))
        scope.launch(Dispatchers.Default) {
            val body = JSONObject().put("text", t).put("re", replyTo ?: "").toString().toByteArray()
            val (eph, ct) = Crypto.wrapFor(pub, to, body, "dm|$tag|$id".toByteArray())
            val e = b64(eph); val c = b64(ct)
            val sig = Identity.sign("dm|$tag|$id|$myId|$to|$ts|${Crypto.sha256Hex((e + c).toByteArray())}".toByteArray())
            transport?.send(JSONObject().put("type", "dm").put("id", id).put("sid", myId).put("n", r.name)
                .put("to", to).put("ts", ts).put("e", e).put("c", c)
                .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
        }
    }

    private fun handleDm(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid) || j.optString("to") != myId) return     // not for me (a relay just passes it on)
        val id = j.getString("id"); val n = j.getString("n"); val ts = j.getLong("ts")
        val e = j.getString("e"); val c = j.getString("c")
        if (c.length > 20_000) return
        if (!verified(j, "dm|$tag|$id|$sid|$myId|$ts|${Crypto.sha256Hex((e + c).toByteArray())}")) return
        val plain = Crypto.unwrap(ecdh.first, Base64.decode(e, Base64.NO_WRAP), myId,
            Base64.decode(c, Base64.NO_WRAP), "dm|$tag|$id".toByteArray()) ?: return
        val body = JSONObject(plain.toString(Charsets.UTF_8))
        val text = body.optString("text").take(4000)
        val re = body.optString("re").ifEmpty { null }
        people.seen(sid, n)
        val shown = people.alias(sid, n)
        known.update { it + (sid to shown) }
        val before = messages.value.size
        add(Msg(id, sid, shown, text, ts, replyTo = re, dm = sid))
        if (messages.value.size == before) return                  // duplicate
        pendingDelivered.add(id)
        val onScreen = AppState.foreground && viewing(tag) && viewingDm == sid
        if (!onScreen) {
            dmUnread.update { it + (sid to ((it[sid] ?: 0) + 1)) }
            unread.update { it + 1 }
        }
        if (!AppState.foreground && !muted.value) Notifier.message(app, displayName(), people.label(sid, n) + " · PRIVATE", text)
    }

    // ---- delivery and read receipts (batched every 1.5 s) ----

    /** Call while a chat (node or private thread) is on screen: everything visible counts as read. */
    fun markRead(dmPeer: String?) {
        messages.value.forEach { m ->
            if (m.senderId != myId && !m.system && m.dm == dmPeer && readReported.add(m.id)) pendingRead.add(m.id)
        }
    }

    private fun flushReceipts() {
        if (pendingDelivered.isEmpty() && pendingRead.isEmpty()) return
        val d = pendingDelivered.take(200); pendingDelivered.removeAll(d.toSet())
        val r = pendingRead.take(200); pendingRead.removeAll(r.toSet())
        transport?.send(JSONObject().put("type", "rcpt").put("sid", myId)
            .put("d", JSONArray(d)).put("r", JSONArray(r)).toString().toByteArray())
    }

    private fun handleRcpt(j: JSONObject) {
        val sid = j.optString("sid")
        if (sid.length != 32 || sid == myId || isBanned(sid)) return
        val mine = messages.value.filter { it.senderId == myId }.map { it.id }.toHashSet()
        fun ids(key: String) = j.optJSONArray(key)?.let { a -> (0 until minOf(a.length(), 200)).map { a.getString(it) } } ?: emptyList()
        var changed = false
        ids("d").filter { it in mine }.forEach { if (deliveredBy.getOrPut(it) { ConcurrentHashMap.newKeySet() }.add(sid)) changed = true }
        ids("r").filter { it in mine }.forEach {
            deliveredBy.getOrPut(it) { ConcurrentHashMap.newKeySet() }.add(sid)
            if (readBy.getOrPut(it) { ConcurrentHashMap.newKeySet() }.add(sid)) changed = true
        }
        if (changed) {
            receipts.value = deliveredBy.keys.associateWith { (deliveredBy[it]?.size ?: 0) to (readBy[it]?.size ?: 0) }
        }
    }

    // ---- typing indicator ----

    fun typingNow(dmTo: String?) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 3_000) return
        lastTypingSent = now
        transport?.send(JSONObject().put("type", "typing").put("sid", myId).put("to", dmTo ?: "")
            .toString().toByteArray())
    }

    private fun handleTyping(j: JSONObject) {
        val sid = j.optString("sid"); val to = j.optString("to")
        if (sid.length != 32 || sid == myId || isBanned(sid)) return
        val key = when (to) { "" -> sid; myId -> "dm:$sid"; else -> return }
        val now = System.currentTimeMillis()
        typing.update { m -> m.filterValues { it > now } + (key to now + 6_000) }
    }

    // ---- reactions ----

    fun react(msgId: String, emoji: String) {
        if (emoji !in REACTIONS) return
        val m = messages.value.firstOrNull { it.id == msgId } ?: return
        val on = myId !in (m.reactions[emoji] ?: emptySet())
        applyReaction(msgId, myId, emoji, on)
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("react|$tag|$msgId|$myId|$emoji|$on|$ts".toByteArray())
            transport?.send(JSONObject().put("type", "react").put("sid", myId).put("id", msgId).put("e", emoji)
                .put("on", on).put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
        }
    }

    private fun handleReact(j: JSONObject) {
        val sid = j.getString("sid"); val id = j.getString("id"); val e = j.getString("e")
        val on = j.getBoolean("on"); val ts = j.getLong("ts")
        if (isBanned(sid) || e !in REACTIONS) return
        if (!verified(j, "react|$tag|$id|$sid|$e|$on|$ts")) return
        applyReaction(id, sid, e, on)
    }

    private fun applyReaction(msgId: String, who: String, e: String, on: Boolean) {
        messages.update { list ->
            list.map { m ->
                if (m.id != msgId) m else {
                    val set = (m.reactions[e] ?: emptySet()).let { if (on) it + who else it - who }
                    m.copy(reactions = if (set.isEmpty()) m.reactions - e else m.reactions + (e to set))
                }
            }
        }
        scheduleSave()
    }

    // ---- edit and delete your own messages ----

    fun edit(msgId: String, newText: String) {
        val t = newText.trim().ifEmpty { return }.take(4000)
        val m = messages.value.firstOrNull { it.id == msgId } ?: return
        if (m.senderId != myId || m.dm != null || m.image != null || m.video != null || m.voice != null) return
        applyEdit(msgId, myId, t, false)
        sendEdit(msgId, t, false)
    }

    fun delete(msgId: String) {
        val m = messages.value.firstOrNull { it.id == msgId } ?: return
        if (m.senderId != myId) return
        applyEdit(msgId, myId, "", true)
        sendEdit(msgId, "", true)
    }

    private fun sendEdit(msgId: String, t: String, del: Boolean) {
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("edit|$tag|$msgId|$myId|$ts|$t|$del".toByteArray())
            transport?.send(JSONObject().put("type", "edit").put("sid", myId).put("id", msgId).put("text", t)
                .put("del", del).put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
        }
    }

    private fun handleEdit(j: JSONObject) {
        val sid = j.getString("sid"); val id = j.getString("id"); val t = j.optString("text")
        val del = j.optBoolean("del"); val ts = j.getLong("ts")
        if (isBanned(sid) || t.length > 4000) return
        if (!verified(j, "edit|$tag|$id|$sid|$ts|$t|$del")) return
        applyEdit(id, sid, t, del)
    }

    /** Only the original sender can change a message; a deleted message also loses its stored media. */
    private fun applyEdit(msgId: String, who: String, t: String, del: Boolean) {
        var target: Msg? = null
        messages.update { list ->
            list.map { m ->
                if (m.id != msgId || m.senderId != who) m else {
                    target = m
                    if (del) m.copy(text = "", deleted = true, image = null, video = null, voice = null, reactions = emptyMap())
                    else if (m.dm == null && !m.deleted) m.copy(text = t, edited = true) else m
                }
            }
        }
        val gone = target
        if (del && gone != null) {
            scope.launch(Dispatchers.IO) {
                gone.image?.let { vault.deleteItem(tag, it) }
                gone.video?.let { vault.deleteItem(tag, videoKey(it)); vault.deleteItem(tag, thumbKey(it)) }
                gone.voice?.let { v -> synchronized(voices) { voices.remove(v) } }
            }
        }
        scheduleSave()
    }

    // ---- cancel / retry / dismiss media ----

    fun cancelTransfer(id: String) {
        cancelled.add(id)
        jobs.remove(id)?.cancel()
        preparing.update { it - id }
        setProgress(id, -1f)
    }

    /** Remove a failed item from the chat on this phone only. */
    fun dismiss(id: String) {
        messages.update { list -> list.filterNot { it.id == id } }
        setProgress(id, null)
        scheduleSave()
    }

    /** Send a failed photo, voice note or video again (as a fresh item). */
    fun retry(m: Msg) {
        if (m.senderId != myId) return
        scope.launch(Dispatchers.IO) {
            val newId = UUID.randomUUID().toString()
            when {
                m.image != null -> {
                    val bytes = vault.read(tag, m.image) ?: run { onNotice("That photo is no longer stored. Pick it again."); return@launch }
                    dismiss(m.id)
                    vault.save(tag, newId, bytes)
                    sendMedia(newId, KIND_IMAGE, bytes, m.imgW, m.imgH, 0L)
                }
                m.voice != null -> {
                    val bytes = synchronized(voices) { voices[m.voice] } ?: run { onNotice("That voice note is gone. Record it again."); return@launch }
                    dismiss(m.id)
                    rememberVoice(newId, bytes)
                    sendMedia(newId, KIND_VOICE, bytes, 0, 0, m.durMs)
                }
                m.video != null -> {
                    val tmp = File(app.cacheDir, "vout_$newId.mp4")
                    if (!vault.decryptTo(tag, videoKey(m.video), tmp)) {
                        onNotice("That video never finished preparing. Pick it again.")
                        return@launch
                    }
                    val thumb = vault.read(tag, thumbKey(m.video))
                    dismiss(m.id)
                    try {
                        vault.saveFrom(tag, videoKey(newId), tmp)
                        thumb?.let { vault.save(tag, thumbKey(newId), it) }
                        val ts = System.currentTimeMillis()
                        add(Msg(newId, myId, room.value.name, "", ts, imgW = m.imgW, imgH = m.imgH, durMs = m.durMs, video = newId))
                        sendVideoFile(newId, ts, tmp, VideoUtil.Info(m.durMs, m.imgW, m.imgH, thumb))
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
    }

    // ---------- Naming ----------

    fun displayName(): String = nickname.value.ifBlank { room.value.code }

    /** How a linked operative is reached right now: "WIFI", "NET", or null. */
    fun route(id: String): String? = (transport as? MultiTransport)?.route(id)

    /** True when the host is this phone, or was pinned from a QR / link invite (so can't be spoofed). */
    val hostVerified: Boolean get() = room.value.created || prefs.getBoolean("hostpin_$tag", false)

    /** Invite for this node; includes the host's fingerprint when we know who the host is. */
    fun invite(): Invite = Invite(room.value.code, room.value.password, hostId.value)

    /** Relays this phone is connected to for this node (-1 if the internet link is off). */
    fun relayCount(): Int = (transport as? MultiTransport)?.relays() ?: 0

    fun rename(name: String) {
        val n = name.trim().take(30)
        if (n.isEmpty()) prefs.edit().remove("nick_$tag").apply()
        else prefs.edit().putString("nick_$tag", n).apply()
        nickname.value = n
        onChanged()
    }

    // ---------- Photos, voice notes and videos ----------
    //
    // Each item is sent as one signed header plus small encrypted pieces. The header lists a fingerprint for
    // every piece, so each piece is checked the moment it arrives: a junk piece from anyone else is thrown away
    // and the real one is still accepted. Pieces without a genuine signed header are ignored entirely.
    // Photos and videos are stored encrypted on the phone. Voice notes are kept in memory only: they vanish
    // when you leave the node or the app is fully closed, and are never written to history.
    // Videos are written piece by piece to a private temporary file while arriving (they can be large).

    private class Assembly(val header: JSONObject, val hashes: List<String>, val file: File?) {
        val chunks = ConcurrentHashMap<Int, ByteArray>()     // photos and voice notes: pieces in memory
        val got = ConcurrentHashMap.newKeySet<Int>()          // which pieces have arrived
        val raf: RandomAccessFile? = file?.let { RandomAccessFile(it, "rw") }
        @Volatile var lastActivity = System.currentTimeMillis()
        @Volatile var done = false

        fun discard() {
            runCatching { raf?.close() }
            file?.delete()
        }
    }

    private val assemblies = ConcurrentHashMap<String, Assembly>()
    private val voices = LinkedHashMap<String, ByteArray>()   // guarded by synchronized(voices)

    /** Progress per item: 0..1 while moving, -1 if it failed. Absent once it is done. */
    val transfers = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Your own videos being shrunk before sending: 0..1 */
    val preparing = MutableStateFlow<Map<String, Float>>(emptyMap())

    fun readImage(m: Msg): ByteArray? = m.image?.let { vault.read(tag, it) }
    fun readVoice(m: Msg): ByteArray? = m.voice?.let { synchronized(voices) { voices[it] } }
    fun readThumb(m: Msg): ByteArray? = m.video?.let { vault.read(tag, thumbKey(it)) }
    fun hasVideo(m: Msg): Boolean = m.video?.let { vault.exists(tag, videoKey(it)) } ?: false
    fun exportVideo(m: Msg, dest: File): Boolean = m.video?.let { vault.decryptTo(tag, videoKey(it), dest) } ?: false
    fun copyVideo(m: Msg, out: OutputStream): Boolean = m.video?.let { vault.copyTo(tag, videoKey(it), out) } ?: false

    private fun setProgress(id: String, p: Float?) {
        transfers.update { if (p == null) it - id else it + (id to p) }
    }

    private fun rememberVoice(id: String, bytes: ByteArray) {
        synchronized(voices) {
            voices[id] = bytes
            var total = voices.values.sumOf { it.size.toLong() }
            val iter = voices.entries.iterator()
            while (total > MAX_VOICE_MEMORY && iter.hasNext()) {
                val e = iter.next()
                if (e.key == id) continue
                total -= e.value.size
                iter.remove()
            }
        }
    }

    /** What goes into saved history: never voice notes, and no incoming photos/videos that didn't finish. */
    private fun saveable(): List<Msg> {
        val moving = transfers.value
        return messages.value.filter { m ->
            m.voice == null &&
                !((m.image != null || m.video != null) && m.senderId != myId && moving.containsKey(m.id))
        }
    }

    /** Which stored files to keep, given the history being kept and your settings. */
    private fun keepKeys(list: List<Msg>, s: AppSettings): Set<String> {
        val keep = HashSet<String>()
        if (s.keepImages) list.forEach { m -> m.image?.let { keep.add(it) } }
        if (s.keepVideos) list.forEach { m -> m.video?.let { keep.add(videoKey(it)); keep.add(thumbKey(it)) } }
        return keep
    }

    private fun dropAssemblies() {
        assemblies.values.forEach { it.discard() }
        assemblies.clear()
    }

    fun sendPhoto(uri: Uri, q: Quality = Quality.STANDARD) {
        scope.launch(Dispatchers.IO) {
            val res = if (q == Quality.ORIGINAL) ImageUtil.original(app, uri)
                      else ImageUtil.compress(app, uri, q.photoSide, q.photoTarget)
            if (res == null) {
                onNotice(if (q == Quality.ORIGINAL) "That photo is over 25 MB or couldn't be read. Try High quality."
                         else "Couldn't read that photo.")
                return@launch
            }
            val id = UUID.randomUUID().toString()
            vault.save(tag, id, res.bytes)
            sendMedia(id, KIND_IMAGE, res.bytes, res.w, res.h, 0L)
        }
    }

    fun sendVoice(bytes: ByteArray, durMs: Long) {
        if (bytes.isEmpty() || bytes.size > MAX_MEDIA) {
            onNotice("That voice note is too large to send.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            rememberVoice(id, bytes)
            sendMedia(id, KIND_VOICE, bytes, 0, 0, durMs)
        }
    }

    fun sendVideo(uri: Uri, q: Quality = Quality.STANDARD) {
        val r = room.value
        scope.launch(Dispatchers.IO) {
            val info = VideoUtil.probe(app, uri)
            if (info == null) {
                onNotice("Couldn't read that video.")
                return@launch
            }
            if (info.durMs > MAX_VIDEO_MS + 999) {
                onNotice("Videos can be up to 5 minutes long. That one is ${fmtDur(info.durMs)}.")
                return@launch
            }
            val id = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            coroutineContext[Job]?.let { jobs[id] = it }
            info.thumb?.let { vault.save(tag, thumbKey(id), it) }
            preparing.update { it + (id to 0f) }
            add(Msg(id, myId, r.name, "", ts, imgW = info.w, imgH = info.h, durMs = info.durMs, video = id))

            val out = File(app.cacheDir, "vout_$id.mp4")
            val ok = try {
                VideoUtil.transcode(app, uri, out, info, if (q == Quality.ORIGINAL) Quality.HIGH else q) { p -> preparing.update { m -> m + (id to p) } }
            } catch (e: Exception) { false }
            preparing.update { it - id }
            if (id in cancelled) { out.delete(); jobs.remove(id); return@launch }
            if (!ok || !out.exists() || out.length() !in 1L..MAX_VIDEO_BYTES.toLong()) {
                out.delete()
                setProgress(id, -1f)
                onNotice(
                    if (ok) "That video is still too large after shrinking. Try a shorter clip."
                    else "Couldn't prepare that video on this phone."
                )
                return@launch
            }
            try {
                vault.saveFrom(tag, videoKey(id), out)
                sendVideoFile(id, ts, out, info)
            } finally {
                out.delete()
                jobs.remove(id)
            }
        }
    }

    private fun pieceHash(b: ByteArray): String = Crypto.sha256Hex(b).take(32)

    private fun header(
        id: String, ts: Long, kind: String, size: Int, hashes: List<String>,
        w: Int, h: Int, dur: Long, thumb: ByteArray?
    ): ByteArray {
        val r = room.value
        val chunks = hashes.size
        val listHash = Crypto.sha256Hex(hashes.joinToString(",").toByteArray())
        val th = thumb?.let { Crypto.sha256Hex(it) } ?: ""
        val sig = Identity.sign("media|$tag|$id|$myId|$ts|$kind|$size|$chunks|$listHash|$w|$h|$dur|$th".toByteArray())
        val j = JSONObject().put("type", "media").put("id", id).put("sid", myId).put("n", r.name)
            .put("ts", ts).put("k", kind).put("size", size).put("chunks", chunks)
            .put("hashes", JSONArray(hashes)).put("w", w).put("h", h).put("dur", dur).put("th", th)
            .put("pk", b64(myPub)).put("sig", b64(sig))
        if (thumb != null) j.put("thumb", b64(thumb))
        return j.toString().toByteArray()
    }

    private fun piece(id: String, i: Int, part: ByteArray): ByteArray =
        JSONObject().put("type", "mc").put("id", id).put("i", i).put("d", b64(part)).toString().toByteArray()

    private suspend fun sendMedia(id: String, kind: String, bytes: ByteArray, w: Int, h: Int, dur: Long) {
        val r = room.value
        val ts = System.currentTimeMillis()
        val chunks = (bytes.size + CHUNK - 1) / CHUNK
        val hashes = (0 until chunks).map { i ->
            pieceHash(bytes.copyOfRange(i * CHUNK, minOf((i + 1) * CHUNK, bytes.size)))
        }
        setProgress(id, 0f)
        add(
            if (kind == KIND_IMAGE) Msg(id, myId, r.name, "", ts, image = id, imgW = w, imgH = h)
            else Msg(id, myId, r.name, "", ts, voice = id, durMs = dur)
        )
        try {
            transport?.sendBlocking(header(id, ts, kind, bytes.size, hashes, w, h, dur, null))
            for (i in 0 until chunks) {
                if (closed) return
                if (id in cancelled) { setProgress(id, -1f); return }
                transport?.sendBlocking(piece(id, i, bytes.copyOfRange(i * CHUNK, minOf((i + 1) * CHUNK, bytes.size))))
                setProgress(id, (i + 1).toFloat() / chunks)
                if (i % 4 == 3) delay(15)   // let chat messages and heartbeats slip in between
            }
            setProgress(id, null)
        } catch (e: Exception) {
            setProgress(id, -1f)
        }
    }

    private suspend fun sendVideoFile(id: String, ts: Long, f: File, info: VideoUtil.Info) {
        val size = f.length().toInt()
        val chunks = (size + CHUNK - 1) / CHUNK
        val buf = ByteArray(CHUNK)
        fun readPiece(raf: RandomAccessFile, i: Int): ByteArray {
            val len = minOf(CHUNK, size - i * CHUNK)
            raf.seek(i.toLong() * CHUNK)
            raf.readFully(buf, 0, len)
            return buf.copyOf(len)
        }
        setProgress(id, 0f)
        try {
            val hashes = RandomAccessFile(f, "r").use { raf -> (0 until chunks).map { pieceHash(readPiece(raf, it)) } }
            transport?.sendBlocking(header(id, ts, KIND_VIDEO, size, hashes, info.w, info.h, info.durMs, info.thumb))
            RandomAccessFile(f, "r").use { raf ->
                for (i in 0 until chunks) {
                    if (closed) return
                    if (id in cancelled) { setProgress(id, -1f); return }
                    transport?.sendBlocking(piece(id, i, readPiece(raf, i)))
                    setProgress(id, (i + 1).toFloat() / chunks)
                    if (i % 4 == 3) delay(15)
                }
            }
            setProgress(id, null)
        } catch (e: Exception) {
            setProgress(id, -1f)
        }
    }

    private fun handleMediaHeader(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid) || sid == myId) return
        val id = j.getString("id"); val n = j.getString("n"); val ts = j.getLong("ts")
        val kind = j.getString("k"); val size = j.getInt("size"); val chunks = j.getInt("chunks")
        val w = j.optInt("w", 0); val h = j.optInt("h", 0); val dur = j.optLong("dur", 0L)
        val th = j.optString("th", "")
        val limit = when (kind) {
            KIND_IMAGE -> ImageUtil.MAX_ORIGINAL
            KIND_VOICE -> MAX_MEDIA
            KIND_VIDEO -> MAX_VIDEO_BYTES
            else -> return
        }
        if (size !in 1..limit || chunks != (size + CHUNK - 1) / CHUNK) return
        if (kind == KIND_VIDEO && dur > MAX_VIDEO_MS + 999) return
        val arr = j.getJSONArray("hashes")
        if (arr.length() != chunks) return
        val hashes = (0 until chunks).map { arr.getString(it) }
        val listHash = Crypto.sha256Hex(hashes.joinToString(",").toByteArray())
        if (!verified(j, "media|$tag|$id|$sid|$ts|$kind|$size|$chunks|$listHash|$w|$h|$dur|$th")) return
        mediaOrigin[id] = sid
        if (synchronized(seen) { seen.contains(id) }) return

        // Video preview picture, checked against the signed fingerprint
        var thumb: ByteArray? = null
        if (kind == KIND_VIDEO && th.isNotEmpty()) {
            val t = j.optString("thumb", "")
            if (t.length > 300_000) return
            thumb = try { Base64.decode(t, Base64.NO_WRAP) } catch (e: Exception) { return }
            if (Crypto.sha256Hex(thumb) != th) return
        }

        sweepTransfers()
        if (assemblies.size >= MAX_ASSEMBLIES) return
        // Videos and large (original) photos are assembled on disk rather than in memory
        val file = if (kind == KIND_VIDEO || size > MAX_MEDIA) File(app.cacheDir, "vin_$id.part") else null
        val a = try { Assembly(j, hashes, file) } catch (e: Exception) { return }
        if (assemblies.putIfAbsent(id, a) != null) { a.discard(); return }
        people.seen(sid, n)
        val shown = people.alias(sid, n)
        known.update { it + (sid to shown) }
        setProgress(id, 0f)
        when (kind) {
            KIND_IMAGE -> add(Msg(id, sid, shown, "", ts, image = id, imgW = w, imgH = h))
            KIND_VOICE -> add(Msg(id, sid, shown, "", ts, voice = id, durMs = dur))
            else -> {
                thumb?.let { tb -> scope.launch(Dispatchers.IO) { vault.save(tag, thumbKey(id), tb) } }
                add(Msg(id, sid, shown, "", ts, imgW = w, imgH = h, durMs = dur, video = id))
            }
        }
    }

    private fun handleMediaChunk(j: JSONObject) {
        val id = j.getString("id"); val i = j.getInt("i"); val d = j.getString("d")
        val a = assemblies[id] ?: return                 // pieces without a signed header are ignored
        if (i !in a.hashes.indices || d.length > 70_000) return
        if (a.got.contains(i)) return
        val bytes = try { Base64.decode(d, Base64.NO_WRAP) } catch (e: Exception) { return }
        if (pieceHash(bytes) != a.hashes[i]) return      // junk piece: ignore it and keep waiting for the real one
        synchronized(a) {
            if (a.done || !a.got.add(i)) return
            val raf = a.raf
            if (raf != null) {
                try {
                    raf.seek(i.toLong() * CHUNK)
                    raf.write(bytes)
                } catch (e: Exception) {
                    a.got.remove(i)
                    return
                }
            } else {
                a.chunks[i] = bytes
            }
        }
        a.lastActivity = System.currentTimeMillis()
        setProgress(id, a.got.size.toFloat() / a.hashes.size)
        if (a.got.size == a.hashes.size) complete(id, a)
    }

    private fun complete(id: String, a: Assembly) {
        synchronized(a) {
            if (a.done) return
            a.done = true
        }
        assemblies.remove(id, a)
        val hd = a.header
        val kind = hd.getString("k"); val size = hd.getInt("size")
        val n = people.label(hd.getString("sid"), hd.getString("n"))
        scope.launch(Dispatchers.IO) {
            try {
                val f = a.file
                if (f != null) {
                    runCatching { a.raf?.close() }
                    if (f.length() != size.toLong()) { setProgress(id, -1f); return@launch }
                    vault.saveFrom(tag, if (kind == KIND_VIDEO) videoKey(id) else id, f)
                } else {
                    val out = ByteArrayOutputStream()
                    for (i in a.hashes.indices) out.write(a.chunks[i] ?: run { setProgress(id, -1f); return@launch })
                    val data = out.toByteArray()
                    if (data.size != size) { setProgress(id, -1f); return@launch }
                    if (kind == KIND_IMAGE) vault.save(tag, id, data) else rememberVoice(id, data)
                }
                setProgress(id, null)
                pendingDelivered.add(id)
                if (!(AppState.foreground && viewing(tag))) unread.update { it + 1 }
                if (!AppState.foreground) {
                    Notifier.message(app, displayName(), n, when (kind) {
                        KIND_IMAGE -> "IMG · Photo"
                        KIND_VIDEO -> "VID · Video"
                        else -> "VOX · Voice note"
                    })
                }
                if (kind != KIND_VOICE) scheduleSave()
            } catch (e: Exception) {
                setProgress(id, -1f)
            } finally {
                a.file?.delete()
            }
        }
    }

    /** Items that stopped arriving for a minute are marked as failed. */
    private fun sweepTransfers() {
        val cutoff = System.currentTimeMillis() - 60_000
        val dead = assemblies.filter { (_, a) -> !a.done && a.lastActivity < cutoff }
        dead.forEach { (id, a) ->
            if (assemblies.remove(id, a)) {
                a.discard()
                setProgress(id, -1f)
            }
        }
    }

    // ---------- Kick: host (instant) and vote ----------

    private fun keepEvidence(target: String, envelope: JSONObject) {
        evidence.getOrPut(target) { CopyOnWriteArrayList() }.add(envelope.toString())
    }

    fun hostKick(target: String) {
        if (hostId.value != myId || target == myId) return
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("kick|$tag|$target|$ts".toByteArray())
            val env = JSONObject().put("type", "kick").put("sid", myId).put("target", target)
                .put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig))
            keepEvidence(target, env)
            transport?.send(env.toString().toByteArray())
            delay(400) // let the notice reach them before we cut the link
            applyBan(target)
        }
    }

    private fun handleKick(j: JSONObject) {
        val sid = j.getString("sid"); val target = j.getString("target"); val ts = j.getLong("ts")
        if (sid != hostId.value || target == sid) return
        if (!verified(j, "kick|$tag|$target|$ts")) return
        keepEvidence(target, j)
        applyBan(target)
    }

    /** Vote yes/no on removing a member. The first yes starts the vote. */
    fun castVote(target: String, yes: Boolean) {
        if (target == myId || target == hostId.value) return
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("kv|$tag|$target|$yes|$ts".toByteArray())
            val env = JSONObject().put("type", "kv").put("sid", myId).put("target", target)
                .put("yes", yes).put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig))
            if (yes) keepEvidence(target, env)
            transport?.send(env.toString().toByteArray())
            recordVote(myId, target, yes)
        }
    }

    private fun handleVote(j: JSONObject) {
        val voter = j.getString("sid"); val target = j.getString("target")
        val yes = j.getBoolean("yes"); val ts = j.getLong("ts")
        if (isBanned(voter) || voter == target || target == hostId.value) return
        if (abs(System.currentTimeMillis() - ts) > VOTE_FRESH_MS) return
        if (voter !in reachable()) return
        if (!verified(j, "kv|$tag|$target|$yes|$ts")) return
        if (yes) keepEvidence(target, j)
        recordVote(voter, target, yes)
    }

    private fun recordVote(voter: String, target: String, yes: Boolean) {
        votes.update { map ->
            val v = map[target] ?: VoteInfo(target, emptySet(), emptySet(), System.currentTimeMillis())
            val nv = if (yes) v.copy(yes = v.yes + voter, no = v.no - voter)
                     else v.copy(no = v.no + voter, yes = v.yes - voter)
            map + (target to nv)
        }
        evaluate(target)
    }

    /** Passes when at least 2 members vote yes AND yes is a strict majority of everyone except the target. */
    private fun evaluate(target: String) {
        val v = votes.value[target] ?: return
        val eligible = (reachable() + myId) - target
        val yes = v.yes.count { it in eligible }
        if (yes >= 2 && yes * 2 > eligible.size) applyBan(target)
    }

    private fun purgeVotes() {
        val cutoff = System.currentTimeMillis() - VOTE_EXPIRY_MS
        votes.update { m -> m.filterValues { it.startedAt >= cutoff } }
    }

    private fun applyBan(target: String, rekey: Boolean = true) {
        if (target == myId) {
            onNotice("You were removed from node ${room.value.code}.")
            leave(false)
            return
        }
        val added = synchronized(banned) { banned.add(target) }
        if (!added) return
        prefs.edit().putStringSet("bans_$tag", synchronized(banned) { HashSet(banned) }).apply()
        transport?.ban(target)
        votes.update { it - target }
        val name = known.value[target] ?: target.take(6)
        add(Msg("sys-ban-$target", "system", "", "SYS · $name REMOVED FROM NODE",
            System.currentTimeMillis(), system = true))
        if (rekey) scheduleRekey()
    }

    // ---------- Key change after a removal ----------
    //
    // Goal: the removed person's passphrase stops working, and everyone else switches automatically.
    // 1. One member (the "leader": the host if present, otherwise the lowest ID; others take over after a delay
    //    if the leader doesn't act) makes a new random passphrase.
    // 2. The leader seals it separately to every remaining member's key-exchange key, so the removed person
    //    (and anyone else) cannot read it. The bundle also carries the signed kick/votes as proof.
    // 3. Each member verifies the proof, opens their sealed copy, and switches keys. No one disconnects.
    // 4. Anyone who was offline is handed the bundle automatically when they reconnect (see rescueFor).

    private fun leaderOrder(): List<String> {
        val banSnap = synchronized(banned) { HashSet(banned) }
        val members = (reachable() + myId) - banSnap
        val host = hostId.value
        return members.sortedWith(compareBy<String>({ if (it == host) 0 else 1 }, { it }))
    }

    private fun scheduleRekey() {
        scope.launch(Dispatchers.Default) {
            val rank = leaderOrder().indexOf(myId)
            if (rank < 0) return@launch
            delay(500L + rank * 6000L)
            issueRekeyIfNeeded()
        }
    }

    private suspend fun issueRekeyIfNeeded() {
        if (closed) return
        val r = room.value

        // Wait (briefly) until we hold every remaining member's key-exchange key
        var members: List<String> = emptyList()
        var banSnap: Set<String> = emptySet()
        var ready = false
        for (attempt in 0 until 10) {
            banSnap = synchronized(banned) { HashSet(banned) }
            members = ((reachable() + myId) - banSnap).toList()
            if (members.all { it == myId || eks.containsKey(it) }) { ready = true; break }
            delay(500)
        }
        if (!ready) return   // try again on the next 30s tick

        val targets = (banSnap - excluded).filter { evidence[it]?.isNotEmpty() == true }.sorted()
        if (targets.isEmpty()) return

        val prev = curKid
        val newPass = RoomCodes.newPassphrase()
        val newKey = Crypto.deriveKey(newPass, r.code)
        val next = Crypto.kid(newKey)

        val w = JSONObject()
        for (m in members) {
            val pub = if (m == myId) myEk else (eks[m] ?: continue)
            val aad = "rekey|$tag|$prev|$next|$m".toByteArray()
            val (eph, ct) = Crypto.wrapFor(pub, m, newPass.toByteArray(), aad)
            w.put(m, JSONObject().put("e", b64(eph)).put("c", b64(ct)))
        }
        val wStr = w.toString()
        val ev = JSONArray()
        targets.forEach { t -> evidence[t]?.forEach { ev.put(it) } }
        val ts = System.currentTimeMillis()
        val sig = Identity.sign(
            "rekey|$tag|$prev|$next|$myId|${targets.joinToString(",")}|$ts|${Crypto.sha256Hex(wStr.toByteArray())}".toByteArray()
        )
        val bundle = JSONObject().put("type", "rekey").put("sid", myId).put("pk", b64(myPub))
            .put("prev", prev).put("next", next).put("targets", JSONArray(targets))
            .put("w", wStr).put("ev", ev).put("ts", ts).put("sig", b64(sig))

        if (curKid != prev) return            // someone else already changed the key while we were working
        transport?.send(bundle.toString().toByteArray())   // sealed with the OLD key so everyone can read it
        acceptBundle(bundle)                               // then we switch ourselves
    }

    /** Is there signed proof that [target] was legitimately removed? Self-contained: needs no live connections. */
    private fun evidenceOk(target: String, ev: List<JSONObject>, recipients: Set<String>): Boolean {
        val yes = HashSet<String>()
        for (j in ev) {
            if (j.optString("target") != target) continue
            val sid = j.optString("sid"); val ts = j.optLong("ts")
            when (j.optString("type")) {
                "kick" -> if (sid == hostId.value && sid != target && verified(j, "kick|$tag|$target|$ts")) return true
                "kv" -> if (j.optBoolean("yes") && sid != target && sid in recipients &&
                    verified(j, "kv|$tag|$target|true|$ts")) yes.add(sid)
            }
        }
        return yes.size >= 2 && yes.size * 2 > recipients.size
    }

    /** Validate a key-change bundle and, if it is genuine, switch to the new key. Used live and for catching up. */
    private suspend fun acceptBundle(b: JSONObject) {
        rekeyLock.withLock {
            try {
                if (closed) return
                val r = room.value
                val prev = b.getString("prev"); val next = b.getString("next")
                // Tie-break: if two operatives rotated the key at the same moment (same starting key), everyone
                // settles on the bundle whose new key fingerprint sorts first, so the node can never split in two.
                val last = bundles.lastOrNull()
                val replacing = prev != curKid && last != null && last.optString("next") == curKid &&
                    last.optString("prev") == prev && next < curKid
                if (prev != curKid && !replacing) return        // stale, duplicate, or a different chain

                val leader = b.getString("sid"); val ts = b.getLong("ts")
                val tArr = b.getJSONArray("targets")
                val targets = (0 until tArr.length()).map { tArr.getString(it) }
                val wStr = b.getString("w")
                val signed = "rekey|$tag|$prev|$next|$leader|${targets.joinToString(",")}|$ts|${Crypto.sha256Hex(wStr.toByteArray())}"
                if (!verified(b, signed)) return
                if (isBanned(leader)) return

                val w = JSONObject(wStr)
                val recipients = w.keys().asSequence().toSet()
                if (leader !in recipients || myId !in recipients) return
                if (targets.isEmpty() || (!replacing && targets.all { it in excluded })) return
                if (targets.any { it in recipients }) return    // the removed person must not get the key

                val evArr = b.getJSONArray("ev")
                val ev = (0 until evArr.length()).mapNotNull { runCatching { JSONObject(evArr.getString(it)) }.getOrNull() }
                if (!targets.all { evidenceOk(it, ev, recipients) }) return

                // Nobody legitimate may be left out (guards against a member sneaking a rival out).
                // Members who connected in the last minute may not have been known to the leader yet.
                val now = System.currentTimeMillis()
                val view = (reachable() + myId) - synchronized(banned) { HashSet(banned) } - targets.toSet()
                if (view.any { it !in recipients && now - (seenAt[it] ?: 0L) > 60_000L }) return

                val mine = w.getJSONObject(myId)
                val aad = "rekey|$tag|$prev|$next|$myId".toByteArray()
                val passBytes = Crypto.unwrap(
                    ecdh.first, Base64.decode(mine.getString("e"), Base64.NO_WRAP), myId,
                    Base64.decode(mine.getString("c"), Base64.NO_WRAP), aad
                ) ?: return
                val newPass = passBytes.toString(Charsets.UTF_8)
                val newKey = Crypto.deriveKey(newPass, r.code)
                if (Crypto.kid(newKey) != next) return

                // ---- commit: switch to the new key ----
                transport?.rekey(next, newKey)
                val oldPass = curPass
                curPass = newPass; curKid = next
                excluded.addAll(targets)
                if (replacing && last != null) bundles.remove(last)   // the losing rotation is dropped
                bundles.add(b)
                while (bundles.size > MAX_BUNDLES) bundles.removeAt(0)
                prevPasses.add(oldPass)
                while (prevPasses.size > MAX_BUNDLES) prevPasses.removeAt(0)
                prefs.edit()
                    .putStringSet("excl_$tag", HashSet(excluded))
                    .putString("bundles_$tag", JSONArray(bundles.map { it.toString() }).toString())
                    .putString("prev_$tag", JSONArray(prevPasses.toList()).toString())
                    .apply()
                room.update { it.copy(password = newPass) }
                onChanged()   // manager saves the new passphrase for auto-rejoin
                targets.forEach { applyBan(it, rekey = false) }
                add(Msg("sys-rekey-$next", "system", "",
                    "SYS · NODE KEY ROTATED · INVITE SHARES THE NEW ACCESS KEY", now, system = true))
                announce()
            } catch (_: Exception) {}
        }
    }

    /** Called by the transport when someone connects with an older key: hand them the bundle that moves them on. */
    private fun rescueFor(peerId: String, theirKid: String): ByteArray? {
        val b = bundles.filter { it.optString("prev") == theirKid }.minByOrNull { it.optString("next") } ?: return null
        val w = runCatching { JSONObject(b.getString("w")) }.getOrNull() ?: return null
        return if (w.has(peerId)) b.toString().toByteArray() else null
    }

    private fun readJsonStrings(s: String?): List<String> {
        if (s == null) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    // ---------- History ----------

    private fun add(m: Msg) {
        synchronized(seen) { if (!seen.add(m.id)) return }
        messages.update { (it + m).sortedBy { x -> x.ts } }
        scheduleSave()
    }

    private fun scheduleSave() {
        if (!settings.value.keepHistory) return
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(1500)
            store.save(tag, saveable(), settings.value.retentionDays)
        }
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        private const val REPORT_TTL = 75_000L
        val REACTIONS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDD25", "\uD83D\uDC40")
        private const val SEEN_TTL = 10 * 60_000L
        private const val VOTE_EXPIRY_MS = 5 * 60_000L
        private const val VOTE_FRESH_MS = 10 * 60_000L
        private const val MAX_BUNDLES = 10
        private const val CHUNK = 48 * 1024
        private const val MAX_MEDIA = 3_000_000
        private const val MAX_ASSEMBLIES = 12
        private const val MAX_VOICE_MEMORY = 40L * 1024 * 1024
        private const val KIND_IMAGE = "image"
        private const val KIND_VOICE = "voice"
        private const val KIND_VIDEO = "video"
    }
}
