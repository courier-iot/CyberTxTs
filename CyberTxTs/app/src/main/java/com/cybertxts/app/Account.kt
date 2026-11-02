package com.cybertxts.app

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Keeping your identity when your phone changes.
 *
 * Your identity key lives in the phone's secure chip and can never be copied out. So instead of copying it,
 * a new phone gets its own key, and something only the real you could produce vouches for it:
 *   dev  your OLD phone signs "this new ID is me"                    (Move to a new phone)
 *   bak  a key sealed inside your encrypted backup file signs it     (Restore a backup)
 *   phr  the key behind your recovery phrase signs it                (Recover with phrase, old phone lost)
 * That statement is a "succession certificate". Other phones check it and carry over the nickname they gave
 * you, your private channels and your host role. A certificate can also revoke the old ID: every node then
 * removes it and rotates its key, so a lost or stolen phone is locked out.
 *
 * Everything in this file is plain Java/Kotlin (no Android classes), so it can be tested off the phone.
 */

private val b64e = Base64.getEncoder()
private val b64d = Base64.getDecoder()
internal fun b64(b: ByteArray): String = b64e.encodeToString(b)
internal fun unb64(s: String): ByteArray? = try { b64d.decode(s) } catch (e: Exception) { null }
private val b64u = Base64.getUrlEncoder().withoutPadding()
private val unb64u = Base64.getUrlDecoder()

/** Same rule as Identity.idFromPublicKey: the ID is the first 32 hex characters of the key's SHA-256. */
internal fun idOf(pub: ByteArray): String = Crypto.sha256Hex(pub).take(32)

internal fun ecVerify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
    val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
    Signature.getInstance("SHA256withECDSA").run { initVerify(pk); update(data); verify(sig) }
} catch (e: Exception) { false }

/** Groups an ID for reading aloud and comparing: 7F3A 91C2 0B77 ... */
fun fingerprint(id: String): String = id.uppercase().chunked(4).joinToString(" ")

// ---------------------------------------------------------------- Recovery phrase

/**
 * 16 words: 15 random (120 bits) plus one check word that catches typos.
 * Every word has a unique first four letters, so typing four letters of each word is enough.
 * The phrase is shown once and never stored; the phone keeps only its public key.
 */
object Recovery {
    val WORDS: List<String> = listOf(
        "acid", "aero", "alloy", "alpha", "amber", "anchor", "angle", "apex", "arc", "argon", "armor", "array",
        "arrow", "astro", "atlas", "atom", "aurora", "axis", "badge", "band", "baron", "basalt", "beacon", "beam",
        "berth", "binary", "bionic", "blade", "blaze", "blink", "bloom", "bolt", "bond", "boost", "breach", "bridge",
        "bronze", "buffer", "cable", "cadet", "canyon", "carbon", "cargo", "castle", "cell", "cinder", "cipher",
        "circuit", "citadel", "clamp", "cloak", "cobalt", "code", "comet", "compass", "condor", "copper", "coral",
        "core", "cosmos", "crater", "crest", "crown", "crystal", "cube", "current", "cyan", "cycle", "dagger",
        "dawn", "delta", "depot", "dial", "diode", "docker", "dome", "draco", "drift", "drone", "dune", "dusk",
        "dynamo", "echo", "eclipse", "edge", "ember", "engine", "epoch", "equator", "falcon", "fathom", "ferro",
        "fiber", "field", "flare", "flash", "flint", "flux", "focus", "forge", "fractal", "frame", "frost", "fuse",
        "galaxy", "gamma", "garnet", "gate", "gauge", "gecko", "geyser", "ghost", "glacier", "glass", "glide",
        "glyph", "gold", "granite", "graph", "gravity", "grid", "halo", "harbor", "hawk", "helix", "helm", "hermit",
        "hex", "horizon", "hull", "hydra", "icon", "igloo", "index", "indigo", "inertia", "ion", "iris", "iron",
        "jade", "jet", "jolt", "joule", "jungle", "kayak", "kelvin", "kernel", "kestrel", "keystone", "kilo",
        "kinetic", "knight", "krypton", "lagoon", "lance", "laser", "lattice", "launch", "lava", "lens", "lever",
        "light", "lumen", "lunar", "lynx", "magma", "magnet", "manta", "marble", "mars", "matrix", "meridian",
        "mesa", "meteor", "micro", "mirage", "module", "monolith", "moon", "mosaic", "motor", "nadir", "nebula",
        "neon", "nexus", "nickel", "nimbus", "noble", "nomad", "nova", "nucleus", "oasis", "obsidian", "ocean",
        "octane", "onyx", "opal", "optic", "oracle", "orbit", "orchid", "origin", "osprey", "oxide", "ozone",
        "panel", "pebble", "pegasus", "phantom", "phase", "photon", "pilot", "pixel", "plasma", "polar", "portal",
        "prism", "probe", "proton", "pulsar", "pylon", "quantum", "quartz", "quasar", "radar", "radius", "raven",
        "reactor", "relay", "ridge", "rocket", "rotor", "ruby", "rune", "saber", "safari", "salvo", "satellite",
        "scanner", "scout", "sector", "shadow", "shard", "shield", "signal", "silicon", "silver", "sirius", "sonar",
        "spark", "specter", "sphere", "spiral", "titan", "vortex", "zenith"
    )
    private const val LABEL = "cybertxts-recovery-v1"
    private val index: Map<String, Int> = WORDS.withIndex().associate { it.value.take(4) to it.index }

