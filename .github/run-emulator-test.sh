#!/bin/bash
set -x
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
D=/sdcard/Android/data/com.sachin.chhotu/files/models
adb shell mkdir -p $D
adb push models/chhotu-brain-gemma4-universal.litertlm $D/
adb push models/kokoro-int8-multi-lang-v1_0 $D/
adb shell ls -la $D $D/kokoro-int8-multi-lang-v1_0
adb logcat -c
adb shell am instrument -w -e class com.sachin.chhotu.ChhotuTest com.sachin.chhotu.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt
adb pull /sdcard/Android/data/com.sachin.chhotu/files/test-report.txt test-report.txt
adb logcat -d -s CHHOTU_TEST:* litert:* AndroidRuntime:* DEBUG:* > logcat.txt
grep -q "OK (1 test)" instrument.txt
