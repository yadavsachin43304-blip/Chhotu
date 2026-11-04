package com.priya.ai;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Priya's skills (like Claude / Gemini skills): each skill = name + short description + instructions.
 * Stored as text files in app storage: skills/<id>.skill  (line 1 = name, line 2 = description, rest = instructions).
 */
public final class SkillStore {

    public static final class Skill {
        public final String id, name, description, instructions;
        public final boolean enabled;
        Skill(String id, String name, String description, String instructions) {
            this(id, name, description, instructions, true);
        }
        Skill(String id, String name, String description, String instructions, boolean enabled) {
            this.id = id; this.name = name; this.description = description; this.instructions = instructions;
            this.enabled = enabled;
        }
    }

    private final File dir;

    public SkillStore(Context c) {
        dir = new File(c.getFilesDir(), "skills");
        if (!dir.exists()) dir.mkdirs();
        seed();
        seedV2();
        seedV3();
        seedV4();
        seedV5(c);
    }

    /** v33: built-in, editable AI Tools Guide (manager mode catalog) seeded from assets — only if missing. */
    private void seedV5(Context c) {
        File marker = new File(dir, ".seeded_v5");
        if (marker.exists()) return;
        if (!new File(dir, idFor(AiTools.SKILL_NAME) + ".skill").exists()) {
            String body = AiTools.seedBody(c);
            if (body.isEmpty()) return; // asset unreadable: try again next start
            save(AiTools.SKILL_NAME,
                    "Manager mode: 30+ AI tools/apps/websites — kaunsa kaam kis tool mein best, package/URL, free/paid, phone par step-by-step. find_best_tool isi se chunta hai.",
                    body);
        }
        try { marker.createNewFile(); } catch (Exception ignored) { }
    }

    /** v29: built-in GrowthPilot Manager skill (added only if missing). */
    private void seedV4() {
        File marker = new File(dir, ".seeded_v4");
        if (marker.exists()) return;
        if (!new File(dir, idFor("GrowthPilot Manager") + ".skill").exists()) {
            save("GrowthPilot Manager",
                    "GrowthPilot Consulting ka business chalana: clients dhundhna, email outreach, form, free health check, paid audits, 'work done, please pay' email.",
                    "GrowthPilot Consulting (business email: growthpilotconsulting1@gmail.com, tagline 'Data • Strategy • Growth'). Sachin ka consulting business.\n"
                            + "SERVICES (price ladder):\n"
                            + "1. FREE Business Health Check — Rs 0, 24 ghante mein: Business Health Score, website quick review, social media analysis, brand visibility, top 5 strengths/improvements, top 3 growth opportunities, 30-day quick wins, professional summary report.\n"
                            + "2. Express Business Audit — Rs 999, 24-48 ghante: executive summary, website performance, brand identity, SEO overview, social + competitor snapshot, marketing review, health score, action plan (PDF 5-7 pages).\n"
                            + "3. Standard Business Growth Audit — Rs 2,999, 2-3 business days: SWOT, website/SEO/social/competitor audits, brand positioning, marketing + pricing review, 30-day action plan (PDF 20-30 pages).\n"
                            + "4. Premium Growth Audit — Rs 7,999 (flagship), 3-5 business days: full health assessment, competitor intelligence, SWOT+PESTLE, technical SEO, customer persona/journey, sales funnel, lead gen, revenue + cost optimization, AI automation recommendations, risk matrix, 30/90/365-day roadmaps (PDF 60-80 pages).\n"
                            + "5. Growth Partnership Program — Rs 24,999/month se: dedicated consultant, monthly strategy + review, KPI, marketing/sales/branding, AI automation guidance, SEO, competitor monitoring, priority support.\n"
                            + "6. AI Automation — AI WhatsApp/email assistant, appointment booking, document processing, AI content, e-commerce automation, data sync/reporting (free consultation, price baad mein tay).\n"
                            + "Target: coaching institutes, schools, restaurants, hotels, clinics, salons, gyms, retail, e-commerce, startups, manufacturing, healthcare, chhote-madhyam business.\n"
                            + "PIPELINE (step by step, har step ke baad task_progress):\n"
                            + "1. Businesses dhundho: web search / Google Maps (naam, city, category, website, public business email).\n"
                            + "2. Unhe professional email bhejo (free Business Health Check offer) growthpilotconsulting1@gmail.com se.\n"
                            + "3. Interest dikhaye to intake form bhejo (form bharo > hum package banate hain > 'work done' email > payment > turant delivery).\n"
                            + "4. Free service (Health Check) bina payment ke deliver karo — high value, taaki client trust kare.\n"
                            + "5. Paid order par kaam karo (audit/report taiyaar).\n"
                            + "6. Kaam poora hone par email: 'Work done, please pay' (amount + service naam).\n"
                            + "RULES (hamesha):\n"
                            + "- Kabhi kuch delete mat karo (email, draft, file, sheet row, lead, task) — archive karo ya status mark karo. Delete lagta ho to pehle Sachin se poochho.\n"
                            + "- Paid kaam ki FINAL delivery se pehle Sachin se poochho: 'Payment received?' — unki 'haan' ke baad hi deliver karo.\n"
                            + "- Kabhi paisa kharch mat karo (koi purchase, ad, subscription, payment nahi).\n"
                            + "- Outreach sirf businesses ko (public business email), kabhi personal logon ko nahi. Spam nahi, har email personal aur sahi.\n"
                            + "- Koi password ya API key kabhi bolna/likhna nahi.\n"
                            + "- Aakhir mein short report: kitne businesses mile, kisko email gaya, kya pending hai.");
        }
        try { marker.createNewFile(); } catch (Exception ignored) { }
    }

