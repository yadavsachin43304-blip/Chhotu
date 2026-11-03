package com.priya.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives Termux RUN_COMMAND results (bundle "result": stdout, stderr, exitCode, err, errmsg). */
public class TermuxResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        TermuxBridge.deliver(intent.getIntExtra(TermuxBridge.EXTRA_ID, -1), intent.getBundleExtra("result"));
    }
}
