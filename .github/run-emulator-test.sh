#!/bin/bash
set -x
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# App ki apni files me model rakhna (Android 11+ me seedha push mana hai, isliye /data/local/tmp -> run-as)
adb push models/chhotu-brain-gemma4-universal.litertlm /data/local/tmp/
(cd models && tar cf ../kokoro.tar kokoro-int8-multi-lang-v1_0)
adb push kokoro.tar /data/local/tmp/
adb shell chmod 644 /data/local/tmp/chhotu-brain-gemma4-universal.litertlm /data/local/tmp/kokoro.tar
D=/sdcard/Android/data/com.sachin.chhotu/files/models
adb shell am start -n com.sachin.chhotu/.MainActivity; sleep 5; adb shell am force-stop com.sachin.chhotu
adb shell run-as com.sachin.chhotu mkdir -p $D
adb shell run-as com.sachin.chhotu cp /data/local/tmp/chhotu-brain-gemma4-universal.litertlm $D/
adb shell run-as com.sachin.chhotu sh -c "'cd $D && tar xf /data/local/tmp/kokoro.tar'"
adb shell run-as com.sachin.chhotu ls -la $D $D/kokoro-int8-multi-lang-v1_0
adb shell rm /data/local/tmp/chhotu-brain-gemma4-universal.litertlm /data/local/tmp/kokoro.tar
adb logcat -c
adb shell am instrument -w -e class com.sachin.chhotu.ChhotuTest com.sachin.chhotu.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt
adb shell run-as com.sachin.chhotu cat /sdcard/Android/data/com.sachin.chhotu/files/test-report.txt > test-report.txt
cat test-report.txt
adb logcat -d -s CHHOTU_TEST:* litert:* AndroidRuntime:* DEBUG:* > logcat.txt
grep -q "OK (1 test)" instrument.txt