    /** v26: truthful phone control (verify every step, press_send for messages, problem-solving loop). */
    private void seedV3() {
        File marker = new File(dir, ".seeded_v3");
        if (marker.exists()) return;
        save("Phone Control",
                "Phone Manager: kisi bhi app mein koi bhi kaam step-by-step — app kholna, tap, type, message bhejna, scroll, drag, popups, verify, sach wali report.",
                "Sachin ka koi bhi phone order (sirf unke saaf order par):\n"
                        + "1. SAMJHO: order shabd-ba-shabd suno. Goal aur 'done' ki pehchaan tay karo (kaam ke baad screen par kya dikhna chahiye). Kuch adhoora ho to EK chhota sawaal.\n"
                        + "2. PLAN: 2 se zyada steps ho to task_plan, Sachin ko ek line mein plan.\n"
                        + "3. Har step: DEKHO (read_screen / look_at_screen) > KARO (open_app, click_on_screen, tap_at, swipe, type_text, scroll_until) > JAANCHO (tool result + read_screen / wait_for_text) > task_progress.\n"
                        + "4. Sirf 'VERIFIED' / 'VERIFIED_SENT' result hi kaam hone ka saboot hai. FAILED / NOT_SENT / NO_CHANGE / UNVERIFIED = kaam nahi hua — 'ho gaya' kabhi mat bolo.\n"
                        + "5. MESSAGE / PROMPT BHEJNA: sahi chat kholo > read_screen se sahi contact pakka > type_text (Sachin ke exact shabd) > Sachin ki haan (agar pehle se 'bhej do' na kaha ho) > press_send(expect_text) > VERIFIED_SENT aaye tabhi 'bhej diya'.\n"
                        + "6. FAIL ho to wajah socho (popup? galat screen? label Hindi mein? scroll? load ho raha?) aur doosra tareeka: dismiss_popup, back, scroll_until, doosra label, index, look_at_screen + tap_at, wait_for_text. Same cheez same tareeke se dobara nahi. Ek step 3 alag koshish ke baad bhi fail > ruk kar Sachin ko batao kya try kiya.\n"
                        + "7. Popup: dismiss_popup. Ad/Sponsored/Install/Buy/Subscribe kabhi nahi. Call, post, payment, account/settings: aakhri button se pehle Sachin se haan.\n"
                        + "8. Delete/remove/reset/uninstall/format/clear data hamesha mana.\n"
                        + "9. Beech mein 1 line status; aakhir mein 1-2 line professional report: kya kiya, kya verify hua, kya bacha. Order ke bahar kuch extra mat karo.");
        try { marker.createNewFile(); } catch (Exception ignored) { }
    }

