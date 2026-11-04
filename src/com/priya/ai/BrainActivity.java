package com.priya.ai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Minimal v27 screen: schedules (pause / resume / run now) and an audio file picker for faceless video. */
public class BrainActivity extends Activity {

    private static final int REQ_AUDIO = 41;
    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if ("pick_audio".equals(getIntent().getStringExtra("page"))) {
            openPicker();
            return;
        }
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        list.setPadding(p, p, p, p);
        sv.addView(list);
        setContentView(sv);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null) render();
    }

    private void openPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
            startActivityForResult(i, REQ_AUDIO);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("audio/*"), REQ_AUDIO);
            } catch (Exception e2) {
                note("[SYSTEM NOTE] Audio picker nahi khula. Sachin se list_recent_audio wali file chunwao.");
                finish();
            }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_AUDIO) return;
        final Uri uri = (res == RESULT_OK && data != null) ? data.getData() : null;
        if (uri == null) {
            note("[SYSTEM NOTE] Sachin ne koi audio file nahi chuni (picker band kiya).");
            if (list == null) finish();
            return;
        }
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String msg;
            try {
                String path = Studio.importPicked(app, uri);
                msg = "[SYSTEM NOTE] Sachin ne audio chuni, path: " + path + " — make_faceless_video mein audio_path yahi do.";
            } catch (Exception e) {
                msg = "[SYSTEM NOTE] Chuni hui audio copy nahi ho payi: " + e.getMessage();
            }
            note(msg);
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(app, m.contains("path:") ? "Audio mil gayi" : "Audio copy fail", Toast.LENGTH_SHORT).show());
        }).start();
        if (list == null) finish();
    }

    private static void note(String s) {
        PriyaJobs.LiveSink l = PriyaJobs.live;
        if (l != null) try { l.note(s); } catch (Throwable ignored) { }
    }

    private void render() {
        list.removeAllViews();
        TextView h = new TextView(this);
        h.setText("Priya — Scheduled tasks");
        h.setTextSize(20);
        list.addView(h);
        Button pick = new Button(this);
        pick.setText("Audio file chuno (faceless video)");
        pick.setOnClickListener(v -> openPicker());
        list.addView(pick);
        JSONArray a = Scheduler.load(this);
        int shown = 0;
        for (int i = 0; i < a.length(); i++) {
            final JSONObject s = a.optJSONObject(i);
            if (s == null || "cancelled".equals(s.optString("status"))) continue;
            shown++;
            final String id = s.optString("id");
            TextView t = new TextView(this);
            t.setText(Scheduler.describe(s));
            t.setPadding(0, 24, 0, 8);
            list.addView(t);
            LinearLayout row = new LinearLayout(this);
            boolean active = "active".equals(s.optString("status"));
            row.addView(btn(active ? "Pause" : "Resume", () -> Scheduler.setStatus(this, id, active ? "paused" : "active")));
            row.addView(btn("Run now", () -> Scheduler.runNow(this, id)));
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (shown == 0) {
            TextView t = new TextView(this);
            t.setText("Koi schedule nahi. Priya se bolo: 'roz subah 9 baje ... karna'.");
            list.addView(t);
        }
        TextView lg = new TextView(this);
        StringBuilder sb = new StringBuilder("\nRecent runs:\n");
        for (JSONObject r : Scheduler.recentRuns(this, 10)) sb.append("• ").append(GeminiRest.clip(r.toString(), 220)).append('\n');
        lg.setText(sb.toString());
        list.addView(lg);
    }

    private interface Act { String go(); }

    private Button btn(String label, final Act a) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> {
            String r;
            try { r = a.go(); } catch (Exception e) { r = "Error: " + e.getMessage(); }
            Toast.makeText(this, GeminiRest.clip(r, 160), Toast.LENGTH_LONG).show();
            render();
        });
        return b;
    }
}
