package com.priya.ai;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Agent Builder storage (plain Java, no android imports, so tests/AgentStoreCheck.java runs on a plain JVM).
 *
 * Layout under the app files dir:  agents/agents.json (every agent ever made), agents/tables/<name>.json (data sheets),
 * agents/approvals.json, agents/drafts.json, agents/archive_runs.jsonl + archive_notes.jsonl (append-only).
 *
 * NOTHING here is ever deleted: there is no delete method. An agent is "archived" (hidden, OFF, schedule cancelled),
 * a table row gets status "archived", old run history / notes move to the append-only archive files.
 * The "money" and "final_paid_delivery" approval rules are forced on every agent and cannot be switched off.
 */
final class AgentStore {

    // ------------------------------------------------------------------ constants

    static final String[] TOOL_GROUPS = {"phone", "content", "messages", "coding"};
    static final String[] TRIGGERS = {"manual", "daily", "hourly", "weekdays", "weekly", "voice"};
    /** Always required, whatever the agent file says. */
    static final String[] FORCED_APPROVALS = {"money", "final_paid_delivery"};
    static final String[] OPTIONAL_APPROVALS = {"any_email", "any_whatsapp"};
    /** Rows with these statuses are never contacted and never reset by an upsert. */
    static final Set<String> BLOCKED_STATUSES = new HashSet<>(Arrays.asList(
            "bounced", "unsubscribed", "do_not_contact", "not_interested", "archived", "invalid"));
    static final Set<String> PAID_STATUSES = new HashSet<>(Arrays.asList(
            "paid_order", "order_received", "work_in_progress", "work_done", "payment_pending", "payment_requested",
            "payment_received", "paid", "delivered"));
    static final int MAX_RUNS = 30;
    static final int MAX_NOTES_CHARS = 8000;
    static final int MAX_ROWS = 20000;

    /** Payment / money request in an outgoing message. */
    static final Pattern MONEY = Pattern.compile("(?i)(please pay|pay now|kindly pay|make (the )?payment|complete (the )?payment|payment (link|details|due|request|pending|reminder)"
            + "|invoice|upi id|upi:|upi -|gpay|phonepe|paytm|bank details|account number|a/c no|ifsc|amount due|balance due|due amount"
            + "|pay (the )?(amount|fee|balance|invoice)|pay ₹|pay rs|send (the )?money|पेमेंट कर|भुगतान कर|paisa bhej|payment kar)");
    /** Handing over a paid deliverable. */
    static final Pattern DELIVERY = Pattern.compile("(?i)(final (report|delivery|deliverable|audit|file|pdf)|deliverable|attached|attachment|\\.pdf\\b|\\bpdf\\b"
            + "|drive\\.google|docs\\.google\\.com/(document|presentation|spreadsheets)|download (link|your|the)|your (full |complete |detailed )?(audit|report|growth plan|roadmap|strategy)"
            + "|report (link|attached|enclosed)|here is your|here's your|please find)");

    private static final Object LOCK = new Object();
    private final File dir;

    AgentStore(File filesDir) {
        dir = new File(filesDir, "agents");
        if (!dir.exists()) dir.mkdirs();
        File t = new File(dir, "tables");
        if (!t.exists()) t.mkdirs();
    }

    // ------------------------------------------------------------------ the Agent

    static final class Agent {
        String id = "", name = "", goal = "", template = "";
        List<String> tools = new ArrayList<>();
        String trigger = "manual", time = "09:00", weekday = "monday", phrase = "";
        int everyHours = 4;
        String recipients = "";
        boolean recipientsFromTable = false;
        int dailyCap = 0;
        List<String> approvals = new ArrayList<>();
        String approvalKeywords = "";
        boolean paidWork = false;
        String preApproved = "";
        String table = "";
        List<String> handoffs = new ArrayList<>();
        String notes = "";
        boolean on = false, archived = false;
        long created, updated;
        List<Map<String, Object>> runs = new ArrayList<>();
        String scheduleId = "";
        String sendsDay = "";
        int sendsCount = 0;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("name", name); m.put("template", template); m.put("goal", goal);
            m.put("tools", new ArrayList<Object>(tools));
            m.put("trigger", trigger); m.put("time", time); m.put("every_hours", (long) everyHours); m.put("weekday", weekday);
            m.put("phrase", phrase);
            m.put("recipients", recipients); m.put("recipients_from_table", recipientsFromTable); m.put("daily_cap", (long) dailyCap);
            m.put("approvals", new ArrayList<Object>(approvals)); m.put("approval_keywords", approvalKeywords);
            m.put("paid_work", paidWork); m.put("pre_approved", preApproved);
            m.put("table", table); m.put("handoffs", new ArrayList<Object>(handoffs));
            m.put("notes", notes); m.put("on", on); m.put("archived", archived);
            m.put("created", created); m.put("updated", updated);
            m.put("runs", new ArrayList<Object>(runs));
            m.put("schedule_id", scheduleId); m.put("sends_day", sendsDay); m.put("sends_count", (long) sendsCount);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Agent fromMap(Map<String, Object> m) {
            Agent a = new Agent();
            a.id = MiniJson.str(m, "id", "");
            a.name = MiniJson.str(m, "name", "");
            a.template = MiniJson.str(m, "template", "");
            a.goal = MiniJson.str(m, "goal", "");
            a.tools = MiniJson.strings(m.get("tools"));
            a.trigger = MiniJson.str(m, "trigger", "manual");
            a.time = MiniJson.str(m, "time", "09:00");
            a.everyHours = (int) MiniJson.lng(m, "every_hours", 4);
            a.weekday = MiniJson.str(m, "weekday", "monday");
            a.phrase = MiniJson.str(m, "phrase", "");
            a.recipients = MiniJson.str(m, "recipients", "");
            a.recipientsFromTable = MiniJson.bool(m, "recipients_from_table", false);
            a.dailyCap = (int) MiniJson.lng(m, "daily_cap", 0);
            a.approvals = MiniJson.strings(m.get("approvals"));
            a.approvalKeywords = MiniJson.str(m, "approval_keywords", "");
            a.paidWork = MiniJson.bool(m, "paid_work", false);
            a.preApproved = MiniJson.str(m, "pre_approved", "");
            a.table = MiniJson.str(m, "table", "");
            a.handoffs = MiniJson.strings(m.get("handoffs"));
            a.notes = MiniJson.str(m, "notes", "");
            a.on = MiniJson.bool(m, "on", false);
            a.archived = MiniJson.bool(m, "archived", false);
            a.created = MiniJson.lng(m, "created", 0);
            a.updated = MiniJson.lng(m, "updated", 0);
            for (Object r : MiniJson.list(m, "runs")) if (r instanceof Map) a.runs.add((Map<String, Object>) r);
            a.scheduleId = MiniJson.str(m, "schedule_id", "");
            a.sendsDay = MiniJson.str(m, "sends_day", "");
            a.sendsCount = (int) MiniJson.lng(m, "sends_count", 0);
            return a;
        }

