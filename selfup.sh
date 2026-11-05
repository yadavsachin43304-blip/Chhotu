#!/data/data/com.termux/files/usr/bin/bash
# Priya AI - self-upgrade helper (runs in Termux). The Priya app calls this; you can run it by hand too.
#
#   bash selfup.sh status        tools + source check
#   bash selfup.sh snapshot      copy the current source to ~/PriyaSelf/backups/<time> (once per upgrade)
#   bash selfup.sh diff          what changed since the snapshot
#   bash selfup.sh build         guard check + build.sh  -> PriyaAI.apk
#   bash selfup.sh apkinfo       sha256 / size / versionCode of the built apk
#   bash selfup.sh guard         verify that the protected safety files were NOT changed
#   bash selfup.sh guard-reset   (ONLY YOU run this by hand) accept your own edits of protected files
#   bash selfup.sh rollback      put the snapshot back and rebuild (old behaviour, NEW version number)
#   bash selfup.sh finalize      upgrade is installed: close the snapshot
#   bash selfup.sh serve <apk> <port>   hand the apk to the Priya app over 127.0.0.1 (used by install)
#
# Priya can NOT edit this file: it is on the protected list below and in the app.
set -u
SRC="$(cd "$(dirname "$0")" && pwd)"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
SELF="$HOME_DIR/PriyaSelf"
BK="$SELF/backups"; LOG="$SELF/log"; STATE="$SELF/state"; APKS="$SELF/apk"
mkdir -p "$BK" "$LOG" "$STATE" "$APKS" 2>/dev/null

# Safety / identity layer + build tooling. Keep in sync with SelfUpdate.PROTECTED in the app.
PROTECTED="selfup.sh build.sh align.py priya-release.jks \
src/com/priya/ai/ActionExecutor.java src/com/priya/ai/HeadlessAgent.java src/com/priya/ai/SelfUpdate.java \
src/com/priya/ai/PriyaService.java src/com/priya/ai/Owner.java src/com/priya/ai/SpeakerVerifier.java \
src/com/priya/ai/TermuxBridge.java src/com/priya/ai/InstallReceiver.java \
src/com/priya/ai/AgentStore.java src/com/priya/ai/AgentRunner.java"

cmd="${1:-status}"; shift || true

need_src() {
  if [ ! -f "$SRC/build.sh" ] || [ ! -d "$SRC/src/com/priya/ai" ]; then
    echo "SRC_BAD: $SRC mein build.sh ya src/com/priya/ai nahi mila"; exit 2
  fi
}

case "$cmd" in
status)
  need_src
  echo "SRC=$SRC"
  echo "JAVA_FILES=$(find "$SRC/src" -name '*.java' | wc -l)"
  for t in aapt2 d8 apksigner javac keytool zip python3 sha256sum; do
    if command -v "$t" >/dev/null 2>&1; then echo "tool:$t=ok"; else echo "tool:$t=MISSING"; fi
  done
  if [ -f "$SRC/.guard.sha256" ]; then echo "GUARD=present"; else echo "GUARD=missing"; fi
  if [ -f "$STATE/snapshot" ]; then echo "SNAPSHOT=$(cat "$STATE/snapshot")"; else echo "SNAPSHOT=none"; fi
  if [ -f "$STATE/last_snapshot" ]; then echo "LAST_SNAPSHOT=$(cat "$STATE/last_snapshot")"; fi
  if [ -f "$STATE/last_build" ]; then echo "LAST_BUILD=$(cat "$STATE/last_build")"; else echo "LAST_BUILD=none"; fi
  ;;

