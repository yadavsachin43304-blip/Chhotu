#!/data/data/com.termux/files/usr/bin/bash
# Priya AI - APK build script for Termux (no Gradle, no Android Studio).
# Usage:  bash build.sh
set -euo pipefail
cd "$(dirname "$0")"
shopt -s nullglob

MIN_SDK=26
TARGET_SDK=33
OUT=build
KS=priya-release.jks
KS_PASS=priyaai123
# Every build gets a HIGHER versionCode than the last one (minutes since 2026-01-01), because Android never installs
# an older versionCode over a newer one - this is what makes self-upgrade AND rollback installable.
VERSION_CODE="${VERSION_CODE:-$(( ($(date +%s) - 1767225600) / 60 ))}"
[ "$VERSION_CODE" -ge 2 ] 2>/dev/null || VERSION_CODE=2
VERSION_NAME="${VERSION_NAME:-35.$(date +%y%m%d.%H%M)}"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARN: %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- find tools
find_tool() {                      # find_tool <name>  -> prints path or nothing
  local n="$1" p
  p="$(command -v "$n" 2>/dev/null || true)"
  if [ -n "$p" ]; then echo "$p"; return; fi
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" "$HOME/Android/Sdk"; do
    [ -n "$root" ] || continue
    for f in "$root"/build-tools/*/"$n"; do [ -x "$f" ] && { echo "$f"; return; }; done
  done
}

AAPT2="$(find_tool aapt2)"
D8="$(find_tool d8)"
APKSIGNER="$(find_tool apksigner)"
ZIPALIGN="$(find_tool zipalign)"
KEYTOOL="$(find_tool keytool)"
JAVAC="$(find_tool javac)"
ZIP="$(find_tool zip)"

missing=""
[ -n "$AAPT2" ]     || missing="$missing aapt2"
[ -n "$D8" ]        || missing="$missing d8"
[ -n "$APKSIGNER" ] || missing="$missing apksigner"
[ -n "$JAVAC" ]     || missing="$missing javac(openjdk-21)"
[ -n "$KEYTOOL" ]   || missing="$missing keytool(openjdk-21)"
[ -n "$ZIP" ]       || missing="$missing zip"
if [ -n "$missing" ]; then
  die "Ye tools nahi mile:$missing
       Termux mein chalao:  pkg install -y openjdk-21 aapt2 apksigner d8 zip
       (agar koi package 'not found' bataye to:  pkg search <naam>)"
fi
[ -n "$ZIPALIGN" ] || { command -v python3 >/dev/null 2>&1 || warn "zipalign aur python3 dono nahi hain - APK install na ho to:  pkg install python"; }

# ---------------------------------------------------------------- android.jar
jar_ok() { [ -f "$1" ] && [ "$(wc -c < "$1" | tr -d ' ')" -gt 500000 ]; }   # an empty / broken jar is skipped, not used
ANDROID_JAR="${ANDROID_JAR:-}"
if [ -z "$ANDROID_JAR" ] || ! jar_ok "$ANDROID_JAR"; then
  ANDROID_JAR=""
  for c in "$PWD/tools/android.jar" "${PREFIX:-/nonexistent}/share/aapt/android.jar" \
           "$HOME"/android-sdk/platforms/android-*/android.jar \
           "${ANDROID_HOME:-/nonexistent}"/platforms/android-*/android.jar; do
    jar_ok "$c" && { ANDROID_JAR="$c"; break; }
  done
fi
if [ -z "$ANDROID_JAR" ]; then
  say "android.jar nahi mila - download kar raha hoon (tools/android.jar)"
  mkdir -p tools
  for url in \
    "https://github.com/Sable/android-platforms/raw/master/android-33/android.jar" \
    "https://github.com/Reginer/aosp-android-jar/raw/main/android-33/android.jar"; do
    if curl -fL --retry 2 -o tools/android.jar "$url" && jar_ok tools/android.jar; then
      ANDROID_JAR="$PWD/tools/android.jar"; break
    fi
    rm -f tools/android.jar
  done
fi
[ -n "$ANDROID_JAR" ] || die "android.jar nahi mila. Kisi bhi API 30-34 ka android.jar 'tools/android.jar' naam se is folder mein rakh do aur dobara chalao."
echo "android.jar : $ANDROID_JAR"

# ---------------------------------------------------------------- build
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/compiled"

say "1/6 Resources compile (aapt2)"
"$AAPT2" compile --dir res -o "$OUT/compiled/res.zip"

say "2/6 Manifest + resources + assets link (aapt2)"
"$AAPT2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml -A assets \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --auto-add-overlay \
  "$OUT/compiled/res.zip"

say "3/6 Java compile (javac)"
# JDK 21's javac writes nameless "MethodParameters" entries; Termux's d8 (R8 3.3.x) crashes on them with
# "NullPointerException: Cannot invoke String.length() ... <parameter1> is null". They are only debug info, so we remove them.
PARAM_FLAG=""
command -v python3 >/dev/null 2>&1 || { warn "python3 nahi mila (pkg install python) - '-parameters' fallback use kar raha hoon"; PARAM_FLAG="-parameters"; }
# shellcheck disable=SC2046
"$JAVAC" --release 8 $PARAM_FLAG -Xlint:-options -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR" -d "$OUT/classes" $(find src -name '*.java')
if command -v python3 >/dev/null 2>&1; then
python3 - "$OUT/classes" <<'PY'
import os, struct, sys

def u2(d, p): return (d[p] << 8) | d[p + 1]
def u4(d, p): return struct.unpack('>I', d[p:p + 4])[0]

def attrs(d, p):
    n = u2(d, p); p += 2; res = []
    for _ in range(n):
        ln = u4(d, p + 2)
        res.append((u2(d, p), p, p + 6 + ln)); p += 6 + ln
    return res, p

def strip(path):
    d = open(path, 'rb').read()
    if d[:4] != b'\xca\xfe\xba\xbe': return 0
    p = 10; cnt = u2(d, 8); utf = {}; i = 1
    while i < cnt:
        t = d[p]
        if t == 1:
            ln = u2(d, p + 1); utf[i] = d[p + 3:p + 3 + ln]; p += 3 + ln
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): p += 5
        elif t in (5, 6): p += 9; i += 1
        elif t in (7, 8, 16, 19, 20): p += 3
        elif t == 15: p += 4
        else: raise ValueError('bad constant tag %d' % t)
        i += 1
    out = bytearray(d[:p]); out += d[p:p + 6]; p += 6
    ic = u2(d, p); out += d[p:p + 2 + 2 * ic]; p += 2 + 2 * ic
    removed = 0
    for kind in (0, 1):                      # 0 = fields, 1 = methods
        n = u2(d, p); out += d[p:p + 2]; p += 2
        for _ in range(n):
            out += d[p:p + 6]; p += 6
            al, q = attrs(d, p)
            keep = [a for a in al if not (kind == 1 and utf.get(a[0]) == b'MethodParameters')]
            removed += len(al) - len(keep)
            out += struct.pack('>H', len(keep))
            for a in keep: out += d[a[1]:a[2]]
            p = q
    out += d[p:]
    if removed: open(path, 'wb').write(bytes(out))
    return removed

