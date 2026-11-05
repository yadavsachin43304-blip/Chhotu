package com.priya.ai;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * Real-time 3D Priya (GLES 2.0). Geometry comes from {@link Avatar3D}; one shader animates jaw / lips / smile /
 * blink / gaze / head sway on the GPU and colours each style (Hologram = additive glowing dots, Cyber = soft-lit,
 * Anime = toon + outline). Lip-sync follows {@link PriyaBus#outLevel} (Priya's live output level).
 *
 * Two modes: main screen (bust, transparent background, drawn above the window as a media overlay) and the small
 * round floating face (head only, dark disc + state ring, setZOrderOnTop).
 * If anything about GL fails the {@link FailListener} is told (on the main thread) so callers fall back to pictures.
 */
public class Avatar3DView extends GLSurfaceView implements GLSurfaceView.Renderer {

    public interface FailListener { void onAvatar3DFailed(); }

    /** Set once GL failed on this device in this process: callers stop trying and use the pictures. */
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

    // ------------------------------------------------------------------ mesh cache (built off the GL thread)

    private static final Avatar3D.Model[] CACHE = new Avatar3D.Model[3];
    private static final Object LOCK = new Object();

    private static Avatar3D.Model cached(int style) {
        synchronized (LOCK) { return CACHE[style]; }
    }

    private static void buildAsync(final int style) {
        synchronized (LOCK) { if (CACHE[style] != null) return; }
        Thread t = new Thread(() -> {
            synchronized (LOCK) {
                if (CACHE[style] != null) return;
                try { CACHE[style] = Avatar3D.build(style); } catch (Throwable ignored) { }
            }
        }, "avatar3d-build");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    // ------------------------------------------------------------------ view

    private final boolean overlay;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FailListener failListener;
    private volatile int style;
    private volatile boolean firstFrame = false;
    private boolean reported = false;

    public Avatar3DView(Context c, boolean overlay) {
        super(c);
        this.overlay = overlay;
        style = clampStyle(Prefs.theme(c));
        buildAsync(style);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new Chooser());   // RGBA 8888 + depth 16 (= setEGLConfigChooser(8,8,8,8,16,0))
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
        buildAsync(s);
        style = s;
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
        try { Prefs.setLastCrash(getContext(), "3D avatar off: " + why); } catch (Throwable ignored) { }
        main.post(() -> {
            if (reported) return;
            reported = true;
            if (failListener != null) failListener.onAvatar3DFailed();
        });
    }

    private final class Chooser implements GLSurfaceView.EGLConfigChooser {
        @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay d) {
            int[] spec = {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */, EGL10.EGL_NONE};
            int[] n = new int[1];
            if (!egl.eglChooseConfig(d, spec, null, 0, n) || n[0] <= 0) {
                fail("no RGBA8888 config");
                throw new IllegalArgumentException("no EGL config");
            }
            EGLConfig[] cs = new EGLConfig[n[0]];
            egl.eglChooseConfig(d, spec, cs, n[0], n);
            int[] v = new int[1];
            for (EGLConfig c : cs) {   // prefer an exact 8888 match with depth >= 16
                int r = attr(egl, d, c, EGL10.EGL_RED_SIZE, v), g = attr(egl, d, c, EGL10.EGL_GREEN_SIZE, v);
                int b = attr(egl, d, c, EGL10.EGL_BLUE_SIZE, v), a = attr(egl, d, c, EGL10.EGL_ALPHA_SIZE, v);
                int dp = attr(egl, d, c, EGL10.EGL_DEPTH_SIZE, v);
                if (r == 8 && g == 8 && b == 8 && a == 8 && dp >= 16) return c;
            }
            return cs[0];
        }

        private int attr(EGL10 egl, EGLDisplay d, EGLConfig c, int a, int[] v) {
            return egl.eglGetConfigAttrib(d, c, a, v) ? v[0] : 0;
        }
    }

    // ------------------------------------------------------------------ shaders

    private static final String VS =
            "attribute vec3 aPos; attribute vec3 aNor; attribute vec4 aCol; attribute vec4 aRig; attribute float aHw;\n" +
            "uniform mat4 uVP;\n" +
            "uniform float uJaw, uSmile, uBlink, uYaw, uPitch, uRoll, uTime, uOutline, uBrow, uWide, uRound;\n" +
            "uniform vec2 uGaze;\n" +
            "uniform vec4 uEye;   // eyeX eyeY eyeZ eyeR\n" +
            "uniform vec4 uMouth; // mouthY mouthZ jawY jawZ\n" +
            "uniform float uLidAng;\n" +
            "varying vec3 vN; varying vec3 vW; varying vec3 vObj; varying vec4 vCol;\n" +
            "vec3 rotX(vec3 p, float a){ float c=cos(a), s=sin(a); return vec3(p.x, p.y*c-p.z*s, p.y*s+p.z*c); }\n" +
            "vec3 rotY(vec3 p, float a){ float c=cos(a), s=sin(a); return vec3(p.x*c+p.z*s, p.y, -p.x*s+p.z*c); }\n" +
            "vec3 rotZ(vec3 p, float a){ float c=cos(a), s=sin(a); return vec3(p.x*c-p.y*s, p.x*s+p.y*c, p.z); }\n" +
            "void main(){\n" +
            "  vec3 p = aPos; vec3 n = aNor;\n" +
            "  float part = floor(aRig.y + 0.5);\n" +
            "  // jaw: rotate chin + lower lip open around the jaw pivot\n" +
            "  vec3 piv = vec3(0.0, uMouth.z, uMouth.w);\n" +
            "  float ja = uJaw * 0.075 * aRig.x;\n" +
            "  if (ja > 0.0) { p = piv + rotX(p - piv, ja); n = rotX(n, ja); }\n" +
            "  // lips: part a little + smile pulls the corners out and up\n" +
            "  if (part > 3.5 && part < 5.5) {\n" +
            "    float corner = aRig.z; float u = aRig.w;\n" +
            "    p.x += sign(u) * corner * (uSmile * 0.014 - uJaw * 0.006 + uWide * 0.009 - uRound * 0.013);\n" +
            "    p.y += corner * uSmile * 0.020;\n" +
            "    if (part < 4.5) p.y += uJaw * 0.008 * (1.0 - corner);\n" +
            "  }\n" +
            "  // mouth interior: opens with the jaw\n" +
            "  if (part > 5.5 && part < 6.5) {\n" +
            "    p.y = uMouth.x + (p.y - uMouth.x) * (1.0 + uJaw * 2.6) - uJaw * 0.034;\n" +
            "    p.x *= 1.0 + uSmile * 0.10 + uWide * 0.14 - uRound * 0.24;\n" +
            "  }\n" +
            "  // brows lift a touch while talking / smiling\n" +
            "  if (part > 2.5 && part < 3.5) p.y += uBrow * 0.018 * (0.6 + 0.4 * aRig.z);\n" +
            "  // eyes: gaze; lids: blink (rotate down around the eyeball centre)\n" +
            "  vec3 ec = vec3(aRig.w * uEye.x, uEye.y, uEye.z);\n" +
            "  if (part > 1.5 && part < 2.5) { vec3 q = rotX(rotY(p - ec, uGaze.x), -uGaze.y); p = ec + q; n = rotX(rotY(n, uGaze.x), -uGaze.y); }\n" +
            "  if (part > 0.5 && part < 1.5) { float a = uBlink * uLidAng; p = ec + rotX(p - ec, a); n = rotX(n, a); }\n" +
            "  // hair sway\n" +
            "  if (part > 7.5 && part < 8.5) { float s = aRig.z; p.x += s * (0.025 * sin(uTime * 1.3 + p.y * 1.7) - uYaw * 0.18); p.z += s * 0.015 * sin(uTime * 0.9 + p.x); }\n" +
            "  // orbit rings turn slowly\n" +
            "  if (part > 10.5) { p = rotY(p, uTime * aRig.z); n = rotY(n, uTime * aRig.z); }\n" +
            "  // body breathing\n" +
            "  p.y += (1.0 - aHw) * 0.012 * sin(uTime * 1.6) * step(part, 9.5) * step(8.5, part);\n" +
            "  // head follows yaw/pitch/roll around the neck\n" +
            "  vec3 neck = vec3(0.0, -0.95, -0.1);\n" +
            "  vec3 hp = neck + rotY(rotX(rotZ(p - neck, uRoll), uPitch), uYaw);\n" +
            "  vec3 hn = rotY(rotX(rotZ(n, uRoll), uPitch), uYaw);\n" +
            "  p = mix(p, hp, aHw); n = normalize(mix(n, hn, aHw));\n" +
            "  if (uOutline > 0.0) {\n" +
            "    bool hull = part < 0.5 || (part > 7.5 && part < 10.5);\n" +
            "    if (!hull) { gl_Position = vec4(3.0, 3.0, 3.0, 1.0); vObj = aPos; vW = p; vN = n; vCol = aCol; return; }\n" +
            "    p += n * uOutline;\n" +
            "  }\n" +
            "  vObj = aPos; vW = p; vN = n; vCol = aCol;\n" +
            "  gl_Position = uVP * vec4(p, 1.0);\n" +
            "}\n";

    private static final String FS =
            "precision mediump float;\n" +
            "uniform float uStyle, uTimeF, uOutlineF, uPass, uDim; // own names: no VS/FS precision clash\n" +
            "uniform vec3 uCam; uniform vec3 uCircle;\n" +
            "varying vec3 vN; varying vec3 vW; varying vec3 vObj; varying vec4 vCol;\n" +
            "void main(){\n" +
            "  if (uCircle.z > 0.0 && distance(gl_FragCoord.xy, uCircle.xy) > uCircle.z) discard;\n" +
            "  if (uPass < 0.5) { gl_FragColor = vec4(0.0); return; }\n" +
            "  vec3 N = normalize(vN);\n" +
            "  vec3 V = normalize(uCam - vW);\n" +
            "  if (uOutlineF > 0.0) { if (dot(N, V) > 0.0) discard; gl_FragColor = vec4(0.13, 0.09, 0.20, 1.0); return; }\n" +
            "  if (dot(N, V) < 0.0) N = -N;\n" +
            "  float ndv = max(dot(N, V), 0.0);\n" +
            "  vec3 base = vCol.rgb; float em = vCol.a;\n" +
            "  vec3 col;\n" +
            "  if (uStyle < 0.5) {\n" +
            "    vec3 an = abs(N);\n" +
            "    vec2 uv = an.z > 0.55 ? vObj.xy : (an.x > an.y ? vObj.zy : vObj.xz);\n" +
            "    float d = length(fract(uv * 34.0) - 0.5);\n" +
            "    float dots = 1.0 - smoothstep(0.20, 0.38, d);\n" +
            "    float fres = pow(1.0 - ndv, 2.0);\n" +
            "    float scan = 0.82 + 0.18 * sin(gl_FragCoord.y * 0.9 - uTimeF * 5.0);\n" +
            "    float k = (0.10 + 0.65 * dots * (0.35 + 0.65 * ndv) + 1.1 * fres + 1.3 * em) * scan;\n" +
            "    col = base * k * 0.62 * uDim;\n" +
            "    float a = clamp(max(col.r, max(col.g, col.b)), 0.0, 1.0);\n" +
            "    gl_FragColor = vec4(col, a);\n" +
            "    return;\n" +
            "  } else if (uStyle < 1.5) {\n" +
            "    vec3 L = normalize(vec3(-0.45, 0.55, 0.85)); vec3 H = normalize(L + V);\n" +
            "    float dif = max(dot(N, L), 0.0); float fill = 0.5 + 0.5 * N.y;\n" +
            "    float spec = pow(max(dot(N, H), 0.0), 28.0) * 0.22;\n" +
            "    vec3 rim = vec3(0.30, 0.95, 0.92) * pow(1.0 - ndv, 3.0) * 0.55;\n" +
            "    col = base * (0.26 + 0.70 * dif + 0.16 * fill) + spec + rim + base * em * 1.4;\n" +
            "  } else {\n" +
            "    vec3 L = normalize(vec3(-0.35, 0.6, 0.8));\n" +
            "    float d = dot(N, L);\n" +
            "    float band = d > 0.50 ? 1.0 : (d > 0.05 ? 0.84 : 0.68);\n" +
            "    float rim = smoothstep(0.62, 0.70, 1.0 - ndv) * 0.22;\n" +
            "    col = base * band + rim * vec3(0.75, 0.70, 1.0) + base * em * 0.8;\n" +
            "  }\n" +
            "  gl_FragColor = vec4(col * uDim, 1.0);\n" +
            "}\n";

    /** Overlay backdrop: dark disc + state-coloured ring, full-screen quad. */
    private static final String BG_VS =
            "attribute vec2 aP; void main(){ gl_Position = vec4(aP, 0.0, 1.0); }\n";
    private static final String BG_FS =
            "precision mediump float;\n" +
            "uniform vec3 uCircle; uniform vec3 uFill; uniform vec3 uRing; uniform float uRingW;\n" +
            "void main(){\n" +
            "  float d = distance(gl_FragCoord.xy, uCircle.xy);\n" +
            "  float inside = 1.0 - smoothstep(uCircle.z - 1.0, uCircle.z + 0.5, d);\n" +
            "  float ring = smoothstep(uCircle.z - uRingW - 1.0, uCircle.z - uRingW, d) * inside;\n" +
            "  vec3 c = mix(uFill, uRing, ring);\n" +
            "  gl_FragColor = vec4(c * inside, inside);\n" +
            "}\n";

    private int prog, bgProg;
    private int aPos, aNor, aCol, aRig, aHw;
    private int uVP, uJaw, uSmile, uBlink, uYaw, uPitch, uRoll, uTime, uOutline, uBrow, uGaze, uEye, uMouth, uLidAng, uWide, uRound;
    private int uStyle, uCam, uCircle, uPass, uDim, uTimeF, uOutlineF;
    private int bgAP, bgCircle, bgFill, bgRing, bgRingW;
    private FloatBuffer quad;

    // uploaded mesh
    private int meshStyle = -1;
    private int[] vbo = new int[0], ibo = new int[0], icount = new int[0];
    private Avatar3D.Model mesh;

    private int vw = 1, vh = 1;
    private final float[] proj = new float[16], view = new float[16], vp = new float[16];
    private final float[] cam = new float[3];

    // ------------------------------------------------------------------ animation state (GL thread)

    private final long t0 = SystemClock.uptimeMillis();
    private long lastFrame = 0, lastDraw = 0;
    private float jaw = 0f, smile = 0.15f, brow = 0f, lipWide = 0f, lipRound = 0f;
    private float blink = 0f;
    private long nextBlink = 0, blinkStart = -1;
    private boolean doubleBlink = false;
    private float gx = 0, gy = 0, gtx = 0, gty = 0;
    private long nextGaze = 0;
    private final java.util.Random rnd = new java.util.Random();

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            prog = link(VS, FS);
            bgProg = link(BG_VS, BG_FS);
            if (prog == 0 || bgProg == 0) { fail("shader"); return; }
            aPos = GLES20.glGetAttribLocation(prog, "aPos");
            aNor = GLES20.glGetAttribLocation(prog, "aNor");
            aCol = GLES20.glGetAttribLocation(prog, "aCol");
            aRig = GLES20.glGetAttribLocation(prog, "aRig");
            aHw = GLES20.glGetAttribLocation(prog, "aHw");
            uVP = u("uVP"); uJaw = u("uJaw"); uSmile = u("uSmile"); uBlink = u("uBlink"); uYaw = u("uYaw");
            uPitch = u("uPitch"); uRoll = u("uRoll"); uTime = u("uTime"); uOutline = u("uOutline"); uBrow = u("uBrow");
            uGaze = u("uGaze"); uEye = u("uEye"); uMouth = u("uMouth"); uLidAng = u("uLidAng");
            uWide = u("uWide"); uRound = u("uRound");
            uStyle = u("uStyle"); uTimeF = u("uTimeF"); uOutlineF = u("uOutlineF"); uCam = u("uCam"); uCircle = u("uCircle"); uPass = u("uPass"); uDim = u("uDim");
            bgAP = GLES20.glGetAttribLocation(bgProg, "aP");
            bgCircle = GLES20.glGetUniformLocation(bgProg, "uCircle");
            bgFill = GLES20.glGetUniformLocation(bgProg, "uFill");
            bgRing = GLES20.glGetUniformLocation(bgProg, "uRing");
            bgRingW = GLES20.glGetUniformLocation(bgProg, "uRingW");
            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
            meshStyle = -1;          // context (re)created: buffers are gone
            vbo = new int[0];
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

    private void upload(Avatar3D.Model m) {
        if (vbo.length > 0) {
            GLES20.glDeleteBuffers(vbo.length, vbo, 0);
            GLES20.glDeleteBuffers(ibo.length, ibo, 0);
        }
        int n = m.chunks.size();
        vbo = new int[n]; ibo = new int[n]; icount = new int[n];
        GLES20.glGenBuffers(n, vbo, 0);
        GLES20.glGenBuffers(n, ibo, 0);
        for (int i = 0; i < n; i++) {
            Avatar3D.Chunk c = m.chunks.get(i);
            float[] vv = orientNormals(c.v);
            FloatBuffer fb = ByteBuffer.allocateDirect(vv.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(vv).position(0);
            ShortBuffer sb = ByteBuffer.allocateDirect(c.idx.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(c.idx).position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[i]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, c.v.length * 4, fb, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[i]);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, c.idx.length * 2, sb, GLES20.GL_STATIC_DRAW);
            icount[i] = c.idx.length;
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        mesh = m;
    }

    /**
     * Avatar3D's grids can come out with inward normals (head/hair/body). The toon outline inflates along the
     * normal, so make skin / hair / body / accessory normals point away from the head centre (or the neck/body
     * axis below the chin). Lighting additionally faces normals to the viewer in the shader.
     */
    private static float[] orientNormals(float[] src) {
        float[] v = src.clone();
        for (int o = 0; o < v.length; o += Avatar3D.STRIDE) {
            int part = (int) (v[o + 11] + 0.5f);
            if (part != Avatar3D.P_SKIN && part != Avatar3D.P_HAIR && part != Avatar3D.P_BODY && part != Avatar3D.P_ACC) continue;
            float x = v[o], y = v[o + 1], z = v[o + 2];
            float dx, dy, dz;
            if (y > -0.75f || part == Avatar3D.P_ACC) { dx = x; dy = y; dz = z; }
            else { dx = x; dy = 0f; dz = z + 0.1f; }
            if (v[o + 3] * dx + v[o + 4] * dy + v[o + 5] * dz < 0f) {
                v[o + 3] = -v[o + 3]; v[o + 4] = -v[o + 4]; v[o + 5] = -v[o + 5];
            }
        }
        return v;
    }

    @Override public void onDrawFrame(GL10 unused) {
        // ~30 fps cap
        long now = SystemClock.uptimeMillis();
        long wait = 33 - (now - lastDraw);
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

    private void drawFrame(long now) {
        GLES20.glDepthMask(true);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (prog == 0) return;

        int st = style;
        if (meshStyle != st) {
            Avatar3D.Model m = cached(st);
            if (m == null) { buildAsync(st); drawBackdrop(); return; }
            upload(m);
            meshStyle = st;
        }

        float dt = lastFrame == 0 ? 0.033f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float t = (now - t0) / 1000f;
        animate(now, t, dt);

        drawBackdrop();
        setupCamera(st);

        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0);
        GLES20.glUniform1f(uJaw, jaw);
        GLES20.glUniform1f(uSmile, smile);
        GLES20.glUniform1f(uBrow, brow);
        GLES20.glUniform1f(uWide, lipWide);
        GLES20.glUniform1f(uRound, lipRound);
        GLES20.glUniform1f(uBlink, blink);
        GLES20.glUniform1f(uLidAng, st == 2 ? 0.95f : 0.80f);
        float yaw = 0.055f * (float) Math.sin(t * 0.37) + 0.025f * (float) Math.sin(t * 0.93 + 1.3) + gx * 0.35f;
        float pitch = 0.030f * (float) Math.sin(t * 0.29 + 0.7) - gy * 0.25f + jaw * 0.025f * (float) Math.sin(t * 7.0);
        float roll = 0.022f * (float) Math.sin(t * 0.23 + 2.1);
        GLES20.glUniform1f(uYaw, yaw);
        GLES20.glUniform1f(uPitch, pitch);
        GLES20.glUniform1f(uRoll, roll);
        GLES20.glUniform1f(uTime, t);
        GLES20.glUniform1f(uTimeF, t % 62.831853f);
        GLES20.glUniform2f(uGaze, gx, gy);
        GLES20.glUniform4f(uEye, mesh.eyeX, mesh.eyeY, mesh.eyeZ, mesh.eyeR);
        GLES20.glUniform4f(uMouth, mesh.mouthY, mesh.mouthZ, mesh.jawY, mesh.jawZ);
        GLES20.glUniform1f(uStyle, st);
        GLES20.glUniform3f(uCam, cam[0], cam[1], cam[2]);
        GLES20.glUniform1f(uDim, PriyaBus.running() ? 1f : 0.82f);
        if (overlay) {
            float r = Math.min(vw, vh) / 2f - Math.max(2f, vw * 0.035f);
            GLES20.glUniform3f(uCircle, vw / 2f, vh / 2f, r);
        } else {
            GLES20.glUniform3f(uCircle, 0, 0, 0);
        }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        if (st == 0) {
            // hologram: depth pre-pass (only the nearest surface glows), then additive
            setOutline(0f);
            GLES20.glUniform1f(uPass, 0f);
            GLES20.glColorMask(false, false, false, false);
            GLES20.glDisable(GLES20.GL_BLEND);
            drawMesh();
            GLES20.glColorMask(true, true, true, true);
            GLES20.glDepthMask(false);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glUniform1f(uPass, 1f);
            drawMesh();
            GLES20.glDepthMask(true);
            GLES20.glDisable(GLES20.GL_BLEND);
        } else {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUniform1f(uPass, 1f);
            if (st == 2) {   // toon outline: inflated hull, only its away-facing side (by normal, not winding)
                setOutline(overlay ? 0.022f : 0.012f);
                drawMesh();
            }
            setOutline(0f);
            drawMesh();
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    }

    private void setOutline(float w) {
        GLES20.glUniform1f(uOutline, w);
        GLES20.glUniform1f(uOutlineF, w);
    }

    private void drawMesh() {
        for (int i = 0; i < vbo.length; i++) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[i]);
            int s = Avatar3D.STRIDE * 4;
            attr(aPos, 3, s, 0);
            attr(aNor, 3, s, 3);
            attr(aCol, 4, s, 6);
            attr(aRig, 4, s, 10);
            attr(aHw, 1, s, 14);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[i]);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, icount[i], GLES20.GL_UNSIGNED_SHORT, 0);
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private static void attr(int loc, int size, int stride, int offFloats) {
        if (loc < 0) return;
        GLES20.glEnableVertexAttribArray(loc);
        GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, stride, offFloats * 4);
    }

    private static final float[][] BG_FILL = {{0.02f, 0.07f, 0.14f}, {0.10f, 0.11f, 0.14f}, {0.12f, 0.13f, 0.24f}};

    private void drawBackdrop() {
        if (!overlay || bgProg == 0) return;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(bgProg);
        float r = Math.min(vw, vh) / 2f - 1f;
        GLES20.glUniform3f(bgCircle, vw / 2f, vh / 2f, r);
        float[] f = BG_FILL[clampStyle(style)];
        GLES20.glUniform3f(bgFill, f[0], f[1], f[2]);
        int col = PriyaBus.state == PriyaBus.SPEAKING ? 0xFFFF7AD9 : PriyaBus.userTalking ? 0xFF4CE0A0 : 0xFF5CE1FF;
        GLES20.glUniform3f(bgRing, ((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f);
        GLES20.glUniform1f(bgRingW, Math.max(2f, vw * 0.035f));
        GLES20.glEnableVertexAttribArray(bgAP);
        GLES20.glVertexAttribPointer(bgAP, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(bgAP);
    }

    /** Frames the bust (main screen) or just the head (floating face) for the current aspect ratio. */
    private void setupCamera(int st) {
        float yLo, yHi, xHalf;
        if (overlay) { yLo = -1.12f; yHi = 1.18f; xHalf = 1.15f; }
        else if (st == 0) { yLo = -2.80f; yHi = 1.30f; xHalf = 1.85f; }
        else { yLo = -2.15f; yHi = 1.30f; xHalf = 1.40f; }
        float aspect = vw / (float) vh;
        float fov = 24f;
        float tanH = (float) Math.tan(Math.toRadians(fov / 2));
        float halfH = Math.max((yHi - yLo) / 2f, xHalf / aspect);
        float dist = halfH / tanH + 1.0f;   // +1: the face sits ~1 unit in front of the origin
        float cy = (yHi + yLo) / 2f;
        cam[0] = 0; cam[1] = cy + 0.15f; cam[2] = dist;
        Matrix.perspectiveM(proj, 0, fov, aspect, Math.max(0.1f, dist - 4f), dist + 4f);
        Matrix.setLookAtM(view, 0, cam[0], cam[1], cam[2], 0, cy, 0, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
    }

    private void animate(long now, float t, float dt) {
        // lip-sync from Priya's live output level: fast attack, ~80 ms release
        boolean speaking = PriyaBus.state == PriyaBus.SPEAKING;
        float target = speaking ? Math.min(1f, PriyaBus.outLevel * 1.8f) : 0f;
        float tau = target > jaw ? 0.025f : 0.080f;
        jaw += (target - jaw) * Math.min(1f, dt / tau);
        if (jaw < 0.003f) jaw = 0f;
        // mouth SHAPE from her voice: spread lips (aa, e, s) vs rounded lips (o, u); smoothed so it never flickers
        float wT = speaking ? PriyaBus.mouthWide : 0f, rT = speaking ? PriyaBus.mouthRound : 0f;
        lipWide += (wT - lipWide) * Math.min(1f, dt / 0.06f);
        lipRound += (rT - lipRound) * Math.min(1f, dt / 0.06f);
        // warm smile while she talks, gentle resting smile otherwise
        float sTarget = speaking ? 0.40f + 0.15f * jaw : (PriyaBus.running() ? 0.20f : 0.12f);
        smile += (sTarget - smile) * Math.min(1f, dt / 0.5f);
        float bTarget = speaking ? 0.35f + 0.6f * jaw : 0f;
        brow += (bTarget - brow) * Math.min(1f, dt / 0.25f);

        // blink every 3-6 s (sometimes twice)
        if (nextBlink == 0) nextBlink = now + 1500 + rnd.nextInt(2500);
        if (blinkStart < 0 && now >= nextBlink) {
            blinkStart = now;
            doubleBlink = rnd.nextFloat() < 0.15f;
        }
        if (blinkStart >= 0) {
            float bt = (now - blinkStart) / 1000f;
            float dur = 0.16f;
            if (bt < dur) blink = 1f - Math.abs(bt / dur * 2f - 1f);
            else if (doubleBlink && bt < dur * 2.3f) {
                float b2 = (bt - dur * 1.3f) / dur;
                blink = b2 < 0 ? 0f : 1f - Math.abs(b2 * 2f - 1f);
            } else {
                blink = 0f;
                blinkStart = -1;
                nextBlink = now + 3000 + rnd.nextInt(3000);
            }
        }

        // gaze drift: pick a new nearby target every 1.5-4 s, look at the viewer more while talking
        if (now >= nextGaze) {
            float range = speaking ? 0.05f : 0.13f;
            gtx = (rnd.nextFloat() * 2 - 1) * range;
            gty = (rnd.nextFloat() * 2 - 1) * range * 0.6f;
            nextGaze = now + 1500 + rnd.nextInt(2500);
        }
        gx += (gtx - gx) * Math.min(1f, dt / 0.12f);
        gy += (gty - gy) * Math.min(1f, dt / 0.12f);
    }
}