        Agent copy() { return fromMap(MiniJson.parseObject(MiniJson.write(toMap()))); }

        boolean hasTool(String g) { return tools.contains(g); }

        String triggerText() {
            switch (trigger) {
                case "daily": return "roz " + time;
                case "hourly": return "har " + everyHours + " ghante (" + time + " se)";
                case "weekdays": return "Mon-Fri " + time;
                case "weekly": return "har " + weekday + " " + time;
                case "voice": return "jab aap bolein \"" + phrase + "\"";
                default: return "sirf jab aap chalayein";
            }
        }
    }

    // ------------------------------------------------------------------ normalise / validate

    static String cleanTable(String t) {
        String s = t == null ? "" : t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** Fixes every field into its allowed range; forced approval rules are always put back. */
    static void normalize(Agent a) {
        a.name = a.name == null ? "" : a.name.replace('\n', ' ').trim();
        if (a.name.length() > 60) a.name = a.name.substring(0, 60).trim();
        if (a.goal == null) a.goal = "";
        if (a.goal.length() > 20000) a.goal = a.goal.substring(0, 20000);
        List<String> tl = new ArrayList<>();
        for (String g : TOOL_GROUPS) for (String t : a.tools) if (g.equalsIgnoreCase(t.trim()) && !tl.contains(g)) tl.add(g);
        a.tools = tl;
        String tr = a.trigger == null ? "manual" : a.trigger.toLowerCase(Locale.ROOT).trim();
        if (tr.equals("every_n_hours") || tr.equals("every") || tr.equals("interval")) tr = "hourly";
        if (tr.equals("schedule")) tr = "daily";
        if (!Arrays.asList(TRIGGERS).contains(tr)) tr = "manual";
        a.trigger = tr;
        a.time = cleanTime(a.time);
        if (a.everyHours < 1) a.everyHours = 1;
        if (a.everyHours > 24) a.everyHours = 24;
        a.weekday = a.weekday == null || a.weekday.trim().isEmpty() ? "monday" : a.weekday.toLowerCase(Locale.ROOT).trim();
        a.phrase = a.phrase == null ? "" : a.phrase.trim();
        if (a.trigger.equals("voice") && a.phrase.isEmpty()) a.phrase = a.name.toLowerCase(Locale.ROOT) + " chalao";
        a.recipients = a.recipients == null ? "" : a.recipients.trim();
        if (a.dailyCap < 0) a.dailyCap = 0;
        if (a.dailyCap > 200) a.dailyCap = 200;
        List<String> ap = new ArrayList<>(Arrays.asList(FORCED_APPROVALS));
        for (String o : OPTIONAL_APPROVALS) for (String x : a.approvals) if (o.equalsIgnoreCase(x.trim()) && !ap.contains(o)) ap.add(o);
        a.approvals = ap;
        a.approvalKeywords = a.approvalKeywords == null ? "" : a.approvalKeywords.trim();
        a.table = cleanTable(a.table);
        if (a.table.isEmpty()) a.table = cleanTable("agent_" + (a.id.isEmpty() ? a.name : a.id));
        List<String> hs = new ArrayList<>();
        for (String h : a.handoffs) if (!h.trim().isEmpty() && !hs.contains(h.trim())) hs.add(h.trim());
        a.handoffs = hs;
        if (a.notes == null) a.notes = "";
    }

    static String cleanTime(String t) {
        try {
            String[] p = (t == null ? "" : t.trim()).split("[:.]");
            int h = Integer.parseInt(p[0].trim()), m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return "09:00";
            return String.format(Locale.ROOT, "%02d:%02d", h, m);
        } catch (Exception e) {
            return "09:00";
        }
    }

    // ------------------------------------------------------------------ file helpers

    private static String readText(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64L * 1024 * 1024)];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Write to a temp file, then rename over the old one (a crash never leaves half a file). */
    private static void writeText(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) {
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        }
    }

    private static void appendLine(File f, Object o) {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write((MiniJson.write(o) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readList(String file) {
        List<Map<String, Object>> out = new ArrayList<>();
        String s = readText(new File(dir, file));
        if (s == null || s.trim().isEmpty()) return out;
        try {
            Object o = MiniJson.parse(s);
            if (o instanceof List) for (Object x : (List<Object>) o) if (x instanceof Map) out.add((Map<String, Object>) x);
        } catch (Exception e) {
            // unreadable: keep a copy aside (never lose it) and start a fresh list
            writeText(new File(dir, file + ".broken-" + System.currentTimeMillis()), s);
        }
        return out;
    }

    private void writeList(String file, List<Map<String, Object>> l) {
        writeText(new File(dir, file), MiniJson.pretty(new ArrayList<Object>(l)));
    }

    private static String newId(String prefix) {
        return prefix + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT) + Integer.toString((int) (Math.random() * 1296), 36).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ agents CRUD (no delete)

    List<Agent> list(boolean includeArchived) {
        synchronized (LOCK) {
            List<Agent> out = new ArrayList<>();
            for (Map<String, Object> m : readList("agents.json")) {
                Agent a = Agent.fromMap(m);
                if (includeArchived || !a.archived) out.add(a);
            }
            return out;
        }
    }

    Agent get(String id) {
        if (id == null) return null;
        for (Agent a : list(true)) if (a.id.equalsIgnoreCase(id.trim())) return a;
        return null;
    }

    /** By id, exact name, then partial name (only non-archived for name matches). */
    Agent find(String q) {
        if (q == null || q.trim().isEmpty()) return null;
        Agent byId = get(q);
        if (byId != null) return byId;
        String s = q.toLowerCase(Locale.ROOT).trim();
        Agent partial = null;
        for (Agent a : list(false)) {
            String n = a.name.toLowerCase(Locale.ROOT);
            if (n.equals(s)) return a;
            if (partial == null && (n.contains(s) || s.contains(n))) partial = a;
        }
        return partial;
    }

    private void putAgent(Agent a) {
        List<Map<String, Object>> l = readList("agents.json");
        boolean found = false;
        for (int i = 0; i < l.size(); i++) {
            if (MiniJson.str(l.get(i), "id", "").equals(a.id)) { l.set(i, a.toMap()); found = true; break; }
        }
        if (!found) l.add(a.toMap());
        writeList("agents.json", l);
    }

    /** New agent: fresh id, OFF unless the caller turned it on, run history empty. */
    Agent create(Agent draft) {
        synchronized (LOCK) {
            Agent a = draft.copy();
            if (a.id.isEmpty() || get(a.id) != null) a.id = newId("A");
            a.created = a.updated = System.currentTimeMillis();
            a.runs = new ArrayList<>();
            a.archived = false;
            a.scheduleId = "";
            normalize(a);
            if (a.name.isEmpty()) a.name = "Agent " + a.id;
            putAgent(a);
            return a;
        }
    }

    /** Edits the config of an existing agent. id, created, run history, notes, counters and schedule are kept. */
    Agent update(String id, Agent changes) {
        synchronized (LOCK) {
            Agent a = get(id);
            if (a == null) return null;
            a.name = changes.name == null || changes.name.trim().isEmpty() ? a.name : changes.name;
            a.goal = changes.goal == null || changes.goal.trim().isEmpty() ? a.goal : changes.goal;
            a.tools = new ArrayList<>(changes.tools);
            a.trigger = changes.trigger; a.time = changes.time; a.everyHours = changes.everyHours; a.weekday = changes.weekday;
            a.phrase = changes.phrase;
            a.recipients = changes.recipients; a.recipientsFromTable = changes.recipientsFromTable; a.dailyCap = changes.dailyCap;
            a.approvals = new ArrayList<>(changes.approvals); a.approvalKeywords = changes.approvalKeywords;
            a.paidWork = changes.paidWork; a.preApproved = changes.preApproved;
            if (changes.table != null && !changes.table.trim().isEmpty()) a.table = changes.table;
            a.handoffs = new ArrayList<>(changes.handoffs);
            a.updated = System.currentTimeMillis();
            normalize(a);
            putAgent(a);
            return a;
        }
    }

    /** Saves runtime fields (schedule id, on/off) without touching the config. */
    Agent setOn(String id, boolean on) {
        synchronized (LOCK) {
            Agent a = get(id);
            if (a == null) return null;
            if (a.archived && on) return a;            // archived agents stay OFF until un-archived
            a.on = on;
            a.updated = System.currentTimeMillis();
            putAgent(a);
            return a;
        }
    }

    void setScheduleId(String id, String scheduleId) {
        synchronized (LOCK) {
            Agent a = get(id);
            if (a == null) return;
            a.scheduleId = scheduleId == null ? "" : scheduleId;
            putAgent(a);
        }
    }

    /** "Delete" for agents: hidden, OFF, kept forever. */
    Agent archive(String id, boolean archived) {
        synchronized (LOCK) {
            Agent a = get(id);
            if (a == null) return null;
            a.archived = archived;
            if (archived) a.on = false;
            a.updated = System.currentTimeMillis();
            putAgent(a);
            return a;
        }
    }

    /** Keeps the last 30 results in the agent; older ones move to archive_runs.jsonl (never thrown away). */
    Agent addRun(String id, String status, String result, String source) {
        synchronized (LOCK) {
            Agent a = get(id);
            if (a == null) return null;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("at", System.currentTimeMillis());
            r.put("status", status == null ? "" : status);
            r.put("source", source == null ? "" : source);
            r.put("result", result == null ? "" : (result.length() > 3000 ? result.substring(0, 3000) + "…" : result));
            a.runs.add(r);
            while (a.runs.size() > MAX_RUNS) {
                Map<String, Object> old = a.runs.remove(0);
                Map<String, Object> arc = new LinkedHashMap<>(old);
                arc.put("agent", a.id);
                appendLine(new File(dir, "archive_runs.jsonl"), arc);
            }
            putAgent(a);
            return a;
        }
    }

    /** Agent memory / log notes. When too long, the oldest half moves to archive_notes.jsonl. */
    Agent addNote(String id, String note) {
        synchronized (LOCK) {
            Agent a = get(id);
            if (a == null || note == null || note.trim().isEmpty()) return a;
            String line = new SimpleDateFormat("d MMM HH:mm", Locale.ROOT).format(new Date()) + " — " + note.trim().replace('\n', ' ');
            if (line.length() > 600) line = line.substring(0, 600) + "…";
            a.notes = a.notes.isEmpty() ? line : a.notes + "\n" + line;
            if (a.notes.length() > MAX_NOTES_CHARS) {
                int cut = a.notes.indexOf('\n', a.notes.length() / 2);
                if (cut < 0) cut = a.notes.length() / 2;
                Map<String, Object> arc = new LinkedHashMap<>();
                arc.put("agent", a.id);
                arc.put("at", System.currentTimeMillis());
                arc.put("notes", a.notes.substring(0, cut));
                appendLine(new File(dir, "archive_notes.jsonl"), arc);
                a.notes = a.notes.substring(cut).trim();
            }
            putAgent(a);
            return a;
        }
    }

    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()); }

    int sendsToday(String id) {
        Agent a = get(id);
        return a == null || !today().equals(a.sendsDay) ? 0 : a.sendsCount;
    }

    /** Counts one outgoing email/message for the daily cap. */
    int noteSend(String id) {
        synchronized (LOCK) {
            Agent a = get(id);
            if (a == null) return 0;
            String d = today();
            if (!d.equals(a.sendsDay)) { a.sendsDay = d; a.sendsCount = 0; }
            a.sendsCount++;
            putAgent(a);
            return a.sendsCount;
        }
    }

    /** null = under the cap (or no cap). */
    String capBlock(Agent a) {
        if (a == null || a.dailyCap <= 0) return null;
        int n = sendsToday(a.id);
        return n >= a.dailyCap ? "aaj ki limit (" + a.dailyCap + ") poori ho gayi" : null;
    }

    // ------------------------------------------------------------------ approval policy

    /** Which approval kinds this outgoing content needs (empty = none). Forced kinds are checked for every agent. */
    static List<String> approvalNeeded(Agent a, String tool, String text, Map<String, Object> row) {
        List<String> out = new ArrayList<>();
        String t = text == null ? "" : text;
        if (MONEY.matcher(t).find()) out.add("money");
        boolean paid = (a != null && a.paidWork) || rowPaid(row);
        if (paid && DELIVERY.matcher(t).find()) out.add("final_paid_delivery");
        if (a != null) {
            if ("email_compose".equals(tool) && a.approvals.contains("any_email")) out.add("any_email");
            if ("whatsapp_chat".equals(tool) && a.approvals.contains("any_whatsapp")) out.add("any_whatsapp");
            String low = t.toLowerCase(Locale.ROOT);
            for (String kw : MiniJson.strings(a.approvalKeywords)) {
                if (kw.length() >= 3 && low.contains(kw.toLowerCase(Locale.ROOT))) out.add("keyword:" + kw.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d,]{2,})");

    /** A lead/order row that is a PAID order (status, or a package/price with an amount of 100+ that is not free). */
    static boolean rowPaid(Map<String, Object> row) {
        if (row == null) return false;
        String st = MiniJson.str(row, "status", "").toLowerCase(Locale.ROOT).trim();
        if (PAID_STATUSES.contains(st)) return true;
        for (String k : new String[]{"package", "service", "plan", "price", "amount", "order"}) {
            String v = MiniJson.str(row, k, "").toLowerCase(Locale.ROOT);
            if (v.isEmpty() || v.contains("free") || v.matches(".*(₹|rs\\.?)\\s*0\\b.*")) continue;
            Matcher m = AMOUNT.matcher(v);
            while (m.find()) {
                try { if (Long.parseLong(m.group(1).replace(",", "")) >= 100) return true; } catch (Exception ignored) { }
            }
        }
        return false;
    }

    static String approvalLabel(String kind) {
        if ("money".equals(kind)) return "paise / payment wali baat";
        if ("final_paid_delivery".equals(kind)) return "paid kaam ki final delivery (payment mila?)";
        if ("any_email".equals(kind)) return "har email";
        if ("any_whatsapp".equals(kind)) return "har WhatsApp message";
        if (kind != null && kind.startsWith("keyword:")) return "'" + kind.substring(8) + "' wali baat";
        return kind;
    }

    // ------------------------------------------------------------------ approvals queue

    List<Map<String, Object>> approvals(String status) {
        synchronized (LOCK) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> m : readList("approvals.json")) {
                if (status == null || status.equals(MiniJson.str(m, "status", ""))) out.add(m);
            }
            return out;
        }
    }

    /** Agent asks the owner. Same agent + kind + recipient already pending = same request (summary refreshed). */
    Map<String, Object> requestApproval(String agentId, String kind, String to, String summary) {
        synchronized (LOCK) {
            Agent a = get(agentId);
            List<Map<String, Object>> l = readList("approvals.json");
            String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
            String t = to == null ? "" : to.trim().toLowerCase(Locale.ROOT);
            for (Map<String, Object> m : l) {
                if ("pending".equals(MiniJson.str(m, "status", "")) && agentId.equals(MiniJson.str(m, "agent", ""))
                        && k.equals(MiniJson.str(m, "kind", "")) && t.equals(MiniJson.str(m, "to", ""))) {
                    m.put("summary", summary == null ? "" : summary);
                    m.put("updated", System.currentTimeMillis());
                    writeList("approvals.json", l);
                    return m;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", newId("P"));
            m.put("agent", agentId);
            m.put("agent_name", a == null ? agentId : a.name);
            m.put("kind", k);
            m.put("to", t);
            m.put("summary", summary == null ? "" : summary);
            m.put("status", "pending");
            m.put("created", System.currentTimeMillis());
            l.add(m);
            writeList("approvals.json", l);
            return m;
        }
    }

    /** Owner's haan / na. Returns the updated item, or null when there is no such pending request. */
    Map<String, Object> decide(String approvalId, boolean approve) {
        synchronized (LOCK) {
            List<Map<String, Object>> l = readList("approvals.json");
            for (Map<String, Object> m : l) {
                if (MiniJson.str(m, "id", "").equalsIgnoreCase(approvalId == null ? "" : approvalId.trim())
                        && "pending".equals(MiniJson.str(m, "status", ""))) {
                    m.put("status", approve ? "approved" : "rejected");
                    m.put("decided", System.currentTimeMillis());
                    writeList("approvals.json", l);
                    return m;
                }
            }
            return null;
        }
    }

    /**
     * True when the owner approved EVERY kind for this agent + recipient (each approval is used once).
     * Nothing is consumed unless all kinds are approved.
     */
    boolean consumeApprovals(String agentId, List<String> kinds, String to) {
        if (kinds == null || kinds.isEmpty()) return true;
        synchronized (LOCK) {
            List<Map<String, Object>> l = readList("approvals.json");
            String t = to == null ? "" : to.trim().toLowerCase(Locale.ROOT);
            List<Map<String, Object>> hit = new ArrayList<>();
            for (String k : kinds) {
                Map<String, Object> found = null;
                for (Map<String, Object> m : l) {
                    if (hit.contains(m)) continue;
                    if ("approved".equals(MiniJson.str(m, "status", "")) && agentId.equals(MiniJson.str(m, "agent", ""))
                            && k.equals(MiniJson.str(m, "kind", "")) && t.equals(MiniJson.str(m, "to", ""))) { found = m; break; }
                }
                if (found == null) return false;
                hit.add(found);
            }
            for (Map<String, Object> m : hit) { m.put("status", "used"); m.put("used", System.currentTimeMillis()); }
            writeList("approvals.json", l);
            return true;
        }
    }

    /** Approved-but-unused items of one agent (given to the agent's next run). */
    List<Map<String, Object>> approvedFor(String agentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : approvals("approved")) if (agentId.equals(MiniJson.str(m, "agent", ""))) out.add(m);
        return out;
    }

    // ------------------------------------------------------------------ recipients

    /** null = allowed; otherwise why not. Fixed list from the owner, or (if enabled) a contactable row of the agent's table. */
    String recipientBlock(Agent a, String value) {
        if (a == null) return "agent nahi mila";
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return "recipient khaali hai";
        String digits = v.replaceAll("[^0-9]", "");
        for (String tok : a.recipients.split("[,;/|\\n ]+")) {
            String t = tok.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (v.contains("@") && t.equals(v)) return null;
            String td = t.replaceAll("[^0-9]", "");
            if (digits.length() >= 10 && td.length() >= 10 && td.endsWith(digits.substring(digits.length() - 10))) return null;
        }
        if (!a.recipientsFromTable) return "ye recipient agent ki allowed list mein nahi hai";
        Map<String, Object> row = findRow(a.table, v);
        if (row == null) return "ye contact agent ki table '" + a.table + "' mein nahi hai";
        String st = MiniJson.str(row, "status", "").toLowerCase(Locale.ROOT);
        if (BLOCKED_STATUSES.contains(st)) return "is contact ka status '" + st + "' hai — ise kabhi message nahi";
        return null;
    }

    // ------------------------------------------------------------------ data tables

    private File tableFile(String table) { return new File(new File(dir, "tables"), cleanTable(table) + ".json"); }

    List<Map<String, Object>> rows(String table) {
        synchronized (LOCK) {
            List<Map<String, Object>> out = new ArrayList<>();
            String s = readText(tableFile(table));
            if (s == null || s.trim().isEmpty()) return out;
            try {
                Map<String, Object> t = MiniJson.parseObject(s);
                for (Object o : MiniJson.list(t, "rows")) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) o;
                        out.add(r);
                    }
                }
            } catch (Exception e) {
                writeText(new File(tableFile(table).getPath() + ".broken-" + System.currentTimeMillis()), s);
            }
            return out;
        }
    }

    private void writeRows(String table, List<Map<String, Object>> rows) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", cleanTable(table));
        t.put("updated", System.currentTimeMillis());
        t.put("rows", new ArrayList<Object>(rows));
        writeText(tableFile(table), MiniJson.pretty(t));
    }

    static String normEmail(String e) { return e == null ? "" : e.trim().toLowerCase(Locale.ROOT).replaceAll("^mailto:", ""); }

    static String domain(String w) {
        if (w == null) return "";
        String s = w.trim().toLowerCase(Locale.ROOT).replaceAll("^https?://", "").replaceAll("^www\\.", "");
        int sl = s.indexOf('/');
        if (sl >= 0) s = s.substring(0, sl);
        return s.contains(".") ? s : "";
    }

    static String normName(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u0900-\\u097F]+", ""); }

    /** Same lead = same email, OR same website domain, OR same business name + city. */
    static boolean sameRow(Map<String, Object> a, Map<String, Object> b) {
        String ea = normEmail(MiniJson.str(a, "email", "")), eb = normEmail(MiniJson.str(b, "email", ""));
        if (!ea.isEmpty() && ea.equals(eb)) return true;
        String da = domain(MiniJson.str(a, "website", "")), db = domain(MiniJson.str(b, "website", ""));
        if (!da.isEmpty() && da.equals(db)) return true;
        String na = normName(MiniJson.str(a, "business", "")), nb = normName(MiniJson.str(b, "business", ""));
        String ca = normName(MiniJson.str(a, "city", "")), cb = normName(MiniJson.str(b, "city", ""));
        return !na.isEmpty() && na.equals(nb) && ca.equals(cb);
    }

    /** By row _id, email or website. */
    Map<String, Object> findRow(String table, String key) {
        if (key == null || key.trim().isEmpty()) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (Map<String, Object> r : rows(table)) {
            if (MiniJson.str(r, "_id", "").equalsIgnoreCase(k)) return r;
            if (!normEmail(MiniJson.str(r, "email", "")).isEmpty() && normEmail(MiniJson.str(r, "email", "")).equals(normEmail(k))) return r;
            String d = domain(k);
            if (!d.isEmpty() && !k.contains("@") && d.equals(domain(MiniJson.str(r, "website", "")))) return r;
        }
        return null;
    }

    /**
     * Insert or merge one row. Never makes a duplicate (see {@link #sameRow}); never removes a row.
     * Rules: status "deleted/removed" becomes "archived"; incoming status "new" never overwrites an existing status;
     * a row whose status is bounced / unsubscribed / do_not_contact / archived is not changed by an agent ("SKIPPED").
     * Returns "INSERTED <id>", "UPDATED <id>: fields" or "SKIPPED <id>: reason".
     */
    String upsert(String table, Map<String, Object> in) {
        synchronized (LOCK) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : in.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
                if (k.isEmpty() || k.equals("_created") || k.equals("_updated")) continue;
                Object v = e.getValue();
                row.put(k, v == null ? "" : (v instanceof Map || v instanceof List) ? MiniJson.write(v) : String.valueOf(v).trim());
            }
            if (row.containsKey("email")) row.put("email", normEmail(String.valueOf(row.get("email"))));
            String st = MiniJson.str(row, "status", "").toLowerCase(Locale.ROOT).trim();
            if (st.matches("delete.*|remove.*|hata.*|drop.*")) st = "archived";
            if (!st.isEmpty()) row.put("status", st);
            List<Map<String, Object>> rows = rows(table);
            Map<String, Object> hit = null;
            String wantId = MiniJson.str(row, "_id", "");
            for (Map<String, Object> r : rows) {
                if ((!wantId.isEmpty() && wantId.equalsIgnoreCase(MiniJson.str(r, "_id", ""))) || sameRow(r, row)) { hit = r; break; }
            }
            long now = System.currentTimeMillis();
            if (hit == null) {
                if (rows.size() >= MAX_ROWS) return "SKIPPED: table bhar gayi (" + MAX_ROWS + " rows) — owner ko batao";
                boolean hasKey = !normEmail(MiniJson.str(row, "email", "")).isEmpty() || !domain(MiniJson.str(row, "website", "")).isEmpty()
                        || !normName(MiniJson.str(row, "business", "")).isEmpty() || !MiniJson.str(row, "name", "").isEmpty()
                        || !MiniJson.str(row, "title", "").isEmpty();
                if (!hasKey) return "SKIPPED: row mein email / website / business naam kuch to chahiye";
                String id = newId("R");
                Map<String, Object> nr = new LinkedHashMap<>();
                nr.put("_id", id);
                for (Map.Entry<String, Object> e : row.entrySet()) if (!e.getKey().equals("_id")) nr.put(e.getKey(), e.getValue());
                if (MiniJson.str(nr, "status", "").isEmpty()) nr.put("status", "new");
                nr.put("_created", now);
                nr.put("_updated", now);
                rows.add(nr);
                writeRows(table, rows);
                return "INSERTED " + id;
            }
            String old = MiniJson.str(hit, "status", "").toLowerCase(Locale.ROOT);
            String id = MiniJson.str(hit, "_id", "");
            if (BLOCKED_STATUSES.contains(old)) return "SKIPPED " + id + ": pehle se hai, status '" + old + "' (dobara contact nahi)";
            StringBuilder changed = new StringBuilder();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String k = e.getKey();
                if (k.equals("_id")) continue;
                String v = String.valueOf(e.getValue());
                if (k.equals("status") && (v.equals("new") || v.isEmpty())) continue;   // "new" never resets progress
                if (v.isEmpty() && !MiniJson.str(hit, k, "").isEmpty()) continue;       // empty never wipes a value
                if (!v.equals(MiniJson.str(hit, k, null))) {
                    hit.put(k, v);
                    changed.append(changed.length() == 0 ? "" : ", ").append(k);
                }
            }
            if (changed.length() == 0) return "SKIPPED " + id + ": pehle se hai (duplicate), kuch naya nahi";
            hit.put("_updated", now);
            writeRows(table, rows);
            return "UPDATED " + id + ": " + changed;
        }
    }

    /**
     * The OWNER changes a row's status from the app (e.g. marks an order 'paid_order', or un-blocks a lead).
     * Unlike {@link #upsert} this may change a stop status. Still never removes a row. Returns the result line.
     */
    String ownerSetStatus(String table, String key, String status, String note) {
        synchronized (LOCK) {
            String st = status == null ? "" : status.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
            if (st.isEmpty()) return "status likho";
            if (st.matches("delete.*|remove.*")) st = "archived";
            Map<String, Object> want = findRow(table, key);
            if (want == null) return "row nahi mili (email ya _id do)";
            List<Map<String, Object>> rows = rows(table);
            for (Map<String, Object> r : rows) {
                if (MiniJson.str(r, "_id", "").equals(MiniJson.str(want, "_id", ""))) {
                    r.put("status", st);
                    if (note != null && !note.trim().isEmpty()) r.put("owner_note", note.trim());
                    r.put("_updated", System.currentTimeMillis());
                    writeRows(table, rows);
                    return "UPDATED " + MiniJson.str(r, "_id", "") + ": status = " + st;
                }
            }
            return "row nahi mili";
        }
    }

    /** Status counts, e.g. "new 12, emailed 5, bounced 1". */
    String tableStats(String table) {
        Map<String, Integer> c = new LinkedHashMap<>();
        List<Map<String, Object>> rs = rows(table);
        for (Map<String, Object> r : rs) {
            String s = MiniJson.str(r, "status", "");
            if (s.isEmpty()) s = "(no status)";
            c.put(s, c.containsKey(s) ? c.get(s) + 1 : 1);
        }
        StringBuilder sb = new StringBuilder(rs.size() + " rows");
        for (Map.Entry<String, Integer> e : c.entrySet()) sb.append(", ").append(e.getKey()).append(' ').append(e.getValue());
        return sb.toString();
    }

    /** Rows as CSV (all columns in first-seen order). */
    String csv(String table, String status, int limit) {
        List<Map<String, Object>> rs = rows(table);
        List<String> cols = new ArrayList<>();
        for (Map<String, Object> r : rs) for (String k : r.keySet()) if (!cols.contains(k) && !k.equals("_created")) cols.add(k);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) sb.append(i == 0 ? "" : ",").append(cols.get(i));
        sb.append('\n');
        int n = 0;
        for (Map<String, Object> r : rs) {
            if (status != null && !status.isEmpty() && !status.equalsIgnoreCase(MiniJson.str(r, "status", ""))) continue;
            if (limit > 0 && n >= limit) break;
            for (int i = 0; i < cols.size(); i++) {
                String v = MiniJson.str(r, cols.get(i), "");
                if (cols.get(i).equals("_updated")) {
                    try { v = new SimpleDateFormat("d MMM HH:mm", Locale.ROOT).format(new Date(Long.parseLong(v))); } catch (Exception ignored) { }
                }
                if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"").replace('\n', ' ') + "\"";
                sb.append(i == 0 ? "" : ",").append(v);
            }
            sb.append('\n');
            n++;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ drafts (create_agent waits for the owner's haan)

    String saveDraft(Agent a) {
        synchronized (LOCK) {
            List<Map<String, Object>> l = readList("drafts.json");
            Map<String, Object> m = new LinkedHashMap<>();
            String id = newId("D");
            m.put("id", id);
            m.put("at", System.currentTimeMillis());
            m.put("used", false);
            m.put("agent", a.toMap());
            l.add(m);
            while (l.size() > 50) appendLine(new File(dir, "archive_drafts.jsonl"), l.remove(0));
            writeList("drafts.json", l);
            return id;
        }
    }

    Agent draft(String id) {
        for (Map<String, Object> m : readList("drafts.json")) {
            if (MiniJson.str(m, "id", "").equalsIgnoreCase(id == null ? "" : id.trim())) {
                if (MiniJson.bool(m, "used", false)) return null;
                return Agent.fromMap(MiniJson.map(m, "agent"));
            }
        }
        return null;
    }

    void markDraftUsed(String id) {
        synchronized (LOCK) {
            List<Map<String, Object>> l = readList("drafts.json");
            for (Map<String, Object> m : l) if (MiniJson.str(m, "id", "").equalsIgnoreCase(id)) m.put("used", true);
            writeList("drafts.json", l);
        }
    }

    // ------------------------------------------------------------------ templates + .md import

    /** Seeds the built-in GrowthPilot agents once (OFF). Existing agents with those ids are never overwritten. */
    void seedTemplates() {
        synchronized (LOCK) {
            File marker = new File(dir, ".seeded_v1");
            if (marker.exists()) return;
            for (Agent t : AgentTemplates.growthPilot()) {
                if (get(t.id) != null) continue;
                Agent a = t.copy();
                a.on = false;
                a.created = a.updated = System.currentTimeMillis();
                normalize(a);
                putAgent(a);
            }
            try { marker.createNewFile(); } catch (IOException ignored) { }
        }
    }

    /** Agent from a Markdown file: optional front matter (name, tools, trigger, time, every_hours, weekday, phrase,
     *  recipients, recipients_from_table, daily_cap, approvals, approval_keywords, table, paid_work, handoffs); body = goal. */
    Agent importMarkdown(String fileName, String text) {
        Agent a = parseMarkdown(fileName, text);
        return a == null ? null : create(a);
    }

    /** Same as importMarkdown but does not save (the caller checks it first). */
    static Agent parseMarkdown(String fileName, String text) {
        if (text == null) return null;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        Agent a = new Agent();
        String body = text;
        if (body.startsWith("---")) {
            int end = body.indexOf("\n---", 3);
            if (end > 0) {
                for (String line : body.substring(3, end).split("\n")) {
                    int c = line.indexOf(':');
                    if (c <= 0) continue;
                    String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
                    String v = line.substring(c + 1).trim().replaceAll("^[\"'\\[]|[\"'\\]]$", "");
                    switch (k) {
                        case "name": case "title": a.name = v; break;
                        case "tools": case "scopes": a.tools = MiniJson.strings(v); break;
                        case "trigger": a.trigger = v; break;
                        case "time": a.time = v; break;
                        case "every_hours": try { a.everyHours = Integer.parseInt(v.replaceAll("[^0-9]", "")); } catch (Exception ignored) { } break;
                        case "weekday": a.weekday = v; break;
                        case "phrase": case "voice_phrase": a.phrase = v; break;
                        case "recipients": a.recipients = v; break;
                        case "recipients_from_table": a.recipientsFromTable = MiniJson.bool(java.util.Collections.<String, Object>singletonMap("x", v), "x", false); break;
                        case "daily_cap": try { a.dailyCap = Integer.parseInt(v.replaceAll("[^0-9]", "")); } catch (Exception ignored) { } break;
                        case "approvals": a.approvals = MiniJson.strings(v); break;
                        case "approval_keywords": a.approvalKeywords = v; break;
                        case "table": a.table = v; break;
                        case "paid_work": a.paidWork = MiniJson.bool(java.util.Collections.<String, Object>singletonMap("x", v), "x", false); break;
                        case "handoffs": a.handoffs = MiniJson.strings(v); break;
                        default: break;
                    }
                }
                int nl = body.indexOf('\n', end + 4);
                body = nl < 0 ? "" : body.substring(nl + 1);
            }
        }
        if (a.name.isEmpty()) {
            String[] lines = body.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty()) continue;
                if (l.startsWith("# ")) {
                    a.name = l.substring(2).trim();
                    StringBuilder rest = new StringBuilder();
                    for (int j = i + 1; j < lines.length; j++) rest.append(lines[j]).append('\n');
                    body = rest.toString();
                }
                break;
            }
        }
        if (a.name.isEmpty()) {
            String fn = fileName == null ? "" : fileName;
            int dot = fn.lastIndexOf('.');
            if (dot > 0) fn = fn.substring(0, dot);
            a.name = fn.replace('_', ' ').replace('-', ' ').trim();
            if (a.name.isEmpty()) a.name = "Imported agent";
        }
        a.goal = body.trim();
        if (a.goal.isEmpty()) return null;
        a.on = false;
        a.template = "md";
        return a;
    }

    /** Two short lines Maya reads before creating / changing an agent. */
    static String summary(Agent a) {
        StringBuilder tools = new StringBuilder("research, likhna, notes, table");
        for (String t : a.tools) tools.append(", ").append(t);
        String rec = a.recipients.isEmpty() && !a.recipientsFromTable ? "kisi ko message nahi"
                : (a.recipientsFromTable ? "sirf table '" + a.table + "' ke contacts" : "") + (a.recipients.isEmpty() ? "" : (a.recipientsFromTable ? " + " : "sirf ") + a.recipients)
                + (a.dailyCap > 0 ? " (roz max " + a.dailyCap + ")" : "");
        StringBuilder ap = new StringBuilder();
        for (String k : a.approvals) ap.append(ap.length() == 0 ? "" : ", ").append(approvalLabel(k));
        if (!a.approvalKeywords.isEmpty()) ap.append(", ").append(a.approvalKeywords);
        return "'" + a.name + "' — " + firstLine(a.goal, 110) + " | Kab: " + a.triggerText() + ".\n"
                + "Tools: " + tools + " | Bhejna: " + rec + " | Pehle aapki haan: " + ap + ".";
    }

    static String firstLine(String s, int n) {
        if (s == null) return "";
        for (String l : s.split("\n")) {
            String t = l.trim().replaceAll("^[#>*\\-\\s]+", "");
            if (!t.isEmpty()) return t.length() > n ? t.substring(0, n) + "…" : t;
        }
        return "";
    }
}
