package com.priya.ai;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * "Living portrait" (2.5D, Live2D-style): Priya's own picture (assets/av_N.webp) drawn as a textured 40x40 GL mesh
 * and deformed every frame - real lip-sync (the matching open-mouth picture av_N_m.webp is warped in, squashed to
 * the current voice level), eye blinks (lid skin stretched down over the eye, lash line follows), head turn / tilt
 * parallax (face dome moves more than the head, the head more than the background), breathing and a little hair /
 * shoulder sway. The landmarks (eyes, mouth, face, head, neck) were measured on each picture and are hard-coded below.
 *
 * Two modes: main screen (the whole picture exactly where ThemeView would draw it, transparent around it, as a media
 * overlay) and the small round floating face (face circle on a dark disc + state ring, setZOrderOnTop).
 * If anything about GL fails the {@link FailListener} is told (main thread) so callers fall back to the pictures.
 */
public class LivePortraitView extends GLSurfaceView implements GLSurfaceView.Renderer {

    public interface FailListener { void onPortraitFailed(); }

    /** Set once GL failed on this device in this process: callers stop trying and use the plain pictures. */
    public static volatile boolean failed = false;

    public static boolean supported(Context c) {
        if (failed) return false;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x20000;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ per-picture landmarks (uv: x/W, y/H)
    // Measured on the original assets (desktop tool work/priya-portrait/params.py, preview frames checked by eye).
    // eyeL(cx,cy,rx,ry) eyeR(..) lid(band above eye that becomes the lid, lash offset above ellipse top, lash
    // thickness, lid tint toward cheek) mouth mask(cx,cy,rx,ry) mouth2(upper-lip line y, open-mouth height, closed/open
    // height ratio, has open picture) face(cx,cy,rx,ry) head(cx,cy,rx,ry) misc(neck y, hair sway, frame 0 rect/1 circle,
    // W/H) tilt(left eye, right eye: eye-line y change per unit dx, anime inner corners sit lower)
    static final float[][] LM = {
            // 0 Hologram (800x870)
            {0.3750f, 0.3805f, 0.0525f, 0.0172f,  0.5925f, 0.3805f, 0.0525f, 0.0172f,  0.0138f, 0.0034f, 0.0057f, 0.0000f,
             0.4813f, 0.6115f, 0.1437f, 0.0828f,  0.5782f, 0.0655f, 0.4400f, 1.0000f,  0.4813f, 0.4655f, 0.2313f, 0.2701f,
             0.4813f, 0.4540f, 0.2562f, 0.3103f,  0.7356f, 0.0000f, 0.0000f, 0.9195f,  0.0f, 0.0f},
            // 1 Cyber (720x720)
            {0.4278f, 0.3667f, 0.0389f, 0.0167f,  0.5792f, 0.3681f, 0.0403f, 0.0167f,  0.0139f, 0.0042f, 0.0069f, 0.0000f,
             0.5028f, 0.5153f, 0.0806f, 0.0444f,  0.4931f, 0.0583f, 0.6000f, 1.0000f,  0.4972f, 0.4417f, 0.1556f, 0.1944f,
             0.4958f, 0.3958f, 0.2292f, 0.2847f,  0.6111f, 0.0035f, 1.0000f, 1.0000f,  0.0f, 0.0f},
            // 2 Anime (760x973)
            {0.3658f, 0.4604f, 0.0684f, 0.0380f,  0.6408f, 0.4604f, 0.0697f, 0.0380f,  0.0103f, 0.0031f, 0.0164f, 0.5500f,
             0.5039f, 0.6197f, 0.1079f, 0.0452f,  0.5951f, 0.0627f, 0.3600f, 1.0000f,  0.5066f, 0.5190f, 0.2500f, 0.2107f,
             0.5066f, 0.4522f, 0.3750f, 0.3392f,  0.7246f, 0.0035f, 0.0000f, 0.7811f,  0.0123f, -0.0123f},
    };
    /** Face circle per picture for the floating face: centre x, centre y, radius (source pixels) + source width. */
    static final float[][] FACE = {{390, 420, 235, 800}, {360, 335, 175, 720}, {383, 480, 235, 760}};

    // ------------------------------------------------------------------ view

    private final boolean overlay;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FailListener failListener;
    private volatile int style;
    private volatile boolean firstFrame = false;
    private boolean reported = false;
    /** Main screen: where the full picture sits inside this view (px). Unset = fit centred. */
    private volatile float rl, rt, rr, rb;
    private volatile boolean hasRect = false;

    // pictures decoded off the GL thread
    private final Object bmpLock = new Object();
    private Bitmap pendClosed, pendOpen;
    private int pendStyle = -1, loadingStyle = -1;

    public LivePortraitView(Context c, boolean overlay) {
        super(c);
        this.overlay = overlay;
        style = clampStyle(Prefs.theme(c));
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new Chooser());
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        if (overlay) setZOrderOnTop(true); else setZOrderMediaOverlay(true);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setClickable(false);
        setFocusable(false);
    }