total = files = 0
for root, _, names in os.walk(sys.argv[1]):
    for nm in names:
        if nm.endswith('.class'):
            files += 1; total += strip(os.path.join(root, nm))
print('d8-fix: %d MethodParameters entries removed (%d class files)' % (total, files))
PY
fi

say "4/6 DEX (d8)"
# shellcheck disable=SC2046
"$D8" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
( cd "$OUT/dex" && "$ZIP" -q ../unsigned.apk ./*.dex )

say "5/6 Align"
if [ -n "$ZIPALIGN" ]; then
  "$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
elif command -v python3 >/dev/null 2>&1; then
  python3 align.py "$OUT/unsigned.apk" "$OUT/aligned.apk"
else
  cp "$OUT/unsigned.apk" "$OUT/aligned.apk"
fi

say "6/6 Sign (apksigner)"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias priya -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Priya AI, O=Priya, C=IN"
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out PriyaAI.apk "$OUT/aligned.apk"
"$APKSIGNER" verify PriyaAI.apk && echo "signature OK"

say "Done: $PWD/PriyaAI.apk  ($(du -h PriyaAI.apk | cut -f1))  version $VERSION_NAME (code $VERSION_CODE)"
if [ -d /sdcard/Download ] && cp PriyaAI.apk /sdcard/Download/PriyaAI.apk 2>/dev/null; then
  echo "Copy bhi ho gaya: /sdcard/Download/PriyaAI.apk"
fi
echo "Install karne ke liye:  termux-open PriyaAI.apk   (ya Files app se PriyaAI.apk par tap karo)"
