package com.cybertxts.app

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64

/*
 * Contacts: direct 1-to-1 links that work without a shared node.
 *
 * A direct link is a private two-person node whose code and access key come from a random 32-byte secret
 * that only the two of you hold. It reuses everything a node does (WiFi + internet linking, encryption,
 * photos, VOX, receipts, typing, reactions), plus an outbox: texts wait on your phone until they're delivered.
 *
 * How the secret gets agreed:
 *   from a node   you send a contact request inside a node you share, sealed to their key; they accept.
 *   QR / link     your code holds a one-time "offer" secret. Whoever scans it meets you in a short-lived offer
 *                 node and sends you a request (with a brand-new secret for the real link, so a leaked QR or
 *                 link can't be used to read your chats). Scanned in person while your code is on screen, it's
 *                 accepted automatically; through a link, you accept it like any request.
 */

data class Contact(
    val id: String, val secret: String, val since: Long, val verified: Boolean = false,
    val verifiedAt: Long = 0L, val note: String = "", val state: String = STATE_ACTIVE
) {
    companion object {
        const val STATE_ACTIVE = "active"
        const val STATE_SENT = "sent"     // we asked; waiting for them to accept and link
    }
}

data class ContactRequest(val from: String, val name: String, val secret: String, val via: String, val ts: Long)

/** What a contact QR code or link carries. kind "q" = QR shown in person, "l" = shared link. */
data class ContactOffer(val id: String, val name: String, val secret: ByteArray, val kind: String, val expires: Long)

object ContactLink {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun build(o: ContactOffer): String =
        "cybertxts://contact?id=${o.id}&n=${java.net.URLEncoder.encode(o.name, "UTF-8")}&s=${enc.encodeToString(o.secret)}&k=${o.kind}&x=${o.expires}"

    fun parse(text: String?): ContactOffer? = try {
        val raw = Regex("cybertxts://contact\\?\\S+").find(text ?: "")?.value ?: throw IllegalArgumentException()
        val q = URI(raw).rawQuery.split("&").associate { p ->
            val i = p.indexOf('='); p.substring(0, i) to URLDecoder.decode(p.substring(i + 1), "UTF-8")
        }
        val id = q.getValue("id"); val s = dec.decode(q.getValue("s")); val k = q.getValue("k")
        require(Regex("^[0-9a-f]{32}$").matches(id) && s.size == 32 && (k == "q" || k == "l"))
        ContactOffer(id, (q["n"] ?: "").trim().take(24), s, k, q["x"]?.toLongOrNull() ?: 0L)
    } catch (e: Exception) { null }

    /** Node code and access key for a secret. Offers and real links use different prefixes. */
    fun codeFor(secret: ByteArray, offer: Boolean) = (if (offer) "CO-" else "DL-") + Crypto.sha256Hex(secret).take(20).uppercase()
    fun keyFor(secret: ByteArray): String = Base64.getEncoder().encodeToString(secret)
}

