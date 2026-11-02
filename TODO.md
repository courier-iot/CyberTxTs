# CyberTxTs: To-Do List

## Decisions
- **Name:** CyberTxTs, by the Stellarite corp. Package `com.cybertxts.app` (installs separately from the old BrassTalks app).
- **Look:** Holo Glass refined. Deep space, neon cyan (you) and crimson (others), open clipped-corner panels, log-style names, IMG / VOX / TX readouts. Mockups are on the "CyberTxTs Mockups" canvas.
- **Words:** Node (room), Spin up / Link to node, Access key, Callsign (permanent), Operative, OP-ID, Unlink, Night ops / Day shift, Comm chirp. Node codes are a space word + 7 digits.
- **Rooms:** max 25 operatives. Host can remove anyone; if the host is away a majority vote (2+ yes) removes an intruder, then the access key rotates for everyone else.
- **Storage:** message log is optional and on-device only; IMG and VID keep-settings are separate; VOX notes are never stored.

## Done (carried over from BrassTalks, rebranded)
- [x] WiFi discovery + serverless encrypted mesh, swappable transport
- [x] Signed per-install identity, permanent callsign, private nicknames
- [x] Multiple nodes, rename (private nickname per node), unread counts
- [x] Host kick, vote kick, automatic key rotation with offline catch-up
- [x] IMG with 4 qualities (Original strips location), VID up to 5 min with 3 qualities, VOX up to 2 min
- [x] TX/RX progress, every piece verified
- [x] Background link service, comm-chirp alerts, encrypted message log with auto-purge
- [x] Replies, copy, timestamps with CYCLE date dividers
- [x] **Internet link (first version):** nodes now work across the internet as well as WiFi. Phones find each other through public Nostr relays (they only see encrypted notes under a hash of the node code), then link directly with WebRTC. WiFi is preferred when both phones share it. Operatives list shows VIA WIFI or VIA NET. Settings > LINK > Internet link (on by default; restart to apply).
- [x] **QR invites + host verification:** INVITE shows a QR code with the node code, access key and the host's fingerprint, plus a SHARE LINK button (cybertxts:// link opens the app). LINK TO NODE > SCAN QR INVITE. Joining from a QR or link pins the host, so nobody else can claim to be host. Operatives list shows ★ HOST · VERIFIED.
- [x] **No Google services:** QR scanning now uses ZXing Android Embedded with our own Holo Glass scan screen (open-corner frame, sweep line, LIGHT, FROM GALLERY). Works on phones without Google Play Services. Internet link uses only Cloudflare's STUN server. Invite QRs are now dark-on-cyan so any scanner reads them.
- [x] **Key-rotation tie-break:** if two operatives rotate the key at the same instant, every phone settles on the same winner, so a node can't split.
- [x] **Original photos:** XMP and IPTC blocks (which can hold location) are now stripped too.
- [x] **Private channels:** long-press a message > MESSAGE PRIVATELY, or MESSAGE on a Star Map card. Sealed to the recipient's key (other operatives and relays can't read it). PRIVATE chips above the chat show threads and unread counts. Text only for now.
- [x] **Delivery and read marks:** ✓ sent, ✓✓ delivered, READ (n) on your messages.
- [x] **Typing indicator:** "VEGA//7F3A TRANSMITTING…" (node chat and private channels).
- [x] **Reactions:** 👍 ❤️ 😂 😮 🔥 👀 from the long-press menu; tap a chip to add/remove yours.
- [x] **Edit / retract:** edit your own text messages (shows EDITED); RETRACT FOR EVERYONE removes a message (and its stored media) on every phone.
- [x] **Per-node mute:** ⋮ > MUTE ALERTS; nodes menu shows MUTED.
- [x] **Cancel / retry / dismiss** for photo, voice and video transfers (and video preparation).
- [x] **Star Map:** each node has CHAT / STAR MAP tabs. MY LINKS shows you in the centre with operatives around you (solid cyan WIFI, dashed crimson NET, dotted relayed/seen), delay in ms, pulses when data moves, totals (linked, TX, RX, relays). FULL MESH shows every link between everyone, with amber warnings for pairs that can't link directly. Tap an operative for their card (route, delay, linked for, data, other shared nodes, NICK / REMOVE / VOTE OUT).
- [x] **Constellation:** nodes menu > CONSTELLATION shows all your nodes as star systems, with operatives you share drawn as bridges. Built only from what your phone knows; never shared.
- [x] **Relay through other operatives:** if two operatives can't link directly, one operative linked to both forwards their (still signed and encrypted) traffic. Settings > LINK > Relay for other operatives (on by default). Votes and key rotation now count relayed operatives too.
- [x] **New:** Holo Glass UI on every screen (callsign, spin up / link, nodes menu, chat, full-screen settings), Night ops / Day shift themes, planet icon, comm chirp, Orbitron / Rajdhani / Share Tech Mono

