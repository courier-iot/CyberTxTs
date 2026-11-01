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

## Internet phase (later)
- [ ] WebRTC transport with serverless discovery (Nostr relays or BitTorrent trackers) keyed by a hash of the node code
- [ ] STUN, plus optional encrypted relay fallback
- [ ] Gossip design for 25-operative nodes over mobile data
- [ ] Per-node WiFi-only / internet switch

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
