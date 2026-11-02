# CyberTxTs

**By Stellarite.** Serverless, end-to-end encrypted group comms for Android. Phones link directly to each other, on the same WiFi or across the internet. There is no CyberTxTs server.

## Features
- **Nodes** (chat rooms): spin one up (you're the host) or link to one with its node code + access key. Be linked to several at once; switch from the ≡ nodes menu. Up to 25 operatives each.
- **Node codes:** a space word + 7 digits, e.g. `NEBULA-0482917`. The access key is what keeps a node private.
- **Identity:** each install has a hardware-backed key; your OP-ID comes from it and every message is signed. Your **callsign** is chosen once at first launch and is permanent. Give other operatives private **nicknames** only you see.
- **Encryption:** everything is end-to-end encrypted with a key derived from the access key. After someone is removed (host kick or majority vote), the access key rotates automatically for everyone else; offline operatives catch up when they reconnect.
- **Media:** IMG (Saver / Standard / High / Original), VID up to 5 minutes (360p / 480p / 720p), VOX notes up to 2 minutes (never stored). TX/RX progress; every piece is verified.
- **Internet link:** phones find each other through public Nostr relays (they only ever see encrypted notes under a hash of the node code), then talk directly over WebRTC. WiFi is used instead when both phones share it.
- **No Google services:** works on de-Googled phones (QR scanning via ZXing, Cloudflare STUN).
- **Invites:** QR codes and tap-to-link `cybertxts://` links that pin the host's fingerprint (no host spoofing).
- **Chat extras:** private channels (sealed to one operative), ✓ / ✓✓ / READ marks, typing indicator, reactions, edit and retract, per-node mute, cancel / retry transfers.
- **Star Map:** see who's linked and how (WiFi / net / relayed), delays and live activity; a full-mesh view with warnings for broken pairs; a Constellation of all your nodes.
- **Relaying:** operatives who can't link directly are reached through someone linked to both (optional, on by default).
- **Holds link** in the background with a notification; comm-chirp message alerts.
- **Message log** saved encrypted on the device only, with auto-purge options.
- **Look:** Holo Glass. Deep-space Night ops theme (neon cyan for you, crimson for others) or Day shift; open clipped-corner panels; log-style names (`VEGA//7F3A · 21:04`); IMG / VOX / TX readouts; Orbitron, Rajdhani and Share Tech Mono fonts.

## Repo layout
```
.github/workflows/build.yml             builds the APK on every push to main (fetches fonts, signs with your key)
.github/workflows/make-signing-key.yml  run once to create the permanent signing key
CyberTxTs/                              Android project (Kotlin + Jetpack Compose), package com.cybertxts.app
  build.gradle.kts, settings.gradle.kts, gradle.properties (4 GB build memory)
  app/build.gradle.kts                  app config and libraries
  app/src/main/java/com/cybertxts/app/
    CyberApp.kt         app startup; owns the one long-lived ChatViewModel
    ChatViewModel.kt    callsign, settings, list of nodes
    NodeSession.kt      one node: messages, operatives, kicks/votes, key rotation, media sending
    LanTransport.kt     WiFi discovery (mDNS) + encrypted direct links
    InternetTransport.kt  internet links: WebRTC data channels, plus MultiTransport (WiFi + internet together)
    Nostr.kt            relay discovery (public Nostr relays) and the small Schnorr signer relays require
    Transport.kt        the swappable transport interface; 25-operative limit
    Crypto.kt           AES-GCM, PBKDF2, sealing keys to operatives
    Identity.kt         per-install keys and signing
    People.kt           permanent callsigns + your private nicknames
    Media.kt            IMG/VID compression, original photos, VOX recorder/player, encrypted media store
    Storage.kt          settings and encrypted message log
    SessionService.kt   background link + notifications
    MainActivity.kt     all screens except the maps
    StarMap.kt          Star Map tab, operative card, Constellation
    Invite.kt           invite links and QR codes
    Scanner.kt          QR scan screen (ZXing, no Google services)
    Theme.kt            Night ops / Day shift colours, fonts, open-corner panels, orbit mark, starfield
  app/src/main/res/     planet icon, splash, notification icon, comm chirp sound
TODO.md                 to-do list, test checklists, signing steps
```

## Build & install
Push to `main`, then Actions > newest "Build APK" run > download **CyberTxTs-apk** > install `CyberTxTs.apk`.
Signing setup steps are at the bottom of TODO.md.
