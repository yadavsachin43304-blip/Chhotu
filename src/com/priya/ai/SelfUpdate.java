package com.priya.ai;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Priya upgrading herself (owner-only). The source lives in Termux (~/PriyaAI, the folder build.sh is in) and is
 * changed, built and installed ONLY through these tools:
 *
 *   self_status -> self_search / self_read -> self_edit / self_write -> self_diff -> self_build (background)
 *   -> owner says "haan" -> self_install   (self_rollback = restore the pre-upgrade source + rebuild)
 *
 * Safety (enforced here and by selfup.sh in Termux, not just by the prompt):
 *  - every change needs a recent spoken order; install needs a fresh spoken YES AFTER the build finished;
 *  - a snapshot of the source is taken before the first edit (rollback);
 *  - the safety / identity layer (PROTECTED) and the build tooling cannot be edited, and selfup.sh refuses to
 *    build or install if their hashes changed;
 *  - new code that deletes things, spawns shells or installs packages by itself is rejected;
 *  - the installer is this class only (PackageInstaller session, apk handed over from Termux over 127.0.0.1 and
 *    checked by SHA-256); Android itself refuses an update that is not signed with the same key.
 * This file is itself protected: Priya cannot edit it.
 */
final class SelfUpdate {

    static final String[] TOOLS = {"self_status", "self_search", "self_read", "self_edit", "self_write",
            "self_diff", "self_build", "self_install", "self_rollback"};

