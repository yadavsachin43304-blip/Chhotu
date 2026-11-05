package com.priya.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Menu > AI Agents: list (ON/OFF, Run now, Edit, Table, History, Archive), the edit form, approvals waiting for the
 * owner, and .md upload. Nothing here deletes anything: "Archive" hides an agent and keeps all its data.
 */
public class AgentsActivity extends Activity {

    private static final int REQ_MD = 41;

    private LinearLayout content;
    private TextView title;
    private String mode = "list";            // list | edit | table | history
    private String currentId = null;         // agent being edited / viewed (null in edit = new)
    private AgentStore.Agent formStart = null;
    private boolean showArchived = false;
    private String tableFilter = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back);
        title = Ui.text(this, "AI Agents", 20, Ui.TEXT, true);
        bar.addView(title);
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
        render();
    }

    @Override protected void onPause() {
        super.onPause();
        PriyaBus.appVisible = false;
    }

    @Override public void onBackPressed() {
        if (!mode.equals("list")) { go("list", null); return; }
        super.onBackPressed();
    }

    private AgentStore store() { return AgentRunner.store(this); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void go(String m, String id) {
        mode = m;
        currentId = id;
        render();
    }

    private void render() {
        content.removeAllViews();
        try {
            switch (mode) {
                case "edit": title.setText(currentId == null ? "Naya agent" : "Agent edit"); renderForm(); break;
                case "table": title.setText("Agent table"); renderTable(); break;
                case "history": title.setText("Agent history"); renderHistory(); break;
                default: title.setText("AI Agents"); renderList(); break;
            }
        } catch (Throwable e) {
            content.addView(Ui.text(this, "Screen banane mein dikkat: " + e.getMessage(), 13, Ui.BAD, false));
        }
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Haan", (d, w) -> yes.run()).setNegativeButton("Nahi", null).show();
    }

    private EditText field(String hint, boolean single) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.SUB);
        e.setTextColor(Ui.TEXT);
        e.setSingleLine(single);
        if (!single) { e.setMinLines(4); e.setGravity(Gravity.TOP); }
        return e;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, Ui.SUB, true);
        t.setPadding(0, Ui.dp(this, 10), 0, 0);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private Button small(String s, int bg, int fg, View.OnClickListener l) {
        Button b = Ui.button(this, s, bg, fg);
        b.setTextSize(12.5f);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), 0);
        return lp;
    }

    private static String when(long ms) {
        return ms <= 0 ? "-" : new SimpleDateFormat("d MMM HH:mm", Locale.ROOT).format(new Date(ms));
    }

    // ================================================================== list

    private void renderList() {
        final AgentStore st = store();
        TextView intro = Ui.text(this, "Agent = aapka apna AI worker: goal, tools, kab chale (schedule / voice phrase / Run now) aur kis kaam se pehle aapki 'haan' chahiye. "
                + "Paise wali baat aur paid kaam ki final delivery hamesha aapki haan ke baad. Agent kabhi kuch delete nahi karte, kabhi payment nahi karte. "
                + "Voice: \"Maya, ek agent banao jo ...\"", 12.5f, Ui.SUB, false);
        intro.setPadding(0, 0, 0, Ui.dp(this, 8));
        content.addView(intro);

        LinearLayout r1 = row();
        r1.addView(small("➕ Naya agent", Ui.ACCENT, 0xFF06202A, v -> { formStart = AgentTemplates.custom(); go("edit", null); }), weight());
        r1.addView(small("📄 .md se agent", Ui.CARD, Ui.TEXT, v -> pickMd()), weight());
        content.addView(r1);

        List<Map<String, Object>> pend = st.approvals("pending");
        if (!pend.isEmpty()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "🙋 Aapki haan chahiye (" + pend.size() + ")", 16, Ui.TEXT, true));
            for (final Map<String, Object> m : pend) {
                String pid = MiniJson.str(m, "id", "");
                TextView t = Ui.text(this, MiniJson.str(m, "agent_name", "") + " • " + AgentStore.approvalLabel(MiniJson.str(m, "kind", "")) + " → "
                        + MiniJson.str(m, "to", "") + "\n" + MiniJson.str(m, "summary", "") + "  (" + pid + ")", 13, Ui.TEXT, false);
                t.setPadding(0, Ui.dp(this, 8), 0, 0);
                c.addView(t);
                LinearLayout r = row();
                r.addView(small("Haan ✓", 0xFF1E4A3A, Ui.GOOD, v -> confirm("Pakka haan?\n" + MiniJson.str(m, "summary", ""), () -> {
                    Map<String, Object> d = st.decide(pid, true);
                    if (d == null) { toast("Ye request ab pending nahi."); render(); return; }
                    String res = AgentRunner.start(this, MiniJson.str(d, "agent", ""), "Owner ne app mein approval " + pid + " (" + MiniJson.str(d, "kind", "") + " → "
                            + MiniJson.str(d, "to", "") + ") par HAAN dabaya: " + MiniJson.str(d, "summary", "") + ". Ab sirf yahi approved kaam karo, phir report.", false, "approval (app)");
                    toast(res.startsWith("JOB_STARTED") ? "Approve ✓ — agent kaam kar raha hai" : "Approve ✓ — " + GeminiRest.clip(res, 90));
                    render();
                })), weight());
                r.addView(small("Na ✗", 0xFF3A2030, Ui.BAD, v -> { st.decide(pid, false); toast("Mana kar diya"); render(); }), weight());
                c.addView(r);
            }
            content.addView(c);
        }

        View sw = Ui.switchRow(this, "Archived agents bhi dikhao", "Archive kiye agent chhupe rehte hain, data bacha rehta hai", showArchived,
                (b, on) -> { showArchived = on; render(); });
        content.addView(sw);

        for (final AgentStore.Agent a : st.list(showArchived)) {
            LinearLayout c = Ui.card(this);
            LinearLayout head = row();
            TextView name = Ui.text(this, (a.archived ? "🗄 " : "🤖 ") + a.name, 16, a.on ? Ui.TEXT : Ui.SUB, true);
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!a.archived) {
                final Switch s = new Switch(this);
                s.setChecked(a.on);
                s.setOnCheckedChangeListener((btn, on) -> {
                    AgentStore.Agent x = st.setOn(a.id, on);
                    String sch = x == null ? "" : AgentRunner.syncSchedule(this, x);
                    toast(a.name + (on ? " ON ✓" : " OFF") + (sch.isEmpty() ? "" : " — " + sch));
                    name.setTextColor(on ? Ui.TEXT : Ui.SUB);
                });
                head.addView(s);
            }
            c.addView(head);
            String last = "";
            if (!a.runs.isEmpty()) {
                Map<String, Object> r = a.runs.get(a.runs.size() - 1);
                last = "\nPichhla run " + when(MiniJson.lng(r, "at", 0)) + ": " + MiniJson.str(r, "status", "") + " — "
                        + GeminiRest.clip(MiniJson.str(r, "result", "").replace('\n', ' '), 120);
            }
            TextView d = Ui.text(this, (a.on ? "ON" : "OFF") + " • " + a.triggerText() + " • table: " + a.table + " (" + st.tableStats(a.table) + ")"
                    + "\n" + AgentStore.firstLine(a.goal, 120) + last, 12.5f, Ui.SUB, false);
            d.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            c.addView(d);
            LinearLayout r1b = row();
            if (!a.archived) {
                r1b.addView(small("▶ Run now", Ui.ACCENT, 0xFF06202A, v -> {
                    if (!a.on) { toast("Pehle agent ON karo."); return; }
                    String res = AgentRunner.start(this, a.id, "Owner ne app se 'Run now' dabaya.", false, "app");
                    toast(res.startsWith("JOB_STARTED") ? "Chal raha hai — notification mein khabar aayegi" : GeminiRest.clip(res, 120));
                }), weight());
                r1b.addView(small("✏️ Edit", Ui.CARD, Ui.TEXT, v -> { formStart = null; go("edit", a.id); }), weight());
            }
            r1b.addView(small("📊 Table", Ui.CARD, Ui.TEXT, v -> { tableFilter = ""; go("table", a.id); }), weight());
            c.addView(r1b);
            LinearLayout r2 = row();
            r2.addView(small("🕘 History", Ui.CARD, Ui.TEXT, v -> go("history", a.id)), weight());
            if (a.archived) {
                r2.addView(small("Unarchive", Ui.CARD, Ui.GOOD, v -> { st.archive(a.id, false); toast("Wapas aa gaya (OFF)"); render(); }), weight());
            } else {
                r2.addView(small("🗄 Archive", 0xFF3A2030, Ui.BAD, v -> confirm("'" + a.name + "' archive karna hai? (OFF ho jayega, schedule ruk jayega; data, table aur history bache rahenge)", () -> {
                    AgentStore.Agent x = st.archive(a.id, true);
                    if (x != null) AgentRunner.syncSchedule(this, x);
                    toast("Archive ho gaya");
                    render();
                })), weight());
            }
            c.addView(r2);
            content.addView(c);
        }
    }

    // ================================================================== edit form

    private void renderForm() {
        final AgentStore st = store();
        final AgentStore.Agent existing = currentId == null ? null : st.get(currentId);
        AgentStore.Agent a = existing != null ? existing : (formStart != null ? formStart : AgentTemplates.custom());

        if (existing == null) {
            content.addView(label("Template se shuru karo"));
            HorizontalScrollView hs = new HorizontalScrollView(this);
            LinearLayout tr = row();
            List<AgentStore.Agent> temps = new ArrayList<>();
            temps.add(AgentTemplates.custom());
            temps.addAll(AgentTemplates.growthPilot());
            for (final AgentStore.Agent t : temps) {
                Button b = small(t.name, t.name.equals(a.name) ? Ui.ACCENT : Ui.CARD, t.name.equals(a.name) ? 0xFF06202A : Ui.TEXT, v -> { formStart = t; render(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, Ui.dp(this, 6), 0);
                tr.addView(b, lp);
            }
            hs.addView(tr);
            content.addView(hs);
        }

        LinearLayout c = Ui.card(this);
        c.addView(label("Naam"));
        final EditText nm = field("Agent ka naam", true);
        nm.setText(a.name);
        c.addView(nm);
        c.addView(label("Goal / instructions (Markdown: kya karna hai, steps, rules, report)"));
        final EditText goal = field("Goal aur steps", false);
        goal.setMinLines(8);
        goal.setText(a.goal);
        c.addView(goal);

        c.addView(label("Tools (research, likhna, notes, table hamesha ON)"));
        final CheckBox[] tools = new CheckBox[AgentStore.TOOL_GROUPS.length];
        String[] toolLabels = {"phone — apps kholna, tap, type, screen padhna", "content — image, voiceover, video", "messages — email / WhatsApp (sirf allowed logon ko)", "coding — Termux"};
        for (int i = 0; i < tools.length; i++) {
            tools[i] = check(toolLabels[i], a.tools.contains(AgentStore.TOOL_GROUPS[i]));
            c.addView(tools[i]);
        }

        c.addView(label("Kab chale"));
        final RadioGroup rg = new RadioGroup(this);
        String[] trLabels = {"Sirf jab main chalaun (manual)", "Roz (time par)", "Har N ghante", "Mon-Fri (time par)", "Hafte mein ek din", "Voice phrase par"};
        for (int i = 0; i < AgentStore.TRIGGERS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(trLabels[i]);
            rb.setTextColor(Ui.TEXT);
            rb.setId(1000 + i);
            rg.addView(rb);
            if (AgentStore.TRIGGERS[i].equals(a.trigger)) rb.setChecked(true);
        }
        if (rg.getCheckedRadioButtonId() == -1) rg.check(1000);
        c.addView(rg);
        final EditText time = field("Time HH:MM (jaise 09:00)", true);
        time.setText(a.time);
        c.addView(time);
        final EditText every = field("Har kitne ghante (hourly ke liye, 1-24)", true);
        every.setInputType(InputType.TYPE_CLASS_NUMBER);
        every.setText(String.valueOf(a.everyHours));
        c.addView(every);
        final EditText wd = field("Din (weekly ke liye: monday..sunday)", true);
        wd.setText(a.weekday);
        c.addView(wd);
        final EditText phrase = field("Voice phrase (jaise 'leads dhoondho')", true);
        phrase.setText(a.phrase);
        c.addView(phrase);

        c.addView(label("Kisko bhej sakta hai"));
        final EditText rec = field("Exact emails / numbers (comma se)", true);
        rec.setText(a.recipients);
        c.addView(rec);
        final CheckBox fromTable = check("Apni table ke contacts ko (bounced / do_not_contact kabhi nahi)", a.recipientsFromTable);
        c.addView(fromTable);
        final EditText cap = field("Roz max kitne email/message (0 = koi limit nahi)", true);
        cap.setInputType(InputType.TYPE_CLASS_NUMBER);
        cap.setText(String.valueOf(a.dailyCap));
        c.addView(cap);

        c.addView(label("Pehle aapki haan"));
        CheckBox f1 = check("Paise / payment wali baat (hamesha)", true);
        f1.setEnabled(false);
        CheckBox f2 = check("Paid kaam ki final delivery — 'payment mila?' (hamesha)", true);
        f2.setEnabled(false);
        c.addView(f1);
        c.addView(f2);
        final CheckBox anyEmail = check("Har email se pehle", a.approvals.contains("any_email"));
        final CheckBox anyWa = check("Har WhatsApp se pehle", a.approvals.contains("any_whatsapp"));
        final CheckBox paid = check("Ye agent paid kaam deliver karta hai (har delivery = approval)", a.paidWork);
        c.addView(anyEmail);
        c.addView(anyWa);
        c.addView(paid);
        final EditText kw = field("In shabdon wale message se pehle bhi poochho (comma se)", true);
        kw.setText(a.approvalKeywords);
        c.addView(kw);

        c.addView(label("Data table aur handoff"));
        final EditText table = field("Table ka naam (jaise growthpilot_leads)", true);
        table.setText(a.table);
        c.addView(table);
        final EditText hand = field("Kin agents ko kaam de sakta hai (ids, comma se)", true);
        hand.setText(android.text.TextUtils.join(", ", a.handoffs));
        c.addView(hand);
        final CheckBox turnOn = existing == null ? check("Save ke baad ON karo", false) : null;
        if (turnOn != null) c.addView(turnOn);

        Button save = Ui.button(this, existing == null ? "Agent banao" : "Update karo", Ui.ACCENT, 0xFF06202A);
        save.setOnClickListener(v -> {
            AgentStore.Agent d = existing != null ? existing.copy() : (formStart != null ? formStart.copy() : new AgentStore.Agent());
            if (existing == null) d.id = "";
            d.name = nm.getText().toString().trim();
            d.goal = goal.getText().toString().trim();
            if (d.name.isEmpty()) { toast("Naam likho."); return; }
            if (d.goal.isEmpty()) { toast("Goal likho."); return; }
            d.tools = new ArrayList<>();
            for (int i = 0; i < tools.length; i++) if (tools[i].isChecked()) d.tools.add(AgentStore.TOOL_GROUPS[i]);
            int ti = rg.getCheckedRadioButtonId() - 1000;
            d.trigger = AgentStore.TRIGGERS[Math.max(0, Math.min(AgentStore.TRIGGERS.length - 1, ti))];
            d.time = time.getText().toString();
            try { d.everyHours = Integer.parseInt(every.getText().toString().trim()); } catch (Exception e) { d.everyHours = 4; }
            d.weekday = wd.getText().toString();
            d.phrase = phrase.getText().toString();
            d.recipients = rec.getText().toString();
            d.recipientsFromTable = fromTable.isChecked();
            try { d.dailyCap = Integer.parseInt(cap.getText().toString().trim()); } catch (Exception e) { d.dailyCap = 0; }
            d.approvals = new ArrayList<>();
            if (anyEmail.isChecked()) d.approvals.add("any_email");
            if (anyWa.isChecked()) d.approvals.add("any_whatsapp");
            d.paidWork = paid.isChecked();
            d.approvalKeywords = kw.getText().toString();
            d.table = table.getText().toString();
            d.handoffs = MiniJson.strings(hand.getText().toString());
            StringBuilder notes = new StringBuilder();
            String bad = AgentTools.check(d, notes);
            if (bad != null) { toast(bad); return; }
            AgentStore.Agent saved;
            if (existing == null) {
                d.on = turnOn != null && turnOn.isChecked();
                saved = st.create(d);
            } else {
                saved = st.update(existing.id, d);
            }
            if (saved == null) { toast("Save nahi hua."); return; }
            String sch = AgentRunner.syncSchedule(this, saved);
            toast((existing == null ? "Agent ban gaya ✓" : "Update ho gaya ✓") + (sch.isEmpty() ? "" : " — " + sch) + notes);
            formStart = null;
            go("list", null);
        });
        c.addView(save, Ui.matchWrap(this, 12));
        Button cancel = Ui.button(this, "Cancel", Ui.CARD, Ui.TEXT);
        cancel.setOnClickListener(v -> { formStart = null; go("list", null); });
        c.addView(cancel, Ui.matchWrap(this, 6));
        content.addView(c);
    }

    private CheckBox check(String s, boolean on) {
        CheckBox cb = new CheckBox(this);
        cb.setText(s);
        cb.setTextColor(Ui.TEXT);
        cb.setChecked(on);
        return cb;
    }

    // ================================================================== table

    private void renderTable() {
        final AgentStore st = store();
        final AgentStore.Agent a = st.get(currentId);
        if (a == null) { go("list", null); return; }
        content.addView(Ui.text(this, a.name + " • table '" + a.table + "'\n" + st.tableStats(a.table), 13, Ui.TEXT, true));
        LinearLayout fr = row();
        final EditText f = field("Status filter (jaise new, emailed) — khaali = sab", true);
        f.setText(tableFilter);
        fr.addView(f, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fr.addView(small("Dikhao", Ui.CARD, Ui.TEXT, v -> { tableFilter = f.getText().toString().trim(); render(); }));
        content.addView(fr);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        TextView t = Ui.text(this, st.csv(a.table, tableFilter, 200), 11, Ui.TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        hs.addView(t);
        LinearLayout card = Ui.card(this);
        card.addView(hs);
        content.addView(card);

        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Row ka status badlo (aap khud — jaise order aaya to 'paid_order')", 14, Ui.TEXT, true));
        final EditText key = field("Email ya _id", true);
        final EditText status = field("Naya status (new, emailed, form_filled, paid_order, do_not_contact, archived...)", true);
        final EditText pkg = field("Note (optional, jaise 'Standard Audit ₹2,999 paid')", true);
        c.addView(key);
        c.addView(status);
        c.addView(pkg);
        Button b = Ui.button(this, "Status save karo", Ui.ACCENT, 0xFF06202A);
        b.setOnClickListener(v -> {
            String r = st.ownerSetStatus(a.table, key.getText().toString(), status.getText().toString(), pkg.getText().toString());
            toast(r);
            if (r.startsWith("UPDATED")) render();
        });
        c.addView(b, Ui.matchWrap(this, 8));
        content.addView(c);
    }

    // ================================================================== history

    private void renderHistory() {
        final AgentStore st = store();
        final AgentStore.Agent a = st.get(currentId);
        if (a == null) { go("list", null); return; }
        content.addView(Ui.text(this, a.name + " • pichhle " + a.runs.size() + " run (purane archive mein surakshit)", 13, Ui.TEXT, true));
        if (a.runs.isEmpty()) content.addView(Ui.text(this, "Abhi tak koi run nahi.", 13, Ui.SUB, false));
        for (int i = a.runs.size() - 1; i >= 0; i--) {
            Map<String, Object> r = a.runs.get(i);
            LinearLayout c = Ui.card(this);
            String stt = MiniJson.str(r, "status", "");
            c.addView(Ui.text(this, when(MiniJson.lng(r, "at", 0)) + " • " + stt + " • " + MiniJson.str(r, "source", ""), 13, stt.equals("DONE") ? Ui.GOOD : Ui.BAD, true));
            TextView t = Ui.text(this, MiniJson.str(r, "result", ""), 12.5f, Ui.TEXT, false);
            t.setTextIsSelectable(true);
            c.addView(t);
            content.addView(c);
        }
        if (!a.notes.isEmpty()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "Agent notes", 14, Ui.TEXT, true));
            c.addView(Ui.text(this, a.notes, 12, Ui.SUB, false));
            content.addView(c);
        }
    }

    // ================================================================== .md upload

    private void pickMd() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/markdown", "text/x-markdown", "text/plain", "*/*"});
            startActivityForResult(i, REQ_MD);
        } catch (Exception e) {
            toast("File picker nahi khula: " + e.getMessage());
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_MD || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String fileName = "agent.md";
        try (android.database.Cursor cur = getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int ix = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (ix >= 0 && cur.getString(ix) != null) fileName = cur.getString(ix);
            }
        } catch (Exception ignored) { }
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) { toast("File nahi khuli."); return; }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0 && bo.size() < 256 * 1024) bo.write(buf, 0, n);
            String text = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            AgentStore.Agent a = AgentStore.parseMarkdown(fileName, text);
            if (a == null) { toast("File khaali hai ya padhi nahi gayi."); return; }
            StringBuilder notes = new StringBuilder();
            String bad = AgentTools.check(a, notes);
            if (bad != null) { toast(bad); return; }
            AgentStore.Agent saved = store().create(a);
            toast("Agent '" + saved.name + "' ban gaya ✓ (OFF — dekh kar ON karo)" + notes);
        } catch (Exception e) {
            toast("File padh nahi paayi: " + e.getMessage());
        }
        render();
    }
}
