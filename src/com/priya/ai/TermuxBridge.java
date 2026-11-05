package com.priya.ai;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs shell commands inside Termux through its RUN_COMMAND intent and waits for the result
 * (stdout, stderr, exit code) that Termux sends back through a PendingIntent to TermuxResultReceiver.
 */
final class TermuxBridge {

    static final String PKG = "com.termux";
    static final String PERM = "com.termux.permission.RUN_COMMAND";
    static final String HOME = "/data/data/com.termux/files/home";
    static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    /** Priya's coding workspace inside Termux home. */
    static final String ROOT = HOME + "/PriyaCode";
    static final String EXTRA_ID = "priya_exec_id";

    static final String SETUP_HELP =
            "TERMUX SETUP CHAHIYE. Sachin ko Hindi mein ye steps saaf-saaf batao (ek-ek karke):\n"
            + "1) Termux F-Droid se install karo (Play Store wala purana hai, mat lena): f-droid.org/packages/com.termux — chaho to open_link se khol do.\n"
            + "2) Termux kholo aur ye command chalao:  mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings\n"
            + "3) Phir:  termux-setup-storage   (popup aaye to Allow)\n"
            + "4) Phir:  pkg update -y && pkg install -y python nodejs git clang\n"
            + "5) Maya app > Menu > Permissions > 'Termux commands' par Allow dabao.\n"
            + "6) Phone Settings > Apps > Termux > Battery > Unrestricted (taaki background mein command chal sake).\n"
            + "Ye ho jaye to bolna 'ho gaya', main termux_check karke code chalana shuru karungi.";

    static final class Result {
        boolean ok;          // Termux ran it and returned
        int exitCode = -1;
        String stdout = "", stderr = "", err = "";
        boolean timedOut;
        String setupProblem; // non-null = Termux is not usable yet
    }

    private static final AtomicInteger SEQ = new AtomicInteger((int) (SystemClock.uptimeMillis() % 100000) + 5000);
    private static final ConcurrentHashMap<Integer, Bundle> RESULTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, CountDownLatch> WAITS = new ConcurrentHashMap<>();

    private TermuxBridge() {}

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean permitted(Context c) {
        return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    /** Called by TermuxResultReceiver on the main thread. */
    static void deliver(int id, Bundle result) {
        if (id < 0) return;
        RESULTS.put(id, result == null ? new Bundle() : result);
        CountDownLatch l = WAITS.get(id);
        if (l != null) l.countDown();
    }

    static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    /**
     * Runs `bash -c script arg0 args...` in Termux (background, no terminal window) and waits up to waitSec.
     */
    static Result bash(Context c, String script, String[] args, int waitSec) {
        Result res = new Result();
        if (!installed(c)) {
            res.setupProblem = "Termux phone par install nahi hai.\n" + SETUP_HELP;
            return res;
        }
        if (!permitted(c)) {
            res.setupProblem = "Maya ko 'Termux commands' (RUN_COMMAND) permission nahi mili.\n" + SETUP_HELP;
            return res;
        }
        int id = SEQ.incrementAndGet();
        String[] full = new String[2 + 1 + (args == null ? 0 : args.length)];
        full[0] = "-c";
        full[1] = script;
        full[2] = "priya";
        if (args != null) System.arraycopy(args, 0, full, 3, args.length);

        Intent back = new Intent(c, TermuxResultReceiver.class).putExtra(EXTRA_ID, id);
        int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= 0x02000000; // FLAG_MUTABLE: Termux fills in the result
        PendingIntent pi = PendingIntent.getBroadcast(c, id, back, flags);

        Intent i = new Intent();
        i.setClassName(PKG, "com.termux.app.RunCommandService");
        i.setAction("com.termux.RUN_COMMAND");
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", full);
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        i.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Maya AI");
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);

        CountDownLatch latch = new CountDownLatch(1);
        WAITS.put(id, latch);
        try {
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } catch (SecurityException se) {
                res.setupProblem = "Termux ne command lene se mana kiya (" + se.getMessage() + ").\n" + SETUP_HELP;
                return res;
            } catch (Exception e) {
                try {
                    c.startService(i);
                } catch (Exception e2) {
                    res.setupProblem = "Termux start nahi hua (" + e2.getClass().getSimpleName() + "). "
                            + "Ek baar Termux app khud kholo, phir dobara try karo.\n" + SETUP_HELP;
                    return res;
                }
            }
            boolean got;
            try { got = latch.await(Math.max(5, waitSec), TimeUnit.SECONDS); } catch (InterruptedException e) { got = false; }
            Bundle b = RESULTS.remove(id);
            if (!got || b == null) {
                res.timedOut = true;
                res.err = "Termux se " + waitSec + " sec mein jawab nahi aaya. Agar ye pehli baar hai to shayad "
                        + "allow-external-apps on nahi hai ya Termux battery-restricted hai.";
                return res;
            }
            res.ok = true;
            res.stdout = nz(b.getString("stdout"));
            res.stderr = nz(b.getString("stderr"));
            res.exitCode = b.getInt("exitCode", -1);
            int errCode = b.getInt("err", -1);
            String errmsg = nz(b.getString("errmsg"));
            if (errCode > 0 || (!errmsg.isEmpty() && res.exitCode == -1)) {
                res.err = errmsg;
                String low = errmsg.toLowerCase(java.util.Locale.ROOT);
                if (low.contains("allow-external-apps") || low.contains("permission")) {
                    res.ok = false;
                    res.setupProblem = "Termux ne bola: " + clip(errmsg, 300) + "\n" + SETUP_HELP;
                }
            }
            return res;
        } finally {
            WAITS.remove(id);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return "…(shuru ka hissa kaata)…\n" + s.substring(s.length() - max);
    }
}