    public void setFailListener(FailListener l) { failListener = l; }

    private static int clampStyle(int s) { return Math.max(0, Math.min(2, s)); }

    /** Follow the avatar choice (0 Hologram, 1 Cyber, 2 Anime). */
    public void setStyle(int s) {
        s = clampStyle(s);
        if (s == style) return;
        style = s;
        decodeAsync(s);
    }

    /** Main screen: the full picture's rect in this view's pixels (it may hang outside the view; it is clipped). */
    public void setImageRect(float l, float t, float r, float b) {
        rl = l; rt = t; rr = r; rb = b;
        hasRect = r - l > 2 && b - t > 2;
    }

    private void decodeAsync(final int s) {
        synchronized (bmpLock) {
            if (loadingStyle == s) return;
            loadingStyle = s;
        }
        final Context app = getContext().getApplicationContext();
        Thread t = new Thread(() -> {
            Bitmap a = load(app, "av_" + s + ".webp"), b = load(app, "av_" + s + "_m.webp");
            synchronized (bmpLock) {
                if (loadingStyle != s) return;
                pendClosed = a; pendOpen = b; pendStyle = s;
            }
            if (a == null) fail("picture " + s);
        }, "portrait-decode");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private Bitmap load(Context c, String name) {
        try (InputStream is = c.getAssets().open(name)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            if (overlay) o.inSampleSize = 2;   // the small face would alias at full size (no NPOT mipmaps in ES 2)
            return BitmapFactory.decodeStream(is, null, o);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The main-screen view lets touches fall through to the ThemeView underneath. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        return overlay && super.onTouchEvent(e);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        armWatchdog();
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        setRenderMode(v == View.VISIBLE ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
    }

    @Override protected void onVisibilityChanged(View changed, int v) {
        super.onVisibilityChanged(changed, v);
        setRenderMode(v == View.VISIBLE && getWindowVisibility() == View.VISIBLE
                ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
    }

    /** No frame within a few seconds of being shown = treat GL as broken on this device. */
    private void armWatchdog() {
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 5000);
    }

    private final Runnable watchdog = () -> {
        if (!firstFrame && isShown() && getWindowVisibility() == View.VISIBLE) fail("no frame");
    };

    private void fail(final String why) {
        failed = true;
        try { Prefs.setLastCrash(getContext(), "Live avatar off: " + why); } catch (Throwable ignored) { }
        main.post(() -> {
            if (reported) return;
            reported = true;
            if (failListener != null) failListener.onPortraitFailed();
        });
    }

    private final class Chooser implements GLSurfaceView.EGLConfigChooser {
        @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay d) {
            int[] spec = {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 0, EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */, EGL10.EGL_NONE};
            int[] n = new int[1];
            if (!egl.eglChooseConfig(d, spec, null, 0, n) || n[0] <= 0) {
                fail("no RGBA8888 config");
                throw new IllegalArgumentException("no EGL config");
            }
            EGLConfig[] cs = new EGLConfig[n[0]];
            egl.eglChooseConfig(d, spec, cs, n[0], n);
            int[] v = new int[1];
            for (EGLConfig c : cs) {   // prefer an exact 8888 match
                int r = attr(egl, d, c, EGL10.EGL_RED_SIZE, v), g = attr(egl, d, c, EGL10.EGL_GREEN_SIZE, v);
                int b = attr(egl, d, c, EGL10.EGL_BLUE_SIZE, v), a = attr(egl, d, c, EGL10.EGL_ALPHA_SIZE, v);
                if (r == 8 && g == 8 && b == 8 && a == 8) return c;
            }
            return cs[0];
        }

        private int attr(EGL10 egl, EGLDisplay d, EGLConfig c, int a, int[] v) {
            return egl.eglGetConfigAttrib(d, c, a, v) ? v[0] : 0;
        }
    }

    // ------------------------------------------------------------------ shaders (desktop twin: work/priya-portrait)

    static final String VS =
            "attribute vec2 aUV;\n" +
            "uniform vec4 uRect;\n" +      // NDC left, top, right, bottom of the full picture
            "uniform vec4 uFace;\n" +
            "uniform vec4 uHead;\n" +
            "uniform vec4 uMisc;\n" +      // neck y, hair sway, frame type, W/H
            "uniform vec4 uMotion;\n" +    // yaw, pitch, roll, breath
            "uniform float uTime;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vec2 p = aUV;\n" +
            "  vec2 c = p - 0.5;\n" +
            "  float pin = uMisc.z < 0.5 ? 1.0 - smoothstep(0.38, 0.48, max(abs(c.x), abs(c.y)))\n" +
            "                            : 1.0 - smoothstep(0.36, 0.46, length(c));\n" +
            "  vec2 h = (p - uHead.xy) / uHead.zw;\n" +
            "  float wHead = 1.0 - smoothstep(0.75, 1.25, length(h));\n" +
            "  vec2 f = (p - uFace.xy) / uFace.zw;\n" +
            "  float rf2 = dot(f, f);\n" +
            "  float dome = max(0.0, 1.0 - rf2); dome = dome * dome;\n" +
            "  float k = 0.45 * wHead + 0.55 * dome;\n" +
            "  vec2 d = uMotion.xy * k;\n" +
            "  float r = uMotion.z * wHead;\n" +
            "  vec2 q = vec2(p.x - uFace.x, p.y - uMisc.x);\n" +
            "  d += vec2(-q.y * r / uMisc.w, q.x * r * uMisc.w);\n" +
            "  float body = smoothstep(uMisc.x - 0.05, uMisc.x + 0.15, p.y);\n" +
            "  d.y -= uMotion.w * (0.6 * body + 0.4 * wHead);\n" +
            "  float hair = wHead * smoothstep(0.9, 1.4, sqrt(rf2));\n" +
            "  float top = uHead.y - uHead.w;\n" +
            "  d.x += uMisc.y * hair * sin(uTime * 1.4 + p.y * 6.0) * clamp((p.y - top) / (2.0 * uHead.w), 0.0, 1.5);\n" +
            "  p += d * pin;\n" +
            "  gl_Position = vec4(mix(uRect.x, uRect.z, p.x), mix(uRect.y, uRect.w, p.y), 0.0, 1.0);\n" +
            "  vUV = aUV;\n" +
            "}\n";

    static final String FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D uTex;\n" +
            "uniform sampler2D uTexO;\n" +
            "uniform vec4 uEyeL;\n" +
            "uniform vec4 uEyeR;\n" +
            "uniform vec4 uLid;\n" +       // band, lash offset, lash thickness, lid tint
            "uniform vec2 uTilt;\n" +      // eye-line tilt (uv y per unit dx) left eye, right eye
            "uniform vec4 uMouth;\n" +
            "uniform vec4 uMouth2;\n" +    // upper-lip y, open height, squash, has open picture
            "uniform float uOpen;\n" +
            "uniform float uBlink;\n" +
            "uniform float uAlpha;\n" +
            "uniform vec3 uCircle;\n" +    // floating face clip (px), z = 0: none
            "varying vec2 vUV;\n" +
            "void eyeLid(vec2 uv, vec4 e0, float tilt, inout float sv, inout vec4 lid, inout float lidOn, inout vec4 lashC, inout float g) {\n" +
            "  float dx = (uv.x - e0.x) / e0.z;\n" +
            "  if (abs(dx) >= 1.0) return;\n" +
            "  vec4 e = vec4(e0.x, e0.y + tilt * dx, e0.z, e0.w);\n" +
            "  float hw = sqrt(max(0.0, 1.0 - dx * dx));\n" +
            "  float top = e.y - e.w * hw - uLid.y * hw;\n" +
            "  float bot = e.y + e.w * hw;\n" +
            "  float bl = uBlink * smoothstep(0.0, 0.35, hw);\n" +   // corners close a little less: no hard edge
            "  float L = top + (bot - top) * bl * 0.96;\n" +
            "  float lt = uLid.z * 0.45 * (0.5 + 0.5 * hw);\n" +
            "  float dd = (uv.y - L - lt * 0.3) / max(lt, 1e-5);\n" +
            "  float gg = exp(-dd * dd) * bl * bl;\n" +
            "  if (gg > g) { g = gg; lashC = texture2D(uTex, vec2(uv.x, top + uLid.z * 0.5 * hw)); }\n" +
            "  float y0 = top - uLid.x * (0.4 + 0.6 * hw);\n" +
            "  if (uv.y < y0 || uv.y > bot) return;\n" +
            "  if (uv.y >= L) {\n" +
            "    sv = top + (uv.y - L) * (bot - top) / max(bot - L, 1e-5);\n" +
            "  } else {\n" +
            "    float us = e.x + (uv.x - e.x) * 0.45;\n" +
            "    float hs = sqrt(max(0.0, 1.0 - dx * dx * 0.2025));\n" +
            "    float tops = e0.y + tilt * dx * 0.45 - e.w * hs - uLid.y * hs;\n" +
            "    float y0s = tops - uLid.x * (0.4 + 0.6 * hs);\n" +
            "    float sl = y0s + (uv.y - y0) * (tops - y0s) / max(L - y0, 1e-5);\n" +
            "    float du = e.z * 0.10;\n" +
            "    vec4 c = (texture2D(uTex, vec2(us - du, sl)) + 2.0 * texture2D(uTex, vec2(us, sl))\n" +
            "            + texture2D(uTex, vec2(us + du, sl))) * 0.25;\n" +
            "    float cy2 = e0.y + e.w * 1.5;\n" +
            "    vec4 ref = (texture2D(uTex, vec2(e.x - e.z * 0.3, cy2)) + texture2D(uTex, vec2(e.x + e.z * 0.3, cy2))) * 0.5;\n" +
            "    c = mix(c, ref, uLid.w);\n" +
            "    vec4 st = texture2D(uTex, vec2(uv.x, y0 + (uv.y - y0) * (top - y0) / max(L - y0, 1e-5)));\n" +
            "    float f = smoothstep(y0, y0 + (L - y0) * 0.6 + 1e-5, uv.y);\n" +   // seamless with the skin above
            "    lid = mix(st, c, f);\n" +
            "    lidOn = 1.0;\n" +
            "  }\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 uv = vUV;\n" +
            "  float sv = uv.y;\n" +
            "  vec4 lid = vec4(0.0); float lidOn = 0.0; vec4 lashC = vec4(0.0); float g = 0.0;\n" +
            "  if (uBlink > 0.001) {\n" +
            "    eyeLid(uv, uEyeL, uTilt.x, sv, lid, lidOn, lashC, g);\n" +
            "    eyeLid(uv, uEyeR, uTilt.y, sv, lid, lidOn, lashC, g);\n" +
            "  }\n" +
            "  vec4 col = lidOn > 0.5 ? lid : texture2D(uTex, vec2(uv.x, sv));\n" +
            "  col = mix(col, lashC, clamp(g * 0.85, 0.0, 1.0));\n" +
            "  vec2 m = (uv - uMouth.xy) / uMouth.zw;\n" +
            "  float w = 1.0 - smoothstep(0.55, 1.0, length(m));\n" +
            "  if (uOpen > 0.001 && w > 0.0) {\n" +
            "    if (uMouth2.w > 0.5) {\n" +
            "      float s = uMouth2.z + (1.0 - uMouth2.z) * uOpen;\n" +
            "      float d = uv.y - uMouth2.x;\n" +
            "      float oh = uMouth2.y;\n" +
            "      float ov = d <= 0.0 ? uv.y : (d < oh * s ? uMouth2.x + d / s : uv.y + oh * (1.0 - s));\n" +
            "      ov = uv.y + (ov - uv.y) * w;\n" +
            "      col = mix(col, texture2D(uTexO, vec2(uv.x, ov)), w * smoothstep(0.0, 0.3, uOpen));\n" +
            "    } else {\n" +      // no open-mouth picture: dark inner mouth between the lips
            "      float gap = uMouth2.y * 0.6 * uOpen;\n" +
            "      vec2 im = vec2((uv.x - uMouth.x) / (uMouth.z * 0.55),\n" +
            "                     (uv.y - (uMouth2.x + uMouth2.y * 0.3)) / max(gap * 0.5, 1e-4));\n" +
            "      float inner = (1.0 - smoothstep(0.8, 1.0, length(im))) * smoothstep(0.0, 0.15, uOpen);\n" +
            "      col = mix(col, vec4(0.16, 0.04, 0.06, 1.0) * col.a, inner);\n" +
            "    }\n" +
            "  }\n" +
            "  col *= uAlpha;\n" +
            "  if (uCircle.z > 0.0) {\n" +
            "    float cd = length(gl_FragCoord.xy - uCircle.xy);\n" +
            "    col *= 1.0 - smoothstep(uCircle.z - 1.5, uCircle.z, cd);\n" +
            "  }\n" +
            "  gl_FragColor = col;\n" +
            "}\n";

    static final String BG_VS =
            "attribute vec2 aP;\nvoid main() { gl_Position = vec4(aP, 0.0, 1.0); }\n";
    static final String BG_FS =
            "precision mediump float;\n" +
            "uniform vec3 uCircle;\n" +
            "uniform vec3 uFill;\n" +
            "uniform vec3 uRing;\n" +
            "uniform float uRingW;\n" +
            "void main() {\n" +
            "  float d = length(gl_FragCoord.xy - uCircle.xy);\n" +
            "  float a = 1.0 - smoothstep(uCircle.z - 1.5, uCircle.z, d);\n" +
            "  float ring = smoothstep(uCircle.z - uRingW - 1.5, uCircle.z - uRingW, d);\n" +
            "  vec3 c = mix(uFill, uRing, ring);\n" +
            "  gl_FragColor = vec4(c * a, a);\n" +
            "}\n";

    static final int GRID = 40;

    /** 41x41 vertices (uv) + triangle indices. */
    static float[] gridVerts() {
        int n = GRID + 1;
        float[] v = new float[n * n * 2];
        int k = 0;
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) { v[k++] = x / (float) GRID; v[k++] = y / (float) GRID; }
        return v;
    }

    static short[] gridIdx() {
        int n = GRID + 1;
        short[] idx = new short[GRID * GRID * 6];
        int k = 0;
        for (int y = 0; y < GRID; y++)
            for (int x = 0; x < GRID; x++) {
                int a = y * n + x, b = a + 1, c = a + n, d = c + 1;
                idx[k++] = (short) a; idx[k++] = (short) c; idx[k++] = (short) b;
                idx[k++] = (short) b; idx[k++] = (short) c; idx[k++] = (short) d;
            }
        return idx;
    }

    // ------------------------------------------------------------------ GL state

    private int prog, bgProg, aUV, bgAP;
    private int uRect, uFace, uHead, uMisc, uMotion, uTime, uTex, uTexO, uEyeL, uEyeR, uLid, uMouth, uMouth2,
            uOpen, uBlink, uAlpha, uCircle, uTilt;
    private int bgCircle, bgFill, bgRing, bgRingW;
    private int vbo, ibo;
    private final int[] tex = new int[2];
    private int texStyle = -1;
    private boolean hasOpenTex = false;
    private float texAspect = 1f;   // W/H of the loaded picture
    private FloatBuffer quad;
    private int vw = 1, vh = 1;
    private long t0 = SystemClock.uptimeMillis(), lastDraw = 0, lastFrame = 0;

    // animation
    private float open = 0, blink = 0, gx = 0, gy = 0, gtx = 0, gty = 0;
    private long nextBlink = 0, blinkStart = -1, nextGaze = 0;
    private boolean doubleBlink = false;
    private final java.util.Random rnd = new java.util.Random();

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            prog = link(VS, FS);
            bgProg = link(BG_VS, BG_FS);
            if (prog == 0 || bgProg == 0) { fail("shader"); return; }
            aUV = GLES20.glGetAttribLocation(prog, "aUV");
            uRect = u("uRect"); uFace = u("uFace"); uHead = u("uHead"); uMisc = u("uMisc"); uMotion = u("uMotion");
            uTime = u("uTime"); uTex = u("uTex"); uTexO = u("uTexO"); uEyeL = u("uEyeL"); uEyeR = u("uEyeR");
            uLid = u("uLid"); uMouth = u("uMouth"); uMouth2 = u("uMouth2"); uOpen = u("uOpen"); uBlink = u("uBlink");
            uAlpha = u("uAlpha"); uCircle = u("uCircle"); uTilt = u("uTilt");
            bgAP = GLES20.glGetAttribLocation(bgProg, "aP");
            bgCircle = GLES20.glGetUniformLocation(bgProg, "uCircle");
            bgFill = GLES20.glGetUniformLocation(bgProg, "uFill");
            bgRing = GLES20.glGetUniformLocation(bgProg, "uRing");
            bgRingW = GLES20.glGetUniformLocation(bgProg, "uRingW");
            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);

