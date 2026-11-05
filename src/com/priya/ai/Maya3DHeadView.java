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
 * Maya's real-3D head (GLES 2.0). Each avatar picture becomes a sculpted 3D mesh (see {@link Maya3DModel}): she
 * turns and tilts her head with true perspective parallax and occlusion, her jaw hinges open in 3D over a mouth
 * cavity with teeth and tongue, lip shapes follow her voice's spectrum (A / O-U / E-I / M-B-P via {@link VoiceBands}
 * and {@link Maya3DAnim}), eyes saccade and blink with moving lids, brows lift on emphasis, she breathes and nods.
 * Behind her a per-theme shader layer animates (hologram rings + dots / cyber constellation / anime bokeh, sparkles
 * and voice waves), calm when idle, following her voice level while she speaks.
 *
 * Two modes like {@link LivePortraitView}: main screen (the whole picture where ThemeView draws it, transparent around
 * it) and the small round floating face. ~30 fps, stops drawing when hidden. If GL fails, {@link #setOnGlFailed} is
 * told so callers fall back (older live portrait, then the plain pictures).
 */
public class Maya3DHeadView extends GLSurfaceView implements GLSurfaceView.Renderer, AvatarGL {

    /** Set once GL failed on this device in this process: callers stop trying this view. */
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

    private final boolean overlay;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable failListener;
    private volatile int style;
    private volatile boolean firstFrame = false;
    private boolean reported = false;
    private volatile float rl, rt, rr, rb;
    private volatile boolean hasRect = false;

    // decoded off the GL thread
    private final Object lock = new Object();
    private Bitmap pendPic, pendGeo;
    private Maya3DModel.Mesh pendMesh;
    private int pendStyle = -1, loadingStyle = -1;

    public Maya3DHeadView(Context c, boolean overlay) {
        super(c);
        this.overlay = overlay;
        style = Maya3DModel.clampStyle(Prefs.theme(c));
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

    @Override public void setOnGlFailed(Runnable r) { failListener = r; }

    @Override public void setStyle(int s) {
        s = Maya3DModel.clampStyle(s);
        if (s == style) return;
        style = s;
        decodeAsync(s);
    }

    @Override public void setImageRect(float l, float t, float r, float b) {
        rl = l; rt = t; rr = r; rb = b;
        hasRect = r - l > 2 && b - t > 2;
    }

    private void decodeAsync(final int s) {
        synchronized (lock) {
            if (loadingStyle == s) return;
            loadingStyle = s;
        }
        final Context app = getContext().getApplicationContext();
        Thread t = new Thread(() -> {
            Bitmap pic = load(app, "av_" + s + ".webp", overlay ? 2 : 1);
            Bitmap geo = load(app, "av_" + s + "_geo.webp", 1);
            Maya3DModel.Mesh m = null;
            if (pic != null && geo != null) {
                try {
                    int gw = geo.getWidth(), gh = geo.getHeight();
                    int[] px = new int[gw * gh];
                    geo.getPixels(px, 0, gw, 0, 0, gw, gh);
                    m = Maya3DModel.build(s, px, gw, gh);
                } catch (Throwable e) {
                    m = null;
                }
            }
            synchronized (lock) {
                if (loadingStyle != s) return;
                pendPic = pic; pendGeo = geo; pendMesh = m; pendStyle = s;
            }
            if (m == null) fail("model " + s);
        }, "maya3d-load");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static Bitmap load(Context c, String name, int sample) {
        try (InputStream is = c.getAssets().open(name)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeStream(is, null, o);
        } catch (Throwable e) {
            return null;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        return overlay && super.onTouchEvent(e);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 6000);
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

    private final Runnable watchdog = () -> {
        if (!firstFrame && isShown() && getWindowVisibility() == View.VISIBLE) fail("no frame");
    };

    private void fail(final String why) {
        failed = true;
        try { Prefs.setLastCrash(getContext(), "3D head off: " + why); } catch (Throwable ignored) { }
        main.post(() -> {
            if (reported) return;
            reported = true;
            if (failListener != null) failListener.run();
        });
    }

    private final class Chooser implements GLSurfaceView.EGLConfigChooser {
        @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay d) {
            EGLConfig c = pick(egl, d, 16);
            if (c == null) c = pick(egl, d, 24);
            if (c == null) {
                fail("no RGBA8888+depth config");
                throw new IllegalArgumentException("no EGL config");
            }
            return c;
        }

        private EGLConfig pick(EGL10 egl, EGLDisplay d, int depth) {
            int[] spec = {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */, EGL10.EGL_NONE};
            int[] n = new int[1];
            if (!egl.eglChooseConfig(d, spec, null, 0, n) || n[0] <= 0) return null;
            EGLConfig[] cs = new EGLConfig[n[0]];
            egl.eglChooseConfig(d, spec, cs, n[0], n);
            int[] v = new int[1];
            for (EGLConfig c : cs) {
                int r = attr(egl, d, c, EGL10.EGL_RED_SIZE, v), g = attr(egl, d, c, EGL10.EGL_GREEN_SIZE, v);
                int b = attr(egl, d, c, EGL10.EGL_BLUE_SIZE, v), a = attr(egl, d, c, EGL10.EGL_ALPHA_SIZE, v);
                if (r == 8 && g == 8 && b == 8 && a == 8 && attr(egl, d, c, EGL10.EGL_DEPTH_SIZE, v) >= 16) return c;
            }
            return cs[0];
        }

        private int attr(EGL10 egl, EGLDisplay d, EGLConfig c, int a, int[] v) {
            return egl.eglGetConfigAttrib(d, c, a, v) ? v[0] : 0;
        }
    }

    // ------------------------------------------------------------------ GL programs

    /** One linked program with every uniform location the frame needs. */
    private static final class Prog {
        int id;
        int uPic, uPicF, uRect, uRot, uPivot, uMouth, uMouth2, uMouthZ, uJaw, uBrowL, uBrowR, uAnim, uAnim2, uAnim3,
                uEyeL, uEyeL2, uEyeR, uEyeR2, uIris, uLid, uEyeA, uTeeth, uStyleF, uFxP, uFxC, uCircle, uAlpha,
                uTex, uGeo, uFill, uRing, uRingW;
        int aUV, aG, aN, aC, aP;

        Prog(String vs, String fs) {
            id = link(vs, fs);
            if (id == 0) return;
            uPic = u("uPic"); uPicF = u("uPicF"); uRect = u("uRect"); uRot = u("uRot"); uPivot = u("uPivot");
            uMouth = u("uMouth"); uMouth2 = u("uMouth2"); uMouthZ = u("uMouthZ"); uJaw = u("uJaw");
            uBrowL = u("uBrowL"); uBrowR = u("uBrowR"); uAnim = u("uAnim"); uAnim2 = u("uAnim2"); uAnim3 = u("uAnim3");
            uEyeL = u("uEyeL"); uEyeL2 = u("uEyeL2"); uEyeR = u("uEyeR"); uEyeR2 = u("uEyeR2"); uIris = u("uIris");
            uLid = u("uLid"); uEyeA = u("uEyeA"); uTeeth = u("uTeeth"); uStyleF = u("uStyleF"); uFxP = u("uFxP");
            uFxC = u("uFxC"); uCircle = u("uCircle"); uAlpha = u("uAlpha"); uTex = u("uTex"); uGeo = u("uGeo");
            uFill = u("uFill"); uRing = u("uRing"); uRingW = u("uRingW");
            aUV = GLES20.glGetAttribLocation(id, "aUV"); aG = GLES20.glGetAttribLocation(id, "aG");
            aN = GLES20.glGetAttribLocation(id, "aN"); aC = GLES20.glGetAttribLocation(id, "aC");
            aP = GLES20.glGetAttribLocation(id, "aP");
        }

        private int u(String n) { return GLES20.glGetUniformLocation(id, n); }

        private static void v4(int loc, float[] v) { if (loc >= 0) GLES20.glUniform4fv(loc, 1, v, 0); }

        void set(Maya3DModel.Uniforms U, float[] circle) {
            v4(uPic, U.pic); v4(uPicF, U.picF); v4(uRect, U.rect);
            if (uRot >= 0) GLES20.glUniformMatrix3fv(uRot, 1, false, U.rot, 0);
            v4(uPivot, U.pivot); v4(uMouth, U.mouth); v4(uMouth2, U.mouth2); v4(uMouthZ, U.mouthZ); v4(uJaw, U.jaw);
            v4(uBrowL, U.browL); v4(uBrowR, U.browR); v4(uAnim, U.anim); v4(uAnim2, U.anim2); v4(uAnim3, U.anim3);
            v4(uEyeL, U.eyeL); v4(uEyeL2, U.eyeL2); v4(uEyeR, U.eyeR); v4(uEyeR2, U.eyeR2); v4(uIris, U.iris);
            v4(uLid, U.lid); v4(uEyeA, U.eyeA); v4(uTeeth, U.teeth); v4(uStyleF, U.styleF); v4(uFxP, U.fxP);
            v4(uFxC, U.fxC);
            if (uAlpha >= 0) GLES20.glUniform1f(uAlpha, U.alpha);
            if (uCircle >= 0) GLES20.glUniform3f(uCircle, circle[0], circle[1], circle[2]);
        }
    }

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

    // ------------------------------------------------------------------ GL state

    private Prog face, cav, fx, bg;
    private final int[] tex = new int[2];
    private final int[] bufs = new int[4];   // face vbo, face ibo, cavity vbo, cavity ibo
    private int faceCount = 0, cavCount = 0, texStyle = -1;
    private Maya3DModel.Mesh mesh;
    private float texAspect = 1f;
    private FloatBuffer quad01, quadNdc;
    private int vw = 1, vh = 1;
    private long t0 = SystemClock.uptimeMillis(), lastDraw = 0, lastFrame = 0;
    private final Maya3DModel.Uniforms U = new Maya3DModel.Uniforms();
    private final Maya3DAnim anim = new Maya3DAnim(SystemClock.uptimeMillis());
    private final float[] bands = new float[4], rect = new float[4], circle = new float[3], noCircle = new float[3];

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            face = new Prog(Maya3DShaders.FACE_VS, Maya3DShaders.FACE_FS);
            cav = new Prog(Maya3DShaders.CAV_VS, Maya3DShaders.CAV_FS);
            fx = new Prog(Maya3DShaders.FX_VS, Maya3DShaders.FX_FS);
            bg = new Prog(Maya3DShaders.BG_VS, Maya3DShaders.BG_FS);
            if (face.id == 0 || cav.id == 0 || bg.id == 0) { fail("shader"); return; }
            quad01 = fbuf(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
            quadNdc = fbuf(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
            GLES20.glGenTextures(2, tex, 0);
            GLES20.glGenBuffers(4, bufs, 0);
            texStyle = -1;
            faceCount = 0;
            synchronized (lock) { loadingStyle = -1; }   // context (re)created: decode + upload again
            decodeAsync(style);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
        } catch (Throwable t) {
            fail("init " + t.getClass().getSimpleName());
        }
    }

    private static FloatBuffer fbuf(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).position(0);
        return b;
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
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);   // premultiplied -> blend ONE, ONE_MINUS_SRC_ALPHA
    }

    /** Uploads a freshly decoded picture + mesh (GL thread). */
    private void takePending() {
        Bitmap pic, geo;
        Maya3DModel.Mesh m;
        int s;
        synchronized (lock) {
            if (pendStyle < 0) return;
            pic = pendPic; geo = pendGeo; m = pendMesh; s = pendStyle;
            pendPic = null; pendGeo = null; pendMesh = null; pendStyle = -1;
        }
        if (pic == null || geo == null || m == null) return;
        upload(tex[0], pic);
        upload(tex[1], geo);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        texAspect = pic.getWidth() / (float) pic.getHeight();
        pic.recycle();
        geo.recycle();
        FloatBuffer vb = fbuf(m.verts);
        ShortBuffer ib = ByteBuffer.allocateDirect(m.idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        ib.put(m.idx).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufs[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.verts.length * 4, vb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufs[1]);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.idx.length * 2, ib, GLES20.GL_STATIC_DRAW);
        FloatBuffer cb = fbuf(m.cav);
        ShortBuffer ci = ByteBuffer.allocateDirect(m.cavIdx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        ci.put(m.cavIdx).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufs[2]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.cav.length * 4, cb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufs[3]);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.cavIdx.length * 2, ci, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        faceCount = m.idx.length;
        cavCount = m.cavIdx.length;
        m.verts = null;   // uploaded; keep only the small numbers
        mesh = m;
        texStyle = s;
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
        GLES20.glDepthMask(true);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (face == null || face.id == 0) return;
        takePending();
        float dt = lastFrame == 0 ? 0.033f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float t = ((now - t0) / 1000f) % 3600f;

        boolean speaking = PriyaBus.state == PriyaBus.SPEAKING;
        bands[0] = PriyaBus.band0; bands[1] = PriyaBus.band1; bands[2] = PriyaBus.band2; bands[3] = PriyaBus.band3;
        anim.step(dt, speaking, speaking ? PriyaBus.outLevel : 0f, bands);

        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        float ringW = Math.max(2f, vw * 0.035f);
        if (overlay) drawBackdrop(ringW);
        int st = texStyle;
        if (st < 0 || faceCount == 0) return;
        float[] lm = Maya3DModel.LM[st];

        // where the full picture goes (px), then NDC
        float l, tp, r, b;
        if (overlay) {
            float rIn = Math.min(vw, vh) / 2f - ringW;
            float sc = rIn / lm[52];                       // picture units -> view px
            float picW = lm[0] * sc, picH = sc;
            l = vw / 2f - lm[50] * sc; tp = vh / 2f - lm[51] * sc; r = l + picW; b = tp + picH;
            circle[0] = vw / 2f; circle[1] = vh / 2f; circle[2] = rIn;
        } else if (hasRect) {
            l = rl; tp = rt; r = rr; b = rb;
        } else {
            float ar = texAspect, w = vw, h = vw / ar;
            if (h > vh) { h = vh; w = vh * ar; }
            l = (vw - w) / 2f; tp = (vh - h) / 2f; r = l + w; b = tp + h;
        }
        rect[0] = l / vw * 2f - 1f; rect[1] = 1f - tp / vh * 2f; rect[2] = r / vw * 2f - 1f; rect[3] = 1f - b / vh * 2f;
        Maya3DModel.fill(U, st, mesh, anim, t, rect);
        U.alpha = overlay || PriyaBus.running() ? 1f : 0.88f;
        float[] circ = overlay ? circle : noCircle;

        // mouth cavity, then the head (depth tested), then the background effects
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LESS);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        if (anim.open > 0.004f) {
            GLES20.glUseProgram(cav.id);
            cav.set(U, circ);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufs[2]);
            GLES20.glEnableVertexAttribArray(cav.aC);
            GLES20.glVertexAttribPointer(cav.aC, 2, GLES20.GL_FLOAT, false, 8, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufs[3]);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, cavCount, GLES20.GL_UNSIGNED_SHORT, 0);
            GLES20.glDisableVertexAttribArray(cav.aC);
        }

        GLES20.glUseProgram(face.id);
        face.set(U, circ);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glUniform1i(face.uTex, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufs[0]);
        GLES20.glEnableVertexAttribArray(face.aUV);
        GLES20.glVertexAttribPointer(face.aUV, 2, GLES20.GL_FLOAT, false, 32, 0);
        GLES20.glEnableVertexAttribArray(face.aG);
        GLES20.glVertexAttribPointer(face.aG, 4, GLES20.GL_FLOAT, false, 32, 8);
        GLES20.glEnableVertexAttribArray(face.aN);
        GLES20.glVertexAttribPointer(face.aN, 3, GLES20.GL_FLOAT, false, 32, 20);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufs[1]);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, faceCount, GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glDisableVertexAttribArray(face.aUV);
        GLES20.glDisableVertexAttribArray(face.aG);
        GLES20.glDisableVertexAttribArray(face.aN);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        if (fx.id != 0 && !overlay) {   // the tiny floating face skips the effects (battery)
            GLES20.glUseProgram(fx.id);
            fx.set(U, circ);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glUniform1i(fx.uTex, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[1]);
            GLES20.glUniform1i(fx.uGeo, 1);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glEnableVertexAttribArray(fx.aP);
            GLES20.glVertexAttribPointer(fx.aP, 2, GLES20.GL_FLOAT, false, 8, quad01);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(fx.aP);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawBackdrop(float ringW) {
        if (bg == null || bg.id == 0) return;
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(bg.id);
        float r = Math.min(vw, vh) / 2f - 1f;
        GLES20.glUniform3f(bg.uCircle, vw / 2f, vh / 2f, r);
        float[] f = BG_FILL[Maya3DModel.clampStyle(style)];
        GLES20.glUniform3f(bg.uFill, f[0], f[1], f[2]);
        int col = PriyaBus.state == PriyaBus.SPEAKING ? 0xFFFF7AD9 : PriyaBus.userTalking ? 0xFF4CE0A0 : 0xFF5CE1FF;
        GLES20.glUniform3f(bg.uRing, ((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f);
        GLES20.glUniform1f(bg.uRingW, ringW);
        GLES20.glEnableVertexAttribArray(bg.aP);
        GLES20.glVertexAttribPointer(bg.aP, 2, GLES20.GL_FLOAT, false, 8, quadNdc);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(bg.aP);
    }
}
