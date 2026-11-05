package com.priya.ai;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Plain-JVM checks for ProgressText (not part of the APK: build.sh compiles only src/).
 * Run:  javac -encoding UTF-8 -d /tmp/pt src/com/priya/ai/ProgressText.java tests/ProgressTextCheck.java
 *       java -cp /tmp/pt com.priya.ai.ProgressTextCheck
 */
public class ProgressTextCheck {
    static int fails = 0;

    static ProgressText.Args a(String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return k -> m.containsKey(k) ? m.get(k) : "";
    }

    static void eq(String what, String got, String want) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "PASS " : "FAIL ") + what + " -> " + got);
        if (!ok) { fails++; System.out.println("     want: " + want); }
    }

    static void has(String what, String got, String part) {
        boolean ok = got != null && got.contains(part);
        System.out.println((ok ? "PASS " : "FAIL ") + what + " -> " + got);
        if (!ok) { fails++; System.out.println("     should contain: " + part); }
    }

    public static void main(String[] x) {
        ProgressText.Args none = a();
        eq("first step", ProgressText.step(null, none, null, "open_app", a("app_name", "WhatsApp")), "Kaam shuru: WhatsApp khol rahi hoon");
        eq("open ok + search", ProgressText.step("open_app", a("app_name", "WhatsApp"), "VERIFIED: WhatsApp screen par khul gaya.",
                "type_text", a("text", "Rahul", "field", "Search")), "WhatsApp khol liya, ab 'Rahul' search kar rahi hoon");
        eq("open failed", ProgressText.done("open_app", a("app_name", "CapCut"), "FAILED: CapCut kholne ki request bheji, par screen par nahi khula"), "CapCut nahi khula");
        eq("package name", ProgressText.doing("open_app", a("app_name", "com.whatsapp")), "Whatsapp khol rahi hoon");
        eq("tap not found", ProgressText.done("click_on_screen", a("text", "Rahul"), "'Rahul' screen par nahi mila. read_screen se..."), "'Rahul' nahi mila ya tap nahi hua");
        eq("blocked", ProgressText.done("click_on_screen", a("text", "Delete"), "BLOCKED: Delete ... role ke khilaf"), "ek step safety rule ki wajah se roka gaya");
        eq("send verified", ProgressText.done("press_send", none, "VERIFIED_SENT: message chat mein dikh raha hai"), "message chala gaya, screen par dikh raha hai");
        eq("send not sent", ProgressText.done("press_send", none, "NOT_SENT: box abhi bhara hai"), "message abhi gaya NAHI");
        eq("send unsure", ProgressText.done("press_send", none, "LIKELY_SENT_UNCONFIRMED"), "message ki koshish ki, par gaya ya nahi abhi pakka nahi");
        eq("whatsapp prefilled is not sent", ProgressText.done("whatsapp_chat", a("phone", "98"), "VERIFIED: WhatsApp chat khula aur message box mein text likha hai. Abhi BHEJA NAHI gaya"),
                "WhatsApp chat khol li, message box mein likha hai (bheja nahi)");
        eq("unverified open", ProgressText.done("open_app", a("app_name", "Chrome"), "UNVERIFIED: Chrome kholne ki request bheji"), "Chrome kholne ki koshish ki, par abhi pakka nahi");
        eq("silent tool", ProgressText.step("recall", a("query", "x"), "...", "use_skill", a("name", "CapCut")), null);
        eq("silent prev, real next", ProgressText.step("recall", none, "...", "open_link", a("url", "https://www.canva.com/design")), "Ab canva.com khol rahi hoon");
        eq("phone back", ProgressText.done("phone_control", a("action", "back"), "Back dabaya."), "ek screen peeche aa gayi");
        eq("type ok", ProgressText.done("type_text", a("text", "Kal milte hain"), "Type kar diya. (Ye sirf likha hai, BHEJA NAHI)"), "'Kal milte hain' likh diya (bheja nahi)");
        eq("long text clipped", ProgressText.doing("type_text", a("text", "abcdefghijklmnopqrstuvwxyz0123456789")), "'abcdefghijklmnopqrstuvwxyz0123…' type kar rahi hoon");
        eq("merge", ProgressText.merge(Arrays.asList("Kaam shuru: WhatsApp khol rahi hoon",
                "WhatsApp khol liya, ab 'Rahul' search kar rahi hoon",
                "'Rahul' search kar diya, ab 'Rahul' par tap kar rahi hoon")),
                "WhatsApp khol liya, 'Rahul' search kar diya, ab 'Rahul' par tap kar rahi hoon");
        eq("search typed", ProgressText.done("type_text", a("text", "Rahul", "field", "Search"), "Type kar diya."), "'Rahul' search kar diya");
        eq("merge single", ProgressText.merge(Arrays.asList("Screen padh li")), "Screen padh li");
        has("outcome ok", String.valueOf(ProgressText.outcome("VERIFIED: x BHEJA NAHI gaya")), "0");
        has("outcome fail", String.valueOf(ProgressText.outcome("Link nahi khula.")), "1");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
