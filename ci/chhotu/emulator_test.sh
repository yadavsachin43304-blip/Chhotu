#!/usr/bin/env bash
# Chhotu Master emulator test (Android 14, x86_64 image with ARM64 translation).
# Runs inside reactivecircus/android-emulator-runner after the emulator has booted.
set -u

PKG=com.sachin.chhotu.master
OUT=out
mkdir -p "$OUT"
REPORT="$OUT/test-report.txt"
: > "$REPORT"
FAILED=0

pass() { echo "PASS: $*" | tee -a "$REPORT"; }
fail() { echo "FAIL: $*" | tee -a "$REPORT"; FAILED=1; }
info() { echo "INFO: $*" | tee -a "$REPORT"; }

finish() {
  adb logcat -d -v time > "$OUT/logcat-full.txt" 2>/dev/null || true
  adb logcat -d -b crash > "$OUT/logcat-crash.txt" 2>/dev/null || true
  adb exec-out screencap -p > "$OUT/final.png" 2>/dev/null || true
  echo "================ TEST REPORT ================"
  cat "$REPORT"
  if [ "$FAILED" -eq 0 ]; then
    echo "CHHOTU EMULATOR TEST: PASS" | tee -a "$REPORT"
  else
    echo "CHHOTU EMULATOR TEST: FAIL" | tee -a "$REPORT"
  fi
  exit "$FAILED"
}

info "abilist: $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
info "android: $(adb shell getprop ro.build.version.release | tr -d '\r') sdk $(adb shell getprop ro.build.version.sdk | tr -d '\r')"

