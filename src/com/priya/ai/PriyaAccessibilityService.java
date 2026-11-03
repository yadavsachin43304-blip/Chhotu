package com.priya.ai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lets Priya operate the phone: global actions (home/back/recents...), tap by text, type text,
 * scroll, read the screen and tap the first YouTube result.
 */
public class PriyaAccessibilityService extends AccessibilityService {

    private static volatile PriyaAccessibilityService instance;
    public static volatile String currentPackage = "";

    public static PriyaAccessibilityService get() { return instance; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            currentPackage = e.getPackageName().toString();
        }
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ gestures

    /** Sends a gesture and (off the main thread) waits until it has finished. */
    private boolean dispatchSync(GestureDescription g, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean sent;
        try {
            sent = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { ok[0] = true; latch.countDown(); }
                @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
            }, null);
        } catch (Exception e) {
            return false;
        }
        if (!sent) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return true; // cannot block the main thread
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return ok[0];
    }

    private static long clampDur(long ms) {
        return Math.max(1, Math.min(ms, 59000));
    }

    public boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, clampDur(durationMs)));
        return dispatchSync(b.build(), durationMs + 3000);
    }

    public boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
        return dispatchSync(b.build(), 3000);
    }

    public boolean longPress(float x, float y, long ms) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, clampDur(Math.max(600, ms))));
        return dispatchSync(b.build(), ms + 3000);
    }

    /**
     * Press-and-hold at (x1,y1) for holdMs, then drag to (x2,y2) in moveMs without lifting the finger.
     * This is what editor timelines (CapCut trim handles, clip reordering) need.
     */
    public boolean drag(float x1, float y1, float x2, float y2, long holdMs, long moveMs) {
        if (holdMs <= 0) return swipe(x1, y1, x2, y2, moveMs);
        try {
            Path p1 = new Path();
            p1.moveTo(x1, y1);
            GestureDescription.StrokeDescription s1 =
                    new GestureDescription.StrokeDescription(p1, 0, clampDur(holdMs), true);
            GestureDescription.Builder b1 = new GestureDescription.Builder();
            b1.addStroke(s1);
            if (dispatchSync(b1.build(), holdMs + 3000)) {
                Path p2 = new Path();
                p2.moveTo(x1, y1);
                p2.lineTo(x2, y2);
                GestureDescription.StrokeDescription s2 = s1.continueStroke(p2, 0, clampDur(moveMs), false);
                GestureDescription.Builder b2 = new GestureDescription.Builder();
                b2.addStroke(s2);
                if (dispatchSync(b2.build(), moveMs + 3000)) return true;
            }
        } catch (Exception ignored) { }
        // fallback: one slow continuous stroke (most views treat the slow start as a hold)
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x1 + 1, y1 + 1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, clampDur(holdMs + moveMs)));
        return dispatchSync(b.build(), holdMs + moveMs + 3000);
    }

    public int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    public int screenH() { return getResources().getDisplayMetrics().heightPixels; }

    /** Horizontal swipe across the middle of the screen (left = content moves left). */
    public boolean scrollHorizontal(boolean left) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float y = m.heightPixels / 2f;
        float r = m.widthPixels * 0.85f;
        float l = m.widthPixels * 0.15f;
        return left ? swipe(r, y, l, y, 350) : swipe(l, y, r, y, 350);
    }

    public boolean scroll(boolean down) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float x = m.widthPixels / 2f;
        float hi = m.heightPixels * 0.72f;
        float lo = m.heightPixels * 0.28f;
        return down ? swipe(x, hi, x, lo, 350) : swipe(x, lo, x, hi, 350);
    }

    public boolean tapFractional(float fx, float fy) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return tap(m.widthPixels * fx, m.heightPixels * fy);
    }

    // ------------------------------------------------------------------ nodes

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickableAncestor(n);
        return c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    public boolean clickByText(String text) {
        return clickSmart(text, 0, false, false).startsWith("OK");
    }

    public boolean typeText(String text) {
        return typeInto(text, "", false).startsWith("OK");
    }

    /** Types into the field matching `field` (text/hint/label/id) or the focused / first editable field. */
    public String typeInto(String text, String field, boolean pressEnter) {
        AccessibilityNodeInfo target = null;
        if (field != null && !field.trim().isEmpty()) {
            String needle = field.trim().toLowerCase(Locale.ROOT);
            for (AccessibilityNodeInfo r : roots()) {
                target = findEditableMatching(r, needle, 0);
                if (target != null) break;
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (target == null && root != null) target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null) for (AccessibilityNodeInfo r : roots()) { target = findEditable(r); if (target != null) break; }
        if (target == null) return "NO_FIELD";
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) return "SET_TEXT_FAILED";
        String extra = "";
        if (pressEnter) {
            SystemClock.sleep(250);
            boolean entered = false;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    entered = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                } catch (Throwable ignored) { }
            }
            extra = entered ? " +ENTER" : " (Enter nahi dab paya: keyboard ke search/enter button ya 'Search' par tap karo)";
        }
        return "OK" + extra;
    }

    private AccessibilityNodeInfo findEditableMatching(AccessibilityNodeInfo n, String needle, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isEditable() && n.isVisibleToUser()) {
            String all = (str(n.getText()) + " " + str(n.getContentDescription()) + " " + hint(n) + " " + shortId(n))
                    .toLowerCase(Locale.ROOT);
            if (all.contains(needle)) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditableMatching(n.getChild(i), needle, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** Taps the first video-looking result on a YouTube search screen. */
    public boolean clickFirstVideoResult() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo hit = findVideoNode(root);
        return hit != null && clickNode(hit);
    }

    private static final String[] AD_HINTS = {"sponsored", "promoted", "ad ·", "ad •", "advertisement", "विज्ञापन", "प्रायोजित"};
    private static final java.util.Set<String> AD_BADGES = new java.util.HashSet<>(java.util.Arrays.asList(
            "ad", "sponsored", "install", "shop now", "learn more", "visit site", "विज्ञापन", "इंस्टॉल करें", "प्रायोजित"));
    private static final String[] DURATION_HINTS = {"minute", "second", "hour", "मिनट", "सेकंड", "घंटे"};
    private static final String[] SKIP_TEXT = {"Skip ad", "Skip ads", "Skip", "विज्ञापन छोड़ें", "छोड़ें"};

    private static boolean has(String low, String[] words) {
        for (String w : words) if (low.contains(w)) return true;
        return false;
    }

    /** True when this result card (or anything inside it) is marked as an ad. */
    private static boolean isAd(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6) return false;
        CharSequence t = n.getText(), d = n.getContentDescription();
        String a = t == null ? "" : t.toString().trim().toLowerCase(Locale.ROOT);
        String b = d == null ? "" : d.toString().trim().toLowerCase(Locale.ROOT);
        if (AD_BADGES.contains(a) || AD_BADGES.contains(b)) return true;
        if (has(a, AD_HINTS) || has(b, AD_HINTS)) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (isAd(n.getChild(i), depth + 1)) return true;
        return false;
    }

    private AccessibilityNodeInfo findVideoNode(AccessibilityNodeInfo n) {
        if (n == null) return null;
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 15 && n.isVisibleToUser()) {
            String low = d.toString().toLowerCase(Locale.ROOT);
            // a real video card says how long it is (e.g. "3 minutes, 20 seconds") and is not an ad, Short or Mix
            if (has(low, DURATION_HINTS) && !has(low, AD_HINTS) && !low.contains("shorts")
                    && !low.contains("mix") && !low.contains("playlist") && !isAd(n, 0)) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findVideoNode(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** Presses YouTube's "Skip ad" button if it is showing. */
    public boolean skipAdIfShown() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        for (String s : SKIP_TEXT) {
            List<AccessibilityNodeInfo> f = root.findAccessibilityNodeInfosByText(s);
            if (f == null) continue;
            for (AccessibilityNodeInfo n : f) {
                CharSequence t = n.getText();
                String tt = t == null ? "" : t.toString().toLowerCase(Locale.ROOT);
                if (tt.contains("skip") || tt.contains("छोड़ें")) {
                    if (n.isVisibleToUser() && clickNode(n)) return true;
                }
            }
        }
        return false;
    }

    /** Rough check that a video player is open (watch page) in the YouTube app. */
    public boolean videoPlayerOpen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !pkg.toString().contains("youtube")) return false;
        List<AccessibilityNodeInfo> p = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/player_view");
        if (p != null && !p.isEmpty()) return true;
        p = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/watch_player");
        if (p != null && !p.isEmpty()) return true;
        return descContains(root, "pause video", 0) || descContains(root, "play video", 0) || descContains(root, "video player", 0);
    }

    private static boolean descContains(AccessibilityNodeInfo n, String needle, int depth) {
        if (n == null || depth > 25) return false;
        CharSequence d = n.getContentDescription();
        if (d != null && d.toString().toLowerCase(Locale.ROOT).contains(needle)) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (descContains(n.getChild(i), needle, depth + 1)) return true;
        return false;
    }

    // ------------------------------------------------------------------ manager-mode helpers

    private static String str(CharSequence c) { return c == null ? "" : c.toString(); }

    private static String hint(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT >= 26) {
            try { return str(n.getHintText()); } catch (Throwable ignored) { }
        }
        return "";
    }

    private static String shortId(AccessibilityNodeInfo n) {
        String id = n.getViewIdResourceName();
        if (id == null) return "";
        int k = id.indexOf(":id/");
        return k >= 0 ? id.substring(k + 4) : id;
    }

    /** Label = text, else content description, else hint. */
    private static String label(AccessibilityNodeInfo n) {
        String t = str(n.getText()).trim();
        if (!t.isEmpty()) return t;
        String d = str(n.getContentDescription()).trim();
        if (!d.isEmpty()) return d;
        return hint(n).trim();
    }

    /** Active window first, then every other interactive window (popups, dialogs, keyboards), never Priya's own. */
    public List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        String me = getPackageName();
        AccessibilityNodeInfo a = getRootInActiveWindow();
        if (a != null && !me.equals(str(a.getPackageName()))) out.add(a);
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    if (me.equals(str(r.getPackageName()))) continue;
                    if (a != null && r.equals(a)) continue;
                    out.add(r);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    private boolean onScreen(Rect r) {
        return r.width() > 0 && r.height() > 0 && r.centerX() >= 0 && r.centerY() >= 0
                && r.centerX() <= screenW() && r.centerY() <= screenH();
    }

    private void collectMatches(AccessibilityNodeInfo n, String needle, List<AccessibilityNodeInfo> exact,
                                List<AccessibilityNodeInfo> partial, int depth) {
        if (n == null || depth > 45) return;
        if (n.isVisibleToUser()) {
            String t = str(n.getText()).trim().toLowerCase(Locale.ROOT);
            String d = str(n.getContentDescription()).trim().toLowerCase(Locale.ROOT);
            String h = hint(n).trim().toLowerCase(Locale.ROOT);
            String id = shortId(n).toLowerCase(Locale.ROOT);
            if (t.equals(needle) || d.equals(needle) || id.equals(needle)) exact.add(n);
            else if ((!t.isEmpty() && t.contains(needle)) || (!d.isEmpty() && d.contains(needle))
                    || (!h.isEmpty() && h.contains(needle))) partial.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) collectMatches(n.getChild(i), needle, exact, partial, depth + 1);
    }

    /** All visible nodes matching the text (exact matches first), across all windows. */
    public List<AccessibilityNodeInfo> findAll(String text) {
        List<AccessibilityNodeInfo> exact = new ArrayList<>(), partial = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return exact;
        String needle = text.trim().toLowerCase(Locale.ROOT);
        for (AccessibilityNodeInfo r : roots()) collectMatches(r, needle, exact, partial, 0);
        exact.addAll(partial);
        return exact;
    }

    public boolean textVisible(String text) {
        return !findAll(text).isEmpty();
    }

    /** Text of a node plus a few levels of its children (to check what a tap would really press). */
    private static void gatherText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 3 || sb.length() > 400) return;
        sb.append(str(n.getText())).append(' ').append(str(n.getContentDescription())).append(' ');
        for (int i = 0; i < n.getChildCount(); i++) gatherText(n.getChild(i), sb, depth + 1);
    }

    private static final String[] AD_WORDS = {"sponsored", "advertisement", "promoted", "विज्ञापन", "प्रायोजित"};

    /** Returns a reason string when tapping this node is not allowed (delete/remove/reset or an ad), else null. */
    static String unsafeReason(String label) {
        if (label == null) return null;
        if (ActionExecutor.forbidden(label)) return "FORBIDDEN";
        String low = label.trim().toLowerCase(Locale.ROOT);
        if (low.equals("ad") || low.startsWith("ad ·") || low.startsWith("ad •")) return "AD";
        for (String w : AD_WORDS) if (low.contains(w)) return "AD";
        return null;
    }

    private String checkTarget(AccessibilityNodeInfo hit) {
        StringBuilder sb = new StringBuilder();
        gatherText(hit, sb, 1); // the node itself and a little below
        AccessibilityNodeInfo c = clickableAncestor(hit);
        if (c != null && c != hit) { sb.append(' ').append(str(c.getText())).append(' ').append(str(c.getContentDescription())); }
        return unsafeReason(sb.toString());
    }

    /**
     * Tap (or long-press) the index-th element whose text / description / id matches.
     * Uses the accessibility click first and falls back to a real finger tap at the element's centre.
     * Returns "OK ..." / "NOT_FOUND" / "FORBIDDEN" / "AD" / "FAILED".
     */
    public String clickSmart(String text, int index, boolean longPress, boolean forceGesture) {
        List<AccessibilityNodeInfo> all = findAll(text);
        if (all.isEmpty()) return "NOT_FOUND";
        if (index < 0) index = 0;
        if (index >= all.size()) index = all.size() - 1;
        AccessibilityNodeInfo hit = all.get(index);
        String bad = checkTarget(hit);
        if (bad != null) return bad;
        Rect r = bounds(hit);
        String where = " @" + r.centerX() + "," + r.centerY() + " (" + all.size() + " match)";
        if (!forceGesture) {
            AccessibilityNodeInfo c = clickableAncestor(hit);
            if (longPress) {
                AccessibilityNodeInfo lc = hit;
                int guard = 0;
                while (lc != null && !lc.isLongClickable() && guard++ < 8) lc = lc.getParent();
                if (lc != null && lc.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "OK long-press '" + label(hit) + "'" + where;
            } else if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "OK tap '" + label(hit) + "'" + where;
            }
        }
        if (!onScreen(r)) return "FAILED";
        boolean ok = longPress ? longPress(r.centerX(), r.centerY(), 900) : tap(r.centerX(), r.centerY());
        return ok ? "OK finger-" + (longPress ? "long-press" : "tap") + " '" + label(hit) + "'" + where : "FAILED";
    }

    /** Deepest visible node at the point, across windows (used to refuse tapping a Delete / ad by position). */
    private AccessibilityNodeInfo nodeAt(AccessibilityNodeInfo n, int x, int y, int depth) {
        if (n == null || depth > 45 || !n.isVisibleToUser()) return null;
        Rect r = bounds(n);
        if (!r.contains(x, y)) return null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo c = nodeAt(n.getChild(i), x, y, depth + 1);
            if (c != null) return c;
        }
        return n;
    }

    /** Null when tapping at (x,y) is fine, else the reason (FORBIDDEN / AD) plus the label found there. */
    public String unsafeAt(int x, int y) {
        for (AccessibilityNodeInfo root : roots()) {
            AccessibilityNodeInfo hit = nodeAt(root, x, y, 0);
            if (hit == null) continue;
            String bad = checkTarget(hit);
            return bad == null ? null : bad + ": '" + label(hit) + "'";
        }
        return null;
    }

    public String labelAt(int x, int y) {
        for (AccessibilityNodeInfo root : roots()) {
            AccessibilityNodeInfo hit = nodeAt(root, x, y, 0);
            if (hit != null) {
                String l = label(hit);
                if (l.isEmpty()) { AccessibilityNodeInfo c = clickableAncestor(hit); if (c != null) l = label(c); }
                if (l.isEmpty()) l = shortId(hit);
                return l;
            }
        }
        return "";
    }

    /** Polls until the text shows up anywhere on screen or the timeout passes. */
    public boolean waitForText(String text, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            if (textVisible(text)) return true;
            if (SystemClock.uptimeMillis() > end) return false;
            SystemClock.sleep(450);
        }
    }

    /** Scrolls in the direction until the text is visible. Returns swipes used, or -1 if never found. */
    public int scrollUntil(String text, String direction, int maxSwipes) {
        for (int i = 0; i <= maxSwipes; i++) {
            if (textVisible(text)) return i;
            if (i == maxSwipes) break;
            boolean ok;
            switch (direction) {
                case "up":    ok = scroll(false); break;
                case "left":  ok = scrollHorizontal(true); break;
                case "right": ok = scrollHorizontal(false); break;
                default:      ok = scroll(true); break;
            }
            if (!ok) return -1;
            SystemClock.sleep(700);
        }
        return -1;
    }

    private static final String[] DISMISS = {
            "not now", "maybe later", "later", "no thanks", "no, thanks", "skip", "got it", "dismiss", "close",
            "ok, got it", "remind me later", "अभी नहीं", "बाद में", "छोड़ें", "समझ गया", "ठीक है", "बंद करें", "नहीं, धन्यवाद"};

    /** Taps one harmless dismiss button on a popup (never Allow/Buy/Install/Subscribe/Delete). Returns what it tapped or null. */
    public String dismissPopup() {
        for (String d : DISMISS) {
            for (AccessibilityNodeInfo n : findAll(d)) {
                String l = label(n).trim().toLowerCase(Locale.ROOT);
                if (l.length() > 24) continue;
                if (!(l.equals(d) || (d.length() >= 5 && l.startsWith(d)))) continue;
                if (checkTarget(n) != null) continue;
                AccessibilityNodeInfo c = clickableAncestor(n);
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return label(n);
                Rect r = bounds(n);
                if (onScreen(r) && tap(r.centerX(), r.centerY())) return label(n);
            }
        }
        return null;
    }

    /**
     * Screen as text for the model: one line per visible element with its centre point, e.g.
     * "[tap] New project @540,812". Elements without text show their view id.
     */
    public String dumpScreen(int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("SCREEN ").append(screenW()).append('x').append(screenH()).append(" | app: ").append(currentPackage).append('\n');
        boolean first = true;
        for (AccessibilityNodeInfo r : roots()) {
            if (!first) sb.append("--- popup/window: ").append(str(r.getPackageName())).append(" ---\n");
            first = false;
            dump(r, sb, maxChars, 0);
            if (sb.length() > maxChars) { sb.append("…(aur bhi hai, scroll karke dekho)\n"); break; }
        }
        return sb.toString();
    }

    private void dump(AccessibilityNodeInfo n, StringBuilder sb, int max, int depth) {
        if (n == null || sb.length() > max || depth > 45 || !n.isVisibleToUser()) return;
        String s = label(n);
        boolean click = n.isClickable() || n.isLongClickable();
        String id = shortId(n);
        if (!s.isEmpty() || (click && !id.isEmpty()) || n.isEditable()) {
            Rect r = bounds(n);
            if (n.isEditable()) sb.append("[input] ");
            else if (click) sb.append("[tap] ");
            if (n.isScrollable()) sb.append("[scroll] ");
            if (n.isCheckable()) sb.append(n.isChecked() ? "[on] " : "[off] ");
            if (n.isSelected()) sb.append("[selected] ");
            if (!s.isEmpty()) sb.append(s.length() > 100 ? s.substring(0, 100) : s.replace('\n', ' '));
            else sb.append("(id:").append(id).append(')');
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) dump(n.getChild(i), sb, max, depth + 1);
    }

    /** JPEG screenshot (Android 11+), scaled to maxWidth. Null when not possible. */
    public byte[] screenshotJpeg(int maxWidth) {
        if (Build.VERSION.SDK_INT < 30) return null;
        return Shot.take(this, maxWidth);
    }
}
