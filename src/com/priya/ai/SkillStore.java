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
