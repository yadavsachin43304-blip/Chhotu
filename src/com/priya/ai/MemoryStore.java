package com.priya.ai;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

/**
 * Plain-text memory with a hard 30 MB cap.
 *   notes.txt         -> vaade / facts the user asked Priya to remember (kept longest)
 *   conversation.log  -> running transcript (oldest part is trimmed first when the cap is hit)
 */
public class MemoryStore {
    public static final long CAP_BYTES = 30L * 1024 * 1024;

    private final File notes;
    private final File convo;
    private final Object lock = new Object();

    public MemoryStore(Context c) {
        File dir = new File(c.getFilesDir(), "priya_memory");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        notes = new File(dir, "notes.txt");
        convo = new File(dir, "conversation.log");
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    public void addNote(String kind, String text) {
        String t = clean(text);
        if (t.isEmpty()) return;
        append(notes, stamp() + " | " + (kind == null || kind.isEmpty() ? "NOTE" : kind.toUpperCase(Locale.ROOT)) + " | " + t);
    }

    public void addConversation(String role, String text) {
        String t = clean(text);
        if (t.isEmpty()) return;
        append(convo, stamp() + " | " + role + ": " + t);
    }

    private void append(File f, String line) {
        synchronized (lock) {
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
            enforceCap();
        }
    }

    public long usedBytes() {
        return notes.length() + convo.length();
    }

    public long conversationBytes() { return convo.length(); }
    public long notesBytes() { return notes.length(); }

    private void enforceCap() {
        if (usedBytes() <= CAP_BYTES) return;
        // 1) drop the oldest 30% of the conversation log
        if (convo.length() > 0) trimHead(convo, (long) (convo.length() * 0.3));
        // 2) if notes alone are somehow huge, drop their oldest part too
        if (usedBytes() > CAP_BYTES) trimHead(notes, usedBytes() - CAP_BYTES + CAP_BYTES / 10);
    }

    private void trimHead(File f, long drop) {
        if (drop <= 0 || !f.exists()) return;
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (RandomAccessFile raf = new RandomAccessFile(f, "r");
             FileOutputStream o = new FileOutputStream(tmp)) {
            long len = raf.length();
            if (drop >= len) { raf.close(); //noinspection ResultOfMethodCallIgnored
                f.delete(); return; }
            raf.seek(drop);
            int b;
            while ((b = raf.read()) != -1 && b != '\n') { /* skip partial line */ }
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = raf.read(buf)) > 0) o.write(buf, 0, r);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private String tail(File f, int maxBytes) {
        synchronized (lock) {
            if (!f.exists() || f.length() == 0) return "";
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                long len = raf.length();
                long start = Math.max(0, len - maxBytes);
                raf.seek(start);
                byte[] b = new byte[(int) (len - start)];
                raf.readFully(b);
                String s = new String(b, StandardCharsets.UTF_8);
                if (start > 0) {
                    int nl = s.indexOf('\n');
                    if (nl >= 0) s = s.substring(nl + 1);
                }
                return s;
            } catch (IOException e) {
                return "";
            }
        }
    }

    /** Text injected into Priya's system prompt at session start. */
    public String contextForPrompt() {
        String n = tail(notes, 10 * 1024).trim();
        String c = tail(convo, 5 * 1024).trim();
        StringBuilder sb = new StringBuilder();
        if (!n.isEmpty()) sb.append("SAVED NOTES / VAADE (oldest to newest):\n").append(n).append("\n\n");
        if (!c.isEmpty()) sb.append("RECENT CONVERSATION:\n").append(c).append("\n");
        return sb.toString();
    }

    public List<String> recentNotes(int count) {
        String t = tail(notes, 128 * 1024).trim();
        List<String> out = new ArrayList<>();
        if (t.isEmpty()) return out;
        String[] lines = t.split("\n");
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) out.add(lines[i]);
        Collections.reverse(out);
        return out;
    }

    private static final class Hit implements Comparable<Hit> {
        final int score;
        final long idx;
        final String line;
        Hit(int score, long idx, String line) { this.score = score; this.idx = idx; this.line = line; }
        @Override public int compareTo(Hit o) {
            if (score != o.score) return Integer.compare(score, o.score);
            return Long.compare(idx, o.idx);
        }
    }

    /** Keyword search over the whole 30 MB store. Newer + better matches rank higher. */
    public String search(String query, int max) {
        if (query == null) return "";
        List<String> tokens = new ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 2) tokens.add(t);
        }
        if (tokens.isEmpty()) return "";
        PriorityQueue<Hit> heap = new PriorityQueue<>();
        long idx = 0;
        synchronized (lock) {
            for (File f : new File[]{notes, convo}) {
                if (!f.exists()) continue;
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        idx++;
                        String low = line.toLowerCase(Locale.ROOT);
                        int score = 0;
                        for (String t : tokens) if (low.contains(t)) score++;
                        if (score == 0) continue;
                        // notes get a small boost
                        if (f == notes) score += 1;
                        heap.offer(new Hit(score, idx, line.length() > 300 ? line.substring(0, 300) : line));
                        if (heap.size() > max) heap.poll();
                    }
                } catch (IOException ignored) { }
            }
        }
        List<Hit> hits = new ArrayList<>(heap);
        Collections.sort(hits, Collections.reverseOrder());
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) sb.append(h.line).append('\n');
        return sb.toString().trim();
    }

    public void clearConversation() {
        synchronized (lock) {
            //noinspection ResultOfMethodCallIgnored
            convo.delete();
        }
    }

    public void clearAll() {
        synchronized (lock) {
            //noinspection ResultOfMethodCallIgnored
            convo.delete();
            //noinspection ResultOfMethodCallIgnored
            notes.delete();
        }
    }
}
