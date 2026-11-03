# CyberTxTs changelog

Each release's notes are the section under its version number. The Release workflow copies them onto the GitHub release.

## 1.1.0

Sound and music.

### Audio files in chats
- **Send music, podcasts or any audio file** from ⋮ > SEND AUDIO FILE: MP3, M4A, AAC, OGG, Opus, FLAC, WAV and more, up to 100 MB and 4 hours. Files go exactly as picked, with no re-compressing.
- Each shows its **title, artist, length and type**, with **play / pause and a seek bar**.
- **Keeps playing** when you scroll, leave the chat or switch screens. A now-playing strip at the top of every screen lets you pause or stop. It pauses for calls, voice notes and other apps, and when headphones are unplugged.
- Works everywhere photos and videos do: nodes, contacts, private channels (sealed to one person), the offline queue for contacts, catch-up, forwarding, search (by title, artist or file name) and the MEDIA gallery.
- Long-press > **SAVE TO MUSIC** keeps a copy in your phone's Music/CyberTxTs folder. Settings > Chats & media > **Keep AUD** chooses whether others' audio files stay after a restart.

### Call sounds
- **Choose your ringtone:** Comm ring (new), Orbit (new), your phone's default, any sound on your phone, or silent. It loops until you answer.
- **Vibrates** when ringing (can be switched off).
- **Ringing tone while you wait** for someone to answer, and short tones when a call **connects, ends, or you mute**.
- **Follows silent mode and Do Not Disturb:** the call screen still appears, quietly.

### App sounds
- **Choose your message tone** inside the app: Comm chirp, Pulse, Beacon, Sonar, Glint (all new except the chirp), your phone's default, any sound on your phone, or silent.
- **A different tone for each node or contact:** ⋮ > ALERT TONE in that chat.
- Optional **quiet sounds inside an open chat** when you send or receive (off by default).
- All of this lives in Settings > **Sounds & alerts**.

Phones on 1.0.x simply don't show audio files sent from 1.1.0; update everyone together.

## 1.0.1

Fixes for the first round of phone testing.

- **Call buttons can be reached again.** On many phones the Answer, Mute, Speaker and End call buttons sat underneath the home / back / app buttons. Calls (and every other full-screen page: Settings, photo and video viewers, Constellation, the QR scanner) now keep clear of the navigation bar, camera notch and keyboard.
- **Roomier call controls.** Video calls show two buttons per row (Mute and Speaker, then Camera and Flip camera), and an incoming call puts Decline on its own row so it's hard to hit by mistake.
- **QR scanner focus.** Focus is now aimed at the scan frame instead of the whole picture. Tap anywhere on the camera picture (or the FOCUS button) to refocus straight away, and use CLOSE-UP on phones that support it for codes held near the lens.

## 1.0.0

The first full release of CyberTxTs: a private messenger with no accounts, no phone numbers, no servers of its own and no Google services. Phones link straight to each other over WiFi or the internet, and everything is end-to-end encrypted and signed.

### Nodes and contacts
- **Nodes:** group chats that live only on the members' phones. Spin one up, invite with a QR code or link (with host verification), kick or vote-kick, hand over the host role. Keys rotate automatically.
- **Contacts:** 1-to-1 direct links with no shared node needed. Add someone by scanning each other's QR in person, from a node, or with a contact link.
- **Private channels** inside a node, sealed to one person.
- **Works anywhere:** WiFi discovery on the same network, the internet anywhere else (direct WebRTC links, found through public Nostr relays that only see encrypted notes), and relaying through a shared operative when two phones can't reach each other. Link diagnostics and link-by-address when a router gets in the way.

### Messaging
- Photos (4 qualities, location stripped), videos up to 5 minutes (with Original quality on request), voice notes up to 2 minutes. Cancel, retry and queue for offline contacts.
- Replies, reactions, edit (with edit time), retract for everyone, forwarding, pins, media gallery, @mentions, polls.
- Delivery and read marks, typing signal (both can be turned off).
- Disappearing messages (1 hour, 1 day, 1 week), drafts, scheduled messages, search in a chat or across everything.
- Catch-up: phones that were away get the last week of messages they missed.

### Calls
- 1-to-1 voice and video calls, end-to-end encrypted, that keep going with the screen off or when you leave the app.

### Your identity and safety
- Signed per-phone identity with a 32-character fingerprint to compare in person, and a look-alike warning for copycat callsigns.
- Recovery phrase (16 words), encrypted backup file, and move-to-a-new-phone by QR, each able to lock a lost phone out of every node.
- App lock with PIN, fingerprint or face; privacy screen; message text hidden on the lock screen and optionally in every alert.
- Reply, mark read and mute from the notification.
- Battery and data tracker in Settings.

### Look and feel
- Holo Glass design with Night ops, Day shift and Match device themes, the comm chirp alert sound, and the new "Signal" app icon.
- Accessibility pass: spoken message headers, labelled controls, larger touch targets.

### Good to know
- CyberTxTs has no servers to recover your account from. Set a recovery phrase or keep a backup file, or a lost phone means a new identity.
- Internet links show your IP address to the operatives you link with directly. Turn off the internet link in Settings > Network to stay WiFi-only.