    /** v25: manager-mode skills (CapCut, coding agent, phone manager). Runs once on existing installs too. */
    private void seedV2() {
        File marker = new File(dir, ".seeded_v2");
        if (marker.exists()) return;
        save("CapCut Video Editor",
                "CapCut mein video edit karna: video chunna, trim, music lagana, text/caption, export — sirf Sachin ke order par.",
                "CapCut (package com.lemon.lvoverseas) mein Sachin ka editing order poora karna:\n"
                        + "0. Order saaf samjho: kaunsa video (latest / naam / date), kitna trim (shuru/aakhir se kitne second), kaunsa music, aur kuch (text, speed). Kuch zaroori na pata ho to ek chhota sawaal. Phir task_plan: kholna > video chunna > trim > music > export > report.\n"
                        + "1. open_app 'CapCut' (na mile to list_apps 'cap'; installed hi na ho to Sachin ko batao). wait_for_text 'New project|Naya' 15 sec. Update/Pro/offer popup aaye to dismiss_popup ya X (look_at_screen + tap_at). 'Try free', 'Subscribe', 'Buy', 'Pro' kabhi mat dabana.\n"
                        + "2. click_on_screen 'New project' (Hindi UI mein 'नया प्रोजेक्ट'). Photos/videos ki permission maange to ye Sachin ke order ke liye zaroori hai — 'Allow' dabana theek hai.\n"
                        + "3. Gallery: 'Videos' tab. Latest video = sabse pehla thumbnail (upar-baayein). Thumbnails par text nahi hota: look_at_screen karke tap_at se thumbnail (ya uske upar-daayein kone ka select circle) dabao. Galat video na chune — duration label dekh kar pakka karo. Phir click_on_screen 'Add'. wait_for_text 'Export' 20 sec (editor khul gaya).\n"
                        + "4. TRIM: look_at_screen. Timeline neeche ke hisse mein hoti hai (aam taur par y≈0.62-0.72), beech mein safed khadi line = playhead, upar timecode (00:05 / 00:30). Clip par tap_at karke select karo — dono kinaron par safed handles aa jaate hain.\n"
                        + "   Shuru se kaatna: playhead ko sahi second par lao (timeline ko swipe karke, timecode dekhte hue), phir LEFT handle ko swipe hold_ms=400 se playhead tak drag karo (handle snap ho jaata hai). Aakhir se kaatna: playhead ko naye end par lao, RIGHT handle ko playhead tak drag. Har drag ke baad look_at_screen se total duration check karo, zarurat ho to thoda adjust.\n"
                        + "   'Split' theek hai, par 'Delete' button Priya ke liye blocked hai — hissa hatane ke liye hamesha handle-drag se trim karo.\n"
                        + "5. MUSIC: clip deselect karo (timeline ke khaali hisse par tap ya back). Neeche toolbar: 'Audio' > 'Sounds' (ya 'Music'). Sachin ne gaana bataya ho to search mein type_text press_enter=true; nahi to unse poochho ya uske bataye mood ki category. Gaane par tap (preview) > '+' / 'Use' (look_at_screen se icon). Music video se lambi ho to music clip select karke uska right handle video ke end tak drag karo. Volume kehne par 'Volume'.\n"
                        + "6. Text/caption order ho to 'Text' > 'Add text' (type_text) ya 'Auto captions'. Speed: clip select > 'Speed'.\n"
                        + "7. EXPORT: upar-daayein 'Export' (ya upar-arrow icon). Resolution ka default theek hai. wait_for_text 'Saved|Done|saved to|100%' 240 sec. Share/TikTok popup aaye to 'Done' ya dismiss_popup — kahin share mat karna.\n"
                        + "8. Verify: read_screen se 'Saved' pakka karo. Report: kya trim hua (kitne sec), kaunsa music, video gallery mein save hua. Har bade step ke baad task_progress.\n"
                        + "Koi step 3 baar fail: ruk jao, Sachin ko batao screen par kya hai aur kya dikkat hai. CapCut ka UI version ke hisaab se badalta hai — label na mile to look_at_screen se icon pehchano.");
        save("Coding Agent",
                "Code likhna, Termux mein chalana, error khud theek karna (max 5 koshish) aur report dena.",
                "Jab Sachin koi program/script/app banane ya chalane ko kahe:\n"
                        + "1. termux_check (pehli baar ya error par). Setup na ho to tool ke steps Sachin ko Hindi mein ek-ek karke batao aur ruk jao.\n"
                        + "2. Chhota project folder naam chuno (jaise 'calculator'). task_plan banao.\n"
                        + "3. code_write_file se poori file likho (path 'calculator/main.py'). Saaf, chhota, sahi code.\n"
                        + "4. code_run (workdir 'calculator', command 'python main.py'). Input wale program ko test ke liye: echo '2 3' | python main.py. Server/loop wale program ka timeout_sec chhota rakho.\n"
                        + "5. exit_code 0 nahi ya STDERR mein error: error padho, asli wajah samjho, code_read_file se line dekho, code_write_file se poori fixed file likho, dobara code_run. ModuleNotFoundError ho to 'pip install <pkg>'; tool missing ho to 'pkg install -y <pkg>'.\n"
                        + "6. Zyada se zyada 5 koshish. Phir bhi fail: ruk jao, asli error aur kya try kiya short mein batao, Sachin se poochho.\n"
                        + "7. Chal jaaye to output check karo ki Sachin ne jo maanga wahi hai. Report: kya banaya, kahan (~/PriyaCode/<folder>), kaise chalana hai, output kya aaya.\n"
                        + "8. Kabhi rm/delete/remove/uninstall/reset wali command ya file-delete karne wala code mat likho — blocked hai.");
        save("Phone Control",
                "Phone Manager: kisi bhi app mein koi bhi kaam step-by-step — app kholna, tap, type, scroll, drag, popups, verify, report.",
                "Sachin ka koi bhi phone order (sirf unke saaf order par):\n"
                        + "1. Order poora suno. Saaf na ho to ek chhota sawaal.\n"
                        + "2. 2 se zyada steps ho to task_plan.\n"
                        + "3. Har step: read_screen (har element ka @x,y) ya look_at_screen (icons/images) > action (open_app, click_on_screen, tap_at, swipe, type_text, scroll_until) > verify (read_screen / wait_for_text) > task_progress.\n"
                        + "4. Popup: dismiss_popup. Permission popup sirf tab Allow jab Sachin ke kaam ke liye zaroori ho. Ad/Sponsored/Install/Buy/Subscribe kabhi nahi.\n"
                        + "5. Atke to: back, dismiss_popup, scroll_until, doosra label (Hindi/English), position par tap. Ek step 3 baar fail > ruk kar Sachin ko batao.\n"
                        + "6. Call, message/post bhejna, payment, account/settings badalna: aakhri button se pehle Sachin se haan lo.\n"
                        + "7. Delete/remove/reset/uninstall/format/clear data hamesha mana.\n"
                        + "8. Beech mein 1 line status, aakhir mein 1-2 line report. Order ke bahar kuch extra mat karo.");
        try { marker.createNewFile(); } catch (Exception ignored) { }
    }

