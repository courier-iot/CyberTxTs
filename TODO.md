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
- [x] **Voice & video calls (1-to-1):** CALL / VID in a contact chat, VOICE CALL / VIDEO CALL from a message's long-press or a Star Map card, Voice call / Video call on a contact card. Direct WebRTC connection, end-to-end encrypted; offer/answer signed with your identity. Mute, speaker, camera on/off, flip, ringtone, missed-call notice.
- [x] **Reply from the notification:** Reply, Mark read and Mute node buttons on message alerts (hidden while app lock is on).
- [x] **Polls:** ⋮ > NEW POLL (2-6 options); signed one-vote-each, change or take back your vote.
- [x] **Privacy switches:** Settings > Chats & media > Privacy: send read marks / typing signal (off = you don't see others' either).
- [x] **Drafts and scheduled messages:** unsent text stays per chat; ⋮ > SCHEDULE MESSAGE (15 min, 1 h, 9 am tomorrow, custom hours); "N SCHEDULED" bar to cancel.
- [x] **Signed receipts and typing signals; "EDITED 21:45" times.**
- [x] **Accessibility pass:** spoken message headers ("From Vega, 9:41 PM, delivered"), headings, announced typing and call status, switch on/off states, labelled reactions and polls, larger touch targets, less text cut-off at big font sizes.
- [x] **Battery & data tracker:** Settings > Battery & data: drain per hour while unplugged, 24 h battery chart, CyberTxTs CPU time, data on WiFi vs mobile, links open, tips, link to Android's battery page. Readings every 10 min, stored on the phone only.
- [x] **Search:** ⋮ > SEARCH in any chat (tap a result to jump to it); nodes menu > SEARCH ALL CHATS across every node and contact.
- [x] **Disappearing messages:** ⋮ > DISAPPEARING: Off / 1 hour / 1 day / 1 week, per node or contact, signed, synced to everyone (including phones that were away); expired messages, photos and videos are deleted and never redistributed by catch-up.
- [x] **@mentions:** type @ for suggestions; a mention highlights the bubble, shows @YOU, and notifies even when the node is muted.
- [x] **Pins + media gallery:** long-press > PIN (signed, synced); a PINNED bar cycles through pins. ⋮ > MEDIA shows every photo and video in the chat.
- [x] **Forwarding:** long-press > FORWARD to any node or contact (text, photo, VOX, video).
- [x] **Original-quality video:** choose Original when sending a video: everyone gets Standard now, the untouched file (up to 400 MB) stays on your phone, and others tap DOWNLOAD ORIGINAL to get it from you.
- [x] **Catch-up on missed messages:** each phone keeps a week of signed messages, private messages (still sealed), photo/video/VOX headers, edits and reactions. When two phones link they swap what the other is missing, exactly as signed. Photos and videos are re-sent from storage. Newcomers never get messages from before they joined; retracted messages aren't handed out again.
- [x] **One shared relay connection:** every node, contact and offer now shares one connection to each of the 4 relays (one subscription, routed by topic) instead of 4 websockets each.
- [x] **Queued media for contacts:** photos, VOX and video to an offline contact show "QUEUED · SENDS WHEN THEY'RE ONLINE" and go out when you link (photos/videos survive a restart; VOX only while the app is running). Cancel available.
- [x] **Contacts + direct links:** Contacts (nodes menu, or the home screen) lists 1-to-1 links that work with no shared node: photos, VOX, video, receipts, typing, reactions, edit/retract all work. Add by scanning each other's code in person (✓ VERIFIED), from a node (long-press a message > ADD CONTACT, or the Star Map card), or by sharing a contact link (single use, 7 days, you approve it). Texts sent while they're offline queue and send themselves when you're both online. Contact card: fingerprint, verify, private note, nickname, remove, block. Included in backups and phone moves; follows someone who moves phones.
- [x] **Look-alike warning:** a new ID using the callsign of someone you already know shows "⚠ NEW OPERATIVE · NOT THE VEGA YOU KNOW" in the log, Operatives list and Star Map card (with both fingerprints). "I know them" stops the warning.
- [x] **Full fingerprints:** 32-character fingerprint on every operative card and in Settings, to compare in person.
- [x] **Move to a new phone:** new phone shows a QR (fresh install > MOVE FROM MY OLD PHONE); old phone scans it (Settings > Backup & devices). Nodes, access keys, contacts, settings and (optionally) the log move over the same WiFi, signed by the old phone. Old phone then offers to erase itself.
- [x] **Recovery phrase:** 16 words (Settings > Security), shown once, never stored. On a new phone, RECOVER WITH PHRASE proves it's you and can lock the old phone out of every node (removed + key rotated).
- [x] **Encrypted backup file:** .ctxb file locked with your passphrase (Settings > Backup & devices). Restoring it on a fresh install brings you back as yourself, optionally locking out the old phone.
- [x] **App lock:** PIN (4-8 digits), optional fingerprint/face, lock delay, privacy screen (hides app switcher, blocks screenshots), escalating wait after 5 wrong PINs, "Forgot PIN?" erase.
- [x] **Host handover:** host can pass the role (Operatives > HOST, or Star Map card > MAKE HOST). Moving phones carries the host role automatically.
- [x] **Settings tidied:** home page with your operative card and status chips (lock / recovery / backup), then Security, Backup & devices, Display, Chats & media, Network, Alerts, Danger zone pages. Alerts can hide message text. Danger zone can erase the phone.
- [x] **Private media:** IMG, VID and VOX now work in private channels. Each item gets its own key sealed to the recipient, goes only to them (or through the one relaying operative), and relays can't open it.
- [x] **Long-press on pictures:** long-press a photo or video directly for the same menu as text (react, reply, message privately, retract).
- [x] **Link diagnostics:** ⋮ > LINK DIAGNOSTICS (or DIAGNOSE on the "No link found" banner) shows WiFi status, your address, discovery and relay status, and plain-words fixes. A crimson ROUTER BLOCKING banner appears when operatives are found on WiFi but can't connect (client isolation).
- [x] **Link by address:** type another phone's address (from its diagnostics screen) when discovery doesn't work. Each node now keeps a steady port.
- [x] **Internet link switch applies instantly** (no restart).
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
- [ ] Original video downloads: resume after a dropped link; let other phones that downloaded it share it too

