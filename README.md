<p align="center">
  <img src=".github/assets/cybertxts-icon.png" width="128" alt="CyberTxTs icon: a chat-bubble planet with a crimson orbit">
</p>

<h1 align="center">CyberTxTs</h1>

<p align="center">
  <b>Private messaging that doesn't run through anyone's servers.</b><br>
  By Stellarite · Android 8 and newer · No accounts, no phone number, no Google
</p>

<p align="center">
  <a href="https://github.com/courier-iot/CyberTxTs/releases/latest"><b>⬇ Download the latest release</b></a>
</p>

---

CyberTxTs is a messenger for small groups and one-to-one chats where **your phones talk straight to each other**. On the same WiFi they link directly. Anywhere else they find each other over the internet and still link directly. There is no CyberTxTs server holding your messages, your contacts or your account, because there isn't one.

Everything is **end-to-end encrypted and signed**: texts, photos, videos, voice notes and calls. Only the people in the chat can read it, and nobody can fake a message from you.

You don't sign up. You open the app, pick a **callsign** (your name in CyberTxTs), and you're ready.

## Get it

1. On your Android phone, open the [latest release](https://github.com/courier-iot/CyberTxTs/releases/latest) and tap **CyberTxTs-x.y.z.apk** under *Assets*.
2. Open the downloaded file. The first time, Android asks you to allow installs from your browser or files app. Allow it, then go back.
3. Tap **Install**. Future versions install over the top and keep everything.

Every release is signed with the same certificate, and its fingerprint is printed in the release notes. If a download ever shows a different one, don't install it.

## Start in a minute

1. **Pick your callsign.** It's permanent, so choose one you like.
2. **Spin up a node** (a group chat), or **link to a node** someone shared with you by scanning their QR code.
3. **Invite people**: open the node, tap **INVITE**, and let them scan the QR code or send them the link.

That's it. Anyone in the node can now chat, send media and see who's online.

---

## The everyday features

### 💬 Nodes: group chats
A **node** is a group chat of up to 25 people that lives only on its members' phones. You can be in several at once and switch between them from the **≡** menu. The person who spins it up is the **host** and can remove people. If the host is away, the others can vote someone out.

### 👤 Contacts: one-to-one chats
**Contacts** are private chats with one person, no group needed. Add someone by scanning each other's QR codes in person (marked **✓ VERIFIED**), from a node you share, or by sending a contact link. Messages you send while they're offline wait and send themselves later.

### 📷 Photos, videos and voice notes
- **IMG**: photos in four qualities. *Original* keeps full quality and strips the location.
- **VID**: videos up to 5 minutes.
- **VOX**: voice notes up to 2 minutes. They're never saved.

You can see progress while sending, and cancel or retry.

### 📞 Voice and video calls
Call any contact from their chat or contact card. Calls are encrypted end to end and keep going if you lock your screen or switch apps. There are buttons for mute, speaker, camera and flip, plus Mute and Hang up in the notification.

### ✨ Chat extras
- **Replies, reactions, edit and retract** (retract removes a message for everyone)
- **✓ sent · ✓✓ delivered · READ** marks, and a typing signal (you can turn both off)
- **@mentions**, **pins**, **polls** and **forwarding**
- **Search** one chat or everything
- **Disappearing messages**: 1 hour, 1 day or 1 week
- **Drafts** stay put; **schedule** a message for later
- **Media gallery** for every photo and video in a chat

### 🔔 Notifications
Alerts play the **comm chirp** sound. You can reply, mark read or mute right from the notification. Message text is always hidden on the lock screen, and you can hide it everywhere.

### 🔒 App lock
Lock CyberTxTs with a PIN, fingerprint or face. While it's on, the app is hidden in the app switcher and screenshots are blocked.

### 🎨 Looks
**Holo Glass** design with **Night ops** (deep space, neon cyan and crimson), **Day shift** or *match your phone*. Spoken labels and large touch targets make it work well with TalkBack.

### 🧳 Don't lose yourself
Because there's no server, **your phone is your account**. Set one of these up early:
- **Recovery phrase**: 16 words you write down (Settings › Security).
- **Encrypted backup file**: a `.ctxb` file locked with your passphrase (Settings › Backup & devices).
- **Move to a new phone**: scan a QR code and everything moves over WiFi.

---

## Words you'll see

| In CyberTxTs | Means |
|---|---|
| **Node** | A group chat |
| **Spin up / Link to node** | Create a group / join a group |
| **Operative** | A person in a node |
| **Callsign** | Your permanent name |
| **OP-ID** | The short code after a name (`VEGA//7F3A`) that tells two Vegas apart |
| **Access key** | The secret that keeps a node private. Whoever has it can join |
| **Unlink** | Leave a node |
| **IMG / VID / VOX** | Photo / video / voice note |
| **TX / RX** | Sending / receiving |

---

## Advanced features in depth

<details open>
<summary><b>How phones find and reach each other</b></summary>

CyberTxTs tries several routes and uses the best one:

1. **Same WiFi:** phones announce themselves on the local network (mDNS) and open direct, encrypted connections. This is fastest, and nothing leaves your network.
2. **Internet:** phones meet through four public **Nostr relays**. These are ordinary public message boards, not CyberTxTs servers. Each node posts only encrypted notes under a *hash* of its code, so a relay can't tell which node it is, who's in it or what's said. Once two phones have found each other, they connect **directly** with WebRTC, using Cloudflare's STUN service only to learn their public address. Your messages never sit on the relay.
3. **Relaying:** if two people can't connect directly (strict routers, mobile networks), someone linked to both forwards their traffic. It stays signed and encrypted, so the relaying phone can't read or change it. You can turn this off in Settings › Network.

**When it won't link:** ⋮ › **LINK DIAGNOSTICS** checks your WiFi, address, discovery and relays and explains the fix in plain words. A red **ROUTER BLOCKING** banner means your WiFi stops devices from talking to each other (common on guest and hotel networks). **Link by address** lets you type another phone's address when discovery doesn't work.

**Trade-off:** a direct internet link means the people you link with can see your IP address. Turn off **Internet link** in Settings › Network to stay WiFi-only.
</details>

<details>
<summary><b>Encryption, identity and signatures</b></summary>

- **Your identity** is a key pair created inside your phone's secure hardware (Android Keystore) the first time you open the app. Your OP-ID comes from it, and **every message, reaction, edit, vote and receipt is signed** with it, so nobody can impersonate you or alter what you sent.
- **Node messages** are encrypted with AES-GCM using a key derived from the node's access key, which is never sent in the clear.
- **Fingerprints:** every operative card shows a 32-character fingerprint. Compare it in person to be certain who you're talking to.
- **Look-alike warning:** if a *new* ID shows up using the callsign of someone you already know, everyone sees **⚠ NEW OPERATIVE · NOT THE VEGA YOU KNOW**, with both fingerprints.
- **Host verification:** QR invites and links carry the host's fingerprint, so after you join from one, no other phone can pretend to be host (**★ HOST · VERIFIED**).
- **Removing someone:** the host can remove anyone. Without the host, a majority vote (at least 2 yes) does it. Either way, **the access key rotates automatically** for everyone who's left, including phones that were offline, and the removed person can't read anything new. If two phones rotate at the same instant, all phones agree on the same winner, so the node can't split.
- **Host handover:** the host can pass the role on (Operatives › HOST). Moving phones carries it over automatically.
</details>

<details>
<summary><b>Private channels</b></summary>

Inside a node, long-press a message › **MESSAGE PRIVATELY** (or **MESSAGE** on a Star Map card) to talk to one person. Each message, photo, video or voice note is **sealed to that person's key**. Other operatives, relays and any phone forwarding it can't open it. **PRIVATE** chips above the chat show your threads and unread counts.
</details>

<details>
<summary><b>Catch-up: messages you missed</b></summary>

Each phone keeps **a week** of signed messages (private ones stay sealed), along with photo, video and voice-note headers, edits and reactions. When two phones link, they swap whatever the other is missing, exactly as signed, so nothing can be altered on the way. Photos and videos are re-sent from storage.

**Privacy guards:** newcomers never receive messages from before they joined, retracted messages are never handed out again, and expired disappearing messages are never redistributed.
</details>

<details>
<summary><b>Disappearing messages</b></summary>

⋮ › **DISAPPEARING** sets 1 hour, 1 day or 1 week for a node or contact. The setting is signed and reaches everyone, including phones that were away. When the time is up, messages and their photos and videos are deleted on every phone, and catch-up won't bring them back.
</details>

<details>
<summary><b>Original-quality photos and video</b></summary>

- **Photos:** *Original* sends the untouched file with location and other hidden metadata (EXIF GPS, XMP and IPTC) removed.
- **Video:** choosing *Original* sends everyone a Standard copy straight away and keeps the untouched file (up to 400 MB) on your phone. Others tap **DOWNLOAD ORIGINAL** to fetch it directly from you. Originals are never passed on by relaying phones.
</details>

<details>
<summary><b>Contacts in depth</b></summary>

- **In person:** scan each other's code. Both fingerprints are checked, and the contact is marked **✓ VERIFIED**.
- **From a node:** long-press someone's message › **ADD CONTACT**, or use their Star Map card. They approve it.
- **By link:** a contact link works once, expires after 7 days, and you approve whoever uses it.
- **Contact cards** show the fingerprint, verification, a private note and nickname, and remove or block.
- **Offline:** texts queue and send themselves. Photos and videos wait even across a restart; voice notes wait while the app is open.
- **Moving phones:** contacts move with you, and follow the other person when *they* move phones.
</details>

<details>
<summary><b>Backups, recovery and moving phones</b></summary>

- **Recovery phrase** (Settings › Security): 16 words, shown once and never stored on the phone. On a new phone, **RECOVER WITH PHRASE** proves you're you and can **lock the old phone out** of every node: it's removed and the keys rotate.
- **Encrypted backup** (Settings › Backup & devices): a `.ctxb` file locked with your passphrase. It holds your nodes, access keys, contacts and settings. Restoring it on a fresh install brings you back as yourself, optionally locking out the old phone.
- **Move to a new phone:** on the new phone choose **MOVE FROM MY OLD PHONE**, then scan its QR code with the old one. Everything (and optionally your message log) moves over the same WiFi, signed by the old phone, and the old phone then offers to erase itself. Operatives keep your nickname, private channels and host role.

Backups are switched off at the Android level on purpose, so a restored system backup can never clone your identity onto another phone.
</details>

<details>
<summary><b>App lock details</b></summary>

- **PIN** of 4 to 8 digits, with optional fingerprint or face unlock and a lock delay you choose.
- **Privacy screen:** the app is hidden in the app switcher and screenshots are blocked.
- After **5 wrong PINs** you have to wait, and the wait grows with each further miss. **Forgot PIN?** offers to erase the app.
- Notification action buttons are hidden while the lock is on. An ongoing call stays reachable on top of the lock screen.
</details>

<details>
<summary><b>Star Map and Constellation</b></summary>

Every node has **CHAT** and **STAR MAP** tabs.
- **MY LINKS** puts you in the centre with everyone around you. A solid cyan line is WiFi, dashed crimson is internet, and dotted means relayed or only seen. It also shows the delay in milliseconds, pulses when data moves, and totals for links, data sent and received, and relays.
- **FULL MESH** shows every link between everyone, with amber warnings for pairs that can't connect directly.
- **Tap anyone** for their card: route, delay, time linked, data, other shared nodes, and nickname, remove or vote out.
- **Constellation** (≡ menu) draws all your nodes as star systems, with people you share shown as bridges. It's built only from what your phone knows and is never shared.
</details>

<details>
<summary><b>Battery and data</b></summary>

Settings › **Battery & data** shows how fast your battery drains per hour while unplugged, a 24-hour battery chart, CyberTxTs' own processor time, data used on WiFi and mobile, and open links. It adds tips and a shortcut to Android's battery page. **Hold link in background** keeps you reachable with the screen off, at some battery cost.
</details>

<details>
<summary><b>What anyone outside your chats can see</b></summary>

| Who | What they can see | What they can't |
|---|---|---|
| **Public Nostr relays** | That some encrypted notes were posted under a hashed topic, and your IP address when you connect to them | Node codes, names, members, messages, media |
| **Cloudflare STUN** | Your IP address when a link starts | Anything you send |
| **A phone relaying for you** | That encrypted traffic passes through | Its contents (and it can't change it) |
| **People you link with directly over the internet** | Your IP address | Private channels not meant for them |
| **Your WiFi network** | That two devices are talking | The contents |

No analytics, no ads, no crash reporting, no Google Play Services.
</details>

<details>
<summary><b>Limits and good to know</b></summary>

- Up to **25 operatives** per node. Videos up to **5 minutes**, voice notes up to **2 minutes**, original videos up to **400 MB**. Catch-up covers **7 days**.
- Your message log is **optional** and stored **encrypted on your phone only**, with auto-purge after 7, 30 or 90 days. Photo and video storage can be switched off separately.
- **No server means no account recovery.** Without a recovery phrase, backup or old phone, a lost phone means a new identity.
- Android only. Calls are one-to-one for now.
- Works on phones **without Google services** (QR scanning uses the open-source ZXing library).
</details>

---

## For developers

<details>
<summary><b>Building, releasing and the code layout</b></summary>

**Tech:** Kotlin and Jetpack Compose, minSdk 26, targetSdk 35. WebRTC (`stream-webrtc-android`), OkHttp websockets for Nostr, ZXing for QR, Media3 Transformer for video, AndroidX Biometric. Fonts (Orbitron, Rajdhani, Share Tech Mono, all SIL OFL) are downloaded at build time.

**Builds (GitHub Actions):**
- **Build test APK** runs on every push to `main` and makes **CyberTxTs Dev** (`com.cybertxts.app.dev`). It sits next to the real app, with its own identity.
- **Release** (Actions › Release › version, or push a tag `v1.2.3`) builds a signed **CyberTxTs** (`com.cybertxts.app`), checks the signature, and publishes the APK, its SHA-256 and the notes from `CyberTxTs/CHANGELOG.md`. Version codes come from the version number, so releases always update cleanly.
- **Signing:** the `SIGNING_PASSWORD` and `SIGNING_KEYSTORE` repository secrets hold the permanent key (alias `cybertxts`, PKCS12). Never lose them: without them, existing installs can't update.

```
.github/workflows/      build.yml (test builds), release.yml, make-signing-key.yml
.github/scripts/        fetch-fonts.sh
.github/assets/         README images
CyberTxTs/CHANGELOG.md  release notes, one section per version
CyberTxTs/app/src/main/java/com/cybertxts/app/
  CyberApp.kt, ChatViewModel.kt        startup and the one long-lived view model
  NodeSession.kt                       a node: messages, members, votes, key rotation, media, catch-up
  LanTransport.kt, InternetTransport.kt, Transport.kt, Nostr.kt   WiFi, WebRTC, relays
  Crypto.kt, Identity.kt, Account.kt   encryption, signing, recovery, backups, phone moves
  Contacts.kt, ContactsUI.kt           contacts and direct chats
  Calls.kt, CallUI.kt                  voice and video calls
  Media.kt, Storage.kt                 media pipeline, settings, encrypted log
  MainActivity.kt, AccountScreens.kt, ChatExtras.kt, StarMap.kt, Diagnostics.kt, Scanner.kt, UsageUI.kt
  Lock.kt, SessionService.kt, Battery.kt, Theme.kt, People.kt, Invite.kt
```
</details>

<p align="center"><sub>CyberTxTs · by Stellarite</sub></p>