            float[] gv = gridVerts();
            short[] gi = gridIdx();
            int[] b = new int[2];
            GLES20.glGenBuffers(2, b, 0);
            vbo = b[0]; ibo = b[1];
            FloatBuffer fb = ByteBuffer.allocateDirect(gv.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(gv).position(0);
            ShortBuffer sb = ByteBuffer.allocateDirect(gi.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(gi).position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, gv.length * 4, fb, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, gi.length * 2, sb, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

            GLES20.glGenTextures(2, tex, 0);
            texStyle = -1;      // context (re)created: textures must be uploaded again
            synchronized (bmpLock) { loadingStyle = -1; }
            decodeAsync(style);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
        } catch (Throwable t) {
            fail("init " + t.getClass().getSimpleName());
        }
    }

    private int u(String n) { return GLES20.glGetUniformLocation(prog, n); }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) { GLES20.glDeleteShader(s); return 0; }
        return s;
    }

    private static int link(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) { GLES20.glDeleteProgram(p); return 0; }
        return p;
    }

    @Override public void onSurfaceChanged(GL10 unused, int w, int h) {
        vw = Math.max(1, w); vh = Math.max(1, h);
        GLES20.glViewport(0, 0, vw, vh);
    }

    private static void upload(int id, Bitmap b) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);   // premultiplied ARGB -> blend ONE, ONE_MINUS_SRC_ALPHA
    }

    /** Uploads freshly decoded pictures (GL thread). */
    private void takePictures() {
        Bitmap a, b;
        int s;
        synchronized (bmpLock) {
            if (pendStyle < 0) return;
            a = pendClosed; b = pendOpen; s = pendStyle;
            pendClosed = null; pendOpen = null; pendStyle = -1;
        }
        if (a == null) return;
        upload(tex[0], a);
        hasOpenTex = b != null && b.getWidth() == a.getWidth() && b.getHeight() == a.getHeight();
        if (hasOpenTex) upload(tex[1], b);
        texAspect = a.getWidth() / (float) a.getHeight();
        texStyle = s;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        a.recycle();
        if (b != null) b.recycle();
    }

    @Override public void onDrawFrame(GL10 unused) {
        long now = SystemClock.uptimeMillis();
        long wait = 33 - (now - lastDraw);   // ~30 fps cap
        if (lastDraw > 0 && wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
            now = SystemClock.uptimeMillis();
        }
        lastDraw = now;
        try {
            drawFrame(now);
            firstFrame = true;
        } catch (Throwable t) {
            fail("draw " + t.getClass().getSimpleName());
        }
    }

    private static final float[][] BG_FILL = {{0.02f, 0.07f, 0.14f}, {0.10f, 0.11f, 0.14f}, {0.12f, 0.13f, 0.24f}};

    private void drawFrame(long now) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (prog == 0) return;
        takePictures();
        float dt = lastFrame == 0 ? 0.033f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float t = (now - t0) / 1000f;
        animate(now, dt);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        float ringW = Math.max(2f, vw * 0.035f);
        if (overlay) drawBackdrop(ringW);
        int st = texStyle;
        if (st < 0) return;          // picture still decoding
        float[] lm = LM[st];

        // where the picture goes, in NDC
        float l, tp, r, b;
        if (overlay) {
            float[] f = FACE[st];
            float rIn = Math.min(vw, vh) / 2f - ringW;
            float sc = rIn / f[2];                       // source px -> view px
            float srcW = f[3], srcH = f[3] / lm[31];
            float pl = vw / 2f - f[0] * sc, pt = vh / 2f - f[1] * sc;
            l = pl; tp = pt; r = pl + srcW * sc; b = pt + srcH * sc;
        } else if (hasRect) {
            l = rl; tp = rt; r = rr; b = rb;
        } else {                                          // fit centred
            float ar = texAspect, w = vw, h = vw / ar;
            if (h > vh) { h = vh; w = vh * ar; }
            l = (vw - w) / 2f; tp = (vh - h) / 2f; r = l + w; b = tp + h;
        }

        GLES20.glUseProgram(prog);
        GLES20.glUniform4f(uRect, l / vw * 2f - 1f, 1f - tp / vh * 2f, r / vw * 2f - 1f, 1f - b / vh * 2f);
        GLES20.glUniform4f(uEyeL, lm[0], lm[1], lm[2], lm[3]);
        GLES20.glUniform4f(uEyeR, lm[4], lm[5], lm[6], lm[7]);
        GLES20.glUniform4f(uLid, lm[8], lm[9], lm[10], lm[11]);
        GLES20.glUniform4f(uMouth, lm[12], lm[13], lm[14], lm[15]);
        GLES20.glUniform4f(uMouth2, lm[16], lm[17], lm[18], hasOpenTex ? 1f : 0f);
        GLES20.glUniform4f(uFace, lm[20], lm[21], lm[22], lm[23]);
        GLES20.glUniform4f(uHead, lm[24], lm[25], lm[26], lm[27]);
        GLES20.glUniform4f(uMisc, lm[28], lm[29], lm[30], lm[31]);
        GLES20.glUniform2f(uTilt, lm[32], lm[33]);

        boolean speaking = PriyaBus.state == PriyaBus.SPEAKING;
        float yaw = 0.0070f * (float) Math.sin(t * 0.37) + 0.0035f * (float) Math.sin(t * 0.93 + 1.3) + gx;
        float pitch = 0.0035f * (float) Math.sin(t * 0.29 + 0.7) + gy
                + (speaking ? 0.0025f * open * (float) Math.sin(t * 6.5) : 0f);
        float roll = 0.014f * (float) Math.sin(t * 0.23 + 2.1);
        float breath = 0.0035f * (0.5f + 0.5f * (float) Math.sin(t * 1.5));
        GLES20.glUniform4f(uMotion, yaw, pitch, roll, breath);
        GLES20.glUniform1f(uTime, t % 6283.1853f);
        GLES20.glUniform1f(uOpen, open);
        GLES20.glUniform1f(uBlink, blink);
        GLES20.glUniform1f(uAlpha, overlay || PriyaBus.running() ? 1f : 0.85f);
        if (overlay) GLES20.glUniform3f(uCircle, vw / 2f, vh / 2f, Math.min(vw, vh) / 2f - ringW);
        else GLES20.glUniform3f(uCircle, 0, 0, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hasOpenTex ? tex[1] : tex[0]);
        GLES20.glUniform1i(uTexO, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 8, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, GRID * GRID * 6, GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glDisableVertexAttribArray(aUV);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawBackdrop(float ringW) {
        if (bgProg == 0) return;
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(bgProg);
        float r = Math.min(vw, vh) / 2f - 1f;
        GLES20.glUniform3f(bgCircle, vw / 2f, vh / 2f, r);
        float[] f = BG_FILL[clampStyle(style)];
        GLES20.glUniform3f(bgFill, f[0], f[1], f[2]);
        int col = PriyaBus.state == PriyaBus.SPEAKING ? 0xFFFF7AD9 : PriyaBus.userTalking ? 0xFF4CE0A0 : 0xFF5CE1FF;
        GLES20.glUniform3f(bgRing, ((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f);
        GLES20.glUniform1f(bgRingW, ringW);
        GLES20.glEnableVertexAttribArray(bgAP);
        GLES20.glVertexAttribPointer(bgAP, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(bgAP);
    }

    private void animate(long now, float dt) {
        // lip-sync from Priya's live output level (published at the playhead): opens ~25 ms, closes ~80 ms
        boolean speaking = PriyaBus.state == PriyaBus.SPEAKING;
        float lvl = speaking ? PriyaBus.outLevel : 0f;
        float target = lvl < 0.02f ? 0f : Math.min(1f, (lvl - 0.02f) * 1.9f);
        float tau = target > open ? 0.025f : 0.080f;
        open += (target - open) * Math.min(1f, dt / tau);
        if (open < 0.003f) open = 0f;

        // blink every 3-6 s (sometimes twice)
        if (nextBlink == 0) nextBlink = now + 1500 + rnd.nextInt(2500);
        if (blinkStart < 0 && now >= nextBlink) {
            blinkStart = now;
            doubleBlink = rnd.nextFloat() < 0.15f;
        }
        if (blinkStart >= 0) {
            float bt = (now - blinkStart) / 1000f;
            float dur = 0.17f;
            if (bt < dur) blink = 1f - Math.abs(bt / dur * 2f - 1f);
            else if (doubleBlink && bt < dur * 2.3f) {
                float b2 = (bt - dur * 1.3f) / dur;
                blink = b2 < 0 ? 0f : 1f - Math.abs(b2 * 2f - 1f);
            } else {
                blink = 0f;
                blinkStart = -1;
                nextBlink = now + 3000 + rnd.nextInt(3000);
            }
            blink = Math.max(0f, Math.min(1f, blink * 1.15f));   // a short full closure at the middle
        }

        // small head turns toward a new point every 1.5-4 s (stiller while talking)
        if (now >= nextGaze) {
            float range = speaking ? 0.002f : 0.0045f;
            gtx = (rnd.nextFloat() * 2 - 1) * range;
            gty = (rnd.nextFloat() * 2 - 1) * range * 0.6f;
            nextGaze = now + 1500 + rnd.nextInt(2500);
        }
        gx += (gtx - gx) * Math.min(1f, dt / 0.6f);
        gy += (gty - gy) * Math.min(1f, dt / 0.6f);
    }
}
