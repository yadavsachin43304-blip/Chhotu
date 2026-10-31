#!/usr/bin/env bash
# Chhotu Master emulator test (Android 14 x86_64 image, x86_64 test APK).
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

RECV="$PKG/com.ai.assistance.operit.core.chhotu.ChhotuSelfTestReceiver"
SEL_TAGS="ChhotuSelfTest:* ChhotuBrain:* ChhotuLlmClient:* ChhotuLlmProc:* LlamaProvider:*"
CRASH_RE="Cmdline: com\.sachin\.chhotu\.master$|Process: com\.sachin\.chhotu\.master,"

# ---------- 2. prepare (skip onboarding / permission level) ----------
adb logcat -c
adb shell am broadcast -a com.sachin.chhotu.SELFTEST_PREPARE -n "$RECV" | tee -a "$OUT/prepare-broadcast.txt"
PREP=""
for i in $(seq 1 24); do
  if adb logcat -d -s ChhotuSelfTest:* | grep -qE "CHHOTU_SELFTEST_PREPARED|CHHOTU_SELFTEST_PREPARE_FAIL"; then
    PREP=$(adb logcat -d -s ChhotuSelfTest:* | grep -m1 -E "CHHOTU_SELFTEST_PREPARED|CHHOTU_SELFTEST_PREPARE_FAIL")
    break
  fi
  sleep 5
done
if echo "$PREP" | grep -q "CHHOTU_SELFTEST_PREPARED"; then
  pass "prepare: $(echo "$PREP" | sed 's/.*CHHOTU_SELFTEST_PREPARED/PREPARED/' | cut -c1-200)"
else
  info "prepare not confirmed: ${PREP:-no CHHOTU_SELFTEST_PREPARED within 2 min}"
fi
adb shell am force-stop "$PKG" || true
sleep 2

# ---------- 3. launch ----------
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 45
MAIN_PID=$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
adb exec-out screencap -p > "$OUT/launch.png"
if [ -n "$MAIN_PID" ]; then
  pass "launch (main pid $MAIN_PID, alive after 45 s)"
else
  fail "launch: process not running after 45 s"
  adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" -A 25 | head -80 | tee -a "$REPORT"
  finish
fi

# run_selftest <name> <extra am args...>: broadcast SELFTEST, wait up to 10 min for END, save log to $OUT/selftest-<name>.txt
run_selftest() {
  local name="$1"; shift
  adb logcat -d -b crash >> "$OUT/crash-accum.txt" 2>/dev/null || true
  adb logcat -d -v time >> "$OUT/logcat-before-$name.txt" 2>/dev/null || true
  adb logcat -c
  adb shell am broadcast -a com.sachin.chhotu.SELFTEST -n "$RECV" "$@" | tee -a "$OUT/selftest-broadcast.txt"
  for i in $(seq 1 60); do
    if adb logcat -d -s ChhotuSelfTest:* | grep -q "CHHOTU_SELFTEST_END"; then
      break
    fi
    sleep 10
  done
  adb logcat -d -s $SEL_TAGS > "$OUT/selftest-$name.txt"
  echo "---- selftest $name ----"
  grep -E "CHHOTU_" "$OUT/selftest-$name.txt" | cut -c1-500
  grep -q "CHHOTU_SELFTEST_END" "$OUT/selftest-$name.txt" || info "selftest $name: no CHHOTU_SELFTEST_END within 10 min"
}

check_llm() {
  local f="$OUT/selftest-$1.txt"
  if grep -q "CHHOTU_SELFTEST_LLM_OK" "$f"; then
    pass "[$1] offline brain reply: $(grep -m1 CHHOTU_SELFTEST_LLM_OK "$f" | sed 's/.*CHHOTU_SELFTEST_LLM_OK//' | cut -c1-300)"
  elif grep -q "CHHOTU_BRAIN_OFFLINE_REPLY_DONE" "$f"; then
    pass "[$1] offline brain reply done (CHHOTU_BRAIN_OFFLINE_REPLY_DONE)"
  else
    fail "[$1] offline brain: $(grep -m1 -E 'CHHOTU_SELFTEST_LLM_FAIL|CHHOTU_SELFTEST_CRASH' "$f" | cut -c1-300)"
  fi
}