    fun newPhrase(): List<String> {
        val seed = ByteArray(15).also { SecureRandom().nextBytes(it) }
        return seed.map { WORDS[it.toInt() and 0xff] } + WORDS[check(seed)]
    }

    private fun check(seed: ByteArray): Int = Crypto.sha256Hex(seed).take(2).toInt(16)

    /** Reads typed words (any case, any spacing, 4+ letters each). Null if they aren't a valid phrase. */
    fun parse(text: String): List<String>? {
        val parts = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (parts.size != 16) return null
        val idx = parts.map { p -> if (p.length < 4) return null else index[p.take(4)] ?: return null }
        val words = idx.map { WORDS[it] }
        val seed = ByteArray(15) { idx[it].toByte() }
        return if (check(seed) == idx[15]) words else null
    }

    /** Which word (1-16) looks wrong, if any, for a helpful message. */
    fun firstUnknownWord(text: String): Int? {
        val parts = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        parts.forEachIndexed { i, p -> if (p.length < 4 || index[p.take(4)] == null) return i + 1 }
        return null
    }

    private fun seed(words: List<String>) = ByteArray(15) { index.getValue(words[it].take(4)).toByte() }

    fun secretKey(words: List<String>): ByteArray = Schnorr.keyFromSeed(seed(words), LABEL)
    fun publicHex(words: List<String>): String = Schnorr.hex(Schnorr.pubOf(secretKey(words)))
}

// ---------------------------------------------------------------- Succession certificates

object Succession {
    data class Link(val old: String, val new: String, val revoke: Boolean, val how: String)

    const val DEVICE = "dev"
    const val BACKUP = "bak"
    const val PHRASE = "phr"

    private fun data(how: String, old: String, new: String, npk: ByteArray, ts: Long, rv: Boolean) =
        "succ|$how|$old|$new|${Crypto.sha256Hex(npk)}|$ts|$rv".toByteArray()

    private fun base(how: String, old: String, new: String, npk: ByteArray, ts: Long, rv: Boolean) =
        JSONObject().put("how", how).put("old", old).put("new", new).put("npk", b64(npk)).put("ts", ts).put("rv", rv)

    /** Old phone vouches for the new one directly. */
    fun device(oldId: String, opk: ByteArray, newId: String, npk: ByteArray, revoke: Boolean, sign: (ByteArray) -> ByteArray): JSONObject {
        val ts = System.currentTimeMillis()
        return base(DEVICE, oldId, newId, npk, ts, revoke).put("opk", b64(opk))
            .put("sig", b64(sign(data(DEVICE, oldId, newId, npk, ts, revoke))))
    }

    /** The delegation written into a backup: "this backup key may name my successor", signed by the identity. */
    fun delegationData(oldId: String, bpkHex: String, dts: Long) = "dlg|$oldId|$bpkHex|$dts".toByteArray()

    fun backup(
        oldId: String, opk: ByteArray, bsk: ByteArray, dts: Long, dsig: ByteArray,
        newId: String, npk: ByteArray, revoke: Boolean
    ): JSONObject {
        val ts = System.currentTimeMillis()
        val bpk = Schnorr.hex(Schnorr.pubOf(bsk))
        val sig = Schnorr.sign(bsk, Schnorr.sha256Bytes(data(BACKUP, oldId, newId, npk, ts, revoke)))
        return base(BACKUP, oldId, newId, npk, ts, revoke).put("opk", b64(opk))
            .put("bpk", bpk).put("dts", dts).put("dsig", b64(dsig)).put("sig", Schnorr.hex(sig))
    }

    /** Signed with the recovery phrase. The new phone doesn't know the old ID: other phones fill it in. */
    fun phrase(words: List<String>, newId: String, npk: ByteArray, revoke: Boolean): JSONObject {
        val ts = System.currentTimeMillis()
        val sk = Recovery.secretKey(words)
        val sig = Schnorr.sign(sk, Schnorr.sha256Bytes(data(PHRASE, "", newId, npk, ts, revoke)))
        return base(PHRASE, "", newId, npk, ts, revoke).put("rpk", Schnorr.hex(Schnorr.pubOf(sk))).put("sig", Schnorr.hex(sig))
    }

    /** The statement every identity makes about its own recovery key: "rcc|ID|recovery public key". */
    fun rccData(id: String, rc: String) = "rcc|$id|$rc".toByteArray()

