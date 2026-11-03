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
        Skill(String id, String name, String description, String instructions) {
            this.id = id; this.name = name; this.description = description; this.instructions = instructions;
        }
    }

    private final File dir;

    public SkillStore(Context c) {
        dir = new File(c.getFilesDir(), "skills");
        if (!dir.exists()) dir.mkdirs();
        seed();
        seedV2();
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
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) return null;
        String id = idFor(name);
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
                    parts.length > 2 ? parts[2].trim() : "");
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

    public synchronized Skill find(String name) {
        if (name == null) return null;
        String q = name.toLowerCase(Locale.ROOT).trim();
        Skill partial = null;
        for (Skill s : list()) {
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
        for (Skill s : list()) sb.append("- ").append(s.name).append(": ").append(s.description).append('\n');
        return sb.toString();
    }
}
