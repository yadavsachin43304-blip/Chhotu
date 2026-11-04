package com.priya.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One activity for the menu pages: permissions, api, memory, settings. */
public class PanelActivity extends Activity {

    private static final int REQ_PERMS = 21;

    private String page = "permissions";
    private LinearLayout content;
    private boolean rendered = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String p = getIntent().getStringExtra("page");
        if (p != null) page = p;
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        TextView back = Ui.text(this, "←", 26, Ui.TEXT, true);
        back.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        bar.addView(Ui.text(this, titleFor(page), 20, Ui.TEXT, true));
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, Ui.dp(this, 4), pad, pad * 2);
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        PriyaBus.appVisible = true;
        if (!rendered || "permissions".equals(page)) {
            render();
            rendered = true;
        }
    }

    private static String titleFor(String p) {
        switch (p) {
            case "api": return "API (Gemini)";
            case "memory": return "Memory (30 MB)";
            case "settings": return "Settings";
            case "skills": return "Skills";
            default: return "Permissions";
        }
    }

    private void render() {
        content.removeAllViews();
        switch (page) {
            case "api": renderApi(); break;
            case "memory": renderMemory(); break;
            case "settings": renderSettings(); break;
            case "skills": renderSkills(); break;
            default: renderPermissions(); break;
        }
    }

    @Override protected void onPause() {
        super.onPause();
        PriyaBus.appVisible = false;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void start(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Ye screen is phone par nahi khul paayi.");
        }
    }

    // ================================================================== permissions

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean accessibilityOn(Context c) {
        if (PriyaAccessibilityService.get() != null) return true;
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/");
    }

    private void permCard(String title, String desc, boolean ok, String btnLabel, View.OnClickListener l) {
        LinearLayout c = Ui.card(this);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, title, 16, Ui.TEXT, true);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(this, ok ? "✓ Allowed" : "✗ Off", 13, ok ? Ui.GOOD : Ui.BAD, true));
        c.addView(head);
        TextView d = Ui.text(this, desc, 12.5f, Ui.SUB, false);
        d.setPadding(0, Ui.dp(this, 4), 0, 0);
        c.addView(d);
        if (!ok && btnLabel != null && l != null) {
            Button b = Ui.button(this, btnLabel, Ui.ACCENT, 0xFF06202A);
            c.addView(b, Ui.matchWrap(this, 10));
            b.setOnClickListener(l);
        }
        content.addView(c);
    }

    private void renderPermissions() {
        TextView intro = Ui.text(this,
                "Priya ko poori tarah kaam karne ke liye ye saari permissions chahiye. Har card par status dikh raha hai.",
                13, Ui.SUB, false);
        intro.setPadding(0, 0, 0, Ui.dp(this, 10));
        content.addView(intro);

        Button all = Ui.button(this, "Saari runtime permissions ek saath do", Ui.ACCENT, 0xFF06202A);
        all.setOnClickListener(v -> {
            List<String> need = new ArrayList<>();
            String[] all1 = {Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE};
            for (String p : all1) if (!has(p)) need.add(p);
            if (Build.VERSION.SDK_INT >= 33 && !has("android.permission.POST_NOTIFICATIONS")) {
                need.add("android.permission.POST_NOTIFICATIONS");
            }
            if (TermuxBridge.installed(this) && !has(TermuxBridge.PERM)) need.add(TermuxBridge.PERM);
            if (need.isEmpty()) toast("Saari runtime permissions pehle se allowed hain.");
            else requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        });
        content.addView(all, Ui.matchWrap(this, 0));
        Button appInfo = Ui.button(this, "App settings kholo (Allow restricted settings ke liye)", Ui.CARD, Ui.TEXT);
        appInfo.setOnClickListener(v -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        content.addView(appInfo, Ui.matchWrap(this, 8));
        View gap = new View(this);
        content.addView(gap, new LinearLayout.LayoutParams(1, Ui.dp(this, 14)));

        permCard("Microphone", "Priya ki awaaz sunne ke liye (RECORD_AUDIO).",
                has(Manifest.permission.RECORD_AUDIO), "Allow karo",
                v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS));

        if (Build.VERSION.SDK_INT >= 33) {
            permCard("Notifications", "Background listening ka notification (aur Stop button) dikhane ke liye.",
                    has("android.permission.POST_NOTIFICATIONS"), "Allow karo",
                    v -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_PERMS));
        }

        permCard("Accessibility Service",
                "Phone control: app kholna, tap, type, scroll, drag, CapCut editing, screen dekhna, YouTube par gana chalana.\n"
                        + "Update ke baad screenshot (look_at_screen) ke liye ise ek baar OFF karke phir ON karo.\n"
                        + "Android 13+ par agar switch grey ho to: App settings > ⋮ (teen dot) > 'Allow restricted settings', phir yahan wapas aao.",
                accessibilityOn(this), "Accessibility settings kholo",
                v -> start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        permCard("Display over other apps",
                "Background se apps/YouTube kholne ke liye zaroori (Android background-launch rule).",
                Settings.canDrawOverlays(this), "Allow karo",
                v -> start(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        permCard("Battery: Unrestricted",
                "Always-listening ke dauran Android Priya ko band na kare.",
                pm.isIgnoringBatteryOptimizations(getPackageName()), "Allow karo",
                v -> start(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));

        permCard("Contacts", "'Mummy ko call lagao' jaise commands mein naam dhundhne ke liye (READ_CONTACTS).",
                has(Manifest.permission.READ_CONTACTS), "Allow karo",
                v -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_PERMS));

        permCard("Phone calls", "Call seedha laga dene ke liye (CALL_PHONE). Priya call se pehle aapse confirm karegi.",
                has(Manifest.permission.CALL_PHONE), "Allow karo",
                v -> requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQ_PERMS));

        boolean termux = TermuxBridge.installed(this);
        permCard("Termux commands (Coding)",
                termux ? "Priya Termux mein code likh kar chala sake (RUN_COMMAND). Termux mein ek baar ye bhi chalana:\n"
                        + "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings\n"
                        + "termux-setup-storage\npkg install -y python nodejs git clang"
                        : "Coding ke liye Termux chahiye — F-Droid se install karo (Play Store wala purana hai). Install ke baad yahan wapas aakar Allow karo.",
                termux && has(TermuxBridge.PERM), termux ? "Allow karo" : "Termux download (F-Droid)",
                v -> {
                    if (TermuxBridge.installed(this)) requestPermissions(new String[]{TermuxBridge.PERM}, REQ_PERMS);
                    else start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")));
                });

        permCard("Install-time permissions",
                "Ye install ke saath apne aap mil jaati hain:\n"
                        + "• INTERNET  • ACCESS_NETWORK_STATE\n"
                        + "• FOREGROUND_SERVICE  • FOREGROUND_SERVICE_MICROPHONE\n"
                        + "• MODIFY_AUDIO_SETTINGS (echo-cancel / speaker routing)\n"
                        + "• WAKE_LOCK (background listening)",
                true, null, null);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERMS && "permissions".equals(page)) render();
    }

    // ================================================================== API

    private TextView[] voiceChips;
    private String chosenVoice;

    private TextView chip(String label, boolean selected) {
        TextView t = Ui.text(this, label, 13, selected ? 0xFF06202A : Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 10);
        t.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        t.setBackground(selected ? Ui.round(this, Ui.ACCENT, 20) : Ui.roundStroke(this, Ui.CARD, Ui.LINE, 20));
        return t;
    }

    private void styleChip(TextView t, boolean selected) {
        t.setTextColor(selected ? 0xFF06202A : Ui.TEXT);
        t.setBackground(selected ? Ui.round(this, Ui.ACCENT, 20) : Ui.roundStroke(this, Ui.CARD, Ui.LINE, 20));
    }

    private void renderApi() {
        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.text(this, "Gemini API key", 16, Ui.TEXT, true));
        TextView h = Ui.text(this, "Key sirf is phone mein save hoti hai. Google AI Studio se free key milti hai.", 12.5f, Ui.SUB, false);
        h.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        c1.addView(h);
        final EditText key = new EditText(this);
        key.setText(Prefs.apiKey(this));
        key.setHint("AIza…");
        key.setHintTextColor(Ui.SUB);
        key.setTextColor(Ui.TEXT);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c1.addView(key, Ui.matchWrap(this, 0));
        LinearLayout kr = new LinearLayout(this);
        kr.setOrientation(LinearLayout.HORIZONTAL);
        Button show = Ui.button(this, "Show / Hide", Ui.CARD, Ui.TEXT);
        show.setOnClickListener(v -> {
            boolean hidden = (key.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            key.setInputType(InputType.TYPE_CLASS_TEXT | (hidden
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            key.setSelection(key.getText().length());
        });
        Button get = Ui.button(this, "Key banao ↗", Ui.CARD, Ui.ACCENT);
        get.setOnClickListener(v -> start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l1.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 6), 0);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l2.setMargins(0, Ui.dp(this, 6), 0, 0);
        kr.addView(show, l1);
        kr.addView(get, l2);
        c1.addView(kr);
        content.addView(c1);

        LinearLayout c2 = Ui.card(this);
        c2.addView(Ui.text(this, "Gemini Live model", 16, Ui.TEXT, true));
        TextView mh = Ui.text(this, "Agar ye model na chale to Priya khud agla model try karti hai.", 12.5f, Ui.SUB, false);
        mh.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        c2.addView(mh);
        final EditText model = new EditText(this);
        model.setText(Prefs.model(this));
        model.setTextColor(Ui.TEXT);
        model.setSingleLine(true);
        c2.addView(model, Ui.matchWrap(this, 0));
        for (final String m : Prefs.MODELS) {
            TextView ch = chip(m, false);
            ch.setOnClickListener(v -> model.setText(m));
            c2.addView(ch, Ui.matchWrap(this, 6));
        }
        content.addView(c2);

        LinearLayout c3 = Ui.card(this);
        c3.addView(Ui.text(this, "Priya ki awaaz (female voice)", 16, Ui.TEXT, true));
        chosenVoice = Prefs.VOICE;
        TextView vh = Ui.text(this, "Ek hi pakki female awaaz: " + Prefs.VOICE
                + ". Har connection / reconnect par yahi awaaz rehti hai, kabhi nahi badalti.", 12.5f, Ui.SUB, false);
        vh.setPadding(0, Ui.dp(this, 4), 0, 0);
        c3.addView(vh);
        content.addView(c3);

        Button save = Ui.button(this, "Save karo", Ui.ACCENT, 0xFF06202A);
        save.setOnClickListener(v -> {
            Prefs.setApiKey(this, key.getText().toString());
            String m = model.getText().toString().trim();
            Prefs.setModel(this, m.isEmpty() ? Prefs.MODELS[0] : m);
            Prefs.setVoice(this, chosenVoice);
            toast("Save ho gaya ✓");
        });
        content.addView(save, Ui.matchWrap(this, 2));

        final TextView result = Ui.text(this, "", 13, Ui.SUB, false);
        Button test = Ui.button(this, "Connection test karo", Ui.CARD, Ui.TEXT);
        test.setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            String m = model.getText().toString().trim();
            if (k.isEmpty()) { result.setTextColor(Ui.BAD); result.setText("Pehle key daalo."); return; }
            testConnection(k, m.isEmpty() ? Prefs.MODELS[0] : m, chosenVoice, result);
        });
        content.addView(test, Ui.matchWrap(this, 8));
        result.setPadding(0, Ui.dp(this, 8), 0, 0);
        content.addView(result);
    }

    private void testConnection(final String key, final String model, String voice, final TextView out) {
        out.setTextColor(Ui.SUB);
        out.setText("Test chal raha hai…");
        final boolean[] done = {false};
        final GeminiLiveClient[] holder = new GeminiLiveClient[1];
        holder[0] = new GeminiLiveClient(key, model, voice, "Test.", false, "", new GeminiLiveClient.Callback() {
            @Override public void onSetupComplete() {
                ui.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    out.setTextColor(Ui.GOOD);
                    out.setText("✓ Connected — key aur model (" + model + ") sahi hain.");
                    holder[0].close();
                });
            }
            @Override public void onAudio(byte[] pcm24k) { }
            @Override public void onInputText(String text) { }
            @Override public void onOutputText(String text) { }
            @Override public void onTurnComplete() { }
            @Override public void onInterrupted() { }
            @Override public void onToolCall(String id, String name, JSONObject args) { }
            @Override public void onResumeHandle(String handle) { }
            @Override public void onGoAway() { }
            @Override public void onClosed(final int code, final String reason, boolean afterSetup) {
                ui.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    out.setTextColor(Ui.BAD);
                    String r = reason == null ? "" : reason;
                    out.setText("✗ Fail (" + code + "): " + (r.length() > 300 ? r.substring(0, 300) + "…" : r));
                });
            }
        });
        holder[0].connect();
        ui.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            out.setTextColor(Ui.BAD);
            out.setText("✗ Timeout — internet ya model check karo.");
            holder[0].close();
        }, 12000);
    }

    // ================================================================== memory

    private void renderMemory() {
        final MemoryStore mem = new MemoryStore(this);

        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.text(this, "Storage", 16, Ui.TEXT, true));
        long used = mem.usedBytes();
        double usedMb = used / (1024.0 * 1024.0);
        TextView u = Ui.text(this, String.format(Locale.US, "%.2f MB / 30 MB used", usedMb), 13, Ui.SUB, false);
        u.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        c1.addView(u);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        pb.setProgress((int) Math.min(1000, used * 1000 / MemoryStore.CAP_BYTES));
        c1.addView(pb, Ui.matchWrap(this, 0));
        TextView info = Ui.text(this,
                "Notes (vaade/facts): " + mem.notesBytes() / 1024 + " KB • Conversation: " + mem.conversationBytes() / 1024
                        + " KB\n30 MB bharne par sabse purani conversation apne aap hat jaati hai; notes/vaade sabse aakhir mein hatte hain.",
                12, Ui.SUB, false);
        info.setPadding(0, Ui.dp(this, 8), 0, 0);
        c1.addView(info);
        content.addView(c1);

        LinearLayout c2 = Ui.card(this);
        c2.addView(Ui.text(this, "Search", 16, Ui.TEXT, true));
        final EditText q = new EditText(this);
        q.setHint("Keyword, jaise: vaada, mummy, meeting");
        q.setHintTextColor(Ui.SUB);
        q.setTextColor(Ui.TEXT);
        q.setSingleLine(true);
        c2.addView(q, Ui.matchWrap(this, 4));
        final TextView res = Ui.text(this, "", 12.5f, Ui.TEXT, false);
        res.setTextIsSelectable(true);
        Button go = Ui.button(this, "Dhundho", Ui.ACCENT, 0xFF06202A);
        go.setOnClickListener(v -> {
            String r = mem.search(q.getText().toString(), 15);
            res.setText(r.isEmpty() ? "Kuch nahi mila." : r);
        });
        c2.addView(go, Ui.matchWrap(this, 6));
        res.setPadding(0, Ui.dp(this, 8), 0, 0);
        c2.addView(res);
        content.addView(c2);

        LinearLayout c3 = Ui.card(this);
        c3.addView(Ui.text(this, "Saved vaade / notes (latest 40)", 16, Ui.TEXT, true));
        List<String> notes = mem.recentNotes(40);
        TextView nt = Ui.text(this, notes.isEmpty() ? "Abhi kuch save nahi hai. Priya se bolo: \"ye yaad rakhna…\""
                : TextUtilsJoin(notes), 12.5f, Ui.TEXT, false);
        nt.setPadding(0, Ui.dp(this, 6), 0, 0);
        nt.setTextIsSelectable(true);
        c3.addView(nt);
        content.addView(c3);

        Button clearConv = Ui.button(this, "Conversation log saaf karo", Ui.CARD, Ui.TEXT);
        clearConv.setOnClickListener(v -> confirm("Sirf conversation log delete hoga. Notes/vaade bache rahenge.", () -> {
            mem.clearConversation();
            render();
        }));
        content.addView(clearConv, Ui.matchWrap(this, 2));
        Button clearAll = Ui.button(this, "Poori memory saaf karo", Ui.CARD, Ui.BAD);
        clearAll.setOnClickListener(v -> confirm("Notes, vaade aur conversation sab delete ho jayenge.", () -> {
            mem.clearAll();
            render();
        }));
        content.addView(clearAll, Ui.matchWrap(this, 8));
    }

    private static String TextUtilsJoin(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append("\n\n");
        return sb.toString().trim();
    }

    private void confirm(String msg, final Runnable yes) {
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("Haan", (d, w) -> yes.run())
                .setNegativeButton("Nahi", null)
                .show();
    }

    // ================================================================== settings

    private void renderSettings() {
        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.switchRow(this, "Background mein hamesha sunna",
                "ON: app band/minimise karne par bhi Priya sunti rahegi (notification dikhega). OFF: sirf app khula ho tab.",
                Prefs.alwaysOn(this), (b, on) -> Prefs.setAlwaysOn(this, on)));
        c1.addView(Ui.switchRow(this, "Echo guard (khud ki awaaz na sune)",
                "Priya bolte waqt mic stream band rehta hai. OFF karne par aap beech mein tok sakte ho, par echo ka risk badhta hai.",
                Prefs.strictEcho(this), (b, on) -> Prefs.setStrictEcho(this, on)));
        c1.addView(Ui.switchRow(this, "Speaker se bolna",
                "Headphone ya Bluetooth laga ho to wahi use hota hai.",
                Prefs.forceSpeaker(this), (b, on) -> Prefs.setForceSpeaker(this, on)));
        c1.addView(Ui.switchRow(this, "Natural HD awaaz",
                "ON (best): Priya ki asli 24 kHz awaaz, bina phone-call wali processing ke — robotic nahi lagegi. "
                        + "Agar Priya apni hi awaaz sun kar khud ko rokne lage to OFF karo (call mode, strong echo cancel).",
                Prefs.naturalVoice(this), (b, on) -> Prefs.setNaturalVoice(this, on)));
        content.addView(c1);

        LinearLayout c2 = Ui.card(this);
        c2.addView(Ui.text(this, "Mic sensitivity", 16, Ui.TEXT, true));
        final TextView lab = Ui.text(this, "Level " + Prefs.sensitivity(this) + " / 10  (zyada = halki awaaz bhi pakdegi)",
                12.5f, Ui.SUB, false);
        lab.setPadding(0, Ui.dp(this, 4), 0, 0);
        c2.addView(lab);
        SeekBar sb = new SeekBar(this);
        sb.setMax(9);
        sb.setProgress(Prefs.sensitivity(this) - 1);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                Prefs.setSensitivity(PanelActivity.this, p + 1);
                lab.setText("Level " + (p + 1) + " / 10  (zyada = halki awaaz bhi pakdegi)");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        c2.addView(sb, Ui.matchWrap(this, 6));
        content.addView(c2);

        LinearLayout c3 = Ui.card(this);
        c3.addView(Ui.text(this, "Aapka naam", 16, Ui.TEXT, true));
        final EditText name = new EditText(this);
        name.setText(Prefs.userName(this));
        name.setHint("Priya aapko kis naam se bulaye?");
        name.setHintTextColor(Ui.SUB);
        name.setTextColor(Ui.TEXT);
        name.setSingleLine(true);
        c3.addView(name, Ui.matchWrap(this, 4));
        Button sv = Ui.button(this, "Save", Ui.ACCENT, 0xFF06202A);
        sv.setOnClickListener(v -> { Prefs.setUserName(this, name.getText().toString()); toast("Save ho gaya ✓"); });
        c3.addView(sv, Ui.matchWrap(this, 6));
        content.addView(c3);

        LinearLayout cv = Ui.card(this);
        cv.addView(Ui.text(this, "Voice Lock (sirf meri awaaz)", 16, Ui.TEXT, true));
        final TextView vInfo = Ui.text(this, Prefs.voiceF0(this) > 0
                ? "Aapki awaaz save hai (" + Math.round(Prefs.voiceF0(this)) + " Hz). ON hone par Priya baaki awaazein ignore karegi."
                : "Pehle neeche button dabakar 8 second normal awaaz mein boliye.", 12.5f, Ui.SUB, false);
        vInfo.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        cv.addView(vInfo);
        cv.addView(Ui.switchRow(this, "Voice Lock ON", "Doosre logon / TV ki awaaz par kaam nahi karegi.",
                Prefs.voiceLock(this), (b, on) -> {
                    if (on && Prefs.voiceF0(this) <= 0) { toast("Pehle apni awaaz record karo."); b.setChecked(false); return; }
                    Prefs.setVoiceLock(this, on);
                }));
        Button rec = Ui.button(this, "🎙  Meri awaaz record karo (8 sec)", Ui.ACCENT, 0xFF06202A);
        rec.setOnClickListener(v -> {
            if (PriyaBus.running()) { toast("Pehle Priya OFF karo."); return; }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7); return;
            }
            rec.setEnabled(false);
            vInfo.setText("Boliye… (8 second tak normal baat karo, jaise Priya se karte ho)");
            new Thread(() -> {
                float f0 = AudioEngine.enroll(8);
                ui.post(() -> {
                    rec.setEnabled(true);
                    if (f0 > 0) {
                        Prefs.setVoiceF0(this, f0);
                        Prefs.setVoiceLock(this, true);
                        vInfo.setText("Ho gaya ✓ Aapki awaaz save hai (" + Math.round(f0) + " Hz). Voice Lock ON.");
                    } else {
                        vInfo.setText("Awaaz saaf nahi mili. Shaant jagah mein dobara try karo.");
                    }
                });
            }).start();
        });
        cv.addView(rec, Ui.matchWrap(this, 6));
        content.addView(cv);

        LinearLayout cf = Ui.card(this);
        cf.addView(Ui.switchRow(this, "Home screen par Priya ka chhota face",
                "Priya ON ho aur aap app se bahar ho to chhota face bolte hue dikhega. 'Overlay' permission chahiye.",
                Prefs.floatingFace(this), (b, on) -> {
                    Prefs.setFloatingFace(this, on);
                    if (on && !Settings.canDrawOverlays(this)) {
                        start(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName())));
                    }
                }));
        content.addView(cf);

        LinearLayout c4 = Ui.card(this);
        c4.addView(Ui.text(this, "Status", 16, Ui.TEXT, true));
        String echo = PriyaBus.echoInfo == null || PriyaBus.echoInfo.isEmpty() ? "Priya chalu hogi tab dikhega" : PriyaBus.echoInfo;
        TextView st = Ui.text(this, "Audio: " + echo + "\nLast good model: "
                + (Prefs.lastGoodModel(this).isEmpty() ? "-" : Prefs.lastGoodModel(this))
                + "\nAccessibility: " + (accessibilityOn(this) ? "ON" : "OFF")
                + (Prefs.lastCrash(this).isEmpty() ? "" : "\nLast error: " + Prefs.lastCrash(this).split("\n")[0]), 12.5f, Ui.SUB, false);
        st.setPadding(0, Ui.dp(this, 4), 0, 0);
        c4.addView(st);
        TextView note = Ui.text(this, "Settings badalne ke baad Priya ko ek baar band karke dobara chalu karo.", 12, Ui.SUB, false);
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        c4.addView(note);
        content.addView(c4);
    }

    // ================================================================== skills

    private void renderSkills() {
        final SkillStore store = new SkillStore(this);
        TextView intro = Ui.text(this, "Priya har kaam se pehle dekhti hai ki uske paas us kaam ka skill hai ya nahi. "
                + "Nahi hota to aapse poochhti hai ki naya skill banaye ya nahi.", 12.5f, Ui.SUB, false);
        intro.setPadding(0, 0, 0, Ui.dp(this, 8));
        content.addView(intro);

        LinearLayout add = Ui.card(this);
        add.addView(Ui.text(this, "➕ Naya skill", 16, Ui.TEXT, true));
        final EditText nm = field("Skill ka naam (jaise: Instagram Caption Writer)", true);
        final EditText ds = field("Kab use kare (ek line)", true);
        final EditText in = field("Steps / instructions (Priya ye follow karegi)", false);
        add.addView(nm, Ui.matchWrap(this, 6));
        add.addView(ds, Ui.matchWrap(this, 6));
        add.addView(in, Ui.matchWrap(this, 6));
        Button save = Ui.button(this, "Save skill", Ui.ACCENT, 0xFF06202A);
        save.setOnClickListener(v -> {
            if (nm.getText().toString().trim().isEmpty()) { toast("Naam likho."); return; }
            store.save(nm.getText().toString(), ds.getText().toString(), in.getText().toString());
            toast("Skill save ho gaya ✓ (Priya ko OFF/ON karo)");
            content.removeAllViews();
            renderSkills();
        });
        add.addView(save, Ui.matchWrap(this, 8));
        content.addView(add);

        for (final SkillStore.Skill sk : store.list()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "🧩 " + sk.name, 16, Ui.TEXT, true));
            TextView d = Ui.text(this, sk.description, 12.5f, Ui.SUB, false);
            d.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            c.addView(d);
            final TextView body = Ui.text(this, sk.instructions, 12.5f, Ui.TEXT, false);
            body.setVisibility(View.GONE);
            c.addView(body);
            c.setOnClickListener(v -> body.setVisibility(body.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
            Button del = Ui.button(this, "Hatao (sirf aap)", 0xFF3A2030, Ui.BAD);
            del.setOnClickListener(v -> confirm("'" + sk.name + "' skill hatana hai?", () -> {
                store.deleteByUser(sk.id);
                content.removeAllViews();
                renderSkills();
            }));
            c.addView(del, Ui.matchWrap(this, 6));
            content.addView(c);
        }
    }

    private EditText field(String hint, boolean single) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.SUB);
        e.setTextColor(Ui.TEXT);
        e.setSingleLine(single);
        if (!single) e.setMinLines(3);
        return e;
    }
}
