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
- [ ] Cancel / retry for IMG and VID transfers
- [ ] Faster sharing in big nodes (one slow phone slows everyone; 25 copies uploaded)
- [ ] Strip XMP location data from original photos
- [ ] Per-node mute
- [ ] Clearer "can't link" diagnostics; manual IP fallback; router client-isolation warning
- [ ] Battery check overnight
- [ ] Tie-break if two operatives rotate the key at the same instant; host verification via the invite
- [ ] Emoji reactions (optional); long-press menu directly on IMG / VID
- [ ] Glow effect on panels (the mockups' soft neon halo; skipped in the app for now)

## Test the internet link (needs 2+ phones on DIFFERENT networks)
- [ ] Phone A on home WiFi, phone B on mobile data: link to the same node; header shows LINK 2/25 within about 30 s
- [ ] Operatives list shows "VIA NET"; put both on the same WiFi and it should switch to "VIA WIFI" after relinking
- [ ] Text, IMG, VOX and a short VID over NET; check TX/RX progress
- [ ] Remove someone over NET: key rotates; a phone that was offline catches up when it comes back
- [ ] Settings > Internet link OFF, restart: only WiFi links remain
- [ ] Note which network combinations fail to link (some mobile carriers and work/school WiFi block direct links)

## Internet link: known limits and next steps
- [ ] **No relay fallback yet:** roughly 10-20% of network pairs (strict carrier or corporate firewalls) can't link directly. Fix: an optional encrypted TURN relay (would need a small server, or a free TURN service)
- [ ] Full mesh: every phone uploads its own copy of each item to every other phone. Fine for small nodes; for 25 operatives on mobile data, add relaying through other phones (gossip)
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