APK=$(ls apk/*.apk | head -1)
info "apk: $APK ($(du -h "$APK" | cut -f1))"

# ---------- 1. install ----------
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1
cat "$OUT/install.txt"
if grep -q "Success" "$OUT/install.txt"; then
  pass "install"
else
  fail "install: $(tail -3 "$OUT/install.txt" | tr '\n' ' ')"
  finish
fi

adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true

# tiny offline model so the offline brain can be verified (real phone uses Qwen3-1.7B-MNN etc.)
adb shell mkdir -p /sdcard/Download/Operit/models/llama
for m in models/*.gguf; do
  adb push "$m" /sdcard/Download/Operit/models/llama/ > /dev/null && info "pushed offline test model $(basename "$m")"
done

# ---------- 2. launch ----------
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 45
PID=$(adb shell pidof "$PKG" | tr -d '\r')
adb exec-out screencap -p > "$OUT/launch.png"
if [ -n "$PID" ]; then
  pass "launch (pid $PID, alive after 45 s)"
else
  fail "launch: process not running after 45 s"
  adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" -A 25 | head -80 | tee -a "$REPORT"
  finish
fi

# ---------- 3. open chat + send a message through the real UI ----------
python3 ci/chhotu/ui_drive.py "$PKG" "$OUT" "Namaste Chhotu, ek line mein apna parichay do" | tee "$OUT/ui_drive.txt"
UI_RC=${PIPESTATUS[0]}
if [ "$UI_RC" -eq 0 ]; then
  pass "chat screen opened and a message was sent from the chat input"
  # wait for the offline brain (no API key on the emulator -> must use on-device model)
  for i in $(seq 1 80); do
    if adb logcat -d -s ChhotuBrain:* | grep -qE "CHHOTU_BRAIN_OFFLINE_REPLY_DONE|CHHOTU_BRAIN_MODE=NONE"; then
      break
    fi
    sleep 15
  done
  adb logcat -d -s ChhotuBrain:* > "$OUT/brain-ui.txt"
  adb exec-out screencap -p > "$OUT/chat_reply.png"
  if grep -q "CHHOTU_BRAIN_MODE=OFFLINE" "$OUT/brain-ui.txt"; then
    pass "UI chat turn auto-routed to OFFLINE brain ($(grep -m1 CHHOTU_BRAIN_MODE=OFFLINE "$OUT/brain-ui.txt" | sed 's/.*reason=//'))"
  else
    info "UI chat turn did not log the offline route"
  fi
  if grep -q "CHHOTU_BRAIN_OFFLINE_REPLY_DONE" "$OUT/brain-ui.txt"; then
    pass "UI chat got an offline reply"
    adb logcat -d | grep "Final llama.cpp output" | tail -2 | cut -c1-400 | tee -a "$REPORT"
  else
    info "UI chat offline reply not confirmed in log"
  fi
  if adb logcat -d | grep -q "speak request"; then
    pass "app TTS was asked to speak the reply (auto-read on)"
    adb logcat -d | grep "speak request" | tail -1 | cut -c1-300 | tee -a "$REPORT"
  else
    info "no in-app TTS speak request seen"
  fi
else
  fail "chat screen not reached by UI driver (see ui_*.png)"
fi

# ---------- 4. self-test: offline brain + Hindi TTS ----------
adb logcat -d -b crash >> "$OUT/crash-accum.txt" 2>/dev/null || true
adb logcat -d -v time > "$OUT/logcat-part1.txt" 2>/dev/null || true
adb logcat -c
adb shell am broadcast -a com.sachin.chhotu.SELFTEST -n "$PKG/com.ai.assistance.operit.core.chhotu.ChhotuSelfTestReceiver" | tee -a "$OUT/selftest-broadcast.txt"
for i in $(seq 1 60); do
  if adb logcat -d -s ChhotuSelfTest:* | grep -q "CHHOTU_SELFTEST_END"; then
    break
  fi
  sleep 15
done
adb logcat -d -s ChhotuSelfTest:* ChhotuBrain:* LlamaProvider:* > "$OUT/selftest.txt"
cat "$OUT/selftest.txt" | grep -E "CHHOTU_" | cut -c1-500
if grep -q "CHHOTU_SELFTEST_LLM_OK" "$OUT/selftest.txt"; then
  pass "offline brain reply (no key -> on-device model): $(grep -m1 CHHOTU_SELFTEST_LLM_OK "$OUT/selftest.txt" | sed 's/.*CHHOTU_SELFTEST_LLM_OK//' | cut -c1-300)"
else
  fail "offline brain self-test: $(grep -m1 -E 'CHHOTU_SELFTEST_LLM_FAIL|CHHOTU_SELFTEST_CRASH' "$OUT/selftest.txt" | cut -c1-300)"
fi
if grep -q "CHHOTU_SELFTEST_TTS_OK" "$OUT/selftest.txt"; then
  pass "TTS synthesised speech: $(grep -m1 CHHOTU_SELFTEST_TTS_OK "$OUT/selftest.txt" | sed 's/.*CHHOTU_SELFTEST_TTS_OK//')"
else
  info "TTS self-test not OK on emulator: $(grep -m1 -E 'CHHOTU_SELFTEST_TTS_(FAIL|ENGINE)' "$OUT/selftest.txt" | cut -c1-300)"
fi
grep -m1 "CHHOTU_SELFTEST_TTS_ENGINE" "$OUT/selftest.txt" | sed 's/.*CHHOTU_SELFTEST_TTS_ENGINE/INFO: TTS engine/' | tee -a "$REPORT"

# ---------- 5. stability ----------
PID2=$(adb shell pidof "$PKG" | tr -d '\r')
if [ -n "$PID2" ]; then pass "app still running at end (pid $PID2)"; else fail "app not running at end"; fi
adb logcat -d -b crash >> "$OUT/crash-accum.txt" 2>/dev/null || true
if grep -q "$PKG" "$OUT/crash-accum.txt"; then
  fail "crash log contains $PKG"
  grep -A 20 "$PKG" "$OUT/crash-accum.txt" | head -60 | tee -a "$REPORT"
else
  pass "no crash recorded for $PKG"
fi

finish
