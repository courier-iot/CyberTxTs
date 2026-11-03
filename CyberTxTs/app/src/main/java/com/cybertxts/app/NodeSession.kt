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
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    val deleted: Boolean = false,
    val pinned: Boolean = false,
    val og: Long = 0L,               // video: size of the original the sender kept (0 = none)
    val ogHave: Boolean = false,     // video: this phone holds the original
    val editedAt: Long = 0L,
    val poll: String? = null,        // poll: JSON array of options (text is the question)
    val votes: Map<String, String> = emptyMap(),  // poll: voter id -> "choice:ts"
    val audio: String? = null,       // audio file (music, podcast...): sent as picked, stored encrypted
    val aTitle: String = "", val aArtist: String = "", val aName: String = "", val aMime: String = "",
    val aSize: Long = 0L
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
    private val viewing: (String) -> Boolean,       // is this node the one on screen?
    /** (recovery public key, my signed commitment to it), if I've set up a recovery phrase. */
    private val recovery: () -> Pair<String, String>? = { null },
    /** Certificates saying earlier IDs of mine moved to this phone. Shown to everyone I link with. */
    private val myChain: () -> List<JSONObject> = { emptyList() },
    /** Someone showed us a succession certificate: the app checks and applies it across all nodes. */
    private val onSuccession: (JSONObject) -> Unit = {},
    /** Direct link (contact chat): who the other person is right now. Null for nodes and offer nodes. */
    val directPeer: (() -> String)? = null,
    /** A contact request arrived here: (this session, from, their callsign, link secret, how they found you). */
    private val onContactRequest: (NodeSession, String, String, ByteArray, String) -> Unit = { _, _, _, _, _ -> },
    private val onPeersChanged: (NodeSession, Set<String>) -> Unit = { _, _ -> },
    /** Call signalling (offer / answer / end) for this node or contact. */
    private val onCall: (NodeSession, JSONObject) -> Unit = { _, _ -> }
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
        if (s.keepHistory) withContext(Dispatchers.IO) { loadFrameLog() }
        if (!prefs.contains("joined_$tag")) prefs.edit().putLong("joined_$tag", System.currentTimeMillis()).apply()
        loadMediaQueue()
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
                sweepExpired()
                sendDueScheduled()
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
        prefs.edit().remove("excl_$t").remove("bundles_$t").remove("prev_$t").remove("nick_$t").remove("mute_$t").remove("hostcert_$t").remove("hostts_$t").remove("dlv_$t").remove("joined_$t").remove("mq_$t").remove("ttl_$t").remove("ttlts_$t").remove("ttlcert_$t").remove("sched_$t").apply()
        scope.launch(Dispatchers.IO) {
            if (eraseHistory || !s.keepHistory) store.delete(t) else store.save(t, snapshot, s.retentionDays)
            if (eraseHistory || !s.keepHistory) vault.delete(t) else vault.prune(t, keepKeys(snapshot, s))
        }
        onLeft(this)
    }

    /** Write the log to disk right now (before a backup or move). */
    fun saveNow() { if (settings.value.keepHistory) store.save(tag, saveable(), settings.value.retentionDays) }

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
            val f = msgFrame(id, ts, t, re)
            logFrame("msg", id, myId, ts, "", f)
            transport?.send(f)
        }
    }

    private fun msgFrame(id: String, ts: Long, t: String, re: String): ByteArray {
        val sig = Identity.sign("msg|$tag|$id|$myId|$ts|$t|$re".toByteArray())
        return JSONObject().put("type", "msg").put("id", id).put("sid", myId)
            .put("n", room.value.name).put("text", t).put("ts", ts).put("re", re)
            .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray()
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
            val hi = JSONObject().put("type", "hi").put("sid", myId).put("n", r.name)
                .put("h", host).put("ts", ts).put("ek", ek).put("lk", lk).put("rl", rl)
                .put("pk", b64(myPub)).put("sig", b64(sig))
            recovery()?.let { (rc, rcs) -> hi.put("rc", rc).put("rcs", rcs) }
            transport?.send(hi.toString().toByteArray())
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
        val fresh = ids - before
        if (fresh.isNotEmpty()) scope.launch(Dispatchers.Default) {
            delay(800); shareCertificates(fresh)
            delay(700)
            fresh.forEach { requestSync(it) }
            if (directPeer != null) { flushOutbox(); flushMediaQueue() }
        }
        onPeersChanged(this, ids)
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
            val dp = directPeer?.invoke()
            if (dp != null && origin != null && origin != myId && type in CONTENT_TYPES && origin !in people.lineage(dp)) return
            when (type) {
                "ping" -> handlePing(j)
                "pong" -> handlePong(j)
                "dm" -> handleDm(j)
                "rcpt" -> handleRcpt(j)
                "typing" -> handleTyping(j)
                "react" -> handleReact(j)
                "edit" -> handleEdit(j)
                "syn" -> handleSync(j)
                "ttl" -> handleTtl(j)
                "poll" -> handlePoll(j)
                "pvote" -> handleVote2(j)
                "call" -> onCall(this, j)
                "pin" -> handlePin(j)
                "ogreq" -> handleOriginalRequest(j)
                "succ" -> j.optJSONObject("c")?.let { c -> if (c.toString().length < 12_000) onSuccession(c) }
                "hosto" -> handleHostOver(j)
                "creq" -> handleContactRequest(j)
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
            val onlyTo = when (type) {
                "dm", "media", "creq", "ogreq", "call" -> j.optString("to").ifEmpty { null }
                "mc" -> mediaTo[j.optString("id")]
                else -> null
            }
            // Originals are sent to one requester only: never pass them on
            val oneOff = (type == "media" || type == "mc") && j.optString("id").endsWith("-og")
            if (origin != null && !oneOff) maybeRelay(origin, bytes, onlyTo)
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
        logFrame("msg", id, sid, ts, "", j.toString().toByteArray())
        people.seen(sid, n)
        val shown = people.alias(sid, n)
        known.update { it + (sid to shown) }
        val isNew = synchronized(seen) { !seen.contains(id) }
        add(Msg(id, sid, shown, text, ts, replyTo = re.ifEmpty { null }))
        if (sid != myId) pendingDelivered.add(id)       // a resent copy still gets a delivery mark back
        if (sid != myId && isNew) {
            if (!(AppState.foreground && viewing(tag))) unread.update { it + 1 }
            val mention = mentionsMe(text)
            if (!AppState.foreground && (!muted.value || mention))
                Notifier.message(app, displayName(), (if (mention) "@YOU · " else "") + people.label(sid, n), text, tag, null)
        }
    }

    /** Does [text] mention me (@ followed by my callsign)? */
    fun mentionsMe(text: String): Boolean {
        val me = room.value.name.trim()
        return me.isNotEmpty() && Regex("@" + Regex.escape(me) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
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
        // Their recovery commitment: kept so their recovery phrase can vouch for a future phone of theirs
        val rc = j.optString("rc"); val rcs = j.optString("rcs")
        if (rc.length == 64 && rcs.isNotEmpty() && rcs.length < 200) runCatching {
            val pk = Base64.decode(j.getString("pk"), Base64.NO_WRAP)
            if (Identity.verify(pk, Succession.rccData(sid, rc), Base64.decode(rcs, Base64.NO_WRAP))) {
                people.pinRecovery(sid, j.getString("pk"), rc, rcs)
            }
        }
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
    private val mediaTo = ConcurrentHashMap<String, String>()        // private media id -> recipient
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
    private fun maybeRelay(origin: String, bytes: ByteArray, onlyTo: String? = null) {
        if (!settings.value.relay || origin == myId) return
        val targets = connected.value.filter { p ->
            (onlyTo == null || p == onlyTo) &&
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
            val f = JSONObject().put("type", "dm").put("id", id).put("sid", myId).put("n", r.name)
                .put("to", to).put("ts", ts).put("e", e).put("c", c)
                .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray()
            logFrame("dm", id, myId, ts, "", f)
            transport?.send(f)
        }
    }

    private fun handleDm(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid)) return
        val id = j.getString("id"); val n = j.getString("n"); val ts = j.getLong("ts")
        val e = j.getString("e"); val c = j.getString("c"); val to = j.optString("to")
        if (c.length > 20_000 || to.length != 32) return
        if (!verified(j, "dm|$tag|$id|$sid|$to|$ts|${Crypto.sha256Hex((e + c).toByteArray())}")) return
        logFrame("dm", id, sid, ts, "", j.toString().toByteArray())
        if (to != myId) return                // sealed to someone else: kept (unreadable) only to pass on if they missed it
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
        if (!AppState.foreground && !muted.value) Notifier.message(app, displayName(), people.label(sid, n) + " · PRIVATE", text, tag, sid)
    }

    // ---- delivery and read receipts (batched every 1.5 s) ----

    /** Call while a chat (node or private thread) is on screen: everything visible counts as read. */
    fun markRead(dmPeer: String?) {
        if (!settings.value.readMarks) return               // read marks off: nothing is reported
        messages.value.forEach { m ->
            if (m.senderId != myId && !m.system && m.dm == dmPeer && readReported.add(m.id)) pendingRead.add(m.id)
        }
    }

    private fun flushReceipts() {
        if (pendingDelivered.isEmpty() && pendingRead.isEmpty()) return
        val d = pendingDelivered.take(200); pendingDelivered.removeAll(d.toSet())
        val r = if (settings.value.readMarks) pendingRead.take(200) else emptyList()
        pendingRead.removeAll(r.toSet())
        val ts = System.currentTimeMillis()
        val sig = Identity.sign("rcpt|$tag|$myId|$ts|${Crypto.sha256Hex((d.joinToString(",") + "/" + r.joinToString(",")).toByteArray())}".toByteArray())
        transport?.send(JSONObject().put("type", "rcpt").put("sid", myId).put("ts", ts)
            .put("d", JSONArray(d)).put("r", JSONArray(r)).put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
    }

    private fun handleRcpt(j: JSONObject) {
        val sid = j.optString("sid")
        if (sid.length != 32 || sid == myId || isBanned(sid)) return
        fun ids(key: String) = j.optJSONArray(key)?.let { a -> (0 until minOf(a.length(), 200)).map { a.getString(it) } } ?: emptyList()
        val ts = j.optLong("ts")
        if (abs(System.currentTimeMillis() - ts) > 10 * 60_000L) return
        // Signed, so nobody can fake someone else's delivered / read marks
        if (!verified(j, "rcpt|$tag|$sid|$ts|${Crypto.sha256Hex((ids("d").joinToString(",") + "/" + ids("r").joinToString(",")).toByteArray())}")) return
        val mine = messages.value.filter { it.senderId == myId }.map { it.id }.toHashSet()
        var changed = false
        ids("d").filter { it in mine }.forEach { if (deliveredBy.getOrPut(it) { ConcurrentHashMap.newKeySet() }.add(sid)) changed = true }
        ids("r").filter { it in mine }.forEach {
            deliveredBy.getOrPut(it) { ConcurrentHashMap.newKeySet() }.add(sid)
            if (readBy.getOrPut(it) { ConcurrentHashMap.newKeySet() }.add(sid)) changed = true
        }
        if (changed) {
            receipts.value = deliveredBy.keys.associateWith { (deliveredBy[it]?.size ?: 0) to (readBy[it]?.size ?: 0) }
            if (directPeer != null) rememberDelivered(deliveredBy.keys)
        }
    }

    // ---- typing indicator ----

    fun typingNow(dmTo: String?) {
        if (!settings.value.typingSignal) return
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 3_000) return
        lastTypingSent = now
        scope.launch(Dispatchers.Default) {
            val to = dmTo ?: ""
            val sig = Identity.sign("typing|$tag|$myId|$to|$now".toByteArray())
            transport?.send(JSONObject().put("type", "typing").put("sid", myId).put("to", to).put("ts", now)
                .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
        }
    }

    private fun handleTyping(j: JSONObject) {
        val sid = j.optString("sid"); val to = j.optString("to"); val ts = j.optLong("ts")
        if (sid.length != 32 || sid == myId || isBanned(sid) || !settings.value.typingSignal) return
        if (abs(System.currentTimeMillis() - ts) > 30_000 || !verified(j, "typing|$tag|$sid|$to|$ts")) return
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
            val f = JSONObject().put("type", "react").put("sid", myId).put("id", msgId).put("e", emoji)
                .put("on", on).put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray()
            logFrame("react", msgId, myId, ts, emoji + on, f)
            transport?.send(f)
        }
    }

    private fun handleReact(j: JSONObject) {
        val sid = j.getString("sid"); val id = j.getString("id"); val e = j.getString("e")
        val on = j.getBoolean("on"); val ts = j.getLong("ts")
        if (isBanned(sid) || e !in REACTIONS) return
        if (!verified(j, "react|$tag|$id|$sid|$e|$on|$ts")) return
        logFrame("react", id, sid, ts, e + on, j.toString().toByteArray())
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
        if (m.senderId != myId || m.dm != null || m.image != null || m.video != null || m.voice != null || m.audio != null) return
        applyEdit(msgId, myId, t, false, System.currentTimeMillis())
        sendEdit(msgId, t, false)
    }

    fun delete(msgId: String) {
        val m = messages.value.firstOrNull { it.id == msgId } ?: return
        if (m.senderId != myId) return
        applyEdit(msgId, myId, "", true, System.currentTimeMillis())
        sendEdit(msgId, "", true)
    }

    private fun sendEdit(msgId: String, t: String, del: Boolean) {
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("edit|$tag|$msgId|$myId|$ts|$t|$del".toByteArray())
            val f = JSONObject().put("type", "edit").put("sid", myId).put("id", msgId).put("text", t)
                .put("del", del).put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray()
            logFrame("edit", msgId, myId, ts, "", f)
            if (del) forgetFrames(msgId)
            transport?.send(f)
        }
    }

    private fun handleEdit(j: JSONObject) {
        val sid = j.getString("sid"); val id = j.getString("id"); val t = j.optString("text")
        val del = j.optBoolean("del"); val ts = j.getLong("ts")
        if (isBanned(sid) || t.length > 4000) return
        if (!verified(j, "edit|$tag|$id|$sid|$ts|$t|$del")) return
        logFrame("edit", id, sid, ts, "", j.toString().toByteArray())
        if (del) forgetFrames(id)
        applyEdit(id, sid, t, del, ts)
    }

    /** Only the original sender can change a message; a deleted message also loses its stored media. */
    private fun applyEdit(msgId: String, who: String, t: String, del: Boolean, at: Long) {
        var target: Msg? = null
        messages.update { list ->
            list.map { m ->
                if (m.id != msgId || m.senderId != who) m else {
                    target = m
                    if (del) m.copy(text = "", deleted = true, image = null, video = null, voice = null, audio = null, reactions = emptyMap(), pinned = false)
                    else if (m.dm == null && !m.deleted && at > m.editedAt) m.copy(text = t, edited = true, editedAt = at) else m
                }
            }
        }
        val gone = target
        if (del && gone != null) {
            scope.launch(Dispatchers.IO) {
                gone.image?.let { vault.deleteItem(tag, it) }
                gone.video?.let { vault.deleteItem(tag, videoKey(it)); vault.deleteItem(tag, thumbKey(it)); vault.deleteItem(tag, ogKey(it)) }
                gone.voice?.let { v -> synchronized(voices) { voices.remove(v) } }
                gone.audio?.let { AudioPlayer.forget(it); vault.deleteItem(tag, audioKey(it)); vault.deleteItem(tag, thumbKey(it)) }
            }
        }
        scheduleSave()
    }

    // ---- cancel / retry / dismiss media ----

    fun cancelTransfer(id: String) {
        cancelled.add(id)
        if (mediaQueue.removeAll { it.id == id }) saveMediaQueue()
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
                    sendMedia(newId, KIND_IMAGE, bytes, m.imgW, m.imgH, 0L, m.dm)
                }
                m.voice != null -> {
                    val bytes = synchronized(voices) { voices[m.voice] } ?: run { onNotice("That voice note is gone. Record it again."); return@launch }
                    dismiss(m.id)
                    rememberVoice(newId, bytes)
                    sendMedia(newId, KIND_VOICE, bytes, 0, 0, m.durMs, m.dm)
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
                        add(Msg(newId, myId, room.value.name, "", ts, imgW = m.imgW, imgH = m.imgH, durMs = m.durMs, video = newId, dm = m.dm))
                        sendVideoFile(newId, ts, tmp, VideoUtil.Info(m.durMs, m.imgW, m.imgH, thumb), m.dm)
                    } finally {
                        tmp.delete()
                    }
                }
                m.audio != null -> {
                    val tmp = File(app.cacheDir, "aout_$newId")
                    if (!vault.decryptTo(tag, audioKey(m.audio), tmp)) {
                        onNotice("That audio file is no longer stored. Pick it again.")
                        return@launch
                    }
                    val meta = vault.read(tag, thumbKey(m.audio)) ?: AudioMeta.encode(m.aTitle, m.aArtist, m.aName, m.aMime)
                    dismiss(m.id)
                    try {
                        vault.saveFrom(tag, audioKey(newId), tmp)
                        vault.save(tag, thumbKey(newId), meta)
                        val ts = System.currentTimeMillis()
                        add(audioMsg(newId, myId, room.value.name, ts, m.durMs, m.dm, meta, tmp.length()))
                        sendVideoFile(newId, ts, tmp, VideoUtil.Info(m.durMs, 0, 0, meta), m.dm, kind = KIND_AUDIO)
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }
    }

    // ---------- Identity continuity and host handover ----------

    private fun shareCertificates(to: Set<String>) {
        if (closed) return
        myChain().forEach { c ->
            transport?.sendTo(to, JSONObject().put("type", "succ").put("sid", myId).put("c", c).toString().toByteArray())
        }
        hostChain().forEach { h -> transport?.sendTo(to, h.toString().toByteArray()) }
        prefs.getString("ttlcert_$tag", null)?.let { c -> transport?.sendTo(to, c.toByteArray()) }
    }

    /**
     * [old] is now [new] (checked by the app). Carry over private channels and the host role; if the certificate
     * revokes [old], remove it from this node and rotate the key so the old phone is locked out.
     */
    fun applySuccessor(old: String, new: String, revoke: Boolean, cert: JSONObject) {
        if (closed) return
        if (old == myId) {
            if (revoke) {
                onNotice("Your identity was recovered on another phone, so this phone was locked out of ${displayName()}.")
                leave(true)
            }
            return
        }
        val knewThem = old in known.value || messages.value.any { it.senderId == old || it.dm == old } || hostId.value == old
        if (!knewThem) return
        messages.update { list -> list.map { if (it.dm == old) it.copy(dm = new) else it } }
        dmUnread.update { m -> m[old]?.let { n -> (m - old) + (new to (m[new] ?: 0) + n) } ?: m }
        known.value[old]?.let { n -> known.update { it + (new to (it[new] ?: n)) } }
        if (hostId.value == old) {
            hostId.value = new
            prefs.edit().putString("host_$tag", new).apply()
        }
        val who = opTagOf(old)
        add(Msg("sys-succ-$old-$new", "system", "",
            if (revoke) "SYS · $who RECOVERED ON A NEW DEVICE · OLD DEVICE LOCKED OUT"
            else "SYS · $who MOVED TO A NEW DEVICE · NOW ${opTagOf(new)}",
            System.currentTimeMillis(), system = true))
        if (revoke) {
            keepEvidence(old, JSONObject().put("type", "succ").put("target", old).put("c", cert))
            applyBan(old)
        }
    }

    private fun opTagOf(id: String) = people.label(id, known.value[id] ?: "Operative").uppercase() + "//" + id.take(4).uppercase()

    private fun hostChain(): List<JSONObject> = readJsonStrings(prefs.getString("hostcert_$tag", null))
        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }

    private fun saveHostCert(j: JSONObject) {
        val list = (hostChain().map { it.toString() } + j.toString()).takeLast(5)
        prefs.edit().putString("hostcert_$tag", JSONArray(list).toString()).apply()
    }

    /** Hand the host role to another linked operative. Everyone checks it came from the current host. */
    fun handOverHost(to: String) {
        if (hostId.value != myId || to == myId || isBanned(to)) return
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("hosto|$tag|$myId|$to|$ts".toByteArray())
            val j = JSONObject().put("type", "hosto").put("sid", myId).put("to", to).put("ts", ts)
                .put("pk", b64(myPub)).put("sig", b64(sig))
            transport?.send(j.toString().toByteArray())
            acceptHost(j, myId, to)
        }
    }

    private fun handleHostOver(j: JSONObject) {
        val sid = j.getString("sid"); val to = j.getString("to"); val ts = j.getLong("ts")
        if (sid != hostId.value || to == sid || to.length != 32 || isBanned(to)) return
        if (ts <= prefs.getLong("hostts_$tag", 0L)) return          // an older handover being replayed
        if (!verified(j, "hosto|$tag|$sid|$to|$ts")) return
        acceptHost(j, sid, to)
    }

    private fun acceptHost(j: JSONObject, from: String, to: String) {
        hostId.value = to
        prefs.edit().putString("host_$tag", to).putLong("hostts_$tag", j.getLong("ts")).apply()
        saveHostCert(j)
        add(Msg("sys-host-${j.getLong("ts")}", "system", "",
            "SYS · HOST ROLE PASSED FROM ${opTagOf(from)} TO ${if (to == myId) "YOU" else opTagOf(to)}",
            System.currentTimeMillis(), system = true))
        if (to == myId) onNotice("You're now the host of ${displayName()}. You can remove operatives instantly.")
        announce()
    }

    // ---------- Drafts and scheduled messages ----------

    fun draft(dm: String?): String = prefs.getString("draft_${tag}_${dm ?: ""}", "") ?: ""
    fun saveDraft(dm: String?, text: String) {
        val k = "draft_${tag}_${dm ?: ""}"
        if (text.isBlank()) prefs.edit().remove(k).apply() else prefs.edit().putString(k, text.take(4000)).apply()
    }

    data class Scheduled(val id: String, val text: String, val at: Long, val dm: String?, val re: String?)
    val scheduled = MutableStateFlow(loadScheduled())

    private fun loadScheduled(): List<Scheduled> = runCatching {
        val a = JSONArray(prefs.getString("sched_$tag", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Scheduled(o.getString("id"), o.getString("t"), o.getLong("at"), o.optString("dm").ifEmpty { null }, o.optString("re").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    private fun saveScheduled() {
        val a = JSONArray()
        scheduled.value.forEach { s -> a.put(JSONObject().put("id", s.id).put("t", s.text).put("at", s.at).put("dm", s.dm ?: "").put("re", s.re ?: "")) }
        prefs.edit().putString("sched_$tag", a.toString()).apply()
    }

    /** Send [text] at [at] (this phone needs to be on, with CyberTxTs running in the background). */
    fun schedule(text: String, at: Long, dm: String?, re: String?) {
        val t = text.trim().ifEmpty { return }.take(4000)
        scheduled.update { (it + Scheduled(UUID.randomUUID().toString(), t, at, dm, re)).sortedBy { s -> s.at } }
        saveScheduled()
    }

    fun cancelScheduled(id: String) { scheduled.update { l -> l.filterNot { it.id == id } }; saveScheduled() }

    private fun sendDueScheduled() {
        val now = System.currentTimeMillis()
        val due = scheduled.value.filter { it.at <= now }
        if (due.isEmpty()) return
        scheduled.update { l -> l.filterNot { s -> due.any { it.id == s.id } } }
        saveScheduled()
        due.forEach { s -> send(s.text, s.re, s.dm) }
    }

    // ---------- Polls ----------
    //
    // A poll is a signed question with up to 6 options. Each operative's vote is signed too, and only their
    // latest vote counts, so nobody can vote twice or vote as someone else.

    fun createPoll(question: String, options: List<String>) {
        val q = question.trim().take(300); val opts = options.map { it.trim().take(100) }.filter { it.isNotEmpty() }.take(6)
        if (q.isEmpty() || opts.size < 2) return
        val id = UUID.randomUUID().toString(); val ts = System.currentTimeMillis()
        val optJson = JSONArray(opts).toString()
        add(Msg(id, myId, room.value.name, q, ts, poll = optJson))
        scope.launch(Dispatchers.Default) {
            val sig = Identity.sign("poll|$tag|$id|$myId|$ts|${Crypto.sha256Hex((q + "\u0000" + optJson).toByteArray())}".toByteArray())
            val f = JSONObject().put("type", "poll").put("id", id).put("sid", myId).put("n", room.value.name).put("q", q)
                .put("o", optJson).put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray()
            logFrame("poll", id, myId, ts, "", f)
            transport?.send(f)
        }
    }

    private fun handlePoll(j: JSONObject) {
        val sid = j.getString("sid"); val id = j.getString("id"); val q = j.getString("q"); val o = j.getString("o"); val ts = j.getLong("ts")
        if (isBanned(sid) || q.length > 300 || o.length > 1_000) return
        val opts = runCatching { JSONArray(o) }.getOrNull() ?: return
        if (opts.length() !in 2..6) return
        if (!verified(j, "poll|$tag|$id|$sid|$ts|${Crypto.sha256Hex((q + "\u0000" + o).toByteArray())}")) return
        logFrame("poll", id, sid, ts, "", j.toString().toByteArray())
        val n = j.optString("n")
        people.seen(sid, n)
        val isNew = synchronized(seen) { !seen.contains(id) }
        add(Msg(id, sid, people.alias(sid, n), q, ts, poll = o))
        if (isNew && sid != myId && !(AppState.foreground && viewing(tag))) unread.update { it + 1 }
    }

    /** Vote for option [choice] (or -1 to take your vote back). */
    fun vote(pollId: String, choice: Int) {
        val ts = System.currentTimeMillis()
        applyVote(pollId, myId, choice, ts)
        scope.launch(Dispatchers.Default) {
            val sig = Identity.sign("pvote|$tag|$pollId|$myId|$choice|$ts".toByteArray())
            val f = JSONObject().put("type", "pvote").put("id", pollId).put("sid", myId).put("c", choice).put("ts", ts)
                .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray()
            logFrame("pvote", pollId, myId, ts, "$choice", f)
            transport?.send(f)
        }
    }

    private fun handleVote2(j: JSONObject) {
        val sid = j.getString("sid"); val id = j.getString("id"); val c = j.getInt("c"); val ts = j.getLong("ts")
        if (isBanned(sid) || c < -1 || c > 5) return
        if (!verified(j, "pvote|$tag|$id|$sid|$c|$ts")) return
        logFrame("pvote", id, sid, ts, "$c", j.toString().toByteArray())
        applyVote(id, sid, c, ts)
    }

    private fun applyVote(pollId: String, who: String, choice: Int, ts: Long) {
        messages.update { l ->
            l.map { m ->
                if (m.id != pollId || m.poll == null) m else {
                    val prev = m.votes[who]?.substringAfter(':')?.toLongOrNull() ?: 0L
                    if (ts <= prev) m else m.copy(votes = m.votes + (who to "$choice:$ts"))
                }
            }
        }
        scheduleSave()
    }

    // ---------- Disappearing messages ----------
    //
    // A node-wide timer that anyone can set (signed). Every phone deletes messages older than it, including their
    // photos and videos, and drops them from the catch-up log so they're never handed out again.

    /** Message lifetime in ms (0 = keep). */
    val ttl = MutableStateFlow(prefs.getLong("ttl_$tag", 0L))

    fun setTtl(v: Long) {
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("ttl|$tag|$v|$ts".toByteArray())
            val j = JSONObject().put("type", "ttl").put("sid", myId).put("v", v).put("ts", ts)
                .put("pk", b64(myPub)).put("sig", b64(sig))
            transport?.send(j.toString().toByteArray())
            acceptTtl(j, myId)
        }
    }

    private fun handleTtl(j: JSONObject) {
        val sid = j.getString("sid"); val v = j.getLong("v"); val ts = j.getLong("ts")
        if (isBanned(sid) || v < 0 || v > 30L * 86_400_000L || ts > System.currentTimeMillis() + 300_000) return
        if (!verified(j, "ttl|$tag|$v|$ts")) return
        acceptTtl(j, sid)
    }

    private fun acceptTtl(j: JSONObject, by: String) {
        val v = j.getLong("v"); val ts = j.getLong("ts")
        if (ts <= prefs.getLong("ttlts_$tag", 0L)) return            // an older setting
        prefs.edit().putLong("ttl_$tag", v).putLong("ttlts_$tag", ts).putString("ttlcert_$tag", j.toString()).apply()
        ttl.value = v
        add(Msg("sys-ttl-$ts", "system", "",
            "SYS · DISAPPEARING MESSAGES " + (if (v == 0L) "OFF" else "SET TO " + ttlLabel(v)) + " BY " +
                (if (by == myId) "YOU" else opTagOf(by)), System.currentTimeMillis(), system = true))
        sweepExpired()
    }

    private fun sweepExpired() {
        val life = ttl.value
        if (life <= 0) return
        val cutoff = System.currentTimeMillis() - life
        val gone = messages.value.filter { it.ts < cutoff && !(it.system && it.id.startsWith("sys-ttl-")) }
        if (gone.isEmpty()) return
        val ids = gone.map { it.id }.toSet()
        messages.update { l -> l.filterNot { it.id in ids } }
        synchronized(frameLog) { pruneLog(System.currentTimeMillis()) }
        scope.launch(Dispatchers.IO) {
            gone.forEach { m ->
                m.image?.let { vault.deleteItem(tag, it) }
                m.video?.let { vault.deleteItem(tag, videoKey(it)); vault.deleteItem(tag, thumbKey(it)); vault.deleteItem(tag, ogKey(it)) }
                m.voice?.let { v -> synchronized(voices) { voices.remove(v) } }
                m.audio?.let { AudioPlayer.forget(it); vault.deleteItem(tag, audioKey(it)); vault.deleteItem(tag, thumbKey(it)) }
            }
        }
        scheduleSave(); scheduleLogSave()
    }

    // ---------- Pins ----------

    fun setPinned(msgId: String, on: Boolean) {
        applyPin(msgId, on)
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("pin|$tag|$msgId|$on|$ts".toByteArray())
            val f = JSONObject().put("type", "pin").put("sid", myId).put("id", msgId).put("on", on).put("ts", ts)
                .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray()
            logFrame("pin", msgId, myId, ts, "$on", f)
            transport?.send(f)
        }
    }

    private fun handlePin(j: JSONObject) {
        val sid = j.getString("sid"); val id = j.getString("id"); val on = j.getBoolean("on"); val ts = j.getLong("ts")
        if (isBanned(sid) || id.length > 64) return
        if (!verified(j, "pin|$tag|$id|$on|$ts")) return
        logFrame("pin", id, sid, ts, "$on", j.toString().toByteArray())
        applyPin(id, on)
    }

    private fun applyPin(msgId: String, on: Boolean) {
        messages.update { l -> l.map { if (it.id == msgId && !it.deleted) it.copy(pinned = on) else it } }
        scheduleSave()
    }

    // ---------- Calls: signalling rides on this node's encrypted links ----------

    /** Send a call signal to [to]. Offers, answers and hang-ups are signed so another operative can't fake them. */
    fun sendCallSignal(to: String, payload: JSONObject) {
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val body = payload.toString()
            val sig = Identity.sign("call|$tag|$myId|$to|$ts|${Crypto.sha256Hex(body.toByteArray())}".toByteArray())
            transport?.send(JSONObject().put("type", "call").put("sid", myId).put("to", to).put("ts", ts).put("b", body)
                .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
        }
    }

    /** Checks a call frame addressed to me; returns its payload if genuine and fresh. */
    fun openCallSignal(j: JSONObject): JSONObject? {
        val sid = j.optString("sid"); val to = j.optString("to"); val ts = j.optLong("ts"); val body = j.optString("b")
        if (to != myId || isBanned(sid) || body.length > 60_000 || abs(System.currentTimeMillis() - ts) > 60_000) return null
        if (!verified(j, "call|$tag|$sid|$to|$ts|${Crypto.sha256Hex(body.toByteArray())}")) return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    // ---------- Search jump, forwarding ----------

    /** Set by search: the chat screen scrolls to this message, then clears it. */
    val jumpTo = MutableStateFlow<String?>(null)

    /** Send a copy of [m] (from [src], which may be another node or contact) here. */
    fun forwardFrom(src: NodeSession, m: Msg) {
        when {
            m.deleted -> {}
            m.image != null -> {
                val bytes = src.readImage(m) ?: run { onNotice("That photo is no longer stored on this phone."); return }
                scope.launch(Dispatchers.IO) {
                    val id = UUID.randomUUID().toString()
                    vault.save(tag, id, bytes)
                    sendMedia(id, KIND_IMAGE, bytes, m.imgW, m.imgH, 0L)
                }
            }
            m.voice != null -> {
                val bytes = src.readVoice(m) ?: run { onNotice("That VOX note is gone (they aren't kept after a restart)."); return }
                sendVoice(bytes, m.durMs)
            }
            m.video != null -> scope.launch(Dispatchers.IO) {
                val id = UUID.randomUUID().toString()
                val tmp = File(app.cacheDir, "vout_fwd_$id.mp4")
                try {
                    if (!src.exportVideo(m, tmp)) { onNotice("That video isn't stored on this phone."); return@launch }
                    val thumb = src.readThumb(m)
                    vault.saveFrom(tag, videoKey(id), tmp)
                    thumb?.let { vault.save(tag, thumbKey(id), it) }
                    val ts = System.currentTimeMillis()
                    add(Msg(id, myId, room.value.name, "", ts, imgW = m.imgW, imgH = m.imgH, durMs = m.durMs, video = id))
                    sendVideoFile(id, ts, tmp, VideoUtil.Info(m.durMs, m.imgW, m.imgH, thumb))
                } finally { tmp.delete() }
            }
            m.audio != null -> scope.launch(Dispatchers.IO) {
                val id = UUID.randomUUID().toString()
                val tmp = File(app.cacheDir, "aout_fwd_$id")
                try {
                    if (!src.exportAudio(m, tmp)) { onNotice("That audio file isn't stored on this phone."); return@launch }
                    val meta = src.readAudioMeta(m) ?: AudioMeta.encode(m.aTitle, m.aArtist, m.aName, m.aMime)
                    vault.saveFrom(tag, audioKey(id), tmp)
                    vault.save(tag, thumbKey(id), meta)
                    val ts = System.currentTimeMillis()
                    add(audioMsg(id, myId, room.value.name, ts, m.durMs, null, meta, tmp.length()))
                    sendVideoFile(id, ts, tmp, VideoUtil.Info(m.durMs, 0, 0, meta), kind = KIND_AUDIO)
                } finally { tmp.delete() }
            }
            m.text.isNotEmpty() -> send(m.text)
        }
    }

    // ---------- Original-quality video on request ----------

    /** Ask the sender for the untouched original of a video. */
    fun requestOriginal(m: Msg) {
        val vid = m.video ?: return
        if (m.og <= 0 || m.ogHave || m.senderId == myId) return
        if (m.senderId !in reachable()) { onNotice("The sender needs to be online to send the original."); return }
        setProgress("$vid-og", 0f)
        transport?.send(JSONObject().put("type", "ogreq").put("sid", myId).put("to", m.senderId).put("id", vid)
            .toString().toByteArray())
    }

    private fun handleOriginalRequest(j: JSONObject) {
        val sid = j.optString("sid"); val id = j.optString("id")
        if (j.optString("to") != myId || isBanned(sid) || sid !in connected.value) return
        val m = messages.value.firstOrNull { it.id == id && it.senderId == myId && it.og > 0 } ?: return
        scope.launch(Dispatchers.IO) {
            val tmp = File(app.cacheDir, "vog_out_$id.mp4")
            try {
                if (!vault.decryptTo(tag, ogKey(id), tmp)) return@launch
                sendVideoFile("$id-og", System.currentTimeMillis(), tmp,
                    VideoUtil.Info(m.durMs, m.imgW, m.imgH, null), onlyTo = setOf(sid))
                setProgress("$id-og", null)
            } finally { tmp.delete() }
        }
    }

    /** Only the original sender's signed original is accepted, and only for a video they actually sent with one. */
    private fun acceptOriginalHeader(j: JSONObject, id: String, sid: String, kind: String, size: Int, hashes: List<String>) {
        val base = id.removeSuffix("-og")
        val m = messages.value.firstOrNull { it.id == base } ?: return
        if (kind != KIND_VIDEO || m.senderId != sid || m.og <= 0 || m.ogHave) return
        if (assemblies.containsKey(id)) return
        sweepTransfers()
        val a = try { Assembly(j, hashes, File(app.cacheDir, "vin_$id.part")) } catch (e: Exception) { return }
        if (assemblies.putIfAbsent(id, a) != null) { a.discard(); return }
        setProgress(id, 0f)
    }

    private fun completeOriginal(id: String, a: Assembly, size: Int) {
        val base = id.removeSuffix("-og")
        scope.launch(Dispatchers.IO) {
            try {
                val f = a.file ?: return@launch
                runCatching { a.raf?.close() }
                if (f.length() != size.toLong()) { setProgress(id, -1f); return@launch }
                vault.saveFrom(tag, videoKey(base), f)          // the original replaces the shrunk copy
                messages.update { l -> l.map { if (it.id == base) it.copy(ogHave = true) else it } }
                setProgress(id, null)
                scheduleSave()
            } catch (e: Exception) {
                setProgress(id, -1f)
            } finally { a.file?.delete() }
        }
    }

    // ---------- Catch-up: phones that were away get what they missed ----------
    //
    // Every phone keeps the signed frames of the last week (messages, sealed private messages, photo/video headers,
    // edits, reactions). When two phones link, each sends a list of what it holds; the other replies with
    // whatever is missing, exactly as originally signed, so nothing can be altered or invented on the way.
    // Retracted messages are dropped from the log, so they aren't handed out again.

    private class Logged(val key: String, val ts: Long, val type: String, val id: String, val frame: String)
    private val frameLog = ArrayList<Logged>()            // guarded by synchronized(frameLog)
    private var logSaveJob: Job? = null
    private val syncedAt = ConcurrentHashMap<String, Long>()

    private fun logFrame(type: String, id: String, sid: String, ts: Long, extra: String, bytes: ByteArray) {
        val key = Crypto.sha256Hex("$type|$id|$sid|$ts|$extra".toByteArray()).take(16)
        val now = System.currentTimeMillis()
        if (ts < now - LOG_DAYS * 86_400_000L) return
        synchronized(frameLog) {
            if (frameLog.any { it.key == key }) return
            frameLog.add(Logged(key, ts, type, id, bytes.toString(Charsets.UTF_8)))
            pruneLog(now)
        }
        scheduleLogSave()
    }

    private fun pruneLog(now: Long) {
        val life = ttl.value
        frameLog.removeAll { it.ts < now - LOG_DAYS * 86_400_000L || (life > 0 && it.ts < now - life) }
        frameLog.sortBy { it.ts }
        var chars = frameLog.sumOf { it.frame.length.toLong() }
        while (frameLog.size > LOG_MAX || chars > LOG_MAX_CHARS) { chars -= frameLog[0].frame.length; frameLog.removeAt(0) }
    }

    private fun forgetFrames(id: String) {
        synchronized(frameLog) { frameLog.removeAll { it.id == id && it.type in setOf("msg", "dm", "media") } }
        scheduleLogSave()
    }

    private fun scheduleLogSave() {
        if (!settings.value.keepHistory) return
        logSaveJob?.cancel()
        logSaveJob = scope.launch(Dispatchers.IO) {
            delay(2_500)
            val a = JSONArray()
            synchronized(frameLog) {
                frameLog.forEach { a.put(JSONObject().put("k", it.key).put("ts", it.ts).put("t", it.type).put("id", it.id).put("f", it.frame)) }
            }
            runCatching { store.saveFrames(tag, a.toString()) }
        }
    }

    private fun loadFrameLog() {
        val txt = store.loadFrames(tag) ?: return
        runCatching {
            val a = JSONArray(txt)
            synchronized(frameLog) {
                frameLog.clear()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    frameLog.add(Logged(o.getString("k"), o.getLong("ts"), o.getString("t"), o.getString("id"), o.getString("f")))
                }
                pruneLog(System.currentTimeMillis())
            }
        }
    }

    private fun requestSync(peer: String) {
        if (closed || peer !in connected.value) return
        val have = synchronized(frameLog) { frameLog.map { it.key } }
        transport?.sendTo(setOf(peer), JSONObject().put("type", "syn").put("sid", myId).put("to", peer)
            .put("since", prefs.getLong("joined_$tag", System.currentTimeMillis()))
            .put("have", JSONArray(have)).toString().toByteArray())
    }

    private fun handleSync(j: JSONObject) {
        val sid = j.optString("sid")
        if (j.optString("to") != myId || sid !in connected.value || isBanned(sid)) return
        val now = System.currentTimeMillis()
        if (now - (syncedAt[sid] ?: 0L) < 30_000) return
        syncedAt[sid] = now
        val have = j.optJSONArray("have")?.let { a -> (0 until minOf(a.length(), 3_000)).map { a.optString(it) }.toHashSet() } ?: hashSetOf()
        // Nobody gets messages from before they joined: their own claim, and never earlier than when we first met them
        val firstMet = people.firstSeenAt(sid) ?: now
        val since = maxOf(j.optLong("since", now), firstMet - 6 * 3_600_000L)
        val missing = synchronized(frameLog) { frameLog.filter { it.key !in have && it.ts >= since } }.sortedBy { it.ts }.takeLast(400)
        if (missing.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (e in missing) {
                if (closed || sid !in connected.value) break
                transport?.sendToBlocking(setOf(sid), e.frame.toByteArray())
                if (e.type == "media") runCatching { resendPieces(sid, e) }
                delay(5)
            }
        }
    }

    /** A photo, voice note or video someone missed: send its pieces again from storage (they match the signed header). */
    private suspend fun resendPieces(to: String, e: Logged) {
        val hd = JSONObject(e.frame)
        if (hd.optString("to").isNotEmpty()) return            // private items are sealed to one person: can't re-send
        val id = hd.getString("id"); val kind = hd.getString("k"); val size = hd.getInt("size")
        val bytes: ByteArray? = when (kind) {
            KIND_IMAGE -> vault.read(tag, id)
            KIND_VOICE -> synchronized(voices) { voices[id] }
            else -> null
        }
        if (bytes != null) {
            if (bytes.size != size) return
            val chunks = (size + CHUNK - 1) / CHUNK
            for (i in 0 until chunks) {
                if (closed || to !in connected.value) return
                transport?.sendToBlocking(setOf(to), piece(id, i, bytes.copyOfRange(i * CHUNK, minOf((i + 1) * CHUNK, size))))
                if (i % 4 == 3) delay(15)
            }
            return
        }
        if (kind != KIND_VIDEO && kind != KIND_AUDIO) return
        val tmp = File(app.cacheDir, "vout_sync_$id.mp4")
        try {
            if (!vault.decryptTo(tag, if (kind == KIND_AUDIO) audioKey(id) else videoKey(id), tmp) || tmp.length() != size.toLong()) return
            val buf = ByteArray(CHUNK)
            RandomAccessFile(tmp, "r").use { raf ->
                val chunks = (size + CHUNK - 1) / CHUNK
                for (i in 0 until chunks) {
                    if (closed || to !in connected.value) return
                    val len = minOf(CHUNK, size - i * CHUNK)
                    raf.seek(i.toLong() * CHUNK); raf.readFully(buf, 0, len)
                    transport?.sendToBlocking(setOf(to), piece(id, i, buf.copyOf(len)))
                    if (i % 4 == 3) delay(15)
                }
            }
        } finally { tmp.delete() }
    }

    // ---------- Contacts: requests and the direct-link outbox ----------

    /** Ask [to] for a direct link, sealing the link's secret to their key. False if we don't have their key yet. */
    fun sendContactRequest(to: String, secret: ByteArray, via: String): Boolean {
        val pub = eks[to] ?: return false
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val (eph, ct) = Crypto.wrapFor(pub, to, JSONObject().put("s", b64(secret)).toString().toByteArray(), "creq|$tag|$to".toByteArray())
            val e = b64(eph); val c = b64(ct)
            val sig = Identity.sign("creq|$tag|$myId|$to|$via|$ts|${Crypto.sha256Hex((e + c).toByteArray())}".toByteArray())
            transport?.send(JSONObject().put("type", "creq").put("sid", myId).put("n", room.value.name).put("to", to)
                .put("via", via).put("ts", ts).put("e", e).put("c", c).put("pk", b64(myPub)).put("sig", b64(sig))
                .toString().toByteArray())
        }
        return true
    }

    private fun handleContactRequest(j: JSONObject) {
        val sid = j.getString("sid")
        if (j.optString("to") != myId || isBanned(sid) || sid == myId) return
        val n = j.optString("n").take(24); val via = j.optString("via"); val ts = j.getLong("ts")
        val e = j.getString("e"); val c = j.getString("c")
        if (c.length > 2_000 || via !in setOf("node", "qr", "link") || abs(System.currentTimeMillis() - ts) > 86_400_000L) return
        if (!verified(j, "creq|$tag|$sid|$myId|$via|$ts|${Crypto.sha256Hex((e + c).toByteArray())}")) return
        val plain = Crypto.unwrap(ecdh.first, Base64.decode(e, Base64.NO_WRAP), myId, Base64.decode(c, Base64.NO_WRAP),
            "creq|$tag|$myId".toByteArray()) ?: return
        val secret = runCatching { Base64.decode(JSONObject(plain.toString(Charsets.UTF_8)).getString("s"), Base64.NO_WRAP) }.getOrNull() ?: return
        if (secret.size != 32) return
        people.seen(sid, n)
        known.update { it + (sid to people.alias(sid, n)) }
        onContactRequest(this, sid, people.alias(sid, n), secret, via)
    }

    private val delivered: MutableSet<String> by lazy {
        ConcurrentHashMap.newKeySet<String>().apply { addAll(prefs.getStringSet("dlv_$tag", emptySet()) ?: emptySet()) }
    }

    private fun rememberDelivered(ids: Collection<String>) {
        val mine = messages.value.filter { it.senderId == myId }.map { it.id }.toSet()
        if (!delivered.addAll(ids.filter { it in mine })) return
        delivered.retainAll(mine)
        prefs.edit().putStringSet("dlv_$tag", HashSet(delivered)).apply()
    }

    fun isDelivered(id: String) = id in delivered || receipts.value[id] != null

    /** Your texts in this direct link that haven't reached them yet. */
    fun queuedCount(): Int = messages.value.count { isQueued(it) }

    private fun isQueued(m: Msg) = m.senderId == myId && !m.system && !m.deleted && m.text.isNotEmpty() &&
        m.image == null && m.video == null && m.voice == null && m.dm == null && !isDelivered(m.id)

    /** Direct links keep an outbox: undelivered texts go out again whenever the other phone links. */
    private fun flushOutbox() {
        if (closed || connected.value.isEmpty()) return
        messages.value.filter { isQueued(it) }.takeLast(50).forEach { m ->
            transport?.send(msgFrame(m.id, m.ts, m.text, m.replyTo ?: ""))
        }
    }

    // ---- direct links: photos, VOX and video wait for the other phone too ----

    private data class Queued(val id: String, val kind: String, val w: Int, val h: Int, val dur: Long)
    private val mediaQueue = CopyOnWriteArrayList<Queued>()
    @Volatile private var flushingMedia = false

    private fun enqueue(q: Queued) {
        if (mediaQueue.none { it.id == q.id }) mediaQueue.add(q)
        setProgress(q.id, QUEUED)
        saveMediaQueue()
    }

    /** Photos and videos survive a restart (they're in the encrypted vault); VOX notes live in memory only. */
    private fun saveMediaQueue() {
        val a = JSONArray()
        mediaQueue.filter { it.kind != KIND_VOICE }.forEach { q ->
            a.put(JSONObject().put("id", q.id).put("k", q.kind).put("w", q.w).put("h", q.h).put("d", q.dur))
        }
        prefs.edit().putString("mq_$tag", a.toString()).apply()
    }

    private fun loadMediaQueue() {
        if (directPeer == null) return
        runCatching {
            val a = JSONArray(prefs.getString("mq_$tag", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val q = Queued(o.getString("id"), o.getString("k"), o.optInt("w"), o.optInt("h"), o.optLong("d"))
                if (mediaQueue.none { it.id == q.id }) mediaQueue.add(q)
                setProgress(q.id, QUEUED)
            }
        }
    }

    /** They linked: send everything that was waiting, one item at a time. */
    private fun flushMediaQueue() {
        if (closed || flushingMedia || mediaQueue.isEmpty() || connected.value.isEmpty()) return
        flushingMedia = true
        scope.launch(Dispatchers.IO) {
            try {
                for (q in mediaQueue.toList()) {
                    if (closed || connected.value.isEmpty()) break
                    mediaQueue.remove(q); saveMediaQueue()
                    if (q.id in cancelled) continue
                    when (q.kind) {
                        KIND_IMAGE -> vault.read(tag, q.id)?.let { sendMedia(q.id, KIND_IMAGE, it, q.w, q.h, 0L) } ?: setProgress(q.id, -1f)
                        KIND_VOICE -> synchronized(voices) { voices[q.id] }?.let { sendMedia(q.id, KIND_VOICE, it, 0, 0, q.dur) } ?: setProgress(q.id, -1f)
                        KIND_VIDEO -> {
                            val tmp = File(app.cacheDir, "vout_q_${q.id}.mp4")
                            try {
                                if (vault.decryptTo(tag, videoKey(q.id), tmp)) {
                                    sendVideoFile(q.id, System.currentTimeMillis(), tmp,
                                        VideoUtil.Info(q.dur, q.w, q.h, vault.read(tag, thumbKey(q.id))))
                                } else setProgress(q.id, -1f)
                            } finally { tmp.delete() }
                        }
                        KIND_AUDIO -> {
                            val tmp = File(app.cacheDir, "aout_q_${q.id}")
                            try {
                                if (vault.decryptTo(tag, audioKey(q.id), tmp)) {
                                    sendVideoFile(q.id, System.currentTimeMillis(), tmp,
                                        VideoUtil.Info(q.dur, 0, 0, vault.read(tag, thumbKey(q.id))), kind = KIND_AUDIO)
                                } else setProgress(q.id, -1f)
                            } finally { tmp.delete() }
                        }
                    }
                }
            } finally { flushingMedia = false }
        }
    }

    // ---------- Naming ----------

    fun displayName(): String = if (directPeer != null) "DIRECT LINK" else nickname.value.ifBlank { room.value.code }

    /** How a linked operative is reached right now: "WIFI", "NET", or null. */
    fun route(id: String): String? = (transport as? MultiTransport)?.route(id)

    /** True when the host is this phone, or was pinned from a QR / link invite (so can't be spoofed). */
    val hostVerified: Boolean get() = room.value.created || prefs.getBoolean("hostpin_$tag", false) || hostId.value == myId

    /** Invite for this node; includes the host's fingerprint when we know who the host is. */
    fun invite(): Invite = Invite(room.value.code, room.value.password, hostId.value)

    /** Turn the internet link on or off for this node without restarting. */
    fun applyInternet(on: Boolean) { (transport as? MultiTransport)?.setInternet(on) }

    data class LinkInfo(
        val onWifi: Boolean, val addresses: List<String>, val port: Int,
        val lanFound: Int, val lanLinked: Int, val lanStuck: Int, val discoveryError: Int?, val advertiseError: Int?,
        val internetOn: Boolean, val relays: Int, val netLinked: Int
    )

    /** Everything the Link diagnostics screen shows. Safe to call often. */
    fun linkInfo(): LinkInfo {
        val mt = transport as? MultiTransport
        val lan = mt?.lan
        val lanLinked = connected.value.count { lan?.isConnected(it) == true }
        return LinkInfo(
            onWifi = lan?.onWifi() ?: false, addresses = lan?.localAddresses() ?: emptyList(), port = lan?.port() ?: 0,
            lanFound = lan?.found() ?: 0, lanLinked = lanLinked, lanStuck = ((lan?.stuck() ?: emptySet()) - connected.value).size,
            discoveryError = lan?.discoveryError, advertiseError = lan?.advertiseError,
            internetOn = mt?.internetOn() ?: false, relays = mt?.relays() ?: -1, netLinked = mt?.netLinked() ?: 0
        )
    }

    /** Link to another phone by the address shown on its diagnostics screen (e.g. 192.168.1.23:41234). */
    suspend fun linkByAddress(text: String): Boolean {
        val m = Regex("^\\s*([0-9]{1,3}(?:\\.[0-9]{1,3}){3})(?::([0-9]{1,5}))?\\s*$").find(text) ?: return false
        val host = m.groupValues[1]
        val port = m.groupValues[2].toIntOrNull() ?: (transport as? MultiTransport)?.lan?.port() ?: return false
        if (port !in 1..65535) return false
        return (transport as? MultiTransport)?.lan?.dialManual(host, port) ?: false
    }

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

    /** How a private item is delivered: the recipient, which link(s) to send on, and the item's own key. */
    private class Seal(val to: String, val pub: ByteArray, val via: Set<String>, val key: SecretKey)

    /** Private media goes straight to the recipient, or through the one operative who relays for us. */
    private fun sealFor(to: String): Seal? {
        val pub = eks[to] ?: return null
        val via = if (to in connected.value) setOf(to) else chosenRelay(myId, to)?.let { setOf(it) } ?: return null
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Seal(to, pub, via, SecretKeySpec(k, "AES"))
    }

    /** Each private piece is encrypted with the item's key; the IV comes from the piece number (the key is used once). */
    private fun sealPiece(key: SecretKey, id: String, i: Int, part: ByteArray): ByteArray {
        val iv = ByteArray(12)
        for (b in 0 until 4) iv[8 + b] = (i ushr (24 - 8 * b)).toByte()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD("dmc|$id|$i".toByteArray())
        return iv + c.doFinal(part)
    }

    private fun out(seal: Seal?, frame: ByteArray, onlyTo: Set<String>? = null) {
        when {
            onlyTo != null -> transport?.sendToBlocking(onlyTo, frame)
            seal == null -> transport?.sendBlocking(frame)
            else -> transport?.sendToBlocking(seal.via, frame)
        }
    }

    private fun cantReach(to: String) =
        onNotice("Can't reach ${people.label(to, known.value[to] ?: "them")} privately right now. Tap Retry once they're linked.")

    private class Assembly(val header: JSONObject, val hashes: List<String>, val file: File?, val key: SecretKey? = null) {
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
    fun hasAudio(m: Msg): Boolean = m.audio?.let { vault.exists(tag, audioKey(it)) } ?: false
    fun exportAudio(m: Msg, dest: File): Boolean = m.audio?.let { vault.decryptTo(tag, audioKey(it), dest) } ?: false
    fun copyAudio(m: Msg, out: OutputStream): Boolean = m.audio?.let { vault.copyTo(tag, audioKey(it), out) } ?: false
    fun readAudioMeta(m: Msg): ByteArray? = m.audio?.let { vault.read(tag, thumbKey(it)) }

    /** An audio-file message, with title / artist / name / type read from its (signed) details. */
    private fun audioMsg(id: String, sid: String, name: String, ts: Long, dur: Long, dm: String?, meta: ByteArray?, size: Long): Msg {
        val (t, a, n, mime) = AudioMeta.decode(meta)
        return Msg(id, sid, name, "", ts, durMs = dur, dm = dm, audio = id,
            aTitle = t.ifEmpty { n.substringBeforeLast('.').ifEmpty { "Audio" } }, aArtist = a, aName = n, aMime = mime, aSize = size)
    }

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
                !((m.image != null || m.video != null || m.audio != null) && m.senderId != myId && moving.containsKey(m.id))
        }
    }

    /** Which stored files to keep, given the history being kept and your settings. */
    private fun keepKeys(list: List<Msg>, s: AppSettings): Set<String> {
        val keep = HashSet<String>()
        if (s.keepImages) list.forEach { m -> m.image?.let { keep.add(it) } }
        if (s.keepVideos) list.forEach { m -> m.video?.let { keep.add(videoKey(it)); keep.add(thumbKey(it)); if (m.og > 0) keep.add(ogKey(it)) } }
        list.forEach { m -> m.audio?.let { if (s.keepAudio || m.senderId == myId) { keep.add(audioKey(it)); keep.add(thumbKey(it)) } } }
        return keep
    }

    private fun dropAssemblies() {
        assemblies.values.forEach { it.discard() }
        assemblies.clear()
    }

    fun sendPhoto(uri: Uri, q: Quality = Quality.STANDARD, dmTo: String? = null) {
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
            sendMedia(id, KIND_IMAGE, res.bytes, res.w, res.h, 0L, dmTo)
        }
    }

    fun sendVoice(bytes: ByteArray, durMs: Long, dmTo: String? = null) {
        if (bytes.isEmpty() || bytes.size > MAX_MEDIA) {
            onNotice("That voice note is too large to send.")
            return
        }
        scope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            rememberVoice(id, bytes)
            sendMedia(id, KIND_VOICE, bytes, 0, 0, durMs, dmTo)
        }
    }

    fun sendVideo(uri: Uri, q: Quality = Quality.STANDARD, dmTo: String? = null) {
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
            add(Msg(id, myId, r.name, "", ts, imgW = info.w, imgH = info.h, durMs = info.durMs, video = id, dm = dmTo))

            // ORIGINAL: everyone gets a Standard copy now; the untouched file stays here for whoever taps DOWNLOAD
            var og = 0L
            if (q == Quality.ORIGINAL && dmTo == null) {
                val tmp = File(app.cacheDir, "vog_$id.mp4")
                try {
                    app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    if (tmp.length() in 1L..MAX_ORIGINAL_VIDEO) { vault.saveFrom(tag, ogKey(id), tmp); og = tmp.length() }
                } catch (e: Exception) { og = 0L } finally { tmp.delete() }
                if (og > 0) messages.update { l -> l.map { if (it.id == id) it.copy(og = og, ogHave = true) else it } }
                else onNotice("That original is over 400 MB, so only the shrunk copy will be sent.")
            }
            val out = File(app.cacheDir, "vout_$id.mp4")
            val shrinkTo = when { q != Quality.ORIGINAL -> q; dmTo != null -> Quality.HIGH; else -> Quality.STANDARD }
            val ok = try {
                VideoUtil.transcode(app, uri, out, info, shrinkTo) { p -> preparing.update { m -> m + (id to p) } }
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
                sendVideoFile(id, ts, out, info, dmTo, og)
            } finally {
                out.delete()
                jobs.remove(id)
            }
        }
    }

    /** Send an audio file exactly as picked: music, a podcast, a recording. No re-encoding. */
    fun sendAudio(uri: Uri, dmTo: String? = null) {
        val r = room.value
        scope.launch(Dispatchers.IO) {
            val info = AudioUtil.probe(app, uri)
            if (info == null) { onNotice("That isn't an audio file this phone can play."); return@launch }
            val tooBig = "Audio files can be up to ${fmtSize(MAX_AUDIO_BYTES.toLong())}."
            if (info.size > MAX_AUDIO_BYTES) { onNotice("$tooBig That one is ${fmtSize(info.size)}."); return@launch }
            if (info.durMs > MAX_AUDIO_MS) { onNotice("Audio files can be up to 4 hours long."); return@launch }
            val id = UUID.randomUUID().toString()
            val tmp = File(app.cacheDir, "aout_$id")
            coroutineContext[Job]?.let { jobs[id] = it }
            try {
                val copied = app.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            total += n
                            if (total > MAX_AUDIO_BYTES) return@use false
                            o.write(buf, 0, n)
                        }
                        true
                    }
                } ?: false
                if (!copied || tmp.length() <= 0L) {
                    onNotice(if (tmp.length() > 0L) tooBig else "Couldn't read that audio file.")
                    return@launch
                }
                val meta = AudioMeta.encode(info.title, info.artist, info.name, info.mime)
                vault.save(tag, thumbKey(id), meta)
                vault.saveFrom(tag, audioKey(id), tmp)
                val ts = System.currentTimeMillis()
                add(audioMsg(id, myId, r.name, ts, info.durMs, dmTo, meta, tmp.length()))
                sendVideoFile(id, ts, tmp, VideoUtil.Info(info.durMs, 0, 0, meta), dmTo, kind = KIND_AUDIO)
            } catch (e: Exception) {
                onNotice("Couldn't read that audio file.")
            } finally {
                tmp.delete()
                jobs.remove(id)
            }
        }
    }

    private fun pieceHash(b: ByteArray): String = Crypto.sha256Hex(b).take(32)

    private fun header(
        id: String, ts: Long, kind: String, size: Int, hashes: List<String>,
        w0: Int, h0: Int, dur0: Long, thumb0: ByteArray?, seal: Seal? = null, og: Long = 0L
    ): ByteArray {
        val r = room.value
        val chunks = hashes.size
        val listHash = Crypto.sha256Hex(hashes.joinToString(",").toByteArray())
        // Private items: size, length, preview and the item key travel sealed to the recipient
        var w = w0; var h = h0; var dur = dur0; var thumb = thumb0
        var tail = ""
        var e = ""; var c = ""
        if (seal != null) {
            val meta = JSONObject().put("mk", b64(seal.key.encoded)).put("w", w).put("h", h).put("dur", dur)
            thumb?.let { meta.put("thumb", b64(it)) }
            val (eph, ct) = Crypto.wrapFor(seal.pub, seal.to, meta.toString().toByteArray(), "dmm|$tag|$id".toByteArray())
            e = b64(eph); c = b64(ct)
            w = 0; h = 0; dur = 0L; thumb = null
            tail = "|${seal.to}|${Crypto.sha256Hex((e + c).toByteArray())}"
        }
        val th = thumb?.let { Crypto.sha256Hex(it) } ?: ""
        if (og > 0) tail += "|og$og"
        val sig = Identity.sign("media|$tag|$id|$myId|$ts|$kind|$size|$chunks|$listHash|$w|$h|$dur|$th$tail".toByteArray())
        val j = JSONObject().put("type", "media").put("id", id).put("sid", myId).put("n", r.name)
            .put("ts", ts).put("k", kind).put("size", size).put("chunks", chunks)
            .put("hashes", JSONArray(hashes)).put("w", w).put("h", h).put("dur", dur).put("th", th)
            .put("pk", b64(myPub)).put("sig", b64(sig))
        if (thumb != null) j.put("thumb", b64(thumb))
        if (seal != null) j.put("to", seal.to).put("e", e).put("c", c)
        if (og > 0) j.put("og", og)
        return j.toString().toByteArray()
    }

    private fun piece(id: String, i: Int, part: ByteArray): ByteArray =
        JSONObject().put("type", "mc").put("id", id).put("i", i).put("d", b64(part)).toString().toByteArray()

    private suspend fun sendMedia(id: String, kind: String, bytes: ByteArray, w: Int, h: Int, dur: Long, dmTo: String? = null) {
        val r = room.value
        val ts = System.currentTimeMillis()
        val chunks = (bytes.size + CHUNK - 1) / CHUNK
        setProgress(id, 0f)
        add(
            if (kind == KIND_IMAGE) Msg(id, myId, r.name, "", ts, image = id, imgW = w, imgH = h, dm = dmTo)
            else Msg(id, myId, r.name, "", ts, voice = id, durMs = dur, dm = dmTo)
        )
        if (directPeer != null && connected.value.isEmpty()) { enqueue(Queued(id, kind, w, h, dur)); return }
        val seal = dmTo?.let { sealFor(it) ?: run { setProgress(id, -1f); cantReach(it); return } }
        fun wire(i: Int): ByteArray {
            val part = bytes.copyOfRange(i * CHUNK, minOf((i + 1) * CHUNK, bytes.size))
            return if (seal == null) part else sealPiece(seal.key, id, i, part)
        }
        val hashes = (0 until chunks).map { i -> pieceHash(wire(i)) }
        try {
            val hdr = header(id, ts, kind, bytes.size, hashes, w, h, dur, null, seal)
            if (seal == null) logFrame("media", id, myId, ts, "", hdr)
            out(seal, hdr)
            for (i in 0 until chunks) {
                if (closed) return
                if (id in cancelled) { setProgress(id, -1f); return }
                out(seal, piece(id, i, wire(i)))
                setProgress(id, (i + 1).toFloat() / chunks)
                if (i % 4 == 3) delay(15)   // let chat messages and heartbeats slip in between
            }
            setProgress(id, null)
        } catch (e: Exception) {
            setProgress(id, -1f)
        }
    }

    private suspend fun sendVideoFile(
        id: String, ts: Long, f: File, info: VideoUtil.Info, dmTo: String? = null,
        og: Long = 0L, onlyTo: Set<String>? = null, kind: String = KIND_VIDEO
    ) {
        val size = f.length().toInt()
        val chunks = (size + CHUNK - 1) / CHUNK
        val buf = ByteArray(CHUNK)
        if (onlyTo == null && directPeer != null && connected.value.isEmpty()) { enqueue(Queued(id, kind, info.w, info.h, info.durMs)); return }
        val seal = dmTo?.let { sealFor(it) ?: run { setProgress(id, -1f); cantReach(it); return } }
        fun readPiece(raf: RandomAccessFile, i: Int): ByteArray {
            val len = minOf(CHUNK, size - i * CHUNK)
            raf.seek(i.toLong() * CHUNK)
            raf.readFully(buf, 0, len)
            val part = buf.copyOf(len)
            return if (seal == null) part else sealPiece(seal.key, id, i, part)
        }
        setProgress(id, 0f)
        try {
            val hashes = RandomAccessFile(f, "r").use { raf -> (0 until chunks).map { pieceHash(readPiece(raf, it)) } }
            val hdr = header(id, ts, kind, size, hashes, info.w, info.h, info.durMs, info.thumb, seal, og)
            if (seal == null && onlyTo == null) logFrame("media", id, myId, ts, "", hdr)
            out(seal, hdr, onlyTo)
            RandomAccessFile(f, "r").use { raf ->
                for (i in 0 until chunks) {
                    if (closed) return
                    if (id in cancelled) { setProgress(id, -1f); return }
                    out(seal, piece(id, i, readPiece(raf, i)), onlyTo)
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
        var w = j.optInt("w", 0); var h = j.optInt("h", 0); var dur = j.optLong("dur", 0L)
        val th = j.optString("th", "")
        val to = j.optString("to", "")
        val e = j.optString("e", ""); val c = j.optString("c", "")
        if (to.isNotEmpty() && (to.length != 32 || c.length > 600_000)) return
        val og = j.optLong("og", 0L)
        val isOriginal = id.endsWith("-og")
        var tail = if (to.isEmpty()) "" else "|$to|${Crypto.sha256Hex((e + c).toByteArray())}"
        if (og > 0) tail += "|og$og"
        val limit = when (kind) {
            KIND_IMAGE -> ImageUtil.MAX_ORIGINAL
            KIND_VOICE -> MAX_MEDIA
            KIND_VIDEO -> if (isOriginal) MAX_ORIGINAL_VIDEO.toInt() else MAX_VIDEO_BYTES
            KIND_AUDIO -> if (isOriginal) return else MAX_AUDIO_BYTES
            else -> return
        }
        if (size !in 1..limit || chunks != (size + CHUNK - 1) / CHUNK) return
        if (kind == KIND_VIDEO && dur > MAX_VIDEO_MS + 999) return
        if (kind == KIND_AUDIO && dur > MAX_AUDIO_MS) return
        val arr = j.getJSONArray("hashes")
        if (arr.length() != chunks) return
        val hashes = (0 until chunks).map { arr.getString(it) }
        val listHash = Crypto.sha256Hex(hashes.joinToString(",").toByteArray())
        if (!verified(j, "media|$tag|$id|$sid|$ts|$kind|$size|$chunks|$listHash|$w|$h|$dur|$th$tail")) return
        mediaOrigin[id] = sid
        if (isOriginal) { acceptOriginalHeader(j, id, sid, kind, size, hashes); return }
        logFrame("media", id, sid, ts, "", j.toString().toByteArray())
        if (to.isNotEmpty()) mediaTo[id] = to
        if (to.isNotEmpty() && to != myId) return          // someone else's private item: a relay just passes it on
        if (synchronized(seen) { seen.contains(id) }) return

        // Private item: open the sealed details (item key, size, length, preview)
        var itemKey: SecretKey? = null
        var thumb: ByteArray? = null
        if (to.isNotEmpty()) {
            val plain = try {
                Crypto.unwrap(ecdh.first, Base64.decode(e, Base64.NO_WRAP), myId,
                    Base64.decode(c, Base64.NO_WRAP), "dmm|$tag|$id".toByteArray())
            } catch (ex: Exception) { null } ?: return
            val meta = try { JSONObject(plain.toString(Charsets.UTF_8)) } catch (ex: Exception) { return }
            val mk = try { Base64.decode(meta.getString("mk"), Base64.NO_WRAP) } catch (ex: Exception) { return }
            if (mk.size != 32) return
            itemKey = SecretKeySpec(mk, "AES")
            w = meta.optInt("w", 0); h = meta.optInt("h", 0); dur = meta.optLong("dur", 0L)
            if (kind == KIND_VIDEO && dur > MAX_VIDEO_MS + 999) return
            if (kind == KIND_AUDIO && dur > MAX_AUDIO_MS) return
            if (kind == KIND_VIDEO || kind == KIND_AUDIO) thumb = meta.optString("thumb", "").takeIf { it.isNotEmpty() && it.length <= 300_000 }
                ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        }

        // Video preview picture, checked against the signed fingerprint
        if (to.isEmpty() && (kind == KIND_VIDEO || kind == KIND_AUDIO) && th.isNotEmpty()) {
            val t = j.optString("thumb", "")
            if (t.length > 300_000) return
            thumb = try { Base64.decode(t, Base64.NO_WRAP) } catch (e: Exception) { return }
            if (Crypto.sha256Hex(thumb) != th) return
        }

        sweepTransfers()
        if (assemblies.size >= MAX_ASSEMBLIES) return
        // Videos and large (original) photos are assembled on disk rather than in memory
        val file = if (kind == KIND_VIDEO || kind == KIND_AUDIO || size > MAX_MEDIA) File(app.cacheDir, "vin_$id.part") else null
        val a = try { Assembly(j, hashes, file, itemKey) } catch (ex: Exception) { return }
        if (assemblies.putIfAbsent(id, a) != null) { a.discard(); return }
        people.seen(sid, n)
        val shown = people.alias(sid, n)
        known.update { it + (sid to shown) }
        setProgress(id, 0f)
        val dm = if (to.isNotEmpty()) sid else null
        when (kind) {
            KIND_IMAGE -> add(Msg(id, sid, shown, "", ts, image = id, imgW = w, imgH = h, dm = dm))
            KIND_VOICE -> add(Msg(id, sid, shown, "", ts, voice = id, durMs = dur, dm = dm))
            KIND_AUDIO -> {
                thumb?.let { tb -> scope.launch(Dispatchers.IO) { vault.save(tag, thumbKey(id), tb) } }
                add(audioMsg(id, sid, shown, ts, dur, dm, thumb, size.toLong()))
            }
            else -> {
                thumb?.let { tb -> scope.launch(Dispatchers.IO) { vault.save(tag, thumbKey(id), tb) } }
                add(Msg(id, sid, shown, "", ts, imgW = w, imgH = h, durMs = dur, video = id, dm = dm, og = og))
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
        val k = a.key
        val data = if (k == null) bytes else Crypto.decrypt(k, bytes, "dmc|$id|$i".toByteArray()) ?: return
        if (data.size != minOf(CHUNK, a.header.getInt("size") - i * CHUNK)) return
        synchronized(a) {
            if (a.done || !a.got.add(i)) return
            val raf = a.raf
            if (raf != null) {
                try {
                    raf.seek(i.toLong() * CHUNK)
                    raf.write(data)
                } catch (e: Exception) {
                    a.got.remove(i)
                    return
                }
            } else {
                a.chunks[i] = data
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
        if (id.endsWith("-og")) { completeOriginal(id, a, size); return }
        val n = people.label(hd.getString("sid"), hd.getString("n"))
        scope.launch(Dispatchers.IO) {
            try {
                val f = a.file
                if (f != null) {
                    runCatching { a.raf?.close() }
                    if (f.length() != size.toLong()) { setProgress(id, -1f); return@launch }
                    vault.saveFrom(tag, when (kind) { KIND_VIDEO -> videoKey(id); KIND_AUDIO -> audioKey(id); else -> id }, f)
                } else {
                    val out = ByteArrayOutputStream()
                    for (i in a.hashes.indices) out.write(a.chunks[i] ?: run { setProgress(id, -1f); return@launch })
                    val data = out.toByteArray()
                    if (data.size != size) { setProgress(id, -1f); return@launch }
                    if (kind == KIND_IMAGE) vault.save(tag, id, data) else rememberVoice(id, data)
                }
                setProgress(id, null)
                pendingDelivered.add(id)
                val from = hd.getString("sid")
                val isPrivate = hd.optString("to").isNotEmpty()
                val onScreen = AppState.foreground && viewing(tag) && (!isPrivate || viewingDm == from)
                if (!onScreen) {
                    unread.update { it + 1 }
                    if (isPrivate) dmUnread.update { it + (from to ((it[from] ?: 0) + 1)) }
                }
                if (!AppState.foreground && !muted.value) {
                    Notifier.message(app, displayName(), if (isPrivate) "$n · PRIVATE" else n, tag = tag, dm = if (isPrivate) from else null, text = when (kind) {
                        KIND_IMAGE -> "IMG · Photo"
                        KIND_VIDEO -> "VID · Video"
                        KIND_AUDIO -> "AUD · " + (messages.value.firstOrNull { it.id == id }?.aTitle?.ifBlank { null } ?: "Audio file")
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
                "succ" -> Succession.verify(j.optJSONObject("c"))?.let { l -> if (l.old == target && l.revoke) return true }
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
        val life = ttl.value
        if (life > 0 && !m.system && m.ts < System.currentTimeMillis() - life) return
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
        private const val LOG_DAYS = 7
        /** Progress value meaning "waiting for the other phone" (direct links). */
        const val QUEUED = -2f
        private const val LOG_MAX = 800
        private const val LOG_MAX_CHARS = 6_000_000L
        private val CONTENT_TYPES = setOf("msg", "media", "dm", "react", "edit", "rcpt", "typing", "kick", "kv", "creq", "hosto", "ttl", "pin", "ogreq", "poll", "pvote", "call")
        private const val MAX_ORIGINAL_VIDEO = 400L * 1024 * 1024
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
        private const val KIND_AUDIO = "audio"
    }
}
