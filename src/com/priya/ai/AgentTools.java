package com.priya.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Voice tools of the AI Agent Builder for the Live session (declared + routed through BrainTools, so the delete hard
 * block and PriyaService's owner-order gate wrap them). create_agent / edit_agent are two-step: a draft + 2-line
 * summary first, the real change only after the owner's "haan" (confirm=true with the draft id).
 */
final class AgentTools {

    private AgentTools() {}

    static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "create_agent", "list_agents", "run_agent", "agent_on_off", "edit_agent", "agent_report", "agent_approvals", "agent_approve"));
    /** Only read: allowed without a fresh owner order. */
    static final Set<String> READ_ONLY = new HashSet<>(Arrays.asList("list_agents", "agent_report", "agent_approvals"));

    private static JSONObject str(String d) throws JSONException { return new JSONObject().put("type", "STRING").put("description", d); }
    private static JSONObject integer(String d) throws JSONException { return new JSONObject().put("type", "INTEGER").put("description", d); }
    private static JSONObject bool(String d) throws JSONException { return new JSONObject().put("type", "BOOLEAN").put("description", d); }
    private static JSONObject arr(String d, String... e) throws JSONException {
        JSONObject items = new JSONObject().put("type", "STRING");
        if (e.length > 0) { JSONArray a = new JSONArray(); for (String s : e) a.put(s); items.put("enum", a); }
        return new JSONObject().put("type", "ARRAY").put("items", items).put("description", d);
    }
    private static JSONObject enumStr(String d, String... v) throws JSONException {
        JSONArray a = new JSONArray();
        for (String s : v) a.put(s);
        return new JSONObject().put("type", "STRING").put("description", d).put("enum", a);
    }
    private static JSONObject fn(String name, String desc, JSONObject props, String... req) throws JSONException {
        JSONObject f = new JSONObject().put("name", name).put("description", desc);
        if (props != null) {
            JSONObject p = new JSONObject().put("type", "OBJECT").put("properties", props);
            if (req.length > 0) { JSONArray r = new JSONArray(); for (String s : req) r.put(s); p.put("required", r); }
            f.put("parameters", p);
        }
        return f;
    }

    private static JSONObject agentFields(JSONObject p) throws JSONException {
        return p.put("name", str("Short agent name"))
                .put("goal", str("Goal + numbered steps + rules in Markdown, written by you from the owner's words (what, for whom, output, report)"))
                .put("tools", arr("Tool groups: phone (apps/screen), content (images/voice/video), messages (email/WhatsApp ONLY to allowed recipients), coding (Termux). Research, writing, notes, its table are always on.",
                        "phone", "content", "messages", "coding"))
                .put("trigger", enumStr("When it runs", "manual", "daily", "hourly", "weekdays", "weekly", "voice"))
                .put("time", str("HH:MM 24h (daily / weekdays / weekly; start time for hourly)"))
                .put("every_hours", integer("For hourly: every N hours (1-24)"))
                .put("weekday", str("For weekly: monday..sunday"))
                .put("phrase", str("Voice phrase that runs it, e.g. 'leads dhoondho'"))
                .put("recipients", str("Exact emails / numbers it may message (owner named them)"))
                .put("recipients_from_table", bool("true = may email contacts in its own data table (leads), never bounced/unsubscribed"))
                .put("daily_cap", integer("Max emails/messages per day, 0 = no cap"))
                .put("approvals", arr("Extra 'ask owner first' rules (money + final paid delivery are always on)", "any_email", "any_whatsapp"))
                .put("approval_keywords", str("Comma words: a message containing one needs the owner's haan first"))
                .put("table", str("Data table name, e.g. growthpilot_leads (shared by agents that use the same name)"))
                .put("handoffs", arr("Agent ids this agent may hand work to"))
                .put("turn_on", bool("true = switch ON after creating (owner asked it to run by itself)"));
    }

    static void declare(JSONArray a) throws JSONException {
        a.put(fn("create_agent", "Create an AI agent from the owner's spoken description. Step 1 (confirm=false): you draft name, goal/steps, tools, trigger, approval rules; returns DRAFT_READY + a 2-line summary — read it to the owner and ask 'bana doon?'. Step 2 only after a clear 'haan': confirm=true with draft_id. template = copy a built-in (gp_lead_finder, gp_outreach, gp_form_followup, gp_health_check, gp_paid_worker, gp_pipeline, custom).",
                agentFields(new JSONObject().put("description", str("Owner's words for the agent"))
                        .put("template", str("Optional template id to start from"))
                        .put("confirm", bool("false = draft only (default); true = create the draft after the owner's haan"))
                        .put("draft_id", str("Draft id from DRAFT_READY (confirm=true)")))));
        a.put(fn("edit_agent", "Change an agent (same id, history kept). confirm=false returns DRAFT_READY + new 2-line summary to read; confirm=true + draft_id applies it after the owner's haan.",
                agentFields(new JSONObject().put("agent", str("Agent id or name"))
                        .put("confirm", bool("false = preview (default); true = apply draft"))
                        .put("draft_id", str("Draft id (confirm=true)")))));
        a.put(fn("list_agents", "List AI agents with ON/OFF, trigger, last result.", null));
        a.put(fn("run_agent", "Run an agent now in the background (it must be ON). Returns JOB_STARTED; steps come as [PROGRESS], result as SYSTEM NOTE.",
                new JSONObject().put("agent", str("Agent id or name")).put("input", str("Optional extra instruction for this run")), "agent"));
        a.put(fn("agent_on_off", "Switch an agent ON (its schedule / voice phrase work) or OFF.",
                new JSONObject().put("agent", str("Agent id or name")).put("on", bool("true = ON, false = OFF")), "agent", "on"));
        a.put(fn("agent_report", "Report of one agent (last runs, its table counts, pending approvals, notes) or of all agents when empty.",
                new JSONObject().put("agent", str("Agent id or name; empty = all"))));
        a.put(fn("agent_approvals", "List agent requests waiting for the owner's haan (payment mila?, money email, ...).", null));
        a.put(fn("agent_approve", "Record the owner's answer to an agent approval request. ONLY after the owner clearly said haan (approve=true) or na (false) for THIS request. Approving re-runs the agent to do that action.",
                new JSONObject().put("approval_id", str("Approval id, e.g. P...")).put("approve", bool("true = haan, false = na")), "approval_id", "approve"));
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> strList(JSONObject args, String k) {
        List<String> out = new ArrayList<>();
        JSONArray ja = args.optJSONArray(k);
        if (ja != null) { for (int i = 0; i < ja.length(); i++) if (!ja.optString(i).trim().isEmpty()) out.add(ja.optString(i).trim()); }
        else if (!args.optString(k, "").trim().isEmpty()) out.addAll(MiniJson.strings(args.optString(k)));
        return out;
    }

    /** Copies the fields present in args onto the agent. */
    static void apply(AgentStore.Agent d, JSONObject args) {
        if (!args.optString("name", "").trim().isEmpty()) d.name = args.optString("name").trim();
        if (!args.optString("goal", "").trim().isEmpty()) d.goal = args.optString("goal").trim();
        if (args.has("tools")) d.tools = strList(args, "tools");
        if (!args.optString("trigger", "").trim().isEmpty()) d.trigger = args.optString("trigger").trim();
        if (!args.optString("time", "").trim().isEmpty()) d.time = args.optString("time").trim();
        if (args.has("every_hours")) d.everyHours = args.optInt("every_hours", d.everyHours);
        if (!args.optString("weekday", "").trim().isEmpty()) d.weekday = args.optString("weekday").trim();
        if (args.has("phrase")) d.phrase = args.optString("phrase", "").trim();
        if (args.has("recipients")) d.recipients = args.optString("recipients", "").trim();
        if (args.has("recipients_from_table")) d.recipientsFromTable = args.optBoolean("recipients_from_table", false);
        if (args.has("daily_cap")) d.dailyCap = args.optInt("daily_cap", 0);
        if (args.has("approvals")) d.approvals = strList(args, "approvals");
        if (args.has("approval_keywords")) d.approvalKeywords = args.optString("approval_keywords", "").trim();
        if (!args.optString("table", "").trim().isEmpty()) d.table = args.optString("table").trim();
        if (args.has("handoffs")) d.handoffs = strList(args, "handoffs");
        if (args.has("turn_on")) d.on = args.optBoolean("turn_on", false);
    }

    /** null = fine, else the problem. Drops 'messages' when no recipient rule exists. */
    static String check(AgentStore.Agent d, StringBuilder notes) {
        if (AgentRunner.goalForbidden(d.goal) || AgentRunner.goalForbidden(d.name)) {
            return "BLOCKED: is agent ke goal mein delete/remove/reset jaisa kaam hai — Maya aisa agent nahi banati (archive / status badalna theek hai).";
        }
        if (d.tools.contains("messages") && d.recipients.trim().isEmpty() && !d.recipientsFromTable) {
            d.tools.remove("messages");
            notes.append(" 'messages' hataya: kisko bhejna hai ye nahi bataya (recipients ya table ke contacts).");
        }
        return null;
    }

    static AgentStore.Agent template(AgentStore st, String id) {
        if (id == null || id.trim().isEmpty()) return null;
        String t = id.trim().toLowerCase();
        if (t.equals("custom")) return AgentTemplates.custom();
        for (AgentStore.Agent a : AgentTemplates.growthPilot()) if (a.id.equals(t) || a.name.equalsIgnoreCase(id.trim())) return a;
        AgentStore.Agent existing = st.find(id);
        return existing;
    }

    // ------------------------------------------------------------------ dispatch

    static String run(Context ctx, String name, JSONObject args) {
        AgentStore st = AgentRunner.store(ctx);
        try {
            switch (name) {
                case "create_agent": return create(ctx, st, args);
                case "edit_agent": return edit(ctx, st, args);
                case "list_agents": return list(st);
                case "run_agent": return AgentRunner.start(ctx, args.optString("agent", ""), args.optString("input", ""), true, "voice");
                case "agent_on_off": {
                    AgentStore.Agent a = st.find(args.optString("agent", ""));
                    if (a == null) return "Agent nahi mila. list_agents dekho.";
                    if (a.archived) return "Agent archived hai — Menu > AI Agents mein Unarchive karo.";
                    a = st.setOn(a.id, args.optBoolean("on", false));
                    String sch = AgentRunner.syncSchedule(ctx, a);
                    return "VERIFIED: '" + a.name + "' ab " + (a.on ? "ON" : "OFF") + " hai" + (sch.isEmpty() ? "" : " — " + sch)
                            + (a.on && a.trigger.equals("voice") ? " — phrase: \"" + a.phrase + "\"" : "") + ".";
                }
                case "agent_report": return report(st, args.optString("agent", ""));
                case "agent_approvals": {
                    List<Map<String, Object>> p = st.approvals("pending");
                    if (p.isEmpty()) return "Koi approval pending nahi.";
                    StringBuilder sb = new StringBuilder("PENDING (owner ki haan chahiye):\n");
                    for (Map<String, Object> m : p) sb.append("- ").append(MiniJson.str(m, "id", "")).append(" | ").append(MiniJson.str(m, "agent_name", ""))
                            .append(" | ").append(AgentStore.approvalLabel(MiniJson.str(m, "kind", ""))).append(" → ").append(MiniJson.str(m, "to", ""))
                            .append(": ").append(MiniJson.str(m, "summary", "")).append('\n');
                    return sb.toString();
                }
                case "agent_approve": {
                    boolean yes = args.optBoolean("approve", false);
                    Map<String, Object> m = st.decide(args.optString("approval_id", ""), yes);
                    if (m == null) return "Is id ki pending request nahi mili. agent_approvals dekho.";
                    if (!yes) return "VERIFIED: request " + MiniJson.str(m, "id", "") + " mana kar di. Agent ye kaam nahi karega.";
                    String agentId = MiniJson.str(m, "agent", "");
                    String r = AgentRunner.start(ctx, agentId, "Owner ne approval " + MiniJson.str(m, "id", "") + " (" + MiniJson.str(m, "kind", "") + " → "
                            + MiniJson.str(m, "to", "") + ") par HAAN kaha: " + MiniJson.str(m, "summary", "") + ". Ab sirf yahi approved kaam karo, phir report.", true, "approval");
                    return "VERIFIED: approve ho gaya. " + r;
                }
                default: return null;
            }
        } catch (Throwable e) {
            return "FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String create(Context ctx, AgentStore st, JSONObject args) {
        if (args.optBoolean("confirm", false)) {
            String did = args.optString("draft_id", "").trim();
            AgentStore.Agent d = st.draft(did);
            if (d == null) return "Draft nahi mila ya pehle hi use ho chuka. Pehle create_agent(confirm=false).";
            if (!d.id.isEmpty() && st.get(d.id) != null) return "Ye draft ek edit ka hai — edit_agent(confirm=true) use karo.";
            StringBuilder notes = new StringBuilder();
            String bad = check(d, notes);
            if (bad != null) return bad;
            AgentStore.Agent a = st.create(d);
            st.markDraftUsed(did);
            String sch = AgentRunner.syncSchedule(ctx, a);
            return "VERIFIED: agent ban gaya [" + a.id + "] " + (a.on ? "ON" : "OFF") + (sch.isEmpty() ? "" : " — " + sch) + ".\n" + AgentStore.summary(a)
                    + (a.on ? "" : "\nOwner kahe to agent_on_off(on=true).");
        }
        AgentStore.Agent base = template(st, args.optString("template", ""));
        AgentStore.Agent d = base == null ? new AgentStore.Agent() : base.copy();
        d.id = "";
        d.on = false;
        apply(d, args);
        String desc = args.optString("description", "").trim();
        if (d.goal.trim().isEmpty()) d.goal = desc;
        if (d.goal.trim().isEmpty()) return "Agent kya kare? Owner ke shabd 'description' ya poora 'goal' do.";
        if (d.name.trim().isEmpty()) d.name = AgentStore.firstLine(desc.isEmpty() ? d.goal : desc, 40);
        StringBuilder notes = new StringBuilder();
        String bad = check(d, notes);
        if (bad != null) return bad;
        AgentStore.normalize(d);
        String did = st.saveDraft(d);
        return "DRAFT_READY " + did + "\n" + AgentStore.summary(d) + "\nBanne ke baad: " + (d.on ? "ON" : "OFF") + "." + notes
                + "\n(Owner ko ye 2 line chhote mein suna kar poochho 'bana doon?'. Saaf 'haan' par create_agent(confirm=true, draft_id='" + did + "'). Badlav chahiye to naya create_agent(confirm=false).)";
    }

    private static String edit(Context ctx, AgentStore st, JSONObject args) {
        if (args.optBoolean("confirm", false)) {
            String did = args.optString("draft_id", "").trim();
            AgentStore.Agent d = st.draft(did);
            if (d == null || d.id.isEmpty() || st.get(d.id) == null) return "Edit draft nahi mila. Pehle edit_agent(confirm=false).";
            StringBuilder notes = new StringBuilder();
            String bad = check(d, notes);
            if (bad != null) return bad;
            AgentStore.Agent a = st.update(d.id, d);
            st.markDraftUsed(did);
            String sch = AgentRunner.syncSchedule(ctx, a);
            return "VERIFIED: '" + a.name + "' badal gaya (id wahi " + a.id + ", history bachi)" + (sch.isEmpty() ? "" : " — " + sch) + ".\n" + AgentStore.summary(a);
        }
        AgentStore.Agent a = st.find(args.optString("agent", ""));
        if (a == null) return "Agent nahi mila. list_agents dekho.";
        AgentStore.Agent d = a.copy();
        boolean wasOn = d.on;
        apply(d, args);
        d.on = wasOn;                                        // ON/OFF only via agent_on_off
        StringBuilder notes = new StringBuilder();
        String bad = check(d, notes);
        if (bad != null) return bad;
        AgentStore.normalize(d);
        String did = st.saveDraft(d);
        return "DRAFT_READY " + did + " (edit of " + a.id + ")\nNaya: " + AgentStore.summary(d) + notes
                + "\n(Owner ko badlav suna kar poochho 'badal doon?'. Saaf 'haan' par edit_agent(confirm=true, draft_id='" + did + "').)";
    }

    private static String list(AgentStore st) {
        List<AgentStore.Agent> l = st.list(false);
        if (l.isEmpty()) return "Koi agent nahi hai.";
        StringBuilder sb = new StringBuilder("AGENTS:\n");
        for (AgentStore.Agent a : l) {
            sb.append("- ").append(a.id).append(" [").append(a.on ? "ON" : "OFF").append("] ").append(a.name).append(" — ").append(a.triggerText());
            if (!a.runs.isEmpty()) {
                Map<String, Object> r = a.runs.get(a.runs.size() - 1);
                sb.append(" | last: ").append(MiniJson.str(r, "status", "")).append(" ").append(GeminiRest.clip(MiniJson.str(r, "result", "").replace('\n', ' '), 80));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String report(AgentStore st, String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            StringBuilder sb = new StringBuilder(list(st));
            int p = st.approvals("pending").size();
            if (p > 0) sb.append(p).append(" approval owner ki haan ke intezaar mein (agent_approvals).\n");
            return sb.toString();
        }
        AgentStore.Agent a = st.find(ref);
        if (a == null) return "Agent nahi mila.";
        StringBuilder sb = new StringBuilder("AGENT REPORT ").append(a.id).append(" [").append(a.on ? "ON" : "OFF").append(a.archived ? ", archived" : "").append("]\n")
                .append(AgentStore.summary(a)).append('\n')
                .append("Table '").append(a.table).append("': ").append(st.tableStats(a.table)).append('\n');
        if (a.dailyCap > 0) sb.append("Aaj bheje: ").append(st.sendsToday(a.id)).append('/').append(a.dailyCap).append('\n');
        int n = 0;
        for (int i = a.runs.size() - 1; i >= 0 && n < 5; i--, n++) {
            Map<String, Object> r = a.runs.get(i);
            sb.append("- ").append(new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.ROOT).format(new java.util.Date(MiniJson.lng(r, "at", 0))))
                    .append(" ").append(MiniJson.str(r, "status", "")).append(": ").append(GeminiRest.clip(MiniJson.str(r, "result", "").replace('\n', ' '), 220)).append('\n');
        }
        for (Map<String, Object> m : st.approvals("pending")) {
            if (a.id.equals(MiniJson.str(m, "agent", ""))) sb.append("PENDING ").append(MiniJson.str(m, "id", "")).append(": ").append(MiniJson.str(m, "summary", "")).append('\n');
        }
        if (!a.notes.isEmpty()) sb.append("Notes: ").append(GeminiRest.clip(a.notes.substring(Math.max(0, a.notes.length() - 600)), 600)).append('\n');
        return sb.toString();
    }
}
