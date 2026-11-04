package com.priya.ai;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Content tools: web fetch, research, text/script, images, TTS, recent audio, and the faceless-video pipeline. */
final class Studio {

    private Studio() {}

    static final String SD = "/storage/emulated/0";
    /** Last audio Sachin picked with the file picker (copied into Music/Priya). */
    static volatile String lastPickedAudio = "";

    static String stamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()); }

    // ================================================================== web fetch

    static String webFetch(String url, int maxChars) {
        if (url == null || url.trim().isEmpty()) return "URL do.";
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        maxChars = Math.max(500, Math.min(maxChars <= 0 ? 6000 : maxChars, 20000));
        try {
            HttpURLConnection h = null;
            int code = 0;
            for (int hop = 0; hop < 5; hop++) {
                h = (HttpURLConnection) new URL(url).openConnection();
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(15000);
                h.setReadTimeout(25000);
                h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36 PriyaAI");
                h.setRequestProperty("Accept", "text/html,application/json,text/plain,*/*");
                h.setRequestProperty("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8");
                code = h.getResponseCode();
                if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), h.getHeaderField("Location")).toString();
                    h.disconnect();
                    continue;
                }
                break;
            }
            InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
            if (in == null) return "FAILED: HTTP " + code + " (koi content nahi).";
            String ct = String.valueOf(h.getContentType()).toLowerCase(Locale.ROOT);
            if (ct.contains("image/") || ct.contains("video/") || ct.contains("audio/") || ct.contains("octet-stream") || ct.contains("pdf")) {
                return "Ye page text nahi hai (" + ct + "). Is tool se sirf web pages/text padhe ja sakte hain.";
            }
            String raw = new String(GeminiRest.readAll(in, 1_500_000), StandardCharsets.UTF_8);
            h.disconnect();
            String title = "";
            java.util.regex.Matcher tm = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(raw);
            if (tm.find()) title = htmlText(tm.group(1)).trim();
            String text = ct.contains("html") || raw.trim().startsWith("<") ? htmlText(raw) : raw;
            text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
            StringBuilder sb = new StringBuilder();
            sb.append(code >= 400 ? "HTTP " + code + " (error page)\n" : "OK HTTP " + code + "\n");
            sb.append("URL: ").append(url).append('\n');
            if (!title.isEmpty()) sb.append("TITLE: ").append(title).append('\n');
            sb.append(text.length() > maxChars ? text.substring(0, maxChars) + "\n…(kaata, " + text.length() + " chars total)" : text);
            return sb.toString();
        } catch (Exception e) {
            return "FAILED: page nahi khula (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ").";
        }
    }

    private static String htmlText(String html) {
        String s = html.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?i)<(br|p|div|li|h[1-6]|tr|section|article|header|footer)[^>]*>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "'").replace("&hellip;", "…");
        return s;
    }

    // ================================================================== saving to shared storage

    /** Saves bytes into a public folder via MediaStore ("Pictures/Priya/x", "Music/Priya", "Documents/Priya"). Returns absolute path. */
    static String saveShared(Context c, byte[] data, String mime, String relDir, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            Uri coll;
            if (relDir.startsWith("Pictures") || relDir.startsWith("DCIM")) coll = MediaStore.Images.Media.getContentUri("external_primary");
            else if (relDir.startsWith("Music") || relDir.startsWith("Recordings")) coll = MediaStore.Audio.Media.getContentUri("external_primary");
            else if (relDir.startsWith("Movies")) coll = MediaStore.Video.Media.getContentUri("external_primary");
            else coll = MediaStore.Files.getContentUri("external_primary");
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, relDir.endsWith("/") ? relDir : relDir + "/");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = cr.insert(coll, v);
            if (u == null) throw new java.io.IOException("MediaStore insert fail");
            try (OutputStream o = cr.openOutputStream(u)) {
                if (o == null) throw new java.io.IOException("stream null");
                o.write(data);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, done, null, null);
            String path = null;
            try (Cursor q = cr.query(u, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
                if (q != null && q.moveToFirst()) path = q.getString(0);
            } catch (Exception ignored) { }
            return path != null ? path : SD + "/" + relDir + "/" + name;
        }
        File dir = new File(Environment.getExternalStorageDirectory(), relDir);
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("folder nahi bana: " + dir);
        File f = new File(dir, name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
        MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
        return f.getAbsolutePath();
    }

    static void scan(Context c, String path) {
        try { MediaScannerConnection.scanFile(c, new String[]{path}, null, null); } catch (Exception ignored) { }
    }

    static String ext(String mime) {
        if (mime == null) return "bin";
        if (mime.contains("png")) return "png";
        if (mime.contains("jpeg") || mime.contains("jpg")) return "jpg";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("wav")) return "wav";
        if (mime.contains("mpeg") || mime.contains("mp3")) return "mp3";
        return "bin";
    }

    // ================================================================== tools: text / image / tts

    static String generateText(Context c, String task, String kind, boolean save) {
        try {
            String sys = "You are Priya's writing engine for Sachin (India). Write exactly what is asked, ready to use, no preamble. "
                    + "Match the requested language (Hinglish/Hindi/English). For scripts: hook in first line, clear scenes, natural spoken style. "
                    + "For image/video prompts: concrete subject, setting, lighting, camera, style. Kind: " + (kind == null ? "general" : kind);
            String out = GeminiRest.text(c, sys, task, 180);
            if (out.isEmpty()) return "FAILED: model ne khaali jawab diya.";
            String saved = "";
            if (save || out.length() > 1500) {
                try {
                    saved = saveShared(c, out.getBytes(StandardCharsets.UTF_8), "text/plain", "Documents/Priya",
                            "priya_" + (kind == null || kind.isEmpty() ? "text" : kind.replaceAll("[^a-zA-Z0-9]", "")) + "_" + stamp() + ".txt");
                } catch (Exception e) { saved = ""; }
            }
            return "VERIFIED: text ban gaya (" + out.length() + " chars)" + (saved.isEmpty() ? "" : ", file: " + saved) + ".\n---\n"
                    + (out.length() > 6000 ? out.substring(0, 6000) + "\n…(baaki file mein)" : out);
        } catch (Exception e) {
            return "FAILED: text nahi bana — " + e.getMessage();
        }
    }

    static String generateImage(Context c, String prompt, String aspect, String folder) {
        if (prompt == null || prompt.trim().isEmpty()) return "Image ka prompt do.";
        String asp = normAspect(aspect);
        try {
            GeminiRest.Media m = GeminiRest.image(c, prompt, asp);
            String sub = folder == null || folder.trim().isEmpty() ? "Pictures/Priya" : "Pictures/Priya/" + folder.replaceAll("[^a-zA-Z0-9_-]", "");
            String path = saveShared(c, m.bytes, m.mime, sub, "priya_" + stamp() + "." + ext(m.mime));
            return "VERIFIED: image ban gayi aur save hui (" + (m.bytes.length / 1024) + " KB, " + asp + ", model " + m.model + "): " + path
                    + " — Gallery > Pictures > Priya mein dikhegi.";
        } catch (Exception e) {
            return "FAILED: image nahi bani — " + e.getMessage() + ". (Prompt badal kar ya chhota karke dobara try karo; "
                    + "logo/real logon ke chehre wali images model mana kar sakta hai.)";
        }
    }

    static String normAspect(String a) {
        if (a == null) return "9:16";
        a = a.trim();
        String[] ok = {"1:1", "9:16", "16:9", "4:5", "5:4", "3:4", "4:3", "2:3", "3:2", "21:9"};
        for (String o : ok) if (o.equals(a)) return a;
        String l = a.toLowerCase(Locale.ROOT);
        if (l.contains("short") || l.contains("reel") || l.contains("vertical") || l.contains("portrait") || l.contains("story")) return "9:16";
        if (l.contains("youtube") || l.contains("landscape") || l.contains("horizontal") || l.contains("wide")) return "16:9";
        if (l.contains("square") || l.contains("post")) return "1:1";
        return "9:16";
    }

    static String tts(Context c, String text, String style) {
        if (text == null || text.trim().isEmpty()) return "Kya bolna hai wo text do.";
        try {
            GeminiRest.Media m = GeminiRest.tts(c, text, style);
            byte[] wav = m.bytes;
            if (!(wav.length > 12 && wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F')) wav = pcmToWav(wav, 24000);
            String path = saveShared(c, wav, "audio/wav", "Music/Priya", "priya_voice_" + stamp() + ".wav");
            return "VERIFIED: voiceover ban gaya (Priya ki awaaz, " + (wav.length / 1024) + " KB): " + path;
        } catch (Exception e) {
            return "FAILED: voiceover nahi bana — " + e.getMessage();
        }
    }

    static byte[] pcmToWav(byte[] pcm, int rate) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int byteRate = rate * 2;
        try {
            o.write(new byte[]{'R', 'I', 'F', 'F'});
            le32(o, 36 + pcm.length);
            o.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            le32(o, 16); le16(o, 1); le16(o, 1); le32(o, rate); le32(o, byteRate); le16(o, 2); le16(o, 16);
            o.write(new byte[]{'d', 'a', 't', 'a'});
            le32(o, pcm.length);
            o.write(pcm);
        } catch (Exception ignored) { }
        return o.toByteArray();
    }

    private static void le32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    private static void le16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }

    // ================================================================== recent audio

    static boolean canReadAudio(Context c) {
        String p = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO" : Manifest.permission.READ_EXTERNAL_STORAGE;
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    static final class AudioItem {
        String path, name;
        long sizeKb, durSec, modified;
    }

    static List<AudioItem> recentAudio(Context c, int limit) {
        List<AudioItem> out = new ArrayList<>();
        if (!lastPickedAudio.isEmpty()) {
            AudioItem a = new AudioItem();
            a.path = lastPickedAudio;
            a.name = new File(lastPickedAudio).getName() + " (aapne abhi chuna)";
            out.add(a);
        }
        if (canReadAudio(c)) {
            String[] cols = {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED, "duration"};
            try (Cursor q = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
                while (q != null && q.moveToNext() && out.size() < limit) {
                    AudioItem a = new AudioItem();
                    a.path = q.getString(0);
                    a.name = q.getString(1);
                    a.sizeKb = q.getLong(2) / 1024;
                    a.modified = q.getLong(3) * 1000;
                    a.durSec = q.getLong(4) / 1000;
                    if (a.path != null) out.add(a);
                }
            } catch (Exception ignored) { }
            if (out.size() > (lastPickedAudio.isEmpty() ? 0 : 1)) return out;
        }
        // fallback: ask Termux (it has storage access after termux-setup-storage)
        TermuxBridge.Result r = TermuxBridge.bash(c,
                "for d in /sdcard/Download /sdcard/Recordings /sdcard/Music /sdcard/Documents /sdcard/Android/media/com.whatsapp/WhatsApp/Media "
                        + "/sdcard/WhatsApp/Media /sdcard/Android/media/com.whatsapp.w4b/WhatsApp\\ Business/Media /sdcard/MIUI/sound_recorder /sdcard/Sounds; do "
                        + "[ -d \"$d\" ] && find \"$d\" -type f \\( -iname '*.mp3' -o -iname '*.m4a' -o -iname '*.wav' -o -iname '*.aac' -o -iname '*.ogg' -o -iname '*.opus' -o -iname '*.amr' -o -iname '*.flac' \\) -size +8k -printf '%T@\\t%s\\t%p\\n' 2>/dev/null; "
                        + "done | sort -rn | head -n " + Math.max(3, limit), null, 40);
        if (r.ok && r.exitCode == 0) {
            for (String line : r.stdout.split("\n")) {
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                AudioItem a = new AudioItem();
                try { a.modified = (long) (Double.parseDouble(p[0]) * 1000); a.sizeKb = Long.parseLong(p[1]) / 1024; } catch (Exception ignored) { }
                a.path = p[2];
                a.name = new File(p[2]).getName();
                out.add(a);
            }
        }
        return out;
    }

    static String listRecentAudio(Context c, int limit) {
        List<AudioItem> l = recentAudio(c, Math.max(3, Math.min(limit <= 0 ? 8 : limit, 20)));
        if (l.isEmpty()) {
            return "Koi recent audio nahi mila. Options: (1) pick_audio_file se Sachin khud file chun le, (2) Priya Menu > Studio mein "
                    + "'Audio permission' Allow karein, (3) Termux setup (termux-setup-storage) taaki main Downloads/WhatsApp folders dekh sakoon.";
        }
        StringBuilder sb = new StringBuilder("RECENT AUDIO (naya pehle):\n");
        SimpleDateFormat f = new SimpleDateFormat("d MMM HH:mm", Locale.ROOT);
        int i = 1;
        for (AudioItem a : l) {
            sb.append(i++).append(") ").append(a.name).append(" | ").append(a.path);
            if (a.durSec > 0) sb.append(" | ").append(a.durSec).append(" sec");
            if (a.sizeKb > 0) sb.append(" | ").append(a.sizeKb).append(" KB");
            if (a.modified > 0) sb.append(" | ").append(f.format(new Date(a.modified)));
            sb.append('\n');
        }
        sb.append("Sachin se pakka karo kaunsi file hai (naam/time bolkar), phir wahi path use karo.");
        return sb.toString();
    }

    /** Copies a picked content Uri into Music/Priya so Termux/ffmpeg can read it. */
    static String importPicked(Context c, Uri uri) throws Exception {
        String name = "voice_" + stamp();
        String mime = c.getContentResolver().getType(uri);
        try (Cursor q = c.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && q.getString(0) != null) name = q.getString(0);
        } catch (Exception ignored) { }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!name.contains(".")) name += "." + (mime != null && mime.contains("mp4") ? "m4a" : "mp3");
        byte[] data;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("file nahi khuli");
            data = GeminiRest.readAll(in, 300L * 1024 * 1024);
        }
        String path = saveShared(c, data, mime == null ? "audio/mpeg" : mime, "Music/Priya", stamp() + "_" + name);
        lastPickedAudio = path;
        return path;
    }

    // ================================================================== termux helpers

    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /** Makes sure ffmpeg exists in Termux (installs it once if missing). Returns null when ready, else a problem text. */
    static String ensureFfmpeg(Context c, PriyaJobs.Job j) {
        TermuxBridge.Result r = TermuxBridge.bash(c, "command -v ffmpeg >/dev/null 2>&1 && command -v ffprobe >/dev/null 2>&1 && echo READY || echo MISSING; "
                + "[ -d /sdcard/Download ] && ls /sdcard/Download >/dev/null 2>&1 && echo STORAGE_OK || echo NO_STORAGE", null, 30);
        if (r.setupProblem != null) return r.setupProblem + "\nPhir:  pkg install -y ffmpeg";
        if (!r.ok) return "Termux ne jawab nahi diya: " + r.err + "\n" + TermuxBridge.SETUP_HELP;
        if (r.stdout.contains("NO_STORAGE")) {
            return "Termux ko phone storage ki permission nahi hai. Sachin Termux kholkar chalaye:  termux-setup-storage  (Allow dabaye), phir dobara bole.";
        }
        if (r.stdout.contains("READY")) return null;
        if (j != null) PriyaJobs.stage(j, "ffmpeg install ho raha hai (pehli baar, 2-5 min)");
        TermuxBridge.Result in = TermuxBridge.bash(c, "export DEBIAN_FRONTEND=noninteractive; (yes | pkg install -y ffmpeg) >/dev/null 2>&1; "
                + "command -v ffmpeg >/dev/null 2>&1 && echo READY || echo FAIL", null, 600);
        if (in.ok && in.stdout.contains("READY")) return null;
        return "ffmpeg Termux mein nahi hai aur khud install nahi ho paya. Sachin Termux mein chalaye:  pkg update -y && pkg install -y ffmpeg  — phir bole 'ho gaya'.";
    }

    static double probeDuration(Context c, String path) {
        TermuxBridge.Result r = TermuxBridge.bash(c, "ffprobe -v error -show_entries format=duration -of csv=p=0 \"$1\"", new String[]{path}, 60);
        if (!r.ok || r.exitCode != 0) return -1;
        try { return Double.parseDouble(r.stdout.trim().split("\\s+")[0]); } catch (Exception e) { return -1; }
    }

    /** Reads a file through Termux in base64 chunks (works without Android storage permission). */
    static byte[] readViaTermux(Context c, String path, long size) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final int CH = 45000;
        for (long off = 0; off < size; off += CH) {
            TermuxBridge.Result r = TermuxBridge.bash(c, "tail -c +$(( $2 + 1 )) \"$1\" | head -c " + CH + " | base64 -w0",
                    new String[]{path, String.valueOf(off)}, 40);
            if (!r.ok || r.exitCode != 0) throw new java.io.IOException("Termux read fail at " + off + ": " + r.err + r.stderr);
            bo.write(Base64.decode(r.stdout.trim(), Base64.DEFAULT));
        }
        return bo.toByteArray();
    }

    static byte[] readFileDirect(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            return GeminiRest.readAll(in, 40L * 1024 * 1024);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================== faceless video

    static String startFacelessVideo(final Context c, final String audioArg, final String topic, final String style,
                                     final String aspectArg, final boolean kenBurns, final String scriptIfNoAudio) {
        final String aspect = normAspect(aspectArg);
        String title = "Faceless video" + (topic == null || topic.isEmpty() ? "" : ": " + GeminiRest.clip(topic, 40));
        PriyaJobs.Job j = PriyaJobs.start(c, "video", title, job -> facelessPipeline(c, job, audioArg, topic, style, aspect, kenBurns, scriptIfNoAudio));
        return "JOB_STARTED " + j.id + ": faceless video ban raha hai background mein (audio samajhna → scenes → images → MP4). "
                + "Ye abhi POORA NAHI hua — Sachin ko bolo ki kaam shuru ho gaya hai aur kuch minute lagenge; khatam hone par notification aur SYSTEM NOTE aayega. "
                + "Beech mein job_status(\"" + j.id + "\") se stage dekh sakti ho.";
    }

    private static String facelessPipeline(Context c, PriyaJobs.Job j, String audioArg, String topic, String style,
                                           String aspect, boolean kenBurns, String scriptIfNoAudio) throws Exception {
        // ---- 0. tools ready?
        PriyaJobs.stage(j, "Termux + ffmpeg check");
        String prob = ensureFfmpeg(c, j);
        if (prob != null) return "FAILED: setup chahiye.\n" + prob;

        String job = "pv_" + stamp();
        String work = TermuxBridge.HOME + "/PriyaCode/video_work/" + job;
        TermuxBridge.bash(c, "mkdir -p \"$1\" /sdcard/Movies/Priya", new String[]{work}, 20);

        // ---- 1. which audio?
        String audio = audioArg == null ? "" : audioArg.trim();
        boolean madeVoice = false;
        if (audio.isEmpty() || audio.equalsIgnoreCase("latest") || audio.equalsIgnoreCase("recent")) {
            if (!lastPickedAudio.isEmpty() && audio.isEmpty()) audio = lastPickedAudio;
            else {
                List<AudioItem> l = recentAudio(c, 3);
                if (!l.isEmpty() && (audio.equalsIgnoreCase("latest") || audio.equalsIgnoreCase("recent"))) audio = l.get(0).path;
            }
        }
        if (audio.isEmpty()) {
            if (scriptIfNoAudio == null || scriptIfNoAudio.trim().isEmpty()) {
                return "FAILED: kaunsa audio use karna hai pata nahi. list_recent_audio se file dikhao ya pick_audio_file se Sachin se chunwao, "
                        + "ya agar Sachin kahe to script likh kar Priya ki awaaz mein voiceover (script_if_no_audio) se video banao.";
            }
            PriyaJobs.stage(j, "voiceover bana rahi hoon (TTS)");
            String t = tts(c, scriptIfNoAudio, "warm, clear, engaging narrator");
            if (!t.startsWith("VERIFIED")) return "FAILED: voiceover nahi bana: " + t;
            audio = t.substring(t.lastIndexOf(": ") + 2).trim();
            madeVoice = true;
        }
        TermuxBridge.Result chk = TermuxBridge.bash(c, "[ -r \"$1\" ] && echo OK || echo NOFILE", new String[]{audio}, 20);
        if (!chk.stdout.contains("OK")) {
            return "FAILED: audio file nahi mili ya Termux use padh nahi pa raha: " + audio
                    + " (Termux mein termux-setup-storage chalana zaroori hai; ya pick_audio_file se file chuno).";
        }

        // ---- 2. duration + small copy for understanding
        PriyaJobs.stage(j, "audio ki length aur awaaz samajh rahi hoon");
        double dur = probeDuration(c, audio);
        if (dur <= 0.5) return "FAILED: audio ki duration nahi nikli (file kharab ya audio nahi hai): " + audio;
        if (dur > 1200) return "FAILED: audio " + Math.round(dur) + " sec ka hai — abhi 20 min tak ka hi support hai. Chhota hissa do.";
        String small = work + "/voice_small.mp3";
        TermuxBridge.Result cr = TermuxBridge.bash(c, "ffmpeg -y -v error -i \"$1\" -vn -ac 1 -ar 16000 -b:a 24k \"$2\" && stat -c %s \"$2\"",
                new String[]{audio, small}, 300);
        long smallSize = 0;
        try { smallSize = Long.parseLong(cr.stdout.trim().split("\\s+")[0]); } catch (Exception ignored) { }
        if (smallSize <= 0) return "FAILED: audio convert nahi hua: " + TermuxBridge.clip(cr.stderr + cr.err, 400);
        byte[] voice = readViaTermux(c, small, smallSize);

        // ---- 3. scenes
        int target = (int) Math.max(3, Math.min(40, Math.round(dur / 5.5)));
        String styleTxt = style == null || style.trim().isEmpty() ? "cinematic, realistic, moody lighting, high detail" : style.trim();
        String prompt = "This is the voiceover for a FACELESS video (" + (topic == null ? "" : "topic: " + topic + ", ") + "duration "
                + String.format(Locale.ROOT, "%.1f", dur) + " seconds). Listen carefully and split it into about " + target
                + " consecutive scenes that follow what is being said. Return ONLY JSON, no markdown:\n"
                + "{\"title\":\"short title\",\"language\":\"hi/en/...\",\"scenes\":[{\"start\":0.0,\"end\":5.2,\"text\":\"what is said\","
                + "\"image_prompt\":\"detailed English prompt for ONE still image that visualises this moment\"}]}\n"
                + "Rules: scenes cover 0 to " + String.format(Locale.ROOT, "%.1f", dur) + " with no gaps; each 2-9 seconds; "
                + "image prompts in visual style: " + styleTxt + "; vertical-friendly composition for aspect " + aspect
                + "; FACELESS: no visible human faces (use silhouettes, people from behind, hands, objects, places, symbols); no text, letters, logos or watermarks in the image.";
        String raw;
        try {
            raw = GeminiRest.audioText(c, voice, "audio/mp3", prompt);
        } catch (Exception e) {
            return "FAILED: audio samajh nahi paayi (Gemini): " + e.getMessage();
        }
        List<double[]> times = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        String vtitle = topic == null ? "" : topic;
        try {
            String js = raw.trim();
            int a = js.indexOf('{'), b = js.lastIndexOf('}');
            JSONObject o = new JSONObject(js.substring(a, b + 1));
            if (vtitle.isEmpty()) vtitle = o.optString("title", "");
            JSONArray sc = o.getJSONArray("scenes");
            for (int i = 0; i < sc.length(); i++) {
                JSONObject s = sc.getJSONObject(i);
                String ip = s.optString("image_prompt", s.optString("text", ""));
                if (ip.isEmpty()) continue;
                times.add(new double[]{s.optDouble("start", -1), s.optDouble("end", -1)});
                prompts.add(ip);
            }
        } catch (Exception e) {
            // fallback: even split, prompts from topic
            times.clear();
            prompts.clear();
        }
        if (prompts.isEmpty()) {
            int n = Math.max(3, Math.min(12, (int) Math.round(dur / 6)));
            for (int i = 0; i < n; i++) {
                times.add(new double[]{dur * i / n, dur * (i + 1) / n});
                prompts.add((topic == null || topic.isEmpty() ? "abstract atmospheric scene" : topic) + ", scene " + (i + 1) + " of " + n
                        + ", " + styleTxt + ", no faces, no text");
            }
        }
        // make contiguous 0..dur
        int n = prompts.size();
        double[] cut = new double[n + 1];
        cut[0] = 0;
        for (int i = 1; i < n; i++) {
            double s = times.get(i)[0];
            if (s <= cut[i - 1] + 1.2 || s >= dur - 1.0 || Double.isNaN(s)) s = Math.min(dur - 0.5, cut[i - 1] + Math.max(1.5, (dur - cut[i - 1]) / (n - i + 1)));
            cut[i] = s;
        }
        cut[n] = dur;

        // ---- 4. images
        String folder = "Pictures/Priya/" + job;
        List<String> imgs = new ArrayList<>();
        int failed = 0;
        String lastErr = "";
        for (int i = 0; i < n; i++) {
            PriyaJobs.stage(j, "image " + (i + 1) + "/" + n + " bana rahi hoon");
            String full = prompts.get(i) + ". Style: " + styleTxt + ". Faceless (no visible human faces). No text, no letters, no watermark.";
            String path = null;
            for (int t = 0; t < 2 && path == null; t++) {
                try {
                    GeminiRest.Media m = GeminiRest.image(c, full, aspect);
                    path = saveShared(c, m.bytes, m.mime, folder, String.format(Locale.ROOT, "scene_%02d.%s", i + 1, ext(m.mime)));
                } catch (Exception e) {
                    lastErr = e.getMessage();
                    full = "Faceless, no text. " + styleTxt + ". " + GeminiRest.clip(prompts.get(i), 200);
                }
            }
            if (path == null) {
                failed++;
                if (!imgs.isEmpty()) path = imgs.get(imgs.size() - 1); // reuse previous image so timing stays right
            }
            imgs.add(path);
        }
        // fill leading nulls
        String firstOk = null;
        for (String p : imgs) if (p != null) { firstOk = p; break; }
        if (firstOk == null) return "FAILED: ek bhi image nahi bani. Wajah: " + lastErr;
        for (int i = 0; i < imgs.size(); i++) if (imgs.get(i) == null) imgs.set(i, firstOk);
        if (failed > n / 2) return "FAILED: " + failed + "/" + n + " images nahi bani (" + lastErr + "). Video nahi banaya — thodi der baad dobara try karo.";

        // ---- 5. segments
        int W = 720, H = 1280;
        if (aspect.equals("16:9") || aspect.equals("21:9")) { W = 1280; H = 720; }
        else if (aspect.equals("1:1")) { W = 1080; H = 1080; }
        else if (aspect.equals("4:5")) { W = 864; H = 1080; }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < n; i++) {
            PriyaJobs.stage(j, "video scene " + (i + 1) + "/" + n + " jod rahi hoon");
            double d = Math.max(0.5, cut[i + 1] - cut[i]);
            int frames = (int) Math.ceil(d * 25);
            String vf;
            if (kenBurns) {
                String z = (i % 2 == 0) ? "min(zoom+0.0007,1.15)" : "if(eq(on,0),1.15,max(zoom-0.0007,1.0))";
                vf = "scale=" + (2 * W) + ":" + (2 * H) + ":force_original_aspect_ratio=increase,crop=" + (2 * W) + ":" + (2 * H)
                        + ",zoompan=z='" + z + "':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=" + frames + ":s=" + W + "x" + H
                        + ":fps=25,format=yuv420p";
            } else {
                vf = "scale=" + W + ":" + H + ":force_original_aspect_ratio=increase,crop=" + W + ":" + H + ",fps=25,format=yuv420p";
            }
            String seg = String.format(Locale.ROOT, "%s/seg_%02d.mp4", work, i + 1);
            TermuxBridge.Result r = TermuxBridge.bash(c,
                    "ffmpeg -y -v error -loop 1 -i \"$1\" -t \"$2\" -vf \"$3\" -r 25 -c:v libx264 -preset veryfast -crf 22 -pix_fmt yuv420p -an \"$4\" && echo SEG_OK",
                    new String[]{imgs.get(i), String.format(Locale.ROOT, "%.3f", d), vf, seg}, 420);
            if (!r.stdout.contains("SEG_OK")) {
                return "FAILED: scene " + (i + 1) + " ka video nahi bana (ffmpeg): " + TermuxBridge.clip(r.stderr + " " + r.err, 500)
                        + ". Images save hain: " + SD + "/" + folder;
            }
            list.append("file '").append(seg).append("'\n");
        }

        // ---- 6. final mp4 with his audio
        PriyaJobs.stage(j, "final MP4 bana rahi hoon (audio + scenes)");
        String safeTitle = vtitle.replaceAll("[^a-zA-Z0-9]+", "_");
        if (safeTitle.length() > 30) safeTitle = safeTitle.substring(0, 30);
        String out = "/sdcard/Movies/Priya/faceless_" + (safeTitle.isEmpty() ? "" : safeTitle + "_") + stamp() + ".mp4";
        TermuxBridge.Result fr = TermuxBridge.bash(c,
                "printf '%s' \"$1\" > \"$2/list.txt\" && ffmpeg -y -v error -f concat -safe 0 -i \"$2/list.txt\" -i \"$3\" -map 0:v:0 -map 1:a:0 "
                        + "-c:v copy -c:a aac -b:a 160k -shortest -movflags +faststart \"$4\" && echo FINAL_OK",
                new String[]{list.toString(), work, audio, out}, 600);
        if (!fr.stdout.contains("FINAL_OK")) {
            return "FAILED: final video jodte waqt error: " + TermuxBridge.clip(fr.stderr + " " + fr.err, 500);
        }

        // ---- 7. verify
        PriyaJobs.stage(j, "video verify kar rahi hoon");
        TermuxBridge.Result v = TermuxBridge.bash(c, "[ -s \"$1\" ] && stat -c %s \"$1\" && ffprobe -v error -show_entries format=duration -of csv=p=0 \"$1\"",
                new String[]{out}, 60);
        String[] parts = v.stdout.trim().split("\\s+");
        long size = 0;
        double vd = -1;
        try { size = Long.parseLong(parts[0]); vd = Double.parseDouble(parts[1]); } catch (Exception ignored) { }
        if (size <= 0 || vd <= 0) return "FAILED: video file check fail (size " + size + ", duration " + vd + "): " + out;
        String abs = out.replace("/sdcard", SD);
        scan(c, abs);
        boolean durOk = Math.abs(vd - dur) < 2.5;
        return "VERIFIED: faceless video ban gaya — " + abs + " (" + (size / 1024 / 1024.0 > 1 ? String.format(Locale.ROOT, "%.1f MB", size / 1024 / 1024.0) : (size / 1024) + " KB")
                + ", " + String.format(Locale.ROOT, "%.1f", vd) + " sec" + (durOk ? ", audio ke barabar" : ", audio " + String.format(Locale.ROOT, "%.1f", dur) + " sec (thoda farq)")
                + ", " + n + " scenes, " + aspect + (kenBurns ? ", ken-burns zoom" : "") + (madeVoice ? ", Priya ka voiceover" : ", aapki awaaz")
                + ")." + (failed > 0 ? " Note: " + failed + " scene ki image nahi bani, pichli image reuse hui." : "")
                + " Images: " + SD + "/" + folder + ". Gallery > Movies > Priya mein dekho.";
    }
}