class Contacts(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val people: PeopleBook,
    private val myId: String,
    private val myName: () -> String?,
    /** Build (not start) a session for a direct link (peer given) or an offer node (peer null). */
    private val makeSession: (code: String, key: String, peer: (() -> String)?, created: Boolean) -> NodeSession,
    private val onNotice: (String) -> Unit,
    private val onAny: () -> Unit                    // something changed: the app refreshes the background service
) {
    val list = MutableStateFlow<List<Contact>>(emptyList())
    val requests = MutableStateFlow<List<ContactRequest>>(emptyList())
    val blocked = MutableStateFlow<Set<String>>(emptySet())
    /** Direct-link session per contact ID. */
    val sessions = MutableStateFlow<Map<String, NodeSession>>(emptyMap())

    private val rng = SecureRandom()
    private val peerRefs = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicReference<String>>()
    private val offerSessions = HashMap<String, NodeSession>()          // tag -> owner offer node
    private val linkOffers = HashMap<String, Long>()                     // offer tag -> expiry
    @Volatile private var qrTag: String? = null                          // the QR offer currently on screen
    private var qrStop: Job? = null

    // ---------------------------------------------------------------- storage

    private fun save() {
        val a = JSONArray()
        list.value.forEach { c ->
            a.put(JSONObject().put("id", c.id).put("s", c.secret).put("since", c.since).put("v", c.verified)
                .put("va", c.verifiedAt).put("note", c.note).put("st", c.state))
        }
        val r = JSONArray()
        requests.value.forEach { q -> r.put(JSONObject().put("from", q.from).put("n", q.name).put("s", q.secret).put("via", q.via).put("ts", q.ts)) }
        prefs.edit().putString(KEY_LIST, a.toString()).putString(KEY_REQ, r.toString())
            .putStringSet(KEY_BLOCK, blocked.value).apply()
        onAny()
    }

    /** Load contacts and start every direct link (call once at launch, and after restoring a backup). */
    fun restore() {
        list.value = runCatching {
            val a = JSONArray(prefs.getString(KEY_LIST, "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                Contact(o.getString("id"), o.getString("s"), o.optLong("since"), o.optBoolean("v"), o.optLong("va"),
                    o.optString("note"), o.optString("st", Contact.STATE_ACTIVE))
            }
        }.getOrDefault(emptyList())
        requests.value = runCatching {
            val a = JSONArray(prefs.getString(KEY_REQ, "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                ContactRequest(o.getString("from"), o.optString("n"), o.getString("s"), o.optString("via"), o.optLong("ts"))
            }
        }.getOrDefault(emptyList())
        blocked.value = prefs.getStringSet(KEY_BLOCK, emptySet()) ?: emptySet()
        list.value.forEach { startDirect(it) }
        // Shared contact links stay open for a week
        runCatching {
            val a = JSONArray(prefs.getString(KEY_OFFERS, "[]"))
            val now = System.currentTimeMillis()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.getLong("x") > now) Base64.getDecoder().decode(o.getString("s"))?.let { startOffer(it, o.getLong("x")) }
            }
        }
    }

    fun contact(id: String): Contact? = list.value.firstOrNull { it.id == id }
    fun isContact(id: String) = list.value.any { it.id == id || it.id in people.lineage(id) }
    fun session(id: String): NodeSession? = sessions.value[id]
    fun allSessions(): List<NodeSession> = sessions.value.values.toList() + synchronized(offerSessions) { offerSessions.values.toList() }

    private fun update(id: String, f: (Contact) -> Contact) {
        list.update { l -> l.map { if (it.id == id) f(it) else it } }
        save()
    }

    // ---------------------------------------------------------------- direct links

    private fun startDirect(c: Contact) {
        if (sessions.value.containsKey(c.id)) return
        val secret = Base64.getDecoder().decode(c.secret)
        val ref = java.util.concurrent.atomic.AtomicReference(c.id)      // follows the contact if they move phones
        peerRefs[c.id] = ref
        val s = makeSession(ContactLink.codeFor(secret, false), ContactLink.keyFor(secret), { ref.get() }, false)
        sessions.update { it + (c.id to s) }
        scope.launch { runCatching { s.start() } }
    }

    private fun stopDirect(id: String, erase: Boolean) {
        val s = sessions.value[id] ?: return
        sessions.update { it - id }
        peerRefs.remove(id)
        runCatching { s.leave(erase) }
    }

    /** A direct session saw who's linked: our first link with someone we asked means they accepted. */
    fun onDirectPeers(session: NodeSession, ids: Set<String>) {
        val id = sessions.value.entries.firstOrNull { it.value === session }?.key ?: return
        val c = contact(id) ?: return
        if (c.state == Contact.STATE_SENT && ids.any { it == id || it in people.lineage(id) }) update(id) { it.copy(state = Contact.STATE_ACTIVE) }
    }

    // ---------------------------------------------------------------- adding

    private fun newSecret() = ByteArray(32).also { rng.nextBytes(it) }

    private fun addContact(id: String, secret: ByteArray, state: String, verified: Boolean) {
        val now = System.currentTimeMillis()
        val c = Contact(id, ContactLink.keyFor(secret), now, verified, if (verified) now else 0L, "", state)
        list.update { l -> l.filterNot { it.id == id } + c }
        requests.update { r -> r.filterNot { it.from == id } }
        save()
        startDirect(c)
    }

    /** Ask someone in a node you share. False if they can't be reached right now. */
    fun requestViaNode(node: NodeSession, id: String): Boolean {
        if (id == myId || isContact(id)) return false
        if (!node.canDm(id)) return false
        val secret = newSecret()
        if (!node.sendContactRequest(id, secret, "node")) return false
        addContact(id, secret, Contact.STATE_SENT, false)
        return true
    }

    /**
     * You scanned someone's code or opened their link. Returns a message to show, or null when the
     * request is on its way.
     */
    fun addFromOffer(o: ContactOffer): String? {
        if (o.id == myId) return "That's your own contact code."
        val existing = contact(o.id)
        if (existing != null) {
            if (o.kind == "q" && !existing.verified) {
                update(o.id) { it.copy(verified = true, verifiedAt = System.currentTimeMillis()) }
                return "Fingerprint checked. ${people.label(o.id, o.name)} is now verified."
            }
            return "${people.label(o.id, o.name)} is already a contact."
        }
        if (o.kind == "l" && o.expires in 1 until System.currentTimeMillis()) return "That contact link has expired. Ask for a new one."
        if (o.name.isNotEmpty()) people.seen(o.id, o.name)
        val real = newSecret()
        addContact(o.id, real, Contact.STATE_SENT, o.kind == "q")
        // Meet them in their offer node and hand over the request there
        val off = makeSession(ContactLink.codeFor(o.secret, true), ContactLink.keyFor(o.secret), null, false)
        scope.launch(Dispatchers.Default) {
            runCatching { off.start() }
            val until = System.currentTimeMillis() + 15 * 60_000L
            var sent = false
            while (System.currentTimeMillis() < until && !sent) {
                if (off.canDm(o.id)) sent = off.sendContactRequest(o.id, real, if (o.kind == "q") "qr" else "link")
                delay(1_500)
            }
            if (sent) delay(20_000)        // let it arrive (and be relayed) before closing
            else onNotice("Couldn't reach ${people.label(o.id, o.name)} to send your request. Their app needs to be open; try again later.")
            runCatching { off.leave(true) }
        }
        return null
    }

    /** A contact request arrived (from any node or offer node). */
    fun onRequest(source: NodeSession, from: String, name: String, secret: ByteArray, via: String) {
        if (from == myId || from in blocked.value) return
        val existing = contact(from)
        if (existing != null) {
            // We both asked at once: both phones keep the same (smaller) secret so they meet in one link
            if (existing.state == Contact.STATE_SENT) {
                val theirs = ContactLink.keyFor(secret)
                val keep = minOf(theirs, existing.secret)
                if (keep != existing.secret) { stopDirect(from, false); update(from) { it.copy(secret = keep, state = Contact.STATE_ACTIVE) }; contact(from)?.let { startDirect(it) } }
                else update(from) { it.copy(state = Contact.STATE_ACTIVE) }
            }
            return
        }
        val inPerson = via == "qr" && qrTag != null && source.tag == qrTag
        if (inPerson) {
            addContact(from, secret, Contact.STATE_ACTIVE, false)
            onNotice("${people.label(from, name)} added you in person. Scan their code too to verify them.")
            return
        }
        if (via == "link") consumeLink(source.tag)
        requests.update { r -> r.filterNot { it.from == from } + ContactRequest(from, name, ContactLink.keyFor(secret), via, System.currentTimeMillis()) }
        save()
    }

    fun accept(r: ContactRequest) {
        addContact(r.from, Base64.getDecoder().decode(r.secret), Contact.STATE_ACTIVE, false)
    }

    fun decline(r: ContactRequest) {
        requests.update { l -> l.filterNot { it.from == r.from } }
        save()
    }

    // ---------------------------------------------------------------- offers (your QR and shared links)

    private fun startOffer(secret: ByteArray, expires: Long?): NodeSession {
        val s = makeSession(ContactLink.codeFor(secret, true), ContactLink.keyFor(secret), null, true)
        synchronized(offerSessions) { offerSessions[s.tag] = s }
        if (expires != null) linkOffers[s.tag] = expires
        scope.launch { runCatching { s.start() } }
        return s
    }

    private fun stopOffer(tag: String) {
        val s = synchronized(offerSessions) { offerSessions.remove(tag) } ?: return
        runCatching { s.leave(true) }
    }

    /** Your QR is on screen: an in-person scan is accepted automatically while it stays open. */
    fun openQr(): ContactOffer {
        qrStop?.cancel()
        val secret = newSecret()
        val s = startOffer(secret, null)
        qrTag = s.tag
        return ContactOffer(myId, myName() ?: "", secret, "q", 0L)
    }

    /** QR closed: stop auto-accepting; keep listening a few minutes for anyone still connecting. */
    fun closeQr() {
        val t = qrTag ?: return
        qrTag = null
        qrStop = scope.launch { delay(10 * 60_000L); if (!linkOffers.containsKey(t)) stopOffer(t) }
    }

    /** A link to share in any app. Works once, for 7 days, while your app can be reached. */
    fun createLink(): String {
        val secret = newSecret()
        val x = System.currentTimeMillis() + 7 * 86_400_000L
        startOffer(secret, x)
        val a = runCatching { JSONArray(prefs.getString(KEY_OFFERS, "[]")) }.getOrDefault(JSONArray())
        val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optLong("x") > System.currentTimeMillis()) keep.put(a.getJSONObject(i))
        keep.put(JSONObject().put("s", Base64.getEncoder().encodeToString(secret)).put("x", x))
        while (keep.length() > 5) {
            val old = keep.getJSONObject(0)
            runCatching { stopOffer(Crypto.roomTag(ContactLink.codeFor(Base64.getDecoder().decode(old.getString("s")), true))) }
            keep.remove(0)
        }
        prefs.edit().putString(KEY_OFFERS, keep.toString()).apply()
        return ContactLink.build(ContactOffer(myId, myName() ?: "", secret, "l", x))
    }

    private fun consumeLink(tag: String) {
        if (!linkOffers.containsKey(tag)) return
        linkOffers.remove(tag)
        val a = runCatching { JSONArray(prefs.getString(KEY_OFFERS, "[]")) }.getOrDefault(JSONArray())
        val keep = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val t = Crypto.roomTag(ContactLink.codeFor(Base64.getDecoder().decode(o.getString("s")), true))
            if (t != tag) keep.put(o)
        }
        prefs.edit().putString(KEY_OFFERS, keep.toString()).apply()
        scope.launch { delay(30_000); stopOffer(tag) }
    }

    // ---------------------------------------------------------------- managing

    fun setNote(id: String, note: String) = update(id) { it.copy(note = note.take(500)) }

    fun remove(id: String) {
        stopDirect(id, true)
        list.update { l -> l.filterNot { it.id == id } }
        save()
    }

    fun block(id: String) {
        remove(id)
        blocked.update { it + id }
        requests.update { l -> l.filterNot { it.from == id } }
        save()
    }

    fun unblock(id: String) { blocked.update { it - id }; save() }

    /** They moved to a new phone (checked certificate): the contact follows them. */
    fun onSuccession(old: String, new: String) {
        if (contact(old) == null || contact(new) != null) return
        sessions.value[old]?.let { s -> sessions.update { (it - old) + (new to s) } }
        peerRefs.remove(old)?.let { r -> r.set(new); peerRefs[new] = r }
        update(old) { it.copy(id = new) }
    }

    fun isOfferSession(s: NodeSession) = synchronized(offerSessions) { offerSessions.values.any { it === s } }

    fun stopAll() {
        sessions.value.values.forEach { runCatching { it.close() } }
        synchronized(offerSessions) { offerSessions.values.forEach { runCatching { it.close() } }; offerSessions.clear() }
        sessions.value = emptyMap()
    }

    companion object {
        const val KEY_LIST = "contacts"
        const val KEY_REQ = "creqs"
        const val KEY_BLOCK = "cblocked"
        const val KEY_OFFERS = "coffers"
    }
}
