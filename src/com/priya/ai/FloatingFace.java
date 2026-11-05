package com.priya.ai;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.InputStream;

/**
 * Small round, lip-syncing Priya face that floats over the home screen / other apps
 * while Priya is ON and her own app is not on screen. Drag to move, tap to open the app.
 */
public class FloatingFace extends View {

    /** Face circle in each avatar image: centre x, centre y, radius (in source pixels). */
    private static final float[][] FACE = {
            {390, 420, 235},   // Hologram
            {360, 335, 175},   // Cyber
            {383, 480, 235},   // Anime
    };

    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final Paint closedP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint openP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean attached = false;
    private int loadedTheme = -1;
    private float mouth = 0f;
    private final int size;
    /** The view actually in the window: the live 3D face, or this picture view (fallback). */
    private View shown;
    private android.opengl.GLSurfaceView gl;

    public FloatingFace(Context c) {
        super(c.getApplicationContext());
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        size = Ui.dp(c, 72);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = c.getResources().getDisplayMetrics().widthPixels - size - Ui.dp(c, 12);
        lp.y = Ui.dp(c, 140);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2.5f));
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            if (shown == gl && gl != null) ((AvatarGL) gl).setStyle(Prefs.theme(getContext()));
            else invalidate();
            h.postDelayed(this, 40);
        }
    };

    /** Called often from the service; shows or hides the face as needed. Main thread only. */
    public void update() {
        boolean want = PriyaBus.running() && !PriyaBus.appVisible && Prefs.floatingFace(getContext())
                && Settings.canDrawOverlays(getContext());
        if (want && !attached) show();
        else if (!want && attached) hide();
    }

    private void show() {
        boolean head = Maya3DHeadView.supported(getContext());
        if (Prefs.avatar3D(getContext()) && (head || LivePortraitView.supported(getContext()))) {
            try {
                if (gl == null) {
                    gl = head ? new Maya3DHeadView(getContext(), true) : new LivePortraitView(getContext(), true);
                    ((AvatarGL) gl).setOnGlFailed(this::fallbackToPicture);
                    gl.setOnTouchListener((v, e) -> handleTouch(e));
                }
                ((AvatarGL) gl).setStyle(Prefs.theme(getContext()));
                wm.addView(gl, lp);
                shown = gl;
                attached = true;
                h.post(frame);
                return;
            } catch (Throwable t) {
                if (gl instanceof Maya3DHeadView || (gl == null && head)) Maya3DHeadView.failed = true;
                else LivePortraitView.failed = true;
                gl = null;
            }
        }
        try {
            loadTheme(Prefs.theme(getContext()));
            wm.addView(this, lp);
            shown = this;
            attached = true;
            h.post(frame);
        } catch (Throwable ignored) { attached = false; shown = null; }
    }

    /** GL failed on this device: swap the 3D face for the picture face in place. */
    private void fallbackToPicture() {
        if (!attached || shown != gl) { gl = null; return; }
        hide();
        gl = null;
        update();
    }

    public void hide() {
        if (!attached) return;
        attached = false;
        h.removeCallbacks(frame);
        View v = shown == null ? this : shown;
        shown = null;
        try { wm.removeView(v); } catch (Throwable ignored) { }
    }

    private Bitmap load(String name) {
        try (InputStream is = getContext().getAssets().open(name)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            return BitmapFactory.decodeStream(is, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    private void loadTheme(int t) {
        if (t == loadedTheme) return;
        loadedTheme = t;
        Bitmap a = load("av_" + t + ".webp");
        Bitmap b = load("av_" + t + "_m.webp");
        closedP.setShader(shaderFor(a, t));
        openP.setShader(b == null ? null : shaderFor(b, t));
    }

    private Shader shaderFor(Bitmap bmp, int t) {
        if (bmp == null) return null;
        float[] f = FACE[Math.max(0, Math.min(2, t))];
        float sx = bmp.getWidth() / (float) (t == 0 ? 800 : t == 1 ? 720 : 760); // assets were sampled by 2
        float cx = f[0] * sx, cy = f[1] * sx, r = f[2] * sx;
        float scale = (size / 2f) / r;
        BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setTranslate(-cx, -cy);
        m.postScale(scale, scale);
        m.postTranslate(size / 2f, size / 2f);
        sh.setLocalMatrix(m);
        return sh;
    }

    @Override protected void onDraw(Canvas c) {
        loadTheme(Prefs.theme(getContext()));
        float target = PriyaBus.state == PriyaBus.SPEAKING ? Math.min(1f, PriyaBus.outLevel * 1.8f) : 0f;
        mouth += (target - mouth) * 0.55f;
        float r = size / 2f - Ui.dp(getContext(), 2);
        if (closedP.getShader() != null) c.drawCircle(size / 2f, size / 2f, r, closedP);
        if (openP.getShader() != null && mouth > 0.02f) {
            openP.setAlpha((int) (255 * Math.min(1f, mouth)));
            c.drawCircle(size / 2f, size / 2f, r, openP);
        }
        int col = PriyaBus.state == PriyaBus.SPEAKING ? 0xFFFF7AD9
                : PriyaBus.userTalking ? 0xFF4CE0A0 : 0xFF5CE1FF;
        ring.setColor(col);
        c.drawCircle(size / 2f, size / 2f, r, ring);
    }

    private float downX, downY;
    private int startX, startY;
    private boolean moved;

    @Override public boolean onTouchEvent(MotionEvent e) {
        return handleTouch(e);
    }

    private boolean handleTouch(MotionEvent e) {
        View target = shown == null ? this : shown;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY();
                startX = lp.x; startY = lp.y; moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = (int) (e.getRawX() - downX), dy = (int) (e.getRawY() - downY);
                if (Math.abs(dx) + Math.abs(dy) > Ui.dp(getContext(), 6)) moved = true;
                lp.x = startX + dx; lp.y = startY + dy;
                try { wm.updateViewLayout(target, lp); } catch (Throwable ignored) { }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) {
                    try {
                        getContext().startActivity(new Intent(getContext(), MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    } catch (Throwable ignored) { }
                }
                return true;
            default:
                return target == this && super.onTouchEvent(e);
        }
    }
}
