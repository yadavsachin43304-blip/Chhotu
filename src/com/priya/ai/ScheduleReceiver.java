package com.priya.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires scheduled tasks and re-arms all alarms after boot / app update / exact-alarm permission change. */
public class ScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? "" : String.valueOf(i.getAction());
        if (Scheduler.ACTION_RUN.equals(a)) {
            String id = i.getStringExtra("id");
            if (id != null) TaskRunnerService.runSchedule(c, id);
            return;
        }
        // BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, MY_PACKAGE_REPLACED, SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        try { Scheduler.rearmAll(c.getApplicationContext()); } catch (Throwable ignored) { }
    }
}
