#!/usr/bin/env bash
# Fonts (all SIL Open Font License, free to bundle in apps), downloaded at build time:
# Orbitron (titles), Rajdhani (text), Share Tech Mono (readouts). Licences go into the app too.
# Run from the CyberTxTs folder.
set -euo pipefail
python3 -m pip install --quiet --user --break-system-packages fonttools
F=app/src/main/res/font; L=app/src/main/assets/licenses; T=$(mktemp -d)
mkdir -p "$F" "$L"
g=https://github.com/google/fonts/raw/main/ofl
curl -fsSL -o "$T/orbitron.ttf" "$g/orbitron/Orbitron%5Bwght%5D.ttf"
# Orbitron only ships as a variable font; cut fixed Medium and Bold copies for Android
python3 -m fontTools.varLib.instancer "$T/orbitron.ttf" wght=500 -o "$F/orbitron_medium.ttf"
python3 -m fontTools.varLib.instancer "$T/orbitron.ttf" wght=700 -o "$F/orbitron_bold.ttf"
curl -fsSL -o "$F/rajdhani_medium.ttf"   "$g/rajdhani/Rajdhani-Medium.ttf"
curl -fsSL -o "$F/rajdhani_semibold.ttf" "$g/rajdhani/Rajdhani-SemiBold.ttf"
curl -fsSL -o "$F/rajdhani_bold.ttf"     "$g/rajdhani/Rajdhani-Bold.ttf"
curl -fsSL -o "$F/share_tech_mono.ttf"   "$g/sharetechmono/ShareTechMono-Regular.ttf"
curl -fsSL -o "$L/Orbitron-OFL.txt"      "$g/orbitron/OFL.txt"
curl -fsSL -o "$L/Rajdhani-OFL.txt"      "$g/rajdhani/OFL.txt"
curl -fsSL -o "$L/ShareTechMono-OFL.txt" "$g/sharetechmono/OFL.txt"
ls -la "$F"
