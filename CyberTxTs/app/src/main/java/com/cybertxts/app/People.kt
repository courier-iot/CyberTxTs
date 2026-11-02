package com.cybertxts.app

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Everyone you've met, by their permanent unique ID (the same across every node).
 *
 * - Aliases are permanent: the first alias seen for an ID is the one shown from then on, so nobody can
 *   pass themselves off as someone else by changing the name their app sends.
 * - Nicknames are private: a name YOU give someone. Only your phone ever sees it.
 * - Look-alikes: a NEW ID using the same callsign as someone you already know is flagged, unless it is
 *   their verified successor (they moved phones) or you've said you know them.
 * - Recovery commitments: each operative's signed statement of their recovery key, kept so their
 *   recovery phrase can later prove a new phone is really them.
 */
class PeopleBook(private val prefs: SharedPreferences) {
    val nicknames = MutableStateFlow(load(KEY_NICK))
    val aliases = MutableStateFlow(load(KEY_ALIAS))
    private var firstSeen: Map<String, String> = load(KEY_FIRST)          // id -> first seen (ms, as text)
    val successors = MutableStateFlow(load(KEY_SUCC))                      // old id -> new id
    val vouched = MutableStateFlow(load(KEY_VOUCH).keys)                   // look-alikes you said you know
    private var commitments: Map<String, String> = load(KEY_RCC)           // id -> {"pk","rc","s"}

    private fun load(key: String): Map<String, String> = try {
        val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    private fun save(key: String, map: Map<String, String>) {
        prefs.edit().putString(key, JSONObject(map).toString()).apply()
    }

    /** Re-read everything (after restoring a backup or moving phones). */
    fun reload() {
        nicknames.value = load(KEY_NICK); aliases.value = load(KEY_ALIAS); firstSeen = load(KEY_FIRST)
        successors.value = load(KEY_SUCC); vouched.value = load(KEY_VOUCH).keys; commitments = load(KEY_RCC)
    }

    /** Record a verified alias. Only the first one ever seen for this ID sticks. */
    fun seen(id: String, alias: String) {
        val a = alias.trim().take(24)
        if (a.isEmpty() || aliases.value.containsKey(id)) return
        if (aliases.value.size >= MAX_PEOPLE) return
        aliases.update { it + (id to a) }
        save(KEY_ALIAS, aliases.value)
        if (!firstSeen.containsKey(id)) {
            firstSeen = firstSeen + (id to System.currentTimeMillis().toString())
            save(KEY_FIRST, firstSeen)
        }
    }

    fun alias(id: String, fallback: String): String = aliases.value[id] ?: fallback

    /** When this phone first met [id] (in any node), or null if never. */
    fun firstSeenAt(id: String): Long? =
        firstSeen[id]?.toLongOrNull() ?: if (aliases.value.containsKey(id)) 0L else null   // met before this was recorded

    fun nickname(id: String): String? = nicknames.value[id]?.takeIf { it.isNotBlank() }

    /** What to show for a person: your nickname if you set one, otherwise their permanent alias. */
    fun label(id: String, fallback: String): String = nickname(id) ?: alias(id, fallback)

    fun setNickname(id: String, nick: String) {
        val n = nick.trim().take(24)
        nicknames.update { if (n.isEmpty()) it - id else it + (id to n) }
        save(KEY_NICK, nicknames.value)
    }

    // ---- look-alikes ----

    /** Every ID linked to [id] by moves (earlier and later phones of the same person). */
    fun lineage(id: String): Set<String> {
        val s = successors.value
        val out = HashSet<String>().apply { add(id) }
        var grew = true
        while (grew) {
            grew = false
            s.forEach { (o, n) -> if ((o in out) != (n in out)) { out.add(o); out.add(n); grew = true } }
        }
        return out
    }

    /**
     * If [id] uses the same callsign as someone you met earlier (and isn't them on a new phone),
     * returns that earlier person's ID. Used to show "NOT THE VEGA YOU KNOW".
     */
    fun lookalikeOf(id: String): String? {
        if (id in vouched.value) return null
        val a = aliases.value[id]?.trim()?.lowercase() ?: return null
        val mine = firstSeen[id]?.toLongOrNull() ?: Long.MAX_VALUE
        val family = lineage(id)
        return aliases.value.entries.firstOrNull { (other, al) ->
            other !in family && al.trim().lowercase() == a && (firstSeen[other]?.toLongOrNull() ?: 0L) < mine
        }?.key
    }

    fun vouch(id: String) {
        vouched.update { it + id }
        save(KEY_VOUCH, vouched.value.associateWith { "1" })
    }

    // ---- recovery commitments ----

    /** Keep the first recovery commitment an ID ever showed us (already signature-checked by the caller). */
    fun pinRecovery(id: String, pkB64: String, rc: String, sig: String) {
        if (commitments.containsKey(id) || commitments.size >= MAX_PEOPLE) return
        commitments = commitments + (id to JSONObject().put("pk", pkB64).put("rc", rc).put("s", sig).toString())
        save(KEY_RCC, commitments)
    }

    /** (public key, signature) of [id]'s recovery commitment, if we have it. */
    fun commitment(id: String): Triple<String, String, String>? = commitments[id]?.let {
        runCatching { JSONObject(it).let { o -> Triple(o.getString("pk"), o.getString("rc"), o.getString("s")) } }.getOrNull()
    }

    /** IDs whose recovery key is [rc] (the earlier phones of whoever holds that phrase). */
    fun idsWithRecovery(rc: String): List<String> = commitments.keys.filter { commitment(it)?.second == rc }

    // ---- moves ----

    /**
     * [old] moved to [new]: your nickname for them and their callsign carry over, and the new ID is never
     * flagged as a look-alike of the old one.
     */
    fun succeed(old: String, new: String) {
        successors.update { it + (old to new) }
        save(KEY_SUCC, successors.value)
        nickname(old)?.let { n -> if (nickname(new) == null) setNickname(new, n) }
        aliases.value[old]?.let { a ->
            aliases.update { it + (new to a) }
            save(KEY_ALIAS, aliases.value)
        }
        val t = firstSeen[old] ?: System.currentTimeMillis().toString()
        firstSeen = firstSeen + (new to t)
        save(KEY_FIRST, firstSeen)
    }

    companion object {
        private const val KEY_NICK = "people_nick"
        private const val KEY_ALIAS = "people_alias"
        private const val KEY_FIRST = "people_first"
        private const val KEY_SUCC = "people_succ"
        private const val KEY_VOUCH = "people_vouch"
        private const val KEY_RCC = "people_rcc"
        private const val MAX_PEOPLE = 5000
        /** Preference keys a backup or move carries over. */
        val KEYS = listOf(KEY_NICK, KEY_ALIAS, KEY_FIRST, KEY_SUCC, KEY_VOUCH, KEY_RCC)
    }
}