    /**
     * A phrase certificate with the old identity's own recovery commitment attached, which makes it
     * self-contained: any phone can check it without having met the old ID.
     */
    fun withCommitment(c: JSONObject, old: String, opkB64: String, rcs: String): JSONObject =
        JSONObject(c.toString()).put("old", old).put("opk", opkB64).put("rcs", rcs)

    /** Checks a certificate using only what's inside it. Returns the link, or null if anything is off. */
    fun verify(c: JSONObject?): Link? = try {
        if (c == null) null else {
            val how = c.getString("how"); val old = c.optString("old"); val new = c.getString("new")
            val npk = unb64(c.getString("npk")) ?: throw IllegalArgumentException()
            val ts = c.getLong("ts"); val rv = c.getBoolean("rv")
            val opk = unb64(c.optString("opk")) ?: throw IllegalArgumentException()
            val ok = new.length == 32 && idOf(npk) == new && old.length == 32 && idOf(opk) == old && old != new &&
                ts < System.currentTimeMillis() + 86_400_000L && when (how) {
                    DEVICE -> ecVerify(opk, data(how, old, new, npk, ts, rv), unb64(c.getString("sig")) ?: ByteArray(0))
                    BACKUP -> {
                        val bpk = c.getString("bpk"); val dts = c.getLong("dts")
                        val bpkB = Schnorr.unhex(bpk) ?: ByteArray(0)
                        ecVerify(opk, delegationData(old, bpk, dts), unb64(c.getString("dsig")) ?: ByteArray(0)) &&
                            Schnorr.verify(bpkB, Schnorr.sha256Bytes(data(how, old, new, npk, ts, rv)),
                                Schnorr.unhex(c.getString("sig")) ?: ByteArray(0))
                    }
                    PHRASE -> {
                        val rpk = c.getString("rpk")
                        ecVerify(opk, rccData(old, rpk), unb64(c.getString("rcs")) ?: ByteArray(0)) &&
                            Schnorr.verify(Schnorr.unhex(rpk) ?: ByteArray(0), Schnorr.sha256Bytes(data(how, "", new, npk, ts, rv)),
                                Schnorr.unhex(c.getString("sig")) ?: ByteArray(0))
                    }
                    else -> false
                }
            if (ok) Link(old, new, rv, how) else null
        }
    } catch (e: Exception) { null }
}

// ---------------------------------------------------------------- Backup files

/** CTXB1 | salt(16) | AES-GCM( PBKDF2(passphrase) , gzip(json) ). Nothing in the file is readable without the passphrase. */
object BackupFile {
    private val MAGIC = "CTXB1".toByteArray()
    private const val ROUNDS = 210_000

    private fun key(pass: String, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass.toCharArray(), salt, ROUNDS, 256)).encoded,
        "AES"
    )

    fun seal(json: String, pass: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return MAGIC + salt + Crypto.encrypt(key(pass, salt), gzip(json.toByteArray()), MAGIC)
    }

    /** Null if it isn't a CyberTxTs backup; throws WrongPassphrase if the passphrase is wrong. */
    fun open(bytes: ByteArray, pass: String): String? {
        if (bytes.size < MAGIC.size + 16 + 28 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val plain = Crypto.decrypt(key(pass, salt), bytes.copyOfRange(MAGIC.size + 16, bytes.size), MAGIC)
            ?: throw WrongPassphrase()
        return gunzip(plain).toString(Charsets.UTF_8)
    }

    class WrongPassphrase : Exception()
}

fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
fun gunzip(b: ByteArray, limit: Int = 200_000_000): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { input ->
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        require(out.size() <= limit)
    }
    out.toByteArray()
}

// ---------------------------------------------------------------- Move to a new phone

/** What the NEW phone shows as a QR code: where to reach it, a one-time secret, and its new identity. */
data class MoveOffer(val host: String, val port: Int, val secret: ByteArray, val id: String, val pub: ByteArray)

object MoveLink {
    fun build(o: MoveOffer): String =
        "cybertxts://move?a=${o.host}:${o.port}&s=${b64u.encodeToString(o.secret)}&id=${o.id}&pk=${b64u.encodeToString(o.pub)}"

    fun parse(text: String?): MoveOffer? = try {
        val raw = Regex("cybertxts://move\\?\\S+").find(text ?: "")?.value ?: throw IllegalArgumentException()
        val q = URI(raw).rawQuery.split("&").associate { p ->
            val i = p.indexOf('='); p.substring(0, i) to URLDecoder.decode(p.substring(i + 1), "UTF-8")
        }
        val (host, port) = q.getValue("a").split(":").let { it[0] to it[1].toInt() }
        val secret = unb64u.decode(q.getValue("s")); val pub = unb64u.decode(q.getValue("pk")); val id = q.getValue("id")
        require(secret.size == 32 && idOf(pub) == id && Regex("^[0-9.]{7,15}$").matches(host) && port in 1..65535)
        MoveOffer(host, port, secret, id, pub)
    } catch (e: Exception) { null }
}
