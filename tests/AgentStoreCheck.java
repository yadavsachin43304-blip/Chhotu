package com.priya.ai;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-JVM checks for AgentStore (not part of the APK: build.sh compiles only src/).
 * Run:  javac -encoding UTF-8 -d /tmp/as src/com/priya/ai/MiniJson.java src/com/priya/ai/AgentStore.java \
 *           src/com/priya/ai/AgentTemplates.java tests/AgentStoreCheck.java
 *       java -cp /tmp/as com.priya.ai.AgentStoreCheck
 */
public class AgentStoreCheck {
    static int fails = 0;

    static void ok(String what, boolean cond, Object got) {
        System.out.println((cond ? "PASS " : "FAIL ") + what + (got == null ? "" : " -> " + got));
        if (!cond) fails++;
    }

    static Map<String, Object> row(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] x) throws Exception {
        File base = Files.createTempDirectory("agentstore").toFile();
        AgentStore st = new AgentStore(base);

        // ---------------------------------------------------------------- MiniJson round trip
        Map<String, Object> j = row("a", "Hindi नमस्ते \"quote\" \\ back\nline", "b", "₹2,999");
        j.put("n", 42L);
        j.put("l", new ArrayList<Object>(Arrays.asList("x", true, null, 1.5)));
        Map<String, Object> back = MiniJson.parseObject(MiniJson.pretty(j));
        ok("json round trip", MiniJson.write(j).equals(MiniJson.write(back)), MiniJson.write(back));

        // ---------------------------------------------------------------- create / edit keeps id
        AgentStore.Agent d = new AgentStore.Agent();
        d.name = "Test Agent";
        d.goal = "Find cafes in Pune";
        d.tools = new ArrayList<>(Arrays.asList("phone", "messages", "bogus"));
        d.trigger = "daily";
        d.time = "7:5";
        AgentStore.Agent a = st.create(d);
        ok("create gives id", a.id.startsWith("A") && a.id.length() > 3, a.id);
        ok("create is OFF by default", !a.on, null);
        ok("bad tool dropped", a.tools.equals(Arrays.asList("phone", "messages")), a.tools);
        ok("time normalised", a.time.equals("07:05"), a.time);
        ok("forced approvals present", a.approvals.containsAll(Arrays.asList(AgentStore.FORCED_APPROVALS)), a.approvals);
        st.addRun(a.id, "DONE", "first run", "test");
        long created = st.get(a.id).created;

        AgentStore.Agent ch = st.get(a.id).copy();
        ch.name = "Renamed Agent";
        ch.goal = "New goal";
        ch.approvals = new ArrayList<>();                  // owner tries to drop every approval rule
        ch.trigger = "hourly";
        ch.everyHours = 3;
        AgentStore.Agent ed = st.update(a.id, ch);
        ok("edit keeps id", ed.id.equals(a.id), ed.id);
        ok("edit changed name/goal", ed.name.equals("Renamed Agent") && ed.goal.equals("New goal"), ed.name);
        ok("edit keeps created + history", ed.created == created && ed.runs.size() == 1, ed.runs.size());
        ok("edit cannot remove forced approvals", ed.approvals.contains("money") && ed.approvals.contains("final_paid_delivery"), ed.approvals);
        ok("only one agent with that id", count(st, a.id) == 1, count(st, a.id));

        // ---------------------------------------------------------------- archive never deletes
        for (Method m : AgentStore.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            ok("no delete-like method: " + m.getName(), !(n.contains("delete") || n.startsWith("remove") || n.contains("wipe") || n.contains("purge")), null);
        }
        st.archive(a.id, true);
        ok("archived hidden from list", count(st.list(false), a.id) == 0, null);
        ok("archived still stored", st.get(a.id) != null && st.get(a.id).archived && !st.get(a.id).on, null);
        ok("archived agent cannot be switched ON", !st.setOn(a.id, true).on, null);
        st.archive(a.id, false);
        ok("unarchive brings it back", count(st.list(false), a.id) == 1, null);
        for (int i = 0; i < 35; i++) st.addRun(a.id, "DONE", "run " + i, "test");
        ok("history keeps last 30", st.get(a.id).runs.size() == AgentStore.MAX_RUNS, st.get(a.id).runs.size());
        File arc = new File(base, "agents/archive_runs.jsonl");
        int arcLines = arc.exists() ? Files.readAllLines(arc.toPath()).size() : 0;
        ok("older runs archived, not lost (1 + 35 - 30 = 6)", arcLines == 6, arcLines);

        // ---------------------------------------------------------------- table upsert dedupe
        String t = "leads_test";
        String r1 = st.upsert(t, row("business", "Glow Salon", "city", "Pune", "email", "Info@Glow.in", "website", "https://www.glow.in/contact", "status", "new"));
        ok("insert", r1.startsWith("INSERTED"), r1);
        String r2 = st.upsert(t, row("business", "Glow Salon", "city", "Pune", "email", "info@glow.in"));
        ok("same email (case) = duplicate, not inserted", !r2.startsWith("INSERTED"), r2);
        String r3 = st.upsert(t, row("business", "Glow Salon & Spa", "email", "hello@glow.in", "website", "glow.in"));
        ok("same website domain = merged", r3.startsWith("UPDATED"), r3);
        String r4 = st.upsert(t, row("business", "glow salon & spa", "city", "pune"));
        ok("same business+city = duplicate", !r4.startsWith("INSERTED"), r4);
        ok("still one row", st.rows(t).size() == 1, st.rows(t).size());
        st.upsert(t, row("email", "hello@glow.in", "status", "emailed"));
        String r5 = st.upsert(t, row("email", "hello@glow.in", "status", "new"));
        ok("'new' never resets progress", "emailed".equals(MiniJson.str(st.findRow(t, "hello@glow.in"), "status", "")), r5);
        st.upsert(t, row("business", "Bean Cafe", "city", "Nashik", "email", "bean@cafe.com", "status", "bounced"));
        String r6 = st.upsert(t, row("business", "Bean Cafe", "city", "Nashik", "email", "bean@cafe.com", "status", "new"));
        ok("bounced lead skipped on re-find", r6.startsWith("SKIPPED"), r6);
        String r7 = st.upsert(t, row("email", "bean@cafe.com", "status", "emailed"));
        ok("bounced lead never re-opened by an agent", r7.startsWith("SKIPPED")
                && "bounced".equals(MiniJson.str(st.findRow(t, "bean@cafe.com"), "status", "")), r7);
        st.upsert(t, row("business", "Fit Gym", "city", "Mumbai", "email", "fit@gym.in"));
        String r8 = st.upsert(t, row("email", "fit@gym.in", "status", "deleted"));
        ok("'deleted' becomes 'archived', row kept", "archived".equals(MiniJson.str(st.findRow(t, "fit@gym.in"), "status", "")) && st.rows(t).size() == 3, r8);
        String r9 = st.upsert(t, row("city", "Pune"));
        ok("row without identity skipped", r9.startsWith("SKIPPED"), r9);
        ok("owner can set a stop status back", st.ownerSetStatus(t, "bean@cafe.com", "new", "re-checked").startsWith("UPDATED"), null);

        // ---------------------------------------------------------------- approval rules
        AgentStore.Agent bare = new AgentStore.Agent();
        bare.approvals = new ArrayList<>();               // even with no rules at all
        List<String> need = AgentStore.approvalNeeded(bare, "email_compose", "Hi, work done! Please pay ₹2,999 via UPI.", null);
        ok("payment step always needs approval", need.contains("money"), need);
        ok("invoice needs approval", AgentStore.approvalNeeded(bare, "email_compose", "Invoice attached for your audit", null).contains("money"), null);
        List<String> free = AgentStore.approvalNeeded(bare, "email_compose",
                "Hi Glow Salon, here is your FREE Business Health Check (₹0). If you want more detail, our Express Audit is ₹999.", row("package", "FREE Business Health Check"));
        ok("free health check is pre-approved", free.isEmpty(), free);
        Map<String, Object> paidRow = row("email", "x@y.in", "package", "Standard Growth Audit ₹2,999", "status", "payment_requested");
        List<String> fin = AgentStore.approvalNeeded(bare, "email_compose", "Here is your complete audit report (PDF attached).", paidRow);
        ok("final paid delivery needs approval", fin.contains("final_paid_delivery"), fin);
        AgentStore.Agent worker = new AgentStore.Agent();
        worker.paidWork = true;
        ok("paid-work agent: any delivery needs approval",
                AgentStore.approvalNeeded(worker, "email_compose", "Please find the final report", null).contains("final_paid_delivery"), null);
        AgentStore.Agent kwA = new AgentStore.Agent();
        kwA.approvalKeywords = "discount, refund";
        ok("owner keyword rule", AgentStore.approvalNeeded(kwA, "email_compose", "We can offer a Discount", null).contains("keyword:discount"), null);

        AgentStore.Agent pa = st.create(templateCopy("gp_paid_worker"));
        List<String> kinds = Arrays.asList("money");
        ok("no approval yet = not allowed", !st.consumeApprovals(pa.id, kinds, "x@y.in"), null);
        Map<String, Object> req = st.requestApproval(pa.id, "money", "X@y.in", "Work done email bhejun?");
        Map<String, Object> again = st.requestApproval(pa.id, "money", "x@y.in", "Work done email bhejun? (2)");
        ok("same request not duplicated", MiniJson.str(req, "id", "").equals(MiniJson.str(again, "id", "")) && st.approvals("pending").size() == 1, null);
        ok("pending is not approved", !st.consumeApprovals(pa.id, kinds, "x@y.in"), null);
        ok("owner says haan", st.decide(MiniJson.str(req, "id", ""), true) != null, null);
        ok("wrong recipient not covered", !st.consumeApprovals(pa.id, kinds, "other@y.in"), null);
        ok("approved = allowed once", st.consumeApprovals(pa.id, kinds, "x@y.in"), null);
        ok("approval used only once", !st.consumeApprovals(pa.id, kinds, "x@y.in"), null);
        Map<String, Object> req2 = st.requestApproval(pa.id, "final_paid_delivery", "x@y.in", "Payment mila?");
        st.decide(MiniJson.str(req2, "id", ""), false);
        ok("owner said na = never allowed", !st.consumeApprovals(pa.id, Arrays.asList("final_paid_delivery"), "x@y.in"), null);

        // ---------------------------------------------------------------- recipients + cap
        AgentStore.Agent em = templateCopy("gp_outreach");
        em.table = t;
        em = st.create(em);
        ok("table contact allowed", st.recipientBlock(em, "hello@glow.in") == null, st.recipientBlock(em, "hello@glow.in"));
        ok("archived contact blocked", st.recipientBlock(em, "fit@gym.in") != null, st.recipientBlock(em, "fit@gym.in"));
        ok("stranger blocked", st.recipientBlock(em, "random@gmail.com") != null, null);
        AgentStore.Agent noTable = em.copy();
        noTable.recipientsFromTable = false;
        noTable.recipients = "owner@example.com";
        ok("fixed list allowed", st.recipientBlock(noTable, "owner@example.com") == null, null);
        ok("table off = table contact blocked", st.recipientBlock(noTable, "hello@glow.in") != null, null);
        ok("outreach cap is 20", em.dailyCap == 20, em.dailyCap);
        for (int i = 0; i < 20; i++) st.noteSend(em.id);
        ok("cap reached after 20", st.capBlock(st.get(em.id)) != null, st.sendsToday(em.id));

        // ---------------------------------------------------------------- templates
        st.seedTemplates();
        st.seedTemplates();
        int gp = 0, on = 0;
        for (AgentStore.Agent g : st.list(true)) if (g.id.startsWith("gp_")) { gp++; if (g.on) on++; }
        ok("6 GrowthPilot templates seeded once", gp == 6, gp);
        ok("templates OFF by default", on == 0, on);
        AgentStore.Agent pipe = st.get("gp_pipeline");
        ok("pipeline hands off a→e", pipe != null && pipe.handoffs.equals(Arrays.asList("gp_lead_finder", "gp_outreach", "gp_form_followup", "gp_health_check", "gp_paid_worker")), pipe == null ? null : pipe.handoffs);
        ok("paid worker is paid_work", st.get("gp_paid_worker").paidWork, null);
        ok("templates carry brand + form", st.get("gp_outreach").goal.contains("growthpilotconsulting1@gmail.com") && st.get("gp_outreach").goal.contains("viewform"), null);

        // ---------------------------------------------------------------- markdown import
        AgentStore.Agent md = st.importMarkdown("x.md", "---\nname: Review Replier\ntools: phone, messages\ntrigger: weekly\nweekday: friday\ntime: 18:30\nrecipients: me@example.com\n---\n# ignored\nReply to reviews.");
        ok("md import", md != null && md.name.equals("Review Replier") && md.trigger.equals("weekly") && md.time.equals("18:30") && !md.on
                && md.tools.equals(Arrays.asList("phone", "messages")), md == null ? null : AgentStore.summary(md));
        AgentStore.Agent md2 = AgentStore.parseMarkdown("my-agent.md", "# Daily News\nRead the news");
        ok("md heading name", md2 != null && md2.name.equals("Daily News") && md2.goal.equals("Read the news"), md2 == null ? null : md2.name);

        System.out.println(AgentStore.summary(st.get("gp_outreach")));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }

    static AgentStore.Agent templateCopy(String id) {
        for (AgentStore.Agent a : AgentTemplates.growthPilot()) if (a.id.equals(id)) { AgentStore.Agent c = a.copy(); c.id = ""; return c; }
        throw new IllegalStateException(id);
    }

    static int count(AgentStore st, String id) { return count(st.list(true), id); }

    static int count(List<AgentStore.Agent> l, String id) {
        int n = 0;
        for (AgentStore.Agent a : l) if (a.id.equals(id)) n++;
        return n;
    }
}
