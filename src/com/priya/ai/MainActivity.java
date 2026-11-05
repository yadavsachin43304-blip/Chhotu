package com.priya.ai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/** Main screen: animated Priya UI + hamburger menu (3 designs, Permissions, API, Memory, Settings). */
public class MainActivity extends Activity implements ThemeView.Listener {

    private static final int REQ_START = 11;

    private FrameLayout root;
    private ThemeView view;
    private FrameLayout drawer;
    private View scrim;
    private LinearLayout panel;
    private boolean drawerOpen = false;
    private int panelW;
    private final LinearLayout[] tiles = new LinearLayout[3];

    private static boolean crashGuard = false;

    /** Background-thread errors are logged instead of killing the app (which threw the user out). */
    static void installCrashGuard(android.content.Context ctx) {
        if (crashGuard) return;
        crashGuard = true;
        final android.content.Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler orig = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                String st = sw.toString();
                Prefs.setLastCrash(app, th.getName() + ": " + (st.length() > 1500 ? st.substring(0, 1500) : st));
            } catch (Throwable ignored) { }
            if (th == android.os.Looper.getMainLooper().getThread() && orig != null) orig.uncaughtException(th, ex);
        });
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashGuard(this);
        try { AiTools.armLearning(this); } catch (Throwable ignored) { }
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        view = new ThemeView(this);
        view.setListener(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.setAvatarRectListener(this::placeAvatar3D);
        buildPowerButton();
        buildDrawer();
        setContentView(root);
        askStartupPermissions();
    }

    private static final int REQ_BOOT_PERMS = 52;

    /** v29: once per install, ask for audio-file (Studio) and notification permissions. */
    private void askStartupPermissions() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("priya_boot", MODE_PRIVATE);
            if (sp.getBoolean("asked_v29", false)) return;
            sp.edit().putBoolean("asked_v29", true).apply();
            ArrayList<String> need = new ArrayList<>();
            String audio = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO"
                    : Manifest.permission.READ_EXTERNAL_STORAGE;
            if (checkSelfPermission(audio) != PackageManager.PERMISSION_GRANTED) need.add(audio);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                need.add("android.permission.POST_NOTIFICATIONS");
            }
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_BOOT_PERMS);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ living portrait (falls back to pictures)
    // (v33: the procedural Avatar3DView heads are no longer used; Priya's own picture is animated instead)

    private LivePortraitView av3d;

    /** Adds / removes the live avatar to match the Settings toggle; pictures are used if GL is off or broke. */
    private void ensureAvatar3D() {
        boolean want = Prefs.avatar3D(this) && LivePortraitView.supported(this);
        if (want && av3d == null) {
            try {
                av3d = new LivePortraitView(this, false);
                av3d.setStyle(Prefs.theme(this));
                av3d.setFailListener(this::dropAvatar3D);
                root.addView(av3d, 1, new FrameLayout.LayoutParams(1, 1));
                view.setAvatar3D(true);
                placeAvatar3D();
            } catch (Throwable t) {
                LivePortraitView.failed = true;
                dropAvatar3D();
            }
        } else if (!want && av3d != null) {
            dropAvatar3D();
        }
    }

    private void dropAvatar3D() {
        if (av3d != null) {
            try { av3d.onPause(); } catch (Throwable ignored) { }
            try { root.removeView(av3d); } catch (Throwable ignored) { }
            av3d = null;
        }
        view.setAvatar3D(false);
    }

    private void placeAvatar3D() {
        if (av3d == null) return;
        android.graphics.RectF r = view.avatarRect();
        if (r.width() < 2 || r.height() < 2) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) av3d.getLayoutParams();
        int w = Math.round(r.width()), h = Math.round(r.height());
        int l = Math.round(r.left), t = Math.round(r.top);
        android.graphics.RectF full = view.avatarImageRect();   // the picture, relative to the GL view
        av3d.setImageRect(full.left - l, full.top - t, full.right - l, full.bottom - t);
        if (lp.width == w && lp.height == h && lp.leftMargin == l && lp.topMargin == t) return;
        lp.width = w; lp.height = h;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = l; lp.topMargin = t;
        av3d.setLayoutParams(lp);
    }

    // ------------------------------------------------------------------ front ON / OFF button

    private TextView power;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private int lastPowerState = -1;

    private void buildPowerButton() {
        power = Ui.text(this, "", 15, 0xFFFFFFFF, true);
        power.setGravity(Gravity.CENTER);
        int ph = Ui.dp(this, 16), pv = Ui.dp(this, 10);
        power.setPadding(ph, pv, ph, pv);
        power.setElevation(Ui.dp(this, 8));
        power.setOnClickListener(v -> onMic());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, Ui.dp(this, 14), Ui.dp(this, 22));
        root.addView(power, lp);
        refreshPower();
    }

    private void refreshPower() {
        boolean on = PriyaBus.running();
        int st = on ? 1 : 0;
        if (st != lastPowerState) {
            lastPowerState = st;
            power.setText(on ? "\u25CF  Priya ON" : "\u23FB  Priya OFF");
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 24));
            g.setColor(on ? 0xE61DB954 : 0xE6C62828);
            g.setStroke(Ui.dp(this, 1), 0x66FFFFFF);
            power.setBackground(g);
        }
    }

    private final Runnable powerTick = new Runnable() {
        @Override public void run() {
            refreshPower();
            ui.postDelayed(this, 300);
        }
    };

    @Override protected void onPause() {
        super.onPause();
        PriyaBus.appVisible = false;
        ui.removeCallbacks(powerTick);
        if (av3d != null) av3d.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        PriyaBus.appVisible = true;
        ensureAvatar3D();
        if (av3d != null) av3d.onResume();
        applyTheme(Prefs.theme(this));
        ui.removeCallbacks(powerTick);
        ui.post(powerTick);
        if (Prefs.apiKey(this).isEmpty() && !PriyaBus.running()) {
            PriyaBus.status = "Pehle Menu > API mein Gemini key daalo";
        } else if (!PriyaBus.running() && PriyaBus.state != PriyaBus.ERROR) {
            PriyaBus.status = "Mic dabao aur Priya se baat karo";
        }
    }

    /** Called when the user leaves the app (Home / app switch). Not called when we open our own screens. */
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!Prefs.alwaysOn(this) && PriyaBus.running()) stopPriya();
    }

    @Override public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ theme

    private void applyTheme(int t) {
        view.setTheme(t);
        if (av3d != null) { av3d.setStyle(t); placeAvatar3D(); }
        getWindow().setStatusBarColor(ThemeView.BG_TOP[t]);
        getWindow().setNavigationBarColor(ThemeView.BG_BOTTOM[t]);
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] != null) {
                tiles[i].setBackground(Ui.roundStroke(this, 0xFF151C33, i == t ? Ui.ACCENT : Ui.LINE, 14));
            }
        }
    }

    // ------------------------------------------------------------------ mic / service

    @Override public void onMic() {
        if (PriyaBus.running()) {
            stopPriya();
            return;
        }
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_START);
            return;
        }
        startPriya();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_START) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startPriya();
            } else {
                Toast.makeText(this, "Microphone permission ke bina Priya sun nahi sakti.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startPriya() {
        if (Prefs.apiKey(this).isEmpty()) {
            Toast.makeText(this, "Pehle Gemini API key daalo.", Toast.LENGTH_LONG).show();
            openPanel("api");
            return;
        }
        PriyaBus.state = PriyaBus.CONNECTING;
        PriyaBus.status = "Priya connect ho rahi hai…";
        try {
            startForegroundService(new Intent(this, PriyaService.class));
        } catch (Exception e) {
            PriyaBus.state = PriyaBus.ERROR;
            PriyaBus.status = "Priya start nahi ho paayi: " + e.getMessage();
        }
    }

    private void stopPriya() {
        startService(new Intent(this, PriyaService.class).setAction(PriyaService.ACTION_STOP));
    }

    // ------------------------------------------------------------------ top-bar buttons

    @Override public void onMenu() { if (drawerOpen) closeDrawer(); else openDrawer(); }
    @Override public void onGear() { openPanel("settings"); }
    @Override public void onProfile() { openPanel("memory"); }

    private void openPanel(String page) {
        Intent i = new Intent(this, PanelActivity.class);
        i.putExtra("page", page);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        closeDrawer();
        startActivity(i);
    }

    // ------------------------------------------------------------------ drawer menu

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setVisibility(View.GONE);

        scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> closeDrawer());
        drawer.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panelW = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF0E1428);
        int pad = Ui.dp(this, 16);
        panel.setPadding(pad, pad, pad, pad);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        Bitmap lb = loadAsset("icon.webp", 2);
        if (lb != null) logo.setImageBitmap(lb);
        head.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(Ui.dp(this, 12), 0, 0, 0);
        ht.addView(Ui.text(this, "Priya AI", 22, Ui.TEXT, true));
        ht.addView(Ui.text(this, "Gemini Live voice assistant", 12, Ui.SUB, false));
        head.addView(ht);
        panel.addView(head);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- UI design section (three designs)
        TextView sec = Ui.text(this, "UI DESIGN", 11, Ui.SUB, true);
        sec.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 8));
        list.addView(sec);
        LinearLayout tileRow = new LinearLayout(this);
        tileRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            int tp = Ui.dp(this, 6);
            tile.setPadding(tp, tp, tp, tp);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap tb = loadAsset("av_" + i + ".webp", 4);
            if (tb != null) iv.setImageBitmap(tb);
            tile.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 78)));
            TextView nm = Ui.text(this, Prefs.THEME_NAMES[i], 12, Ui.TEXT, true);
            nm.setGravity(Gravity.CENTER);
            tile.addView(nm);
            tile.setOnClickListener(v -> {
                Prefs.setTheme(this, idx);
                applyTheme(idx);
                closeDrawer();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            tileRow.addView(tile, lp);
            tiles[i] = tile;
        }
        list.addView(tileRow);

        // --- options
        TextView sec2 = Ui.text(this, "OPTIONS", 11, Ui.SUB, true);
        sec2.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 8));
        list.addView(sec2);
        list.addView(menuItem("🔐", "Permissions", "Mic, Accessibility, Overlay, Contacts, Calls…", v -> openPanel("permissions")));
        list.addView(menuItem("🔑", "API (Gemini)", "API key aur model save karo", v -> openPanel("api")));
        list.addView(menuItem("🧩", "Skills", "Priya ke skills dekho, naya skill banao", v -> openPanel("skills")));
        list.addView(menuItem("🔧", "Self-upgrade", "Priya khud ko update kare (Termux se)", v -> openPanel("selfup")));
        list.addView(menuItem("⏰", "Schedules", "Scheduled kaam: pause / resume / abhi chalao", v -> openBrain("schedules")));
        list.addView(menuItem("🎬", "Studio", "Audio file chuno — faceless video shuru karo", v -> openBrain("pick_audio")));
        list.addView(menuItem("🧠", "Memory (30 MB)", "Vaade, notes aur conversation text", v -> openPanel("memory")));
        list.addView(menuItem("⚙️", "Settings", "Always listening, echo guard, voice", v -> openPanel("settings")));

        drawer.addView(panel, new FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(drawer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openBrain(String page) {
        closeDrawer();
        try {
            startActivity(new Intent(this, BrainActivity.class).putExtra("page", page));
        } catch (Exception e) {
            Toast.makeText(this, "Screen nahi khula: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private View menuItem(String icon, String title, String sub, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.roundStroke(this, Ui.CARD, Ui.LINE, 14));
        int p = Ui.dp(this, 12);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, Ui.dp(this, 8));
        row.setLayoutParams(lp);

        TextView ic = Ui.text(this, icon, 22, Ui.TEXT, false);
        ic.setGravity(Gravity.CENTER);
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 40), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(Ui.text(this, title, 16, Ui.TEXT, true));
        tx.addView(Ui.text(this, sub, 12, Ui.SUB, false));
        row.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(l);
        return row;
    }

    private void openDrawer() {
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        if (av3d != null) av3d.setVisibility(View.INVISIBLE); // its surface sits above the window: hide under the menu
        panel.setTranslationX(-panelW);
        panel.animate().translationX(0).setDuration(210).start();
        scrim.animate().alpha(1f).setDuration(210).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        scrim.animate().alpha(0f).setDuration(180).start();
        panel.animate().translationX(-panelW).setDuration(180)
                .withEndAction(() -> {
                    if (!drawerOpen) {
                        drawer.setVisibility(View.GONE);
                        if (av3d != null) av3d.setVisibility(View.VISIBLE);
                    }
                }).start();
    }

    private Bitmap loadAsset(String name, int sample) {
        try (InputStream is = getAssets().open(name)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeStream(is, null, o);
        } catch (IOException e) {
            return null;
        }
    }
}