    private static String idFor(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u0900-\\u097F]+", "-").replaceAll("^-+|-+$", "");
        return id.isEmpty() ? ("skill-" + System.currentTimeMillis()) : id;
    }

    private void seed() {
        File marker = new File(dir, ".seeded");
        if (marker.exists()) return;
        save("Business Expert",
                "Business strategy, finance, pricing, marketing, sales funnel, SEO, automation, OKR, negotiation (30 business skills).",
                "Jab user business, startup, pricing, marketing, sales, SEO, automation, YouTube growth, hiring ya negotiation ki baat kare:\n"
                        + "1. Pehle samjho uska business kya hai aur goal kya hai (ek chhota sawaal agar zaroori ho).\n"
                        + "2. 'knowledge' tool se related skill notes dhundho (jaise 'pricing strategy', 'unit economics', 'sales funnel').\n"
                        + "3. Framework use karo (SWOT/PESTLE, CAC/LTV, funnel, OKR) aur seedhe, practical 3-5 steps do.\n"
                        + "4. Numbers ho to calculation bolkar samjhao. Aakhir mein agla ek kaam batao.");
        save("Video Editing Expert",
                "Video editing terms, cuts, transitions, keyframes, captions, colour, audio, aur AI editing prompts likhna.",
                "Jab user video editing ya AI editor ke liye prompt ki baat kare:\n"
                        + "1. 'knowledge' tool se term ya recipe dhundho (jaise 'J cut', 'ripple delete', 'keyframe', 'captions').\n"
                        + "2. Term ka simple matlab Hinglish mein batao.\n"
                        + "3. Prompt formula use karo: ACTION + TARGET + TIMING + STYLE/VALUES + CONSTRAINT.\n"
                        + "4. Ready-to-copy prompt bolo, timestamps aur percentages saaf ho.");
        save("Coding Expert",
                "Programming help: Java, Kotlin, Android, Python, JavaScript, web, APIs, debugging.",
                "Jab user coding ka sawaal pooche:\n"
                        + "1. Error ya goal samjho; error text ho to uski asli wajah pehle batao.\n"
                        + "2. Sabse simple sahi solution do, code chhota rakho, important line bolkar samjhao.\n"
                        + "3. Steps numbered rakho: kya file, kya change, kaise test.\n"
                        + "4. Pakka na ho to bolo 'ye check karo' aur test ka tareeka do. Jhooth mat bolo.");
        save("Phone Control",
                "Phone ke kaam: app kholna, YouTube, message type karna, settings dekhna, scroll, tap, call, torch, volume.",
                "Phone par koi bhi kaam:\n"
                        + "1. read_screen se dekho screen par kya hai.\n"
                        + "2. Ek-ek step karo (open_app, click_on_screen, type_text, phone_control) aur har step ke baad read_screen se check karo.\n"
                        + "3. Kaam na bane to doosra raasta try karo (search, scroll, back).\n"
                        + "4. Call, message bhejna, payment ya settings badalne se pehle user se haan lo.\n"
                        + "5. Delete, remove, reset, uninstall, format, clear data kabhi mat karo - ye tumhare role ke khilaf hai.\n"
                        + "6. Aakhir mein ek line mein batao kya kiya.");
        try { marker.createNewFile(); } catch (Exception ignored) { }
    }

    public synchronized Skill save(String name, String description, String instructions) {
        return saveWithId(null, name, description, instructions);
    }

    /** Save (or edit) a skill. A non-null id keeps the same file, so editing keeps the same skill. */
    public synchronized Skill saveWithId(String keepId, String name, String description, String instructions) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) return null;
        String id = (keepId != null && !keepId.isEmpty()) ? keepId : idFor(name);
        String body = name + "\n" + (description == null ? "" : description.replace('\n', ' ').trim()) + "\n"
                + (instructions == null ? "" : instructions.trim()) + "\n";
        try (FileOutputStream o = new FileOutputStream(new File(dir, id + ".skill"))) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        return new Skill(id, name, description, instructions);
    }

    private Skill read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            String s = new String(b, 0, n, StandardCharsets.UTF_8);
            String[] parts = s.split("\n", 3);
            String id = f.getName().replace(".skill", "");
            return new Skill(id, parts[0].trim(), parts.length > 1 ? parts[1].trim() : "",
                    parts.length > 2 ? parts[2].trim() : "", !new File(dir, id + ".off").exists());
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized List<Skill> list() {
        List<Skill> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".skill"));
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) {
            Skill s = read(f);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** Turn a skill on/off. Disabled skills stay saved but are not offered to the model. */
    public synchronized void setEnabled(String id, boolean on) {
        File off = new File(dir, id + ".off");
        try {
            if (on) off.delete();   // removes only the internal on/off flag, never the skill itself
            else off.createNewFile();
        } catch (Exception ignored) { }
    }

    public synchronized Skill byId(String id) {
        File f = new File(dir, id + ".skill");
        return f.exists() ? read(f) : null;
    }

    /** Import a Markdown skill file (Gemini/Claude style): name from front matter, else first # heading, else filename. */
    public synchronized Skill importMarkdown(String fileName, String text) {
        if (text == null) return null;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String name = null, desc = "";
        String body = text;
        if (body.startsWith("---")) {
            int end = body.indexOf("\n---", 3);
            if (end > 0) {
                String fm = body.substring(3, end);
                for (String line : fm.split("\n")) {
                    int c = line.indexOf(':');
                    if (c <= 0) continue;
                    String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
                    String v = line.substring(c + 1).trim().replaceAll("^[\"']|[\"']$", "");
                    if ((k.equals("name") || k.equals("title")) && !v.isEmpty()) name = v;
                    else if (k.equals("description") && !v.isEmpty()) desc = v;
                }
                int nl = body.indexOf('\n', end + 4);
                body = nl < 0 ? "" : body.substring(nl + 1);
            }
        }
        if (name == null) {
            String[] lines = body.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty()) continue;
                if (l.startsWith("# ")) {
                    name = l.substring(2).trim();
                    StringBuilder rest = new StringBuilder();
                    for (int j = i + 1; j < lines.length; j++) rest.append(lines[j]).append('\n');
                    body = rest.toString();
                }
                break;
            }
        }
        if (name == null || name.isEmpty()) {
            String fn = fileName == null ? "" : fileName;
            int dot = fn.lastIndexOf('.');
            if (dot > 0) fn = fn.substring(0, dot);
            name = fn.replace('_', ' ').replace('-', ' ').trim();
            if (name.isEmpty()) name = "Imported skill";
        }
        body = body.trim();
        if (desc.isEmpty()) {
            for (String l : body.split("\n")) {
                String t = l.trim().replaceAll("^[#>*\\-\\s]+", "");
                if (!t.isEmpty()) { desc = t.length() > 140 ? t.substring(0, 140) + "…" : t; break; }
            }
        }
        if (body.isEmpty()) return null;
        return save(name, desc, body);
    }

    /** Only enabled skills are visible to the model (use_skill). */
    public synchronized Skill find(String name) {
        if (name == null) return null;
        String q = name.toLowerCase(Locale.ROOT).trim();
        Skill partial = null;
        for (Skill s : list()) {
            if (!s.enabled) continue;
            String n = s.name.toLowerCase(Locale.ROOT);
            if (n.equals(q) || s.id.equals(idFor(name))) return s;
            if (partial == null && (n.contains(q) || q.contains(n))) partial = s;
        }
        return partial;
    }

    /** Only the user can delete a skill (from the Skills screen). Priya has no tool for this. */
    public synchronized boolean deleteByUser(String id) {
        return new File(dir, id + ".skill").delete();
    }

    public String summaryForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Skill s : list()) if (s.enabled) sb.append("- ").append(s.name).append(": ").append(s.description).append('\n');
        return sb.toString();
    }
}