# ---------- 4. self-test: offline brain + Hindi TTS ----------
run_selftest main
check_llm main
if grep -q "CHHOTU_SELFTEST_TTS_OK" "$OUT/selftest-main.txt"; then
  pass "TTS synthesised speech: $(grep -m1 CHHOTU_SELFTEST_TTS_OK "$OUT/selftest-main.txt" | sed 's/.*CHHOTU_SELFTEST_TTS_OK//')"
else
  fail "TTS self-test: $(grep -m1 -E 'CHHOTU_SELFTEST_TTS_(FAIL|ENGINE)' "$OUT/selftest-main.txt" | cut -c1-300)"
fi
grep -m1 "CHHOTU_SELFTEST_TTS_ENGINE" "$OUT/selftest-main.txt" | sed 's/.*CHHOTU_SELFTEST_TTS_ENGINE/INFO: TTS engine/' | tee -a "$REPORT"

# ---------- 5. crash probe: native crash in :llm must not kill the main process ----------
run_selftest crashprobe --ez llm true --ez tts false --ez crash_probe true
if grep -q "CHHOTU_SELFTEST_CRASHSAFE_OK" "$OUT/selftest-crashprobe.txt"; then
  pass "crash-safe: $(grep -m1 CHHOTU_SELFTEST_CRASHSAFE_OK "$OUT/selftest-crashprobe.txt" | sed 's/.*CHHOTU_SELFTEST_CRASHSAFE_OK//' | cut -c1-200)"
else
  fail "crash-safe probe: $(grep -m1 CHHOTU_SELFTEST_CRASHSAFE_FAIL "$OUT/selftest-crashprobe.txt" | cut -c1-300)"
fi

# ---------- 6. LLM again after the :llm crash (service must recover) ----------
run_selftest after-crash --ez tts false
check_llm after-crash

# ---------- 7. UI chat (info only) ----------
timeout 600 python3 ci/chhotu/ui_drive.py "$PKG" "$OUT" "Namaste Chhotu, ek line mein apna parichay do" | tee "$OUT/ui_drive.txt"
UI_RC=${PIPESTATUS[0]}
if [ "$UI_RC" -eq 0 ]; then
  info "UI: chat screen opened and a message was sent"
  for i in $(seq 1 60); do
    if adb logcat -d -s ChhotuBrain:* | grep -qE "CHHOTU_BRAIN_OFFLINE_REPLY_DONE|CHHOTU_BRAIN_MODE=NONE"; then
      break
    fi
    sleep 10
  done
  adb logcat -d -s ChhotuBrain:* > "$OUT/brain-ui.txt"
  adb exec-out screencap -p > "$OUT/chat_reply.png"
  grep -q "CHHOTU_BRAIN_OFFLINE_REPLY_DONE" "$OUT/brain-ui.txt" && info "UI chat got an offline reply" || info "UI chat offline reply not confirmed in log"
else
  info "UI: chat screen not reached by UI driver (rc $UI_RC, see ui_*.png) - not a hard failure"
fi

# ---------- 8. stability ----------
PID_END=$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
if [ -n "$PID_END" ] && [ "$PID_END" = "$MAIN_PID" ]; then
  pass "main process unchanged and alive (pid $MAIN_PID)"
else
  fail "main process changed or died (start $MAIN_PID, end ${PID_END:-none})"
fi
adb logcat -d -b crash >> "$OUT/crash-accum.txt" 2>/dev/null || true
if grep -qE "$CRASH_RE" "$OUT/crash-accum.txt"; then
  fail "crash log contains the main process $PKG"
  grep -E -A 20 "$CRASH_RE" "$OUT/crash-accum.txt" | head -60 | tee -a "$REPORT"
else
  pass "no crash recorded for main process $PKG"
  grep -q "$PKG:llm" "$OUT/crash-accum.txt" && info "expected :llm crash(es) recorded (crash probe)"
fi

finish
