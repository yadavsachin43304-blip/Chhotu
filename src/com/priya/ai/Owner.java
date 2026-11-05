package com.priya.ai;

import android.content.Context;

/**
 * The owner's name in one place. Older prompts, skills and tool texts were written with "Sachin"; every text that
 * goes to Gemini (setup prompt, tool results, REST calls) passes through {@link #fix} so Priya always uses the
 * name saved in Settings (default "Vivek") — change it in Menu > Settings > Owner naam.
 */
final class Owner {

    /** Honorific form used in spoken lines: "Vivek ji". */
    static volatile String name = "Vivek";

    private Owner() {}

    static void load(Context c) {
        try { name = clean(Prefs.ownerName(c)); } catch (Throwable ignored) { }
    }

    /** Letters, digits, space, dot and hyphen only: the name is pasted into JSON and prompts. */
    static String clean(String s) {
        if (s == null) return "Vivek";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.trim().toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == ' ' || ch == '.' || ch == '-') sb.append(ch);
        }
        String r = sb.toString().trim();
        if (r.length() > 40) r = r.substring(0, 40).trim();
        return r.isEmpty() ? "Vivek" : r;
    }

    static String fix(String s) {
        if (s == null) return null;
        String n = name;
        return "Sachin".equals(n) || s.indexOf("Sachin") < 0 ? s : s.replace("Sachin", n);
    }

    static String fix(Context c, String s) {
        load(c);
        return fix(s);
    }

    /** "Vivek ji" - the respectful form Priya speaks. */
    static String ji() { return name + " ji"; }

    /** The one refusal line for any voice that is not the owner's. */
    static String refusalLine() {
        return "Main sirf " + ji() + " ki baat sunti hoon. Kripya aap apna kaam khud karein.";
    }
}