    /** Same list as PROTECTED in selfup.sh. */
    static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "selfup.sh", "build.sh", "align.py", "priya-release.jks",
            "src/com/priya/ai/ActionExecutor.java", "src/com/priya/ai/HeadlessAgent.java",
            "src/com/priya/ai/SelfUpdate.java", "src/com/priya/ai/PriyaService.java",
            "src/com/priya/ai/Owner.java", "src/com/priya/ai/SpeakerVerifier.java",
            "src/com/priya/ai/TermuxBridge.java", "src/com/priya/ai/InstallReceiver.java"));

    private static final long EDIT_WINDOW_MS = 20 * 60 * 1000L;
    private static final long INSTALL_WINDOW_MS = 3 * 60 * 1000L;

    /** Set by PriyaService from the owner's live transcript. */
    static volatile long lastOwnerMs = 0;
    static volatile String lastOwnerText = "";

    private static volatile boolean snapshotDone = false;
    private static volatile int edits = 0;
    private static volatile boolean buildOk = false;
    private static volatile long buildDoneMs = 0;
    private static volatile int builtVc = -1;
    static volatile boolean installing = false;

    private SelfUpdate() {}

    static boolean isSelfTool(String name) {
        for (String t : TOOLS) if (t.equals(name)) return true;
        return false;
    }

    static void onOwnerSpeech(String text) {
        lastOwnerMs = System.currentTimeMillis();
        lastOwnerText = text == null ? "" : text;
    }

    // ------------------------------------------------------------------ tool declarations

    private static JSONObject str(String d) throws JSONException { return new JSONObject().put("type", "STRING").put("description", d); }
    private static JSONObject integer(String d) throws JSONException { return new JSONObject().put("type", "INTEGER").put("description", d); }
    private static JSONObject bool(String d) throws JSONException { return new JSONObject().put("type", "BOOLEAN").put("description", d); }
    private static JSONObject fn(String name, String desc, JSONObject props, String... req) throws JSONException {
        JSONObject f = new JSONObject().put("name", name).put("description", desc);
        if (props != null) {
            JSONObject p = new JSONObject().put("type", "OBJECT").put("properties", props);
            if (req.length > 0) {
                JSONArray r = new JSONArray();
                for (String s : req) r.put(s);
                p.put("required", r);
            }
            f.put("parameters", p);
        }
        return f;
    }

    static void declare(JSONArray a) throws JSONException {
        a.put(fn("self_status", "SELF-UPGRADE step 1: check that Priya's own source + build tools are ready in Termux (folder, tools, last build, backup). Call first when the owner asks Priya to upgrade / update / improve herself.", null));
        a.put(fn("self_search", "SELF-UPGRADE: search Priya's own source code (Java, XML, assets) for a word / method name. Returns file:line matches.",
                new JSONObject().put("query", str("Text to find, e.g. 'onToolCall' or 'barge'")), "query"));
        a.put(fn("self_read", "SELF-UPGRADE: read a source file with line numbers (e.g. 'src/com/priya/ai/BrainTools.java'). Reads 150 lines from from_line; or lists a folder.",
                new JSONObject().put("path", str("Path relative to the source folder"))
                        .put("from_line", integer("First line, default 1"))
                        .put("to_line", integer("Last line, default from_line+149")), "path"));
        a.put(fn("self_edit", "SELF-UPGRADE: change a source file by exact text replacement (find must occur EXACTLY ONCE; copy it from self_read). First edit takes a backup snapshot. Protected safety files, delete/shell-exec code and build tooling are refused. Needs a fresh order from the owner.",
                new JSONObject().put("path", str("Path relative to the source folder"))
                        .put("find", str("Exact existing text (unique in the file, include a few neighbouring lines)"))
                        .put("replace", str("New text")), "path", "find", "replace"));
        a.put(fn("self_write", "SELF-UPGRADE: create a NEW source file (or overwrite=true to replace a non-protected one) with its full content. Only under src/, res/, assets/ (text files). New tools go into a new class and are wired through BrainTools (declare + run).",
                new JSONObject().put("path", str("Path relative to the source folder, e.g. 'src/com/priya/ai/MyFeature.java'"))
                        .put("content", str("Full file content"))
                        .put("overwrite", bool("true = replace an existing file")), "path", "content"));
        a.put(fn("self_diff", "SELF-UPGRADE: show which files and lines changed since the backup snapshot (use before self_build to tell the owner what changed).", null));
        a.put(fn("self_build", "SELF-UPGRADE: compile the changed source into a new APK in Termux (javac + d8 + sign). Runs in the BACKGROUND (1-3 min): returns JOB_STARTED, the result arrives as a SYSTEM NOTE. Never says 'installed'. Needs a fresh order.",
                new JSONObject().put("force", bool("true = build even if nothing was edited"))));
        a.put(fn("self_install", "SELF-UPGRADE: install the freshly built APK over the running app (app restarts). Allowed ONLY after a successful self_build AND the owner said a clear yes AFTER hearing what changed. Pass confirmed=true only if they did. Tell them first that the app will restart.",
                new JSONObject().put("confirmed", bool("true only if the owner clearly said yes to installing"))
                        .put("summary", str("1-2 lines: what changed in this upgrade (shown after the restart)")), "confirmed"));
        a.put(fn("self_rollback", "SELF-UPGRADE: put the source back to the backup from before the last upgrade and rebuild it (new version number, old behaviour). Then self_install after the owner's yes. Use when an upgrade misbehaves.",
                new JSONObject().put("confirmed", bool("true only if the owner clearly asked for / agreed to the rollback")), "confirmed"));
    }

    // ------------------------------------------------------------------ plumbing

    private static String dirName(Context c) {
        String d = Prefs.selfSrcDir(c);
        if (d == null) d = "PriyaAI";
        d = d.trim().replace("~/", "");
        while (d.startsWith("/")) d = d.substring(1);
        if (d.isEmpty() || d.contains("..") || !d.matches("[A-Za-z0-9._-]+")) return "PriyaAI";
        return d;
    }

    static String srcDir(Context c) { return TermuxBridge.HOME + "/" + dirName(c); }

    private static TermuxBridge.Result script(Context c, String cmd, int waitSec, String... extra) {
        String[] args = new String[2 + extra.length];
        args[0] = srcDir(c) + "/selfup.sh";
        args[1] = cmd;
        System.arraycopy(extra, 0, args, 2, extra.length);
        return TermuxBridge.bash(c, "if [ -f \"$1\" ]; then bash \"$1\" \"${@:2}\"; else echo SELFUP_MISSING; fi", args, waitSec);
    }

    private static TermuxBridge.Result raw(Context c, String shell, int waitSec, String... args) {
        return TermuxBridge.bash(c, shell, args, waitSec);
    }

    private static String fmt(TermuxBridge.Result r) {
        if (r.setupProblem != null) return r.setupProblem;
        if (!r.ok) return "TERMUX: " + (r.err.isEmpty() ? "jawab nahi aaya" : r.err);
        String o = r.stdout.trim();
        if (r.exitCode != 0 && !r.stderr.trim().isEmpty()) o += "\n" + r.stderr.trim();
        return o;
    }

    private static final String SRC_HELP =
            "SOURCE TAIYAAR NAHI. Vivek ji ko Hindi mein ek-ek karke ye steps batao:\n"
            + "1) Termux kholo aur chalao:  cd ~ && unzip -o /sdcard/Download/PriyaAI-v35.zip   (zip ka sahi naam jo bhi ho; source folder ka naam PriyaAI hona chahiye, ya Menu > Self-upgrade mein folder ka naam badlo)\n"
            + "2) cd ~/PriyaAI && bash selfup.sh guard-reset      (sirf ek baar — ye safety files ko lock karta hai)\n"
            + "3) pkg install -y openjdk-21 aapt2 apksigner d8 zip python\n"
            + "4) Phone Settings > Priya AI > 'Install unknown apps' ON.\n"
            + "Ye ho jaye to 'ho gaya' bolein, main self_status dobara chalati hoon.";

    private static boolean installedVersionOk(Context c, int vc) {
        return vc > installedVc(c);
    }

    static int installedVc(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; }
        catch (Throwable e) { return -1; }
    }

    static String installedName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Throwable e) { return "?"; }
    }

    private static String needOrder(long windowMs) {
        long age = System.currentTimeMillis() - lastOwnerMs;
        if (lastOwnerMs == 0 || age > windowMs) {
            return "BLOCKED: owner ka taaza order nahi hai (" + (windowMs / 60000) + " minute ke andar). Pehle unse saaf poochho / unka order lo, phir ye tool chalao.";
        }
        return null;
    }

    // ------------------------------------------------------------------ path / content checks

    /** Normalised relative path inside the source folder, or null when it is not acceptable. */
    private static String cleanRel(Context c, String p) {
        if (p == null) return null;
        p = p.trim().replace('\\', '/');
        String dn = dirName(c);
        for (String pre : new String[]{srcDir(c) + "/", "~/" + dn + "/", dn + "/", "./"}) {
            if (p.startsWith(pre)) { p = p.substring(pre.length()); break; }
        }
        if (p.isEmpty() || p.startsWith("/") || p.contains("\u0000")) return null;
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals("..") || seg.startsWith(".")) return null;
        }
        String low = p.toLowerCase(Locale.ROOT);
        if (low.endsWith(".jks") || low.endsWith(".keystore") || low.endsWith(".apk")) return null;
        return p;
    }

    private static boolean writableRoot(String rel) {
        if (rel.equals("AndroidManifest.xml") || rel.equals("README.md")) return true;
        if (!(rel.startsWith("src/") || rel.startsWith("res/") || rel.startsWith("assets/"))) return false;
        String low = rel.toLowerCase(Locale.ROOT);
        return low.endsWith(".java") || low.endsWith(".xml") || low.endsWith(".md") || low.endsWith(".txt")
                || low.endsWith(".json") || low.endsWith(".properties");
    }

    private static final Pattern DANGEROUS_CODE = Pattern.compile(
            "(?i)(\\.delete\\s*\\(|deleteRecursively|Files\\.delete|deleteFile\\s*\\(|deleteDatabase|Runtime\\s*\\.\\s*getRuntime|ProcessBuilder"
                    + "|rm\\s+-r|\\brmdir\\b|clearApplicationUserData|wipeData|DevicePolicyManager|PackageInstaller|ACTION_DELETE|ACTION_UNINSTALL_PACKAGE"
                    + "|REQUEST_DELETE_PACKAGES|factory\\s*reset|\\bsu\\s+-c|Settings\\.Secure|android\\.permission\\.(DELETE|WRITE_SECURE|INSTALL_PACKAGES|MANAGE_EXTERNAL))");

    private static String dangerIn(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = DANGEROUS_CODE.matcher(text);
        return m.find() ? m.group() : null;
    }

    // ------------------------------------------------------------------ tool entry

    static String run(Context ctx, String name, JSONObject a) {
        try {
            switch (name) {
                case "self_status":   return status(ctx);
                case "self_search":   return search(ctx, a.optString("query", ""));
                case "self_read":     return read(ctx, a.optString("path", ""), a.optInt("from_line", 1), a.optInt("to_line", 0));
                case "self_edit":     return edit(ctx, a.optString("path", ""), a.optString("find", ""), a.optString("replace", ""));
                case "self_write":    return write(ctx, a.optString("path", ""), a.optString("content", ""), a.optBoolean("overwrite", false));
                case "self_diff":     return diff(ctx);
                case "self_build":    return build(ctx, a.optBoolean("force", false));
                case "self_install":  return install(ctx, a.optBoolean("confirmed", false), a.optString("summary", ""));
                case "self_rollback": return rollback(ctx, a.optBoolean("confirmed", false));
                default: return "Unknown self tool: " + name;
            }
        } catch (Throwable e) {
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ status

    static String status(Context ctx) {
        TermuxBridge.Result r = script(ctx, "status", 40);
        String o = fmt(r);
        if (r.setupProblem != null) return o;
        if (o.contains("SELFUP_MISSING") || o.contains("SRC_BAD") || !r.ok) return SRC_HELP + "\n(Termux ne bola: " + TermuxBridge.clip(o, 200) + ")";
        StringBuilder missing = new StringBuilder();
        for (String line : o.split("\n")) if (line.startsWith("tool:") && line.endsWith("=MISSING")) missing.append(line.substring(5, line.indexOf('='))).append(' ');
        String lastBuild = "";
        for (String line : o.split("\n")) if (line.startsWith("LAST_BUILD=")) lastBuild = line.substring(11);
        int vc = -1;
        java.util.regex.Matcher m = Pattern.compile("versionCode=(\\d+)").matcher(lastBuild);
        if (m.find()) vc = Integer.parseInt(m.group(1));
        String note = "";
        if (vc > 0 && installedVc(ctx) >= vc && o.contains("SNAPSHOT=") && !o.contains("SNAPSHOT=none")) {
            raw(ctx, "bash \"$1\" finalize", 30, srcDir(ctx) + "/selfup.sh");
            note = "\n(Pichla upgrade install ho chuka hai — backup band kar diya.)";
        }
        boolean canInstall = true;
        try { canInstall = Build.VERSION.SDK_INT < 26 || ctx.getPackageManager().canRequestPackageInstalls(); } catch (Throwable ignored) { }
        StringBuilder sb = new StringBuilder("SELF STATUS:\n");
        sb.append("App abhi: version ").append(installedName(ctx)).append(" (code ").append(installedVc(ctx)).append(")\n");
        sb.append(o).append('\n');
        if (missing.length() > 0) sb.append("KAMI: Termux mein ye tools nahi hain: ").append(missing).append("— chalao: pkg install -y openjdk-21 aapt2 apksigner d8 zip python\n");
        if (o.contains("GUARD=missing")) sb.append("KAMI: safety lock nahi bana. Vivek ji Termux mein ek baar chalayein: cd ~/").append(dirName(ctx)).append(" && bash selfup.sh guard-reset\n");
        if (!canInstall) sb.append("KAMI: Android Settings > Priya AI > 'Install unknown apps' ON nahi hai (bina iske update install nahi ho sakta).\n");
        if (missing.length() == 0 && !o.contains("GUARD=missing") && canInstall) sb.append("READY: self-upgrade ke liye sab taiyaar hai.\n");
        sb.append(note);
        return sb.toString();
    }

    // ------------------------------------------------------------------ read-only helpers

    private static String search(Context ctx, String q) {
        q = q == null ? "" : q.trim();
        if (q.length() < 2 || q.length() > 80) return "Dhundhne ke liye 2-80 akshar ka shabd do.";
        TermuxBridge.Result r = raw(ctx,
                "cd \"$1\" 2>/dev/null || { echo SRC_BAD; exit 2; }; "
                        + "grep -rniF --include='*.java' --include='*.xml' --include='*.md' --include='*.txt' -- \"$2\" src res assets AndroidManifest.xml 2>/dev/null | cut -c1-210 | head -n 60",
                30, srcDir(ctx), q);
        String o = fmt(r);
        if (o.contains("SRC_BAD")) return SRC_HELP;
        return o.isEmpty() ? "Kuch nahi mila: '" + q + "'" : TermuxBridge.clip(o, 6500);
    }

    private static String read(Context ctx, String path, int from, int to) {
        String rel = cleanRel(ctx, path);
        if (rel == null) return "Path galat hai. Source folder ke andar relative path do, jaise 'src/com/priya/ai/BrainTools.java'.";
        if (from < 1) from = 1;
        if (to < from) to = from + 149;
        if (to - from > 400) to = from + 400;
        TermuxBridge.Result r = raw(ctx,
                "cd \"$1\" 2>/dev/null || { echo SRC_BAD; exit 2; }\n"
                        + "if [ -d \"$2\" ]; then ls -1 \"$2\" | head -n 120; exit 0; fi\n"
                        + "[ -f \"$2\" ] || { echo NOT_FOUND; exit 3; }\n"
                        + "echo \"FILE $2 ($(wc -l < \"$2\") lines)\"\n"
                        + "awk -v s=\"$3\" -v e=\"$4\" 'NR>=s && NR<=e {printf \"%d| %s\\n\", NR, $0}' \"$2\" | head -c 9000",
                30, srcDir(ctx), rel, String.valueOf(from), String.valueOf(to));
        String o = fmt(r);
        if (o.contains("SRC_BAD")) return SRC_HELP;
        if (o.contains("NOT_FOUND")) return "Ye file nahi mili: " + rel + ". self_search se sahi naam dhundho.";
        return o;
    }

    private static String diff(Context ctx) {
        return TermuxBridge.clip(fmt(script(ctx, "diff", 60)), 6500);
    }

    // ------------------------------------------------------------------ edits

    private static String ensureSnapshot(Context ctx) {
        if (snapshotDone) return null;
        TermuxBridge.Result r = script(ctx, "snapshot", 120);
        String o = fmt(r);
        if (o.contains("SNAPSHOT_OK") || o.contains("SNAPSHOT_EXISTS")) { snapshotDone = true; return null; }
        if (o.contains("SELFUP_MISSING") || o.contains("SRC_BAD")) return SRC_HELP;
        return "Backup (snapshot) nahi bana, isliye badlav roka: " + TermuxBridge.clip(o, 300);
    }

    private static String checkWrite(Context ctx, String path, String newText) {
        String block = needOrder(EDIT_WINDOW_MS);
        if (block != null) return block;
        if (installing) return "BLOCKED: install chal raha hai.";
        String rel = cleanRel(ctx, path);
        if (rel == null) return "Path galat hai (absolute / .. / hidden file / key file mana hai).";
        if (PROTECTED.contains(rel)) {
            return "BLOCKED: '" + rel + "' protected hai (safety / identity / build ka hissa) — ise Priya nahi badal sakti. Vivek ji chahein to wo khud badal sakte hain.";
        }
        if (!writableRoot(rel)) return "BLOCKED: sirf src/, res/, assets/ ke text files (java/xml/md/txt/json) aur AndroidManifest.xml badle ja sakte hain.";
        String bad = dangerIn(newText);
        if (bad != null) {
            return "BLOCKED: naye code mein '" + bad + "' hai — delete / shell / package-install jaisa code Priya apne mein nahi jod sakti (role ke khilaf). Bina iske tareeka socho.";
        }
        return null;
    }

    private static String edit(Context ctx, String path, String find, String replace) {
        String block = checkWrite(ctx, path, replace);
        if (block != null) return block;
        if (find == null || find.isEmpty()) return "'find' khaali hai. self_read se exact text copy karo.";
        if (find.length() > 20000 || replace.length() > 30000) return "Badlav bahut bada hai — chhote-chhote self_edit mein todo.";
        String rel = cleanRel(ctx, path);
        String snap = ensureSnapshot(ctx);
        if (snap != null) return snap;
        String py = "import sys,base64\n"
                + "p=sys.argv[1]; f=base64.b64decode(sys.argv[2]).decode('utf-8'); r=base64.b64decode(sys.argv[3]).decode('utf-8')\n"
                + "try:\n    d=open(p,encoding='utf-8').read()\nexcept Exception as e:\n    print('NOFILE'); sys.exit(2)\n"
                + "n=d.count(f)\n"
                + "if n==0:\n    print('NOMATCH'); sys.exit(3)\n"
                + "if n>1:\n    print('MULTI %d' % n); sys.exit(4)\n"
                + "open(p,'w',encoding='utf-8').write(d.replace(f,r,1))\n"
                + "print('EDIT_OK')\n";
        TermuxBridge.Result r = raw(ctx, "cd \"$1\" 2>/dev/null || { echo SRC_BAD; exit 2; }; python3 -c \"$2\" \"$3\" \"$4\" \"$5\"",
                40, srcDir(ctx), py, rel, TermuxBridge.b64(find), TermuxBridge.b64(replace));
        String o = fmt(r);
        if (o.contains("EDIT_OK")) {
            edits++;
            buildOk = false;
            return "EDIT_OK: " + rel + " badal diya (" + find.length() + " -> " + replace.length() + " akshar). "
                    + "Aur badlav ho to karo; sab ho jaye to self_diff, phir self_build.";
        }
        if (o.contains("NOMATCH")) return "NOMATCH: 'find' ka text file mein exact nahi mila (spaces/line breaks dekho). self_read se wahi hissa dobara padho aur copy karo.";
        if (o.contains("MULTI")) return o.trim() + ": 'find' ek se zyada jagah hai — kuch aas-paas ki lines jod kar unique banao.";
        if (o.contains("NOFILE")) return "File nahi mili: " + rel + ". Nayi file ke liye self_write use karo.";
        if (o.contains("SRC_BAD")) return SRC_HELP;
        return "Edit nahi hua: " + TermuxBridge.clip(o, 300) + " (Termux mein python install hai? pkg install python)";
    }

    private static String write(Context ctx, String path, String content, boolean overwrite) {
        String block = checkWrite(ctx, path, content);
        if (block != null) return block;
        if (content == null || content.isEmpty()) return "Content khaali hai.";
        if (content.length() > 60000) return "File 60 KB se badi hai — kai chhoti files mein todo.";
        String rel = cleanRel(ctx, path);
        String snap = ensureSnapshot(ctx);
        if (snap != null) return snap;
        TermuxBridge.Result r = raw(ctx,
                "cd \"$1\" 2>/dev/null || { echo SRC_BAD; exit 2; }\n"
                        + "if [ -e \"$2\" ] && [ \"$4\" != \"1\" ]; then echo EXISTS; exit 3; fi\n"
                        + "mkdir -p \"$(dirname \"$2\")\" && printf '%s' \"$3\" | base64 -d > \"$2\" && echo \"WRITE_OK $(wc -c < \"$2\") bytes, $(wc -l < \"$2\") lines\"",
                40, srcDir(ctx), rel, TermuxBridge.b64(content), overwrite ? "1" : "0");
        String o = fmt(r);
        if (o.contains("WRITE_OK")) {
            edits++;
            buildOk = false;
            return o.trim() + " — " + rel + ". Naya tool/class ho to BrainTools (declare + run) se jodo, phir self_build.";
        }
        if (o.contains("EXISTS")) return "Ye file pehle se hai: " + rel + ". Chhota badlav ho to self_edit, poori badalni ho to overwrite=true.";
        if (o.contains("SRC_BAD")) return SRC_HELP;
        return "File nahi likhi: " + TermuxBridge.clip(o, 300);
    }

    // ------------------------------------------------------------------ build / rollback

    private static String build(final Context ctx, boolean force) {
        String block = needOrder(EDIT_WINDOW_MS);
        if (block != null) return block;
        if (installing) return "BLOCKED: install chal raha hai.";
        if (edits == 0 && !force) return "Abhi koi badlav nahi hua (self_edit / self_write pehle). Phir bhi build karna ho to force=true.";
        final Context app = ctx.getApplicationContext();
        PriyaJobs.Job j = PriyaJobs.start(app, "build", "Priya apna naya version bana rahi hai", job -> {
            PriyaJobs.stage(job, "compile + sign (Termux mein 1-3 minute)");
            return buildResult(app, fmt(script(app, "build", 700)));
        });
        return "JOB_STARTED " + j.id + ": naya version Termux mein ban raha hai (1-3 minute). Tab tak aaram se baat karti raho; "
                + "result SYSTEM NOTE se aayega. 'BUILD_OK' aane tak install ki baat mat karo.";
    }

    private static String buildResult(Context app, String out) {
        java.util.regex.Matcher m = Pattern.compile("BUILD_OK versionCode=(\\d+|\\?) size=(\\d+)").matcher(out);
        if (m.find()) {
            int vc = m.group(1).equals("?") ? 0 : Integer.parseInt(m.group(1));
            if (vc > 0 && !installedVersionOk(app, vc)) {
                buildOk = false;
                return "FAILED: build bana par version number (" + vc + ") installed (" + installedVc(app) + ") se bada nahi — update install nahi ho sakta. build.sh ka version code check karo.";
            }
            buildOk = true;
            buildDoneMs = System.currentTimeMillis();
            builtVc = vc;
            edits = 0;
            return "VERIFIED: BUILD_OK — naya APK bana (versionCode " + (vc == 0 ? "?" : String.valueOf(vc)) + ", " + (Long.parseLong(m.group(2)) / 1024) + " KB). Install abhi NAHI hua.";
        }
        buildOk = false;
        if (out.contains("GUARD_FAIL")) return "FAILED: " + TermuxBridge.clip(out, 700);
        return "FAILED: " + TermuxBridge.clip(out, 2200);
    }

    private static String rollback(final Context ctx, boolean confirmed) {
        String block = needOrder(10 * 60 * 1000L);
        if (block != null) return block;
        if (!confirmed || !looksLikeYes(lastOwnerText)) {
            return "BLOCKED: rollback tabhi jab owner ne saaf kaha ho. Unse poochho: 'Pichle version jaisa wapas bana loon?' aur unki haan ke baad confirmed=true.";
        }
        if (installing) return "BLOCKED: install chal raha hai.";
        final Context app = ctx.getApplicationContext();
        PriyaJobs.Job j = PriyaJobs.start(app, "rollback", "Priya pichla version wapas bana rahi hai", job -> {
            PriyaJobs.stage(job, "backup restore + build (1-3 minute)");
            String out = fmt(script(app, "rollback", 800));
            if (out.contains("NO_SNAPSHOT")) return "FAILED: " + out;
            String r = buildResult(app, out);
            return r.startsWith("VERIFIED") ? r.replace("BUILD_OK", "ROLLBACK_BUILD_OK") : r;
        });
        return "JOB_STARTED " + j.id + ": pichla source wapas rakh kar naya build bana rahi hoon (1-3 minute). Result SYSTEM NOTE se.";
    }

    // ------------------------------------------------------------------ install

    private static final Pattern YES = Pattern.compile(
            "(?i)(\\bha+n?\\b|\\bhaan\\b|\\bhaa\\b|\\bhan\\b|\\bji\\b|\\byes\\b|\\byeah\\b|\\byep\\b|\\bok\\b|\\bokay\\b|\\bthee?k\\b|\\bkar\\s*do\\b|\\bkardo\\b|\\bkaro\\b|\\bchalo\\b|\\bchalu\\b"
                    + "|\\binstall\\b|\\bupgrade\\b|\\bupdate\\b|\\bsure\\b|\\bbilkul\\b|हाँ|हां|ठीक|कर दो|करो|चलो)");
    private static final Pattern NO = Pattern.compile(
            "(?i)(\\bnahi\\b|\\bnahin\\b|\\bmat\\b|\\bna karo\\b|\\bruko\\b|\\bcancel\\b|\\bstop\\b|\\bno\\b|\\bnope\\b|\\bnahi\\b|नहीं|मत|रुको|ठहरो)");

    static boolean looksLikeYes(String t) {
        if (t == null) return false;
        t = t.trim();
        return !t.isEmpty() && YES.matcher(t).find() && !NO.matcher(t).find();
    }

    private static String install(final Context ctx, boolean confirmed, String summary) {
        final Context app = ctx.getApplicationContext();
        if (installing) return "BLOCKED: install pehle se chal raha hai.";
        if (!buildOk) return "BLOCKED: pehle self_build chalao aur 'BUILD_OK' aane do. Bina nayi build ke install nahi hoga.";
        long now = System.currentTimeMillis();
        if (lastOwnerMs <= buildDoneMs + 1500) {
            return "BLOCKED: build poori hone ke BAAD owner ne abhi kuch nahi bola. Unhe 1-2 line mein batao kya badla, saaf bolo ki install par app restart hogi, "
                    + "aur poochho 'install kar doon?' — unki 'haan' sunne ke baad hi dobara self_install(confirmed=true).";
        }
        if (now - lastOwnerMs > INSTALL_WINDOW_MS) return "BLOCKED: owner ki haan purani ho gayi (3 minute+). Dobara poochho.";
        if (!confirmed) return "BLOCKED: confirmed=true tabhi do jab owner ne saaf 'haan' kaha ho.";
        if (!looksLikeYes(lastOwnerText)) {
            return "BLOCKED: owner ki aakhri baat 'haan / install karo' jaisi nahi lagi (suna: '" + TermuxBridge.clip(lastOwnerText, 80)
                    + "'). Dobara saaf poochho.";
        }
        if (builtVc > 0 && !installedVersionOk(app, builtVc)) return "BLOCKED: ye version pehle se installed hai.";
        try {
            if (Build.VERSION.SDK_INT >= 26 && !app.getPackageManager().canRequestPackageInstalls()) {
                try {
                    app.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable ignored) { }
                return "NEEDS_PERMISSION: Settings mein Priya AI ke liye 'Install unknown apps' ON karna hoga — maine wo screen khol di hai. "
                        + "Vivek ji ko ON karne ko bolo, phir bolein 'ho gaya' aur self_install dobara.";
            }
        } catch (Throwable ignored) { }

        installing = true;
        Prefs.setUpgradeNote(app, summary == null || summary.trim().isEmpty() ? "Naya version install hua." : summary.trim());
        new Thread(() -> {
            SystemClock.sleep(5500); // let Priya finish saying "update ho rahi hoon"
            String fail = doInstall(app);
            if (fail != null) {
                installing = false;
                PriyaJobs.notifyOwner(app, "⚠️ Priya ka update install nahi hua", fail, 0x5e1f);
            }
        }, "priya-self-install").start();
        return "INSTALL_STARTING: 5 second mein update shuru hogi. Abhi EK chhoti line bolo: 'theek hai, update ho rahi hoon — thodi der baad mic dobara dabana.' "
                + "Phir kuch aur mat karo; app band ho kar naye version mein khulegi. Agar phone par 'Update' ka box aaye to main use dabane ki koshish karungi.";
    }

    /** Streams the apk from Termux over 127.0.0.1 into a PackageInstaller session. Returns null once committed, else the failure text. */
    private static String doInstall(Context app) {
        try {
            String g = fmt(script(app, "guard", 40));
            if (!g.contains("GUARD_OK")) return "Safety lock (guard) pass nahi hua: " + TermuxBridge.clip(g, 300);
            String info = fmt(script(app, "apkinfo", 30));
            java.util.regex.Matcher m = Pattern.compile("sha256=([0-9a-f]{64}) size=(\\d+) path=(\\S+)").matcher(info);
            if (!m.find()) return "APK ki jaankari nahi mili: " + TermuxBridge.clip(info, 200);
            final String sha = m.group(1);
            final long size = Long.parseLong(m.group(2));
            final String apkPath = m.group(3);
            if (size < 200000 || size > 120L * 1024 * 1024) return "APK ka size ajeeb hai (" + size + " bytes).";

            final int port = 41000 + new Random().nextInt(3000);
            Thread serve = new Thread(() -> script(app, "serve", 90, apkPath, String.valueOf(port)), "priya-self-serve");
            serve.start();

            Socket sock = null;
            for (int i = 0; i < 30 && sock == null; i++) {
                SystemClock.sleep(i == 0 ? 1500 : 700);
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                    s.setSoTimeout(30000);
                    sock = s;
                } catch (Exception ignored) { }
            }
            if (sock == null) return "Termux se APK nahi mila (port " + port + "). Termux chal raha hai? 'python' installed hai?";

            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(app.getPackageName());
            sp.setSize(size);
            int sid = pi.createSession(sp);
            PackageInstaller.Session session = pi.openSession(sid);
            try {
                DataInputStream in = new DataInputStream(sock.getInputStream());
                long len = in.readLong();
                if (len != size) { session.abandon(); return "APK ka size match nahi hua (" + len + " vs " + size + ")."; }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                OutputStream out = session.openWrite("priya.apk", 0, size);
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                while (got < size) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, size - got));
                    if (n < 0) break;
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                    got += n;
                }
                session.fsync(out);
                out.close();
                try { sock.close(); } catch (Exception ignored) { }
                StringBuilder hex = new StringBuilder();
                for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
                if (got != size || !hex.toString().equals(sha)) {
                    session.abandon();
                    return "APK transfer sahi nahi hua (checksum mismatch) — install roka.";
                }
                Intent cb = new Intent(app, InstallReceiver.class).setAction(InstallReceiver.ACTION);
                PendingIntent pend = PendingIntent.getBroadcast(app, sid, cb, PendingIntent.FLAG_UPDATE_CURRENT | 0x02000000);
                session.commit(pend.getIntentSender());
            } finally {
                try { session.close(); } catch (Throwable ignored) { }
            }
            watchInstaller();
            return null;
        } catch (Throwable e) {
            return "Install start nahi hua: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** After the owner's yes: taps Update / Install on the system installer window if it shows up (up to ~90 s). */
    static void watchInstaller() {
        new Thread(() -> {
            long until = SystemClock.elapsedRealtime() + 90000;
            while (SystemClock.elapsedRealtime() < until) {
                SystemClock.sleep(1200);
                PriyaAccessibilityService a = PriyaAccessibilityService.get();
                if (a == null) continue;
                String pkg = PriyaAccessibilityService.currentPackage;
                if (pkg == null || !pkg.contains("packageinstaller")) continue;
                for (String label : new String[]{"Update", "Install", "Install anyway", "Continue", "अपडेट", "इंस्टॉल"}) {
                    try {
                        if (a.clickSmart(label, 0, false, false).startsWith("OK")) { SystemClock.sleep(1500); break; }
                    } catch (Throwable ignored) { }
                }
            }
        }, "priya-installer-watch").start();
    }

    // ------------------------------------------------------------------ after the restart

    /** Called from the receiver when the new version starts: tells the owner what changed and closes the snapshot. */
    static void onPackageReplaced(final Context app) {
        installing = false;
        snapshotDone = false;
        String note = Prefs.upgradeNote(app);
        if (note != null && !note.isEmpty()) {
            PriyaJobs.notifyOwner(app, "✅ Priya naye version par chal rahi hai", note + "\n(Mic dobara dabana.)", 0x5e1e);
            Prefs.setUpgradeNote(app, "");
        }
        new Thread(() -> {
            try { raw(app, "bash \"$1/selfup.sh\" finalize", 40, srcDir(app)); } catch (Throwable ignored) { }
        }, "priya-self-finalize").start();
    }

    static String notifBody(Context c) {
        return status(c);
    }

    /** Used by the Self-upgrade screen. */
    static boolean canInstall(Context c) {
        try { return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls(); }
        catch (Throwable e) { return true; }
    }
}
