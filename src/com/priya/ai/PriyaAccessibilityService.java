package com.priya.ai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

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

    public boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs));
        return dispatchGesture(b.build(), null, null);
    }

    public boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
        return dispatchGesture(b.build(), null, null);
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || text == null || text.trim().isEmpty()) return false;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        if (found != null) {
            for (AccessibilityNodeInfo n : found) {
                if (n.isVisibleToUser() && clickNode(n)) return true;
            }
        }
        return clickByDescription(root, text.toLowerCase(Locale.ROOT));
    }

    private boolean clickByDescription(AccessibilityNodeInfo n, String needle) {
        if (n == null) return false;
        CharSequence d = n.getContentDescription();
        if (d != null && n.isVisibleToUser() && d.toString().toLowerCase(Locale.ROOT).contains(needle)) {
            if (clickNode(n)) return true;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            if (clickByDescription(n.getChild(i), needle)) return true;
        }
        return false;
    }

    public boolean typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null) target = findEditable(root);
        if (target == null) return false;
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
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

    public String dumpScreen(int maxChars) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        dump(root, sb, maxChars);
        return sb.toString();
    }

    private void dump(AccessibilityNodeInfo n, StringBuilder sb, int max) {
        if (n == null || sb.length() > max || !n.isVisibleToUser()) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String s = (t != null && t.length() > 0) ? t.toString() : (d != null ? d.toString() : "");
        if (!s.isEmpty()) {
            if (n.isClickable()) sb.append("[tap] ");
            sb.append(s.length() > 140 ? s.substring(0, 140) : s).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) dump(n.getChild(i), sb, max);
    }

    /** Taps the first video-looking result on a YouTube search screen. */
    public boolean clickFirstVideoResult() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo hit = findVideoNode(root);
        return hit != null && clickNode(hit);
    }

    private static final String[] VIDEO_HINTS = {"views", "view", "watching", "minutes", "seconds", "मिनट", "बार देखा", "व्यूज़"};

    private AccessibilityNodeInfo findVideoNode(AccessibilityNodeInfo n) {
        if (n == null) return null;
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 15 && n.isVisibleToUser()) {
            String low = d.toString().toLowerCase(Locale.ROOT);
            for (String h : VIDEO_HINTS) {
                if (low.contains(h) && !low.contains("shorts")) return n;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findVideoNode(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }
}