snapshot)
  need_src
  if [ -f "$STATE/snapshot" ] && [ -d "$BK/$(cat "$STATE/snapshot")" ]; then
    echo "SNAPSHOT_EXISTS $(cat "$STATE/snapshot")"; exit 0
  fi
  ts="$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$BK/$ts"
  ( cd "$SRC" && tar cf - --exclude=./build --exclude=./tools --exclude='./*.apk' . ) | ( cd "$BK/$ts" && tar xf - ) || { echo "SNAPSHOT_FAILED"; exit 4; }
  echo "$ts" > "$STATE/snapshot"
  rm -f "$STATE/last_build"
  # keep the newest 8 backups only (this script's own housekeeping)
  ls -1dt "$BK"/*/ 2>/dev/null | tail -n +9 | while read -r d; do rm -rf "$d"; done
  echo "SNAPSHOT_OK $ts"
  ;;

diff)
  need_src
  snap="$(cat "$STATE/snapshot" 2>/dev/null || true)"
  [ -n "$snap" ] && [ -d "$BK/$snap" ] || { echo "NO_SNAPSHOT: abhi koi badlav shuru nahi hua"; exit 0; }
  echo "=== FILES ==="
  diff -rq -x build -x tools -x '*.apk' -x .git "$BK/$snap" "$SRC" | sed "s#$BK/$snap#BEFORE#; s#$SRC#NOW#"
  echo "=== LINES ==="
  diff -ru -x build -x tools -x '*.apk' -x .git "$BK/$snap" "$SRC" | grep -E '^(\+\+\+|[-+][^-+])' | head -c 7000
  ;;

guard)
  need_src
  if [ ! -f "$SRC/.guard.sha256" ]; then echo "GUARD_MISSING: .guard.sha256 nahi hai. Aap khud chalao:  bash $SRC/selfup.sh guard-reset"; exit 5; fi
  out="$(cd "$SRC" && sha256sum -c .guard.sha256 2>&1 | grep -v ': OK$' || true)"
  if [ -n "$out" ]; then echo "GUARD_FAIL: protected file badli hui hai:"; echo "$out"; exit 5; fi
  echo "GUARD_OK"
  ;;

guard-reset)
  need_src
  ( cd "$SRC" && for f in $PROTECTED; do [ -f "$f" ] && sha256sum "$f"; done > .guard.sha256 )
  echo "GUARD_RESET done ($(wc -l < "$SRC/.guard.sha256") files)"
  ;;

build)
  need_src
  bash "$SRC/selfup.sh" guard > "$LOG/guard.txt" 2>&1 || { echo "GUARD_FAIL"; cat "$LOG/guard.txt"; exit 5; }
  cd "$SRC" || exit 2
  rm -f "$SRC/PriyaAI.apk"
  start="$(date +%s)"
  bash ./build.sh > "$LOG/build.log" 2>&1
  rc=$?
  if [ "$rc" -ne 0 ] || [ ! -s "$SRC/PriyaAI.apk" ]; then
    rm -f "$STATE/last_build"
    echo "BUILD_FAILED rc=$rc"
    grep -E 'error:|ERROR|cannot find symbol|FAILED|Exception|symbol:|location:' "$LOG/build.log" | head -40
    echo "--- log tail ---"
    tail -n 20 "$LOG/build.log"
    exit 3
  fi
  vc="?"
  if command -v aapt2 >/dev/null 2>&1; then
    vc="$(aapt2 dump badging "$SRC/PriyaAI.apk" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
    [ -n "$vc" ] || vc="?"
  fi
  size="$(wc -c < "$SRC/PriyaAI.apk" | tr -d ' ')"
  echo "versionCode=$vc size=$size built=$(date +%s) secs=$(( $(date +%s) - start ))" > "$STATE/last_build"
  cp "$SRC/PriyaAI.apk" "$APKS/PriyaAI-$vc.apk" 2>/dev/null
  echo "BUILD_OK versionCode=$vc size=$size"
  ;;

apkinfo)
  need_src
  [ -s "$SRC/PriyaAI.apk" ] || { echo "NO_APK"; exit 2; }
  echo "sha256=$(sha256sum "$SRC/PriyaAI.apk" | cut -d' ' -f1) size=$(wc -c < "$SRC/PriyaAI.apk" | tr -d ' ') path=$SRC/PriyaAI.apk"
  ;;

serve)
  # serve <apk> <port>: one-shot hand-over of the apk to the app (8-byte big-endian length, then the bytes)
  apk="${1:-}"; port="${2:-0}"
  [ -s "$apk" ] || { echo "NO_APK"; exit 2; }
  python3 - "$apk" "$port" <<'PY'
import socket, sys
apk, port = sys.argv[1], int(sys.argv[2])
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", port))
s.listen(1)
s.settimeout(60)
c, _ = s.accept()
data = open(apk, "rb").read()
c.sendall(len(data).to_bytes(8, "big") + data)
c.close()
s.close()
print("SERVED", len(data))
PY
  ;;

rollback)
  need_src
  snap="$(cat "$STATE/snapshot" 2>/dev/null || cat "$STATE/last_snapshot" 2>/dev/null || true)"
  [ -n "$snap" ] && [ -d "$BK/$snap" ] || { echo "NO_SNAPSHOT: wapas jaane ke liye koi backup nahi mila"; exit 2; }
  rb="$SELF/rolled_back/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$rb"
  # files that exist now but not in the backup are moved aside (kept, not deleted)
  ( cd "$SRC" && find . \( -path ./build -o -path ./tools -o -path ./.git \) -prune -o -type f ! -name '*.apk' -print ) | while read -r f; do
    if [ ! -e "$BK/$snap/$f" ]; then mkdir -p "$rb/$(dirname "$f")"; mv "$SRC/$f" "$rb/$f"; fi
  done
  ( cd "$BK/$snap" && tar cf - . ) | ( cd "$SRC" && tar xf - ) || { echo "RESTORE_FAILED"; exit 4; }
  echo "RESTORED from $snap (moved-aside files: $rb)"
  bash "$SRC/selfup.sh" build
  ;;

finalize)
  if [ -f "$STATE/snapshot" ]; then mv "$STATE/snapshot" "$STATE/last_snapshot"; echo "FINALIZED $(cat "$STATE/last_snapshot")"; else echo "NOTHING_TO_FINALIZE"; fi
  ;;

*)
  echo "unknown command: $cmd"; exit 64
  ;;
esac
