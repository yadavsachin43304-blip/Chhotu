package com.priya.ai;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Receives the PackageInstaller status of a self-upgrade. The system asks for the user's confirmation once
 * (STATUS_PENDING_USER_ACTION): we open that screen and SelfUpdate.watchInstaller taps "Update" for the owner who
 * just said yes. Protected file: Priya cannot edit it.
 */
public class InstallReceiver extends BroadcastReceiver {

    static final String ACTION = "com.priya.ai.SELF_INSTALL_STATUS";

    @Override public void onReceive(Context c, Intent i) {
        if (i == null) return;
        Context app = c.getApplicationContext();
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            boolean started = false;
            try {
                PriyaAccessibilityService a = PriyaAccessibilityService.get();
                if (a != null) a.startActivity(confirm); else app.startActivity(confirm);
                started = true;
            } catch (Throwable ignored) { }
            if (!started) {
                // background start refused: a tap on this notification opens the same confirm screen
                try {
                    PriyaJobs.ensureChannel(app);
                    PendingIntent open = PendingIntent.getActivity(app, 77, confirm, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    android.app.NotificationManager nm = (android.app.NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.notify(0x5e20, new android.app.Notification.Builder(app, PriyaJobs.CHANNEL)
                            .setContentTitle("Maya ka update taiyaar hai")
                            .setContentText("Tap karke 'Update' dabayein")
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentIntent(open)
                            .setAutoCancel(true)
                            .build());
                } catch (Throwable ignored) { }
            }
            SelfUpdate.watchInstaller();
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            // the process is replaced; the new version announces itself from ScheduleReceiver (MY_PACKAGE_REPLACED)
        } else {
            SelfUpdate.installing = false;
            PriyaJobs.notifyOwner(app, "⚠️ Maya ka update install nahi hua",
                    (msg == null ? "Android ne install roka (code " + status + ")." : msg)
                            + "\nPurana version waisa hi chal raha hai. Phir koshish karne ke liye Maya se bolo.", 0x5e21);
        }
    }
}