## First build checklist (new repo)
- [ ] Create the new repo; upload `.github/` and `CyberTxTs/` exactly as in the zip (`.github` at the very top)
- [ ] Actions > Build APK: the "Fetch fonts" step should list 6 font files
- [ ] Paste any compile errors back to Claude (this rebuild hasn't been compiled yet)
- [ ] Set up the signing key (steps at the bottom) before sharing the APK
- [ ] Uninstall BrassTalks if you like; CyberTxTs installs as a separate app

## Test the new look
- [ ] First launch: callsign screen with spinning orbit, "BY STELLARITE"
- [ ] Spin up / Link: node code with REROLL, access key, INITIALISE NODE
- [ ] Chat: open-corner panels (your corner top-left, theirs top-right), `NAME//ABCD · time` log lines, IMG / VOX / TX buttons, crimson INVITE
- [ ] Nodes menu (≡): operative card, ACTIVE NODES list, VIEWING tag, crimson unread count
- [ ] Settings: OPERATIVE, DISPLAY, ARCHIVE, TRANSMISSION QUALITY, LINK, DANGER ZONE; switches work
- [ ] Day shift theme is readable; Match device follows the phone
- [ ] Comm chirp plays for a message with the app closed; notification shows the planet icon
- [ ] Re-run the earlier feature tests (kick + key rotation, media qualities, VOX, background link)

## Next (agreed)
- [ ] Original-quality VID by tap-to-download: everyone gets the shrunk version; only those who tap DOWNLOAD get the original; resumable; later, other phones help share pieces

## Follow-ups
- [ ] Faster sharing in big nodes (one slow phone slows everyone; 25 copies uploaded)
- [ ] Clearer "can't link" diagnostics; manual IP fallback; router client-isolation warning
- [ ] Battery check overnight
- [ ] Long-press menu directly on IMG / VID pictures
- [ ] Glow effect on panels (the mockups' soft neon halo; skipped in the app for now)

## Test the internet link (needs 2+ phones on DIFFERENT networks)
- [ ] Phone A on home WiFi, phone B on mobile data: link to the same node; header shows LINK 2/25 within about 30 s
- [ ] Operatives list shows "VIA NET"; put both on the same WiFi and it should switch to "VIA WIFI" after relinking
- [ ] Text, IMG, VOX and a short VID over NET; check TX/RX progress
- [ ] Remove someone over NET: key rotates; a phone that was offline catches up when it comes back
- [ ] Settings > Internet link OFF, restart: only WiFi links remain
- [ ] Note which network combinations fail to link (some mobile carriers and work/school WiFi block direct links)

## Test security + everyday features (update ALL phones together)
- [ ] First scan asks for camera permission; decline it and FROM GALLERY still works with a screenshot of the invite
- [ ] LIGHT turns the torch on and off; CANCEL closes the scanner; scanning a random QR shows NOT A CYBERTXTS INVITE
- [ ] INVITE shows a QR; the other phone uses LINK TO NODE > SCAN QR INVITE and gets a "Link to CODE?" prompt mentioning the host fingerprint
- [ ] SHARE LINK into a chat app; tapping the cybertxts:// link opens CyberTxTs with the same prompt
- [ ] After joining by QR, Operatives shows ★ HOST · VERIFIED
- [ ] Original-quality JPEG: no location in a gallery app's details
- [ ] Private channel: A messages B privately; C sees nothing (not even in the node chat); unread chip on B
- [ ] ✓ then ✓✓ then READ appear on your message as the other phone receives and opens it
- [ ] TRANSMITTING… appears while the other person types, and disappears ~6 s after they stop
- [ ] Reactions show on every phone; tapping your chip removes it
- [ ] Edit a message (EDITED shows everywhere); retract a photo (gone on every phone)
- [ ] MUTE ALERTS: no notification for that node, other nodes still alert
- [ ] Start sending a video, CANCEL, then RETRY; DISMISS removes it
- [ ] Two phones remove the same person at nearly the same moment: everyone ends on the same key (no split)

## Everyday follow-ups
- [ ] Photos / voice / video in private channels
- [ ] Sign receipts and typing signals (currently unsigned; only node members can send them)
- [ ] Edit history / "edited at" time

## Test the Star Map and relaying (3 phones)
- [ ] STAR MAP > MY LINKS: each operative shows VIA WIFI / VIA NET and a delay in ms within ~20 s; dots pulse while sending a photo
- [ ] FULL MESH with 3 phones: three lines; force one pair apart (e.g. phone A WiFi-only with internet link off, phone C mobile data) and check the amber warning says RELAYED VIA B
- [ ] With that setup, A and C can still message each other through B; turn B's "Relay for other operatives" off and the warning changes to CAN'T GET THROUGH
- [ ] Tap an operative: card details are right; NICK, REMOVE (host) and VOTE OUT work
- [ ] Remove someone while a pair is relayed: the key rotation reaches the relayed operative too
- [ ] CONSTELLATION with 2+ nodes sharing a person: bridge drawn; tapping a system opens that node
- [ ] Update ALL phones together: link reports changed the "hi" message format

## Internet link: known limits and next steps
- [ ] **No relay fallback yet:** roughly 10-20% of network pairs (strict carrier or corporate firewalls) can't link directly. Fix: an optional encrypted TURN relay (would need a small server, or a free TURN service)
- [ ] Full mesh: every phone uploads its own copy of each item to every other phone. Relaying now only covers pairs that can't link directly (one hop); a smarter fan-out could cut upload for big nodes
- [ ] Changing "Internet link" needs an app restart; make it apply live
- [ ] Choose / add your own relays in Settings
- [ ] Battery and data use check on mobile data
- [ ] Peers can see each other's IP address (normal for direct links); a relay fallback could hide it as an option

---

## Signing key (do this in the new repo)
**Why:** without a permanent key every build needs an uninstall, which wipes your callsign, nodes and logs.

1. Upload `make-signing-key.yml` and `build.yml` to `.github/workflows/` (they're already in the zip).
2. Settings > Secrets and variables > Actions > New repository secret: `SIGNING_PASSWORD` (16+ characters). Save it in a password manager.
3. Actions > "Make signing key (run once)" > Run workflow. Download the artifact, open `SIGNING_KEYSTORE.txt`, copy all the text.
4. New repository secret: `SIGNING_KEYSTORE` = that text.
5. Back up `SIGNING_KEYSTORE.txt` AND the password somewhere safe. Delete the artifact from the run page.
6. Actions > Build APK > Run workflow. Install. From then on, updates install over the top.

**Never lose the key or password:** without them, no future update can install over existing installs.