## Follow-ups
- [ ] Faster sharing in big nodes (one slow phone slows everyone; 25 copies uploaded)
- [ ] Battery check overnight
- [ ] Glow effect on panels (the mockups' soft neon halo; skipped in the app for now)

## Test the internet link (needs 2+ phones on DIFFERENT networks)
- [ ] Phone A on home WiFi, phone B on mobile data: link to the same node; header shows LINK 2/25 within about 30 s
- [ ] Operatives list shows "VIA NET"; put both on the same WiFi and it should switch to "VIA WIFI" after relinking
- [ ] Text, IMG, VOX and a short VID over NET; check TX/RX progress
- [ ] Remove someone over NET: key rotates; a phone that was offline catches up when it comes back
- [ ] Settings > Internet link OFF, restart: only WiFi links remain
- [ ] Note which network combinations fail to link (some mobile carriers and work/school WiFi block direct links)

## Test calls, polls, notifications (update ALL phones together)
- [ ] Contact chat > CALL: other phone rings (also with the app in the background: notification); Answer; both hear each other; Mute, Speaker, End
- [ ] VID call: both videos show; Cam off, Flip; answer a video call as Voice only
- [ ] Call someone already on a call: "Busy on another call"; don't answer for 45 s: "No answer" / "Missed call"
- [ ] Call between phones on different networks (mobile data vs WiFi)
- [ ] Notification Reply sends the message; Mark read clears unread; Mute node mutes it
- [ ] Poll: 3 phones vote, counts match everywhere; change and take back a vote
- [ ] Privacy: turn read marks off: others stop seeing READ from you; you stop seeing theirs
- [ ] Type half a message, leave the chat and come back: draft is still there; schedule one for 15 min: it sends
- [ ] TalkBack on: messages read as "From Vega, 9:41 PM, delivered"; switches say On/Off; typing is announced

## Later
- [ ] Desktop companion (parked in /parked/companion: browser on the same WiFi, encrypted with a pairing code)
- [ ] Group calls in nodes (calls are 1-to-1 for now)
- [ ] Keep a call going with the app in the background (needs a microphone/camera foreground service)

## Test this round (update ALL phones together)
- [ ] Settings > Battery & data: readings appear after ~10 min; chart fills over a few hours; Reset clears it
- [ ] ⋮ > SEARCH finds a word; tapping a result scrolls to it and flashes the bubble; SEARCH ALL CHATS opens the right node / contact
- [ ] Set disappearing to 1 hour: everyone sees "SET TO 1 HOUR BY …"; a phone that was offline picks it up when it relinks; old messages vanish
- [ ] Mute a node, then get @mentioned there: notification still arrives ("@YOU · …")
- [ ] Pin two messages: PINNED 1/2 bar cycles; unpin works on every phone
- [ ] Forward a photo and a text from a node to a contact
- [ ] Send a video as Original: others see DOWNLOAD ORIGINAL · size; tapping it downloads and shows ORIGINAL QUALITY ✓

## Test catch-up, shared relays, queued media (update ALL phones together)
- [ ] Node with A, B, C: close B's app, chat on A and C (text, photo, reaction, edit, retract one), reopen B: everything arrives in order; the retracted one doesn't
- [ ] Private message to B while B is away: arrives when B relinks (even if only C is online)
- [ ] Invite a brand-new phone D: it does NOT receive last week's messages
- [ ] Settings > Network diagnostics: relays still show 4/4 with several nodes and contacts open
- [ ] Contact offline: send a photo and a video: QUEUED; contact comes online: both send; Cancel works on a queued item
- [ ] Battery: compare overnight drain with a few nodes and contacts against the last build

## Test contacts (update ALL phones together)
- [ ] A: Contacts > + ADD shows a QR; B: Contacts > + ADD > SCAN THEIR CODE: both list each other within ~30 s; B shows ✓ VERIFIED; A taps Verify and scans B to verify too
- [ ] Direct chat with no shared node: text, IMG, VOX, VID, reactions, READ marks, TRANSMITTING…
- [ ] Turn B's WiFi and data off, send 2 texts from A: "QUEUED" and "Pending: 2 messages"; B back online: they arrive, marks turn ✓✓
- [ ] From a node: long-press someone's message > ADD CONTACT; they see CONTACT REQUEST and ACCEPT
- [ ] SHARE CONTACT LINK into another app; the other phone taps it, confirms; you get a request to accept; the same link can't be used twice
- [ ] Contact card: private note survives a restart; Remove deletes the direct log; Block stops their requests
- [ ] Notification for a direct message says "NAME · DIRECT LINK"
- [ ] Backup / move to a new phone: contacts and direct logs come across; the contact sees "MOVED TO A NEW DEVICE" in your direct chat

## Contacts follow-ups

## Test identity + security round (update ALL phones together)
- [ ] Look-alike: on a spare phone, pick the SAME callsign as an existing operative and link: everyone sees the ⚠ warning; "I know them" clears it
- [ ] Settings > Security > Set up recovery phrase: 16 words, screenshot blocked, 3-word check; Test my phrase says MATCHES
- [ ] Settings > Backup & devices > Export backup: file saves; "Last backup today" shows on the Settings home
- [ ] Move: fresh install on phone B > MOVE FROM MY OLD PHONE; phone A scans; B gets nodes and log; other operatives see "MOVED TO A NEW DEVICE", keep their nickname for you, private channels continue
- [ ] Host moves phones: the new phone is host everywhere (★ HOST · VERIFIED)
- [ ] Restore backup on a fresh install (with "lock out old phone"): old phone is removed from its nodes and the key rotates
- [ ] Recover with phrase on a fresh install, then rejoin by invite: operatives see "RECOVERED ON A NEW DEVICE · OLD DEVICE LOCKED OUT"; old phone (if on) leaves its nodes
- [ ] App lock: set PIN, leave the app for longer than the delay: lock screen; fingerprint works; 5 wrong PINs makes it wait; picking a photo doesn't lock you out
- [ ] Privacy screen: app switcher shows a blank card; screenshots blocked
- [ ] Alerts > Hide message text: notification says only "New transmission"
- [ ] Host handover: host taps HOST on someone; everyone sees "HOST ROLE PASSED"; new host can REMOVE
- [ ] Danger zone > Erase this phone (type ERASE): app closes; reopening starts fresh with a new OP-ID

## Test this round (update ALL phones together)
- [ ] Private channel: send IMG, VID and VOX; only the recipient gets them; a third phone shows nothing
- [ ] Private media through a relay (A and C not directly linked, B relaying): arrives at C; B shows nothing
- [ ] Private media to someone not linked: "Can't reach … privately" and RETRY works once they link
- [ ] Long-press a photo and a video: menu opens; a normal tap still opens / plays
- [ ] ⋮ > LINK DIAGNOSTICS: WiFi, address, discovery and relay lines look right; turn WiFi off and it says NOT ON WIFI
- [ ] Guest / public WiFi with client isolation: ROUTER BLOCKING banner (internet link off makes it easier to see)
- [ ] Link by address: two phones on the same WiFi, type one's address into the other; they link
- [ ] Settings > Internet link off: NET links drop straight away; on again: they come back within ~30 s

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
