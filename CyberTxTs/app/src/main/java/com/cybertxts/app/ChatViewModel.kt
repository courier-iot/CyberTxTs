package com.cybertxts.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * The app's one long-lived brain. Holds your profile, your settings, and every node you are aboard.
 * (Each node is a NodeSession with its own connections and keys.)
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Identity.securePrefs(app)
    private val store = HistoryStore(app)
    private val vault = PhotoVault(app)
    /** Nicknames you give people, and everyone's permanent alias. */
    val people = PeopleBook(prefs)
    val myId: String = Identity.deviceId()
    private val myPub: ByteArray = Identity.publicKeyBytes()
    private val ecdh = Identity.ecdhKeys(prefs)

    val settings = MutableStateFlow(SettingsStore.load(prefs))
    val profileName = MutableStateFlow<String?>(prefs.getString("profile_name", null))
    val nodes = MutableStateFlow<List<NodeSession>>(emptyList())
    val active = MutableStateFlow<NodeSession?>(null)
    val joining = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)
    /** (number of nodes, total other people connected) for the notification */
    val status = MutableStateFlow(0 to 0)

    /** Contacts screen showing, and which contact's direct chat is open (contact ID). */
    val contactsOpen = MutableStateFlow(false)
    val directChat = MutableStateFlow<String?>(null)

    /** Direct chat open on screen (its session tag), so its messages count as read. */
    val openDirect = MutableStateFlow<String?>(null)

    /** Contacts and their direct links. */
    val contacts: Contacts = Contacts(
        viewModelScope, prefs, people, myId, { profileName.value },
        makeSession = { code, key, peer, created ->
            makeSession(RoomInfo(code, key, profileName.value ?: "Operative", created), peer, contact = true)
        },
        onNotice = { notice.value = it },
        onAny = { ensureService() }
    )

    /** A contact link or QR opened from outside the app, waiting for confirmation. */
    val pendingContact = MutableStateFlow<ContactOffer?>(null)

    /** Recovery phrase public key (null = not set up) and when you last exported a backup. */
    val recoveryPub = MutableStateFlow(prefs.getString("rec_pub", null))
    val lastBackup = MutableStateFlow(prefs.getLong("last_backup", 0L))

    init {
        AppLock.init(prefs)
        migrateOldSave()
        ensureCommitment()
        restore()
        contacts.restore()
        ensureService()
    }

    private fun ensureService() {
        if (settings.value.keepAlive && (nodes.value.isNotEmpty() || contacts.list.value.isNotEmpty())) SessionService.start(getApplication())
    }

    // ---------- Profile ----------

    /** Your alias is chosen once, at first launch, and is permanent for this install. */
    fun setProfileName(name: String) {
        if (profileName.value != null) return
        val n = name.trim().take(24)
        if (n.isEmpty()) return
        prefs.edit().putString("profile_name", n).apply()
        profileName.value = n
    }

    // ---------- Nodes ----------

    private fun makeSession(r: RoomInfo, peer: (() -> String)? = null, contact: Boolean = false): NodeSession = NodeSession(
        app = getApplication(), scope = viewModelScope, prefs = prefs, store = store,
        vault = vault, people = people, myId = myId, myPub = myPub, ecdh = ecdh, settings = settings, initial = r,
        onChanged = { if (!contact) { persist(); refreshStatus() } },
        onNotice = { notice.value = it },
        onLeft = { if (!contact) removeSession(it) },
        viewing = { t -> active.value?.tag == t || openDirect.value == t },
        recovery = { commitment() },
        myChain = { chain() },
        onSuccession = { c -> viewModelScope.launch(Dispatchers.Default) { runCatching { applySuccession(c) } } },
        directPeer = peer,
        onContactRequest = { src, from, n, secret, via -> runCatching { contacts.onRequest(src, from, n, secret, via) } },
        onPeersChanged = { src, ids -> if (peer != null) runCatching { contacts.onDirectPeers(src, ids) } }
    )

    fun offerContact(o: ContactOffer?) { if (o != null) pendingContact.value = o }

    /** An invite opened from a link or scanned QR, waiting for the user to confirm. */
    val pendingInvite = MutableStateFlow<Invite?>(null)

    fun offerInvite(i: Invite?) { if (i != null) pendingInvite.value = i }

    fun join(code: String, password: String, created: Boolean, host: String? = null) {
        val name = profileName.value ?: return
        if (joining.value) return
        // Pin the host's fingerprint from the invite: only that phone will ever be accepted as host
        if (!created && host != null && host.length == 32 && host != myId) {
            val t = Crypto.roomTag(code)
            prefs.edit().putString("host_$t", host).putBoolean("hostpin_$t", true).apply()
        }
        val existing = nodes.value.firstOrNull { it.tag == Crypto.roomTag(code) }
        if (existing != null) {
            select(existing)
            notice.value = "You're already linked to that node."
            return
        }
        viewModelScope.launch {
            joining.value = true
            val s = makeSession(RoomInfo(code, password, name, created))
            nodes.value = nodes.value + s      // show it right away
            select(s)
            persist(); refreshStatus()
            if (settings.value.keepAlive) SessionService.start(getApplication())
            runCatching { s.start() }
            joining.value = false
        }
    }

    fun select(s: NodeSession?) {
        active.value = s
        if (s != null) {
            s.unread.value = 0
            prefs.edit().putString("active_tag", s.tag).apply()
        }
    }

    fun markActiveRead() { active.value?.unread?.value = 0 }

    private fun removeSession(s: NodeSession) {
        val rest = nodes.value - s
        nodes.value = rest
        if (active.value === s) select(rest.lastOrNull())
        persist(); refreshStatus()
        if (rest.isEmpty() && contacts.list.value.isEmpty()) SessionService.stop(getApplication())
    }

    private fun entry(r: RoomInfo) = JSONObject().put("code", r.code).put("pw", r.password)
        .put("name", r.name).put("created", r.created)

    private fun persist() {
        val arr = JSONArray()
        nodes.value.forEach { arr.put(entry(it.room.value)) }
        prefs.edit().putString("nodes", arr.toString()).apply()
    }

    private fun refreshStatus() {
        val list = nodes.value
        status.value = list.size to list.sumOf { it.peers.value }
    }

    /** Older versions saved a single room; carry it over as the first node. */
    private fun migrateOldSave() {
        if (prefs.getString("nodes", null) != null) return
        val code = prefs.getString("room_code", null)
        val pw = prefs.getString("room_pw", null)
        val name = prefs.getString("name", null)
        if (profileName.value == null && name != null) {
            prefs.edit().putString("profile_name", name).apply()
            profileName.value = name
        }
        if (code != null && pw != null && name != null) {
            val arr = JSONArray().put(entry(RoomInfo(code, pw, name, prefs.getBoolean("room_created", false))))
            prefs.edit().putString("nodes", arr.toString())
                .remove("room_code").remove("room_pw").remove("room_created").apply()
        }
    }

    /** You stay aboard every node until you leave it: restore them all on launch. */
    private fun restore() {
        val arr = runCatching { JSONArray(prefs.getString("nodes", "[]")) }.getOrDefault(JSONArray())
        val list = (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                RoomInfo(o.getString("code"), o.getString("pw"), o.getString("name"), o.optBoolean("created", false))
            }.getOrNull()
        }
        if (list.isEmpty()) return
        val sessions = list.map { makeSession(it) }
        nodes.value = sessions
        val lastTag = prefs.getString("active_tag", null)
        active.value = sessions.firstOrNull { it.tag == lastTag } ?: sessions.first()
        refreshStatus()
        sessions.forEach { s -> viewModelScope.launch { runCatching { s.start() } } }
        if (settings.value.keepAlive) SessionService.start(getApplication())
    }

    /** Should the background service be running? */
    fun wantsSession(): Boolean =
        nodes.value.isNotEmpty() || (prefs.getString("nodes", "[]") ?: "[]").length > 2 || contacts.list.value.isNotEmpty()

    // ---------- Settings ----------

    fun updateSettings(s: AppSettings) {
        val keepChanged = s.keepAlive != settings.value.keepAlive
        val netChanged = s.internet != settings.value.internet
        settings.value = s
        if (netChanged) (nodes.value + contacts.allSessions()).forEach { runCatching { it.applyInternet(s.internet) } }
        SettingsStore.save(prefs, s)
        if (keepChanged && nodes.value.isNotEmpty()) {
            if (s.keepAlive) SessionService.start(getApplication()) else SessionService.stop(getApplication())
        }
        if (!s.keepHistory) viewModelScope.launch(Dispatchers.IO) { store.deleteAll() }
    }

    fun eraseAllHistory() { viewModelScope.launch(Dispatchers.IO) { store.deleteAll(); vault.deleteAll() } }

    fun clearNotice() { notice.value = null }

    // ---------- Recovery phrase ----------

    /** My signed statement "my recovery key is X", re-signed whenever this install's identity changes. */
    private fun ensureCommitment() {
        val rc = prefs.getString("rec_pub", null) ?: return
        if (prefs.getString("rec_sig_id", null) == myId && prefs.getString("rec_sig", null) != null) return
        val sig = b64(Identity.sign(Succession.rccData(myId, rc)))
        prefs.edit().putString("rec_sig", sig).putString("rec_sig_id", myId).apply()
    }

    private fun commitment(): Pair<String, String>? {
        val rc = prefs.getString("rec_pub", null) ?: return null
        val s = prefs.getString("rec_sig", null) ?: return null
        return if (prefs.getString("rec_sig_id", null) == myId) rc to s else null
    }

    fun setRecovery(words: List<String>) {
        val rc = Recovery.publicHex(words)
        prefs.edit().putString("rec_pub", rc).remove("rec_sig").remove("rec_sig_id").apply()
        recoveryPub.value = rc
        ensureCommitment()
    }

    fun checkRecovery(words: List<String>): Boolean = Recovery.publicHex(words) == prefs.getString("rec_pub", null)

    // ---------- Succession: "this new ID is me" ----------

    private fun chain(): List<JSONObject> = runCatching {
        val a = JSONArray(prefs.getString("succ_chain", "[]"))
        (0 until a.length()).map { JSONObject(a.getString(it)) }
    }.getOrDefault(emptyList())

    private fun saveChain(list: List<JSONObject>) {
        prefs.edit().putString("succ_chain", JSONArray(list.takeLast(10).map { it.toString() }).toString()).apply()
    }

    private val succLock = Any()

    /** Check a certificate someone showed us and apply it in every node (once per node). */
    private fun applySuccession(c0: JSONObject) {
        val c0New = c0.optString("new")
        val candidates = ArrayList<JSONObject>()
        if (c0.optString("how") == Succession.PHRASE && c0.optString("old").isEmpty()) {
            // A recovery-phrase certificate names no old ID: find who committed to that recovery key
            val rc = c0.optString("rpk")
            people.idsWithRecovery(rc).filter { it != c0New }.forEach { id ->
                people.commitment(id)?.let { (pk, _, s) -> candidates.add(Succession.withCommitment(c0, id, pk, s)) }
            }
            commitment()?.let { (rc2, rcs) ->
                if (rc2 == rc && c0New != myId) candidates.add(Succession.withCommitment(c0, myId, b64(myPub), rcs))
            }
        } else candidates.add(c0)

        for (c in candidates) {
            val l = Succession.verify(c) ?: continue
            if (l.new == myId) continue
            val key = "${l.old}>${l.new}"
            if (l.old != myId && people.successors.value[l.old] != l.new) people.succeed(l.old, l.new)
            if (l.old != myId) contacts.onSuccession(l.old, l.new)
            (nodes.value + contacts.sessions.value.values).forEach { s ->
                val dk = "$key@${s.tag}"
                val fresh = synchronized(succLock) {
                    val done = prefs.getStringSet("succ_done", emptySet()) ?: emptySet()
                    if (dk in done) false else { prefs.edit().putStringSet("succ_done", done + dk).apply(); true }
                }
                if (fresh) runCatching { s.applySuccessor(l.old, l.new, l.revoke, c) }
            }
        }
    }

    // ---------- Backup, restore, move ----------

    private val globalKeys = listOf("profile_name", "nodes", "active_tag", "rec_pub", "succ_chain", "succ_done",
        Contacts.KEY_LIST, Contacts.KEY_REQ, Contacts.KEY_BLOCK) + PeopleBook.KEYS
    private val nodePrefixes = listOf("host_", "hostpin_", "hostcert_", "hostts_", "nick_", "mute_", "bans_", "excl_", "bundles_", "prev_", "s_", "dlv_")

    /** Everything worth carrying to a new phone. Never includes keys: this phone's keys stay on this phone. */
    private fun snapshot(includeHistory: Boolean): JSONObject {
        val p = JSONObject()
        prefs.all.forEach { (k, v) ->
            if (k in globalKeys || nodePrefixes.any { k.startsWith(it) }) {
                val e = when (v) {
                    is Boolean -> JSONObject().put("b", v)
                    is Int -> JSONObject().put("i", v)
                    is Long -> JSONObject().put("l", v)
                    is String -> JSONObject().put("s", v)
                    is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
                    else -> null
                }
                if (e != null) p.put(k, e)
            }
        }
        val o = JSONObject().put("v", 1).put("oid", myId).put("opk", b64(myPub)).put("prefs", p)
            .put("at", System.currentTimeMillis())
        if (includeHistory) {
            val h = JSONObject()
            (nodes.value + contacts.sessions.value.values).forEach { s -> store.readRaw(s.tag)?.let { h.put(s.tag, it) } }
            o.put("history", h)
        }
        return o
    }

    /** Fresh install only: take on everything from a snapshot, then link to every node. */
    private fun importSnapshot(o: JSONObject, cert: JSONObject?) {
        val oid = o.optString("oid")
        val p = o.getJSONObject("prefs")
        val ed = prefs.edit()
        p.keys().forEach { k ->
            val e = p.getJSONObject(k)
            when {
                e.has("b") -> ed.putBoolean(k, e.getBoolean("b"))
                e.has("i") -> ed.putInt(k, e.getInt("i"))
                e.has("l") -> ed.putLong(k, e.getLong("l"))
                e.has("s") -> ed.putString(k, if (k.startsWith("host_") && e.getString("s") == oid) myId else e.getString("s"))
                e.has("set") -> { val a = e.getJSONArray("set"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        ed.remove("rec_sig").remove("rec_sig_id").commit()
        // Saved logs: your old ID's messages become yours
        o.optJSONObject("history")?.let { h ->
            h.keys().forEach { t ->
                runCatching {
                    val arr = JSONArray(h.getString(t))
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        if (m.optString("sid") == oid) m.put("sid", myId)
                    }
                    store.writeRaw(t, arr.toString())
                }
            }
        }
        if (cert != null) saveChain(chain() + cert)
        people.reload()
        if (oid.length == 32 && oid != myId) people.succeed(oid, myId)
        settings.value = SettingsStore.load(prefs)
        recoveryPub.value = prefs.getString("rec_pub", null)
        ensureCommitment()
        profileName.value = prefs.getString("profile_name", null)
        restore()
        contacts.restore()
        ensureService()
    }

    /** An encrypted backup file's bytes. The file can also vouch for your next phone (see Succession). */
    suspend fun exportBackup(pass: String, includeHistory: Boolean): ByteArray = withContext(Dispatchers.Default) {
        (nodes.value + contacts.sessions.value.values).forEach { runCatching { it.saveNow() } }
        val (bsk, bpk) = Schnorr.newKey()
        val dts = System.currentTimeMillis()
        val dsig = Identity.sign(Succession.delegationData(myId, Schnorr.hex(bpk), dts))
        val o = snapshot(includeHistory)
            .put("bak", JSONObject().put("bsk", Schnorr.hex(bsk)).put("dts", dts).put("dsig", b64(dsig)))
        BackupFile.seal(o.toString(), pass)
    }

    fun markBackedUp() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_backup", now).apply()
        lastBackup.value = now
    }

    /** Returns an error message, or null on success. */
    suspend fun restoreBackup(bytes: ByteArray, pass: String, revokeOld: Boolean): String? = withContext(Dispatchers.Default) {
        if (profileName.value != null || nodes.value.isNotEmpty()) return@withContext "Restoring is only possible on a fresh install."
        val txt = try { BackupFile.open(bytes, pass) } catch (e: BackupFile.WrongPassphrase) {
            return@withContext "Wrong backup passphrase."
        } catch (e: Exception) { null } ?: return@withContext "That file isn't a CyberTxTs backup."
        try {
            val o = JSONObject(txt)
            val oid = o.getString("oid")
            val opk = unb64(o.getString("opk")) ?: return@withContext "This backup is damaged."
            val bak = o.getJSONObject("bak")
            val cert = if (oid == myId) null else {
                val c = Succession.backup(oid, opk, Schnorr.unhex(bak.getString("bsk")) ?: ByteArray(0), bak.getLong("dts"),
                    unb64(bak.getString("dsig")) ?: ByteArray(0), myId, myPub, revokeOld)
                Succession.verify(c) ?: return@withContext "This backup is damaged."
                c
            }
            withContext(Dispatchers.Main) { importSnapshot(o, cert) }
            null
        } catch (e: Exception) { "This backup is damaged." }
    }

    /** Fresh install, old phone gone: the recovery phrase vouches for this phone. */
    fun recoverWithPhrase(words: List<String>, callsign: String, revokeOld: Boolean) {
        val cert = Succession.phrase(words, myId, myPub, revokeOld)
        prefs.edit().putString("rec_pub", Recovery.publicHex(words)).apply()
        recoveryPub.value = prefs.getString("rec_pub", null)
        ensureCommitment()
        saveChain(chain() + cert)
        setProfileName(callsign)
    }

    sealed class MoveState {
        object Idle : MoveState()
        class Waiting(val link: String) : MoveState()
        object Receiving : MoveState()
        object Done : MoveState()
        class Failed(val why: String) : MoveState()
    }
    val moveState = MutableStateFlow<MoveState>(MoveState.Idle)
    private var moveJob: Job? = null
    @Volatile private var moveServer: ServerSocket? = null
    private val moveAad = "cybertxts-move".toByteArray()

    /** NEW phone: wait for the old phone (shows a QR with a one-time secret). */
    fun startMoveReceive() {
        cancelMove()
        moveJob = viewModelScope.launch(Dispatchers.IO) {
            val host = LanTransport.localIPv4().firstOrNull()
            if (host == null) {
                moveState.value = MoveState.Failed("Connect this phone to WiFi first (the same WiFi as your old phone), or turn on its hotspot and join it from the old phone.")
                return@launch
            }
            val ss = try { ServerSocket(0) } catch (e: Exception) { moveState.value = MoveState.Failed("Couldn't open a connection on this phone."); return@launch }
            moveServer = ss
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            moveState.value = MoveState.Waiting(MoveLink.build(MoveOffer(host, ss.localPort, secret, myId, myPub)))
            try {
                ss.soTimeout = 20 * 60_000
                ss.accept().use { s ->
                    moveState.value = MoveState.Receiving
                    s.soTimeout = 120_000
                    val input = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream())
                    val n = input.readInt()
                    require(n in 1..MAX_MOVE)
                    val buf = ByteArray(n).also { input.readFully(it) }
                    val key = SecretKeySpec(secret, "AES")
                    val plain = Crypto.decrypt(key, buf, moveAad) ?: throw IllegalStateException("bad secret")
                    val o = JSONObject(gunzip(plain).toString(Charsets.UTF_8))
                    val cert = o.getJSONObject("cert")
                    val l = Succession.verify(cert)
                    require(l != null && l.new == myId && l.old == o.getString("oid"))
                    withContext(Dispatchers.Main) { importSnapshot(o, cert) }
                    val ok = Crypto.encrypt(key, "OK".toByteArray(), moveAad)
                    out.writeInt(ok.size); out.write(ok); out.flush()
                    moveState.value = MoveState.Done
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (moveState.value !is MoveState.Idle) moveState.value = MoveState.Failed("The move didn't complete. Keep both phones on the same WiFi and try again.")
            } finally {
                runCatching { ss.close() }
                moveServer = null
            }
        }
    }

    fun cancelMove() {
        moveJob?.cancel(); moveJob = null
        runCatching { moveServer?.close() }; moveServer = null
        moveState.value = MoveState.Idle
    }

    /** OLD phone: send everything to the new phone, signed over to its new ID. Error message, or null on success. */
    suspend fun sendMove(offer: MoveOffer, includeHistory: Boolean): String? = withContext(Dispatchers.IO) {
        if (offer.id == myId) return@withContext "That's this phone's own code."
        try {
            (nodes.value + contacts.sessions.value.values).forEach { runCatching { it.saveNow() } }
            val cert = Succession.device(myId, myPub, offer.id, offer.pub, false) { Identity.sign(it) }
            val o = snapshot(includeHistory).put("cert", cert)
            val key = SecretKeySpec(offer.secret, "AES")
            val enc = Crypto.encrypt(key, gzip(o.toString().toByteArray()), moveAad)
            if (enc.size > MAX_MOVE) return@withContext "Too much to move at once. Try again without the message log."
            Socket().use { s ->
                s.connect(InetSocketAddress(offer.host, offer.port), 8_000)
                s.soTimeout = 120_000
                val out = DataOutputStream(s.getOutputStream().buffered())
                out.writeInt(enc.size); out.write(enc); out.flush()
                val input = DataInputStream(s.getInputStream())
                val n = input.readInt()
                require(n in 1..1000)
                val reply = Crypto.decrypt(key, ByteArray(n).also { input.readFully(it) }, moveAad)
                if (reply?.toString(Charsets.UTF_8) != "OK") return@withContext "The new phone didn't accept the move."
            }
            null
        } catch (e: Exception) {
            "Couldn't reach the new phone. Both phones need to be on the same WiFi (or the old phone on the new phone's hotspot), with the QR code still showing."
        }
    }

    /** Remove everything from this phone: nodes, logs, media, contacts, settings and its identity key. */
    fun wipeEverything(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            cancelMove()
            val list = nodes.value
            nodes.value = emptyList(); active.value = null
            list.forEach { runCatching { it.close() } }
            contacts.stopAll()
            SessionService.stop(getApplication())
            runCatching { store.deleteAll() }; runCatching { vault.deleteAll() }
            prefs.edit().clear().commit()
            getApplication<Application>().cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            Identity.destroy()
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private companion object { const val MAX_MOVE = 150_000_000 }

    override fun onCleared() {
        nodes.value.forEach { runCatching { it.close() } }
        contacts.stopAll()
    }
}
