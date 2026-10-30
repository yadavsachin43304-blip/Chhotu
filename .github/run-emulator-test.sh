#!/bin/bash
set -x
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# App ki apni files me model rakhna (Android 11+ me seedha push mana hai, isliye /data/local/tmp -> run-as)
adb push models/chhotu-brain-gemma4-universal.litertlm /data/local/tmp/
(cd models && tar cf ../voice.tar vits-piper-hi_IN-pratham-medium)
adb push voice.tar /data/local/tmp/
adb shell chmod 644 /data/local/tmp/chhotu-brain-gemma4-universal.litertlm /data/local/tmp/voice.tar
adb logcat -G 16M; adb logcat -c
adb shell am instrument -w -e class com.sachin.chhotu.ChhotuTest com.sachin.chhotu.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt
adb logcat -d -s CHHOTU_TEST:* AndroidRuntime:* DEBUG:* > logcat.txt
adb logcat -d -s CHHOTU_TEST:I -v raw > test-report.txt
cat test-report.txt
adb shell run-as com.sachin.chhotu ls files 2>/dev/null; adb pull /sdcard/Android/data/com.sachin.chhotu/files/voice-sample.wav . || true
grep -q "OK (1 test)" instrument.txt
