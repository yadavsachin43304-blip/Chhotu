package com.priya.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

/**
 * Draws the three Priya UI designs:
 *   0 = Hologram (gold/cyan dotted head, orbit rings, circular mic with side waves)
 *   1 = Cyber    (portrait in a ring, glass panel with bar waveform, big filled mic)
 *   2 = Anime    (headphones girl, neon purple waves around a ring mic)
 * The avatar artwork comes from assets/av_N.webp; everything else (rings, waves, mic, icons) is animated live.
 */
public class ThemeView extends View {

    public interface Listener {
        void onMenu();
        void onMic();
        void onGear();
        void onProfile();
    }

    public static final int[] BG_TOP = {0xFF061224, 0xFF1F222C, 0xFF232840};
    public static final int[] BG_BOTTOM = {0xFF040B18, 0xFF181A22, 0xFF1A1F39};
    private static final int[] ACCENT = {0xFF5CE1FF, 0xFF7DF3E8, 0xFFB07BFF};
    private static final int GOLD = 0xFFF5C542;

    private Listener listener;
    private int theme = -1;
    private Bitmap avatar;
    private Bitmap avatarOpen;   // same artwork with the mouth open: cross-faded by voice level = lip-sync
    private final Paint mouthPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private float mouth = 0f;

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();

    private final float[] hist = new float[48];
    private int histPos = 0;
    private long lastHist = 0;
    private float cur = 0f;
    private float t = 0f;
    private final long t0 = SystemClock.uptimeMillis();

    // layout
    private int W, H;
    private float avCx, avCy, avW, avH;
    private float micX, micY, micR, textTop;
    private float panelL, panelT, panelR, panelB;
    private float menuL, menuT, menuSize;
    private float gearX, gearY, profX, profY, iconR;

    // shaders (rebuilt on size/theme change)
    private Shader glowShader, micFillShader, bgShader, barShader;
    private Shader blobA, blobB;

    // text cache
    private StaticLayout stLayout, usLayout, prLayout;
    private String stKey = "", usKey = "", prKey = "";

    public ThemeView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        setClickable(true);
    }

    public void setListener(Listener l) { listener = l; }

    // ---- 3D avatar hook: when a live 3D Priya is shown on top, skip the flat picture (glow and rings stay)
    private boolean avatar3D = false;
    private Runnable avatarRectListener;
    private final RectF avatarBox = new RectF();

    public void setAvatar3D(boolean on) {
        if (avatar3D == on) return;
        avatar3D = on;
        invalidate();
    }

    public void setAvatarRectListener(Runnable r) { avatarRectListener = r; }

    /** Where the avatar artwork sits (px, in this view), clipped clear of the top icons and the text below it. */
    public RectF avatarRect() { return avatarBox; }

    public void setTheme(int newTheme) {
        if (newTheme == theme) return;
        theme = newTheme;
        avatar = null;
        try (InputStream is = getContext().getAssets().open("av_" + theme + ".webp")) {
            avatar = BitmapFactory.decodeStream(is);
        } catch (IOException ignored) { }
        avatarOpen = null;
        try (InputStream is = getContext().getAssets().open("av_" + theme + "_m.webp")) {
            avatarOpen = BitmapFactory.decodeStream(is);
        } catch (IOException ignored) { }
        stKey = usKey = prKey = "";
        relayout();
        invalidate();
    }

    private float dp(float v) { return v * d; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
    }

    private void relayout() {
        W = getWidth();
        H = getHeight();
        if (W == 0 || H == 0 || theme < 0) return;

        menuL = dp(10);
        menuT = dp(12);
        menuSize = dp(52);
        iconR = dp(26);
        profX = W - dp(14) - iconR;
        profY = dp(14) + iconR;
        gearX = profX - iconR * 2 - dp(14);
        gearY = profY;

        float ratio = (avatar != null) ? (float) avatar.getHeight() / avatar.getWidth() : 1f;
        switch (theme) {
            case 0:
                avW = 0.98f * W; avCx = W / 2f; avCy = 0.36f * H;
                micX = W / 2f; micY = 0.80f * H; micR = 0.16f * W;
                break;
            case 1:
                avW = 0.80f * W; avCx = W / 2f; avCy = 0.30f * H;
                panelL = 0.04f * W; panelR = 0.96f * W; panelT = 0.60f * H; panelB = 0.975f * H;
                micX = W / 2f; micY = panelT + 0.80f * (panelB - panelT); micR = 0.105f * W;
                break;
            default:
                avW = 0.88f * W; avCx = W / 2f; avCy = 0.40f * H;
                micX = W / 2f; micY = 0.865f * H; micR = 0.105f * W;
                break;
        }
        avH = avW * ratio;
        textTop = avCy + avH / 2f + dp(2);
        avatarBox.set(Math.max(0, avCx - avW / 2f), Math.max(dp(76), avCy - avH / 2f),
                Math.min(W, avCx + avW / 2f), Math.min(textTop, theme == 1 ? panelT : H));
        if (avatarRectListener != null) post(avatarRectListener);

        int acc = ACCENT[theme];
        glowShader = new RadialGradient(avCx, avCy, avW * 0.62f,
                new int[]{Ui.withAlpha(acc, 150), Ui.withAlpha(acc, 0)}, null, Shader.TileMode.CLAMP);
        micFillShader = new RadialGradient(micX, micY - micR * 0.25f, micR,
                new int[]{0xFFD3FEFF, 0xFF58D6F4}, null, Shader.TileMode.CLAMP);
        bgShader = new LinearGradient(0, 0, 0, H, BG_TOP[theme], BG_BOTTOM[theme], Shader.TileMode.CLAMP);
        blobA = new RadialGradient(W * 0.18f, H * 0.24f, W * 0.7f,
                new int[]{0x40866BFF, 0x00866BFF}, null, Shader.TileMode.CLAMP);
        blobB = new RadialGradient(W * 0.85f, H * 0.62f, W * 0.7f,
                new int[]{0x3050D8C8, 0x0050D8C8}, null, Shader.TileMode.CLAMP);
        if (theme == 1) {
            barShader = new LinearGradient(panelL, 0, panelR, 0, 0xFF7DF3E8, 0xFF5568FF, Shader.TileMode.CLAMP);
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        if (theme < 0 || W == 0) return;
        long now = SystemClock.uptimeMillis();
        t = (now - t0) / 1000f;

        int st = PriyaBus.state;
        boolean on = PriyaBus.running();
        float target = st == PriyaBus.SPEAKING ? PriyaBus.outLevel : (on ? PriyaBus.micLevel : 0f);
        cur += (target - cur) * 0.25f;
        if (now - lastHist > 40) {
            histPos = (histPos + 1) % hist.length;
            hist[histPos] = Math.min(1f, cur * 1.5f);
            lastHist = now;
        }

        // background
        bgPaint.setShader(bgShader);
        c.drawRect(0, 0, W, H, bgPaint);
        if (theme == 1) {
            fillPaint.setShader(blobA);
            c.drawRect(0, 0, W, H, fillPaint);
            fillPaint.setShader(blobB);
            c.drawRect(0, 0, W, H, fillPaint);
            fillPaint.setShader(null);
        }
        if (theme == 2) drawFrame(c);

        drawAvatar(c, on);
        switch (theme) {
            case 0:
                drawSides(c, new int[]{0xCC5CE1FF, 0xCCF5C542, 0x885CE1FF},
                        new float[]{0.030f, 0.024f, 0.045f}, new float[]{3.0f, -2.2f, 4.1f}, 1.6f, H * 0.060f, on);
                drawMic0(c, on);
                break;
            case 1:
                drawPanel(c);
                drawMic1(c, on);
                break;
            default:
                drawSides(c, new int[]{0xDDA66BFF, 0xCCFF5CC8, 0xCC5CC8FF},
                        new float[]{0.028f, 0.036f, 0.022f}, new float[]{2.6f, -3.3f, 1.9f}, 1.8f, H * 0.050f, on);
                drawMic2(c, on);
                break;
        }
        drawTop(c);
        drawTexts(c, st);

        postInvalidateOnAnimation();
    }

    private void drawFrame(Canvas c) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.5f));
        p.setColor(0x887A6CFF);
        rf.set(dp(8), dp(6), W - dp(8), H - dp(6));
        c.drawRoundRect(rf, dp(26), dp(26), p);
    }

    private void drawAvatar(Canvas c, boolean on) {
        float acc = on ? 1f : 0.35f;
        glowPaint.setShader(glowShader);
        glowPaint.setAlpha((int) (255 * Math.min(1f, (0.30f + cur * 0.9f) * acc)));
        c.drawCircle(avCx, avCy, avW * 0.62f, glowPaint);

        if (avatar != null && !avatar3D) {
            float breathe = 1f + 0.010f * (float) Math.sin(t * 1.6f) + (on ? 0.035f * cur : 0f);
            rf.set(avCx - avW / 2f * breathe, avCy - avH / 2f * breathe,
                    avCx + avW / 2f * breathe, avCy + avH / 2f * breathe);
            bmpPaint.setAlpha(on ? 255 : 215);
            c.drawBitmap(avatar, null, rf, bmpPaint);
            // lip-sync: open the mouth with Priya's live voice level
            float target = PriyaBus.state == PriyaBus.SPEAKING ? Math.min(1f, PriyaBus.outLevel * 1.8f) : 0f;
            mouth += (target - mouth) * 0.55f;
            if (avatarOpen != null && mouth > 0.02f) {
                mouthPaint.setAlpha((int) (Math.min(1f, mouth) * (on ? 255 : 215)));
                c.drawBitmap(avatarOpen, null, rf, mouthPaint);
            }
        }

        if (theme == 1 && on) { // rotating arcs around the portrait ring
            float r = avW / 2f * 1.01f;
            rf.set(avCx - r, avCy - r, avCx + r, avCy + r);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(3));
            p.setColor(0xCC9AA6FF);
            c.drawArc(rf, t * 60f, 55f + 40f * cur, false, p);
            p.setColor(0xAA7DF3E8);
            c.drawArc(rf, t * 60f + 180f, 40f + 30f * cur, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    private void drawSides(Canvas c, int[] cols, float[] freq, float[] spd, float widthDp, float ampMax, boolean on) {
        float amp = on ? ampMax * (0.16f + Math.min(1f, cur * 1.7f) + 0.07f * (float) Math.sin(t * 2f))
                : ampMax * 0.08f;
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(widthDp));
        for (int k = 0; k < cols.length; k++) {
            p.setColor(cols[k]);
            for (int side = -1; side <= 1; side += 2) {
                path.reset();
                float x0 = micX + side * micR * 1.05f;
                float x1 = side < 0 ? dp(4) : W - dp(4);
                for (int i = 0; i <= 64; i++) {
                    float u = i / 64f;
                    float x = x0 + (x1 - x0) * u;
                    float env = (float) Math.pow(Math.sin(Math.PI * (0.10 + 0.90 * u)), 1.2);
                    float dist = Math.abs(x - micX);
                    float y = micY + amp * env * (float) Math.sin(dist * freq[k] / d * 2f - t * spd[k] * side + k);
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                c.drawPath(path, p);
            }
        }
    }

    private void drawPanel(Canvas c) {
        // glass panel
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x26FFFFFF);
        rf.set(panelL, panelT, panelR, panelB);
        c.drawRoundRect(rf, dp(26), dp(26), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(rf, dp(26), dp(26), p);

        // bar waveform (mirrored from the centre, newest samples in the middle)
        boolean on = PriyaBus.running();
        float cy = panelT + 0.36f * (panelB - panelT);
        int n = 54;
        float left = panelL + dp(10), right = panelR - dp(10);
        float step = (right - left) / (n - 1);
        p.setShader(barShader);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(3));
        float maxH = (panelB - panelT) * 0.34f;
        for (int i = 0; i < n; i++) {
            int fromCentre = Math.abs(i - n / 2);
            float v = hist[(histPos - fromCentre + hist.length * 2) % hist.length];
            float wobble = 0.10f + 0.07f * (float) Math.sin(t * 3f + i * 0.7f);
            float h = dp(3) + maxH * Math.min(1f, (on ? v : 0f) + wobble * (on ? 1f : 0.4f))
                    * (0.35f + 0.65f * (1f - fromCentre / (float) (n / 2 + 1)));
            float x = left + step * i;
            c.drawLine(x, cy - h, x, cy + h, p);
        }
        p.setShader(null);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    // ------------------------------------------------------------------ mic buttons

    private int stateColor(int base) {
        return PriyaBus.state == PriyaBus.ERROR ? Ui.BAD : base;
    }

    private void drawMic0(Canvas c, boolean on) {
        float pulse = 1f + (on ? 0.08f * cur + 0.015f * (float) Math.sin(t * 3f) : 0f);
        int cyan = stateColor(0xFF5CE1FF);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF07162B);
        c.drawCircle(micX, micY, micR * 0.95f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(7));
        p.setColor(Ui.withAlpha(cyan, 55));
        c.drawCircle(micX, micY, micR * pulse, p);
        p.setStrokeWidth(dp(2));
        p.setColor(on ? cyan : Ui.withAlpha(cyan, 140));
        c.drawCircle(micX, micY, micR * pulse, p);
        p.setStrokeWidth(dp(1.5f));
        p.setColor(Ui.withAlpha(GOLD, on ? 230 : 120));
        c.drawCircle(micX, micY, micR * 0.82f, p);
        p.setStrokeWidth(dp(1));
        p.setColor(Ui.withAlpha(cyan, 110));
        c.drawCircle(micX, micY, micR * 0.62f, p);
        // equaliser ticks on the upper arc
        int n = 26;
        p.setStrokeWidth(dp(2));
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(-160 + i * (140f / (n - 1)));
            float v = on ? hist[(histPos - i + hist.length * 2) % hist.length] : 0f;
            float r0 = micR * 1.12f, r1 = r0 + dp(3) + v * dp(14);
            p.setColor(i % 3 == 0 ? Ui.withAlpha(GOLD, 220) : Ui.withAlpha(cyan, 200));
            c.drawLine(micX + (float) Math.cos(a) * r0, micY + (float) Math.sin(a) * r0,
                    micX + (float) Math.cos(a) * r1, micY + (float) Math.sin(a) * r1, p);
        }
        drawMicIcon(c, micX, micY, micR * 0.95f, cyan, dp(2.6f));
    }

    private void drawMic1(Canvas c, boolean on) {
        int cyan = stateColor(0xFF7DF3E8);
        float pulse = 1f + (on ? 0.05f * cur : 0f);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2.5f));
        p.setColor(Ui.withAlpha(0xFF6C8CFF, on ? 170 : 90));
        c.drawCircle(micX, micY, micR * 1.22f * pulse, p);
        if (on) {
            rf.set(micX - micR * 1.22f, micY - micR * 1.22f, micX + micR * 1.22f, micY + micR * 1.22f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(3.5f));
            p.setColor(0xFF8FE9FF);
            c.drawArc(rf, t * 90f, 70f, false, p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }
        p.setStrokeWidth(dp(1.2f));
        p.setColor(Ui.withAlpha(0xFF6C8CFF, 120));
        float ro = micR * 1.42f;
        c.drawCircle(micX, micY, ro, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.withAlpha(0xFF8FA4FF, 200));
        c.drawCircle(micX, micY - ro, dp(4), p);
        c.drawCircle(micX, micY + ro, dp(4), p);
        c.drawCircle(micX - ro, micY, dp(4), p);
        c.drawCircle(micX + ro, micY, dp(4), p);

        fillPaint.setShader(micFillShader);
        fillPaint.setAlpha(on ? 255 : 170);
        c.drawCircle(micX, micY, micR * 0.86f * pulse, fillPaint);
        fillPaint.setShader(null);
        drawMicIcon(c, micX, micY, micR * 0.95f, on ? 0xFF243044 : 0xFF3A465C, dp(2.8f));
    }

    private void drawMic2(Canvas c, boolean on) {
        float pulse = 1f + (on ? 0.07f * cur + 0.012f * (float) Math.sin(t * 3f) : 0f);
        int violet = stateColor(0xFFB07BFF);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(8));
        p.setColor(Ui.withAlpha(violet, 50));
        c.drawCircle(micX, micY, micR * 1.05f * pulse, p);
        p.setStrokeWidth(dp(2.6f));
        p.setColor(on ? 0xFFC9A0FF : 0xAAC9A0FF);
        c.drawCircle(micX, micY, micR * pulse, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1B2040);
        c.drawCircle(micX, micY, micR * 0.86f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.6f));
        p.setColor(0xFF6C7DFF);
        c.drawCircle(micX, micY, micR * 0.86f, p);
        drawMicIcon(c, micX, micY, micR * 0.9f, violet, dp(2.4f));
    }

    private void drawMicIcon(Canvas c, float cx, float cy, float s, int color, float stroke) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(stroke);
        p.setColor(color);
        rf.set(cx - s * 0.17f, cy - s * 0.42f, cx + s * 0.17f, cy + s * 0.06f);
        c.drawRoundRect(rf, s * 0.17f, s * 0.17f, p);
        rf.set(cx - s * 0.30f, cy - s * 0.24f, cx + s * 0.30f, cy + s * 0.22f);
        c.drawArc(rf, 0f, 180f, false, p);
        c.drawLine(cx, cy + s * 0.22f, cx, cy + s * 0.38f, p);
        c.drawLine(cx - s * 0.15f, cy + s * 0.38f, cx + s * 0.15f, cy + s * 0.38f, p);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    // ------------------------------------------------------------------ top bar

    private void drawTop(Canvas c) {
        int col = theme == 0 ? 0xFF7DD3EC : (theme == 1 ? 0xFF9AA0B3 : 0xFF8FA4FF);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(3));
        p.setColor(col);
        float x0 = menuL + dp(12), y0 = menuT + dp(15);
        float w1 = dp(28), w2 = theme == 1 ? dp(28) : dp(28), w3 = theme == 1 ? dp(17) : dp(28);
        c.drawLine(x0, y0, x0 + w1, y0, p);
        c.drawLine(x0, y0 + dp(9), x0 + w2, y0 + dp(9), p);
        c.drawLine(x0, y0 + dp(18), x0 + w3, y0 + dp(18), p);
        p.setStrokeCap(Paint.Cap.BUTT);

        if (theme == 1) {
            // gear button
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x33FFFFFF);
            c.drawCircle(gearX, gearY, iconR, p);
            p.setColor(0xFF8FD8F0);
            for (int i = 0; i < 8; i++) {
                c.save();
                c.rotate(i * 45f, gearX, gearY);
                rf.set(gearX - iconR * 0.10f, gearY - iconR * 0.62f, gearX + iconR * 0.10f, gearY - iconR * 0.38f);
                c.drawRect(rf, p);
                c.restore();
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(4));
            c.drawCircle(gearX, gearY, iconR * 0.26f, p);
            p.setStyle(Paint.Style.FILL);
            // profile button
            p.setColor(0x33FFFFFF);
            c.drawCircle(profX, profY, iconR, p);
            p.setColor(0xFF7DF3E8);
            c.drawCircle(profX, profY - iconR * 0.22f, iconR * 0.26f, p);
            rf.set(profX - iconR * 0.55f, profY + iconR * 0.10f, profX + iconR * 0.55f, profY + iconR * 1.0f);
            c.save();
            c.clipRect(profX - iconR, profY - iconR, profX + iconR, profY + iconR * 0.95f);
            c.drawArc(rf, 180f, 180f, true, p);
            c.restore();
        }
    }

    // ------------------------------------------------------------------ texts

    private StaticLayout layoutFor(String s, int w, float sp, int color, boolean bold, int maxLines) {
        tp.setTextSize(dp(sp));
        tp.setColor(color);
        tp.setFakeBoldText(bold);
        return StaticLayout.Builder.obtain(s, 0, s.length(), tp, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    private void drawTexts(Canvas c, int st) {
        int w = (int) (W * 0.86f);
        float x = (W - w) / 2f;
        float y = textTop;
        boolean err = st == PriyaBus.ERROR;

        String s = PriyaBus.status == null ? "" : PriyaBus.status;
        int col = err ? Ui.BAD : ACCENT[theme];
        String k = s + "|" + col + "|" + w;
        if (!k.equals(stKey) || stLayout == null) {
            stLayout = layoutFor(s, w, err ? 13f : 15f, col, true, err ? 5 : 2);
            stKey = k;
        }
        y = drawLayout(c, stLayout, x, y) + dp(6);

        if (!PriyaBus.running()) return;
        String u = PriyaBus.userText == null ? "" : PriyaBus.userText;
        if (!u.isEmpty()) {
            String ku = u + "|" + w;
            if (!ku.equals(usKey) || usLayout == null) {
                usLayout = layoutFor("Aap: " + u, w, 12.5f, 0xAAFFFFFF, false, 2);
                usKey = ku;
            }
            y = drawLayout(c, usLayout, x, y) + dp(4);
        }
        String pr = PriyaBus.priyaText == null ? "" : PriyaBus.priyaText;
        if (!pr.isEmpty()) {
            String kp = pr + "|" + w;
            if (!kp.equals(prKey) || prLayout == null) {
                prLayout = layoutFor("Priya: " + pr, w, 14f, 0xFFFFFFFF, false, 3);
                prKey = kp;
            }
            drawLayout(c, prLayout, x, y);
        }
    }

    private float drawLayout(Canvas c, StaticLayout sl, float x, float y) {
        c.save();
        c.translate(x, y);
        sl.draw(c);
        c.restore();
        return y + sl.getHeight();
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
            float x = e.getX(), y = e.getY();
            if (x >= menuL && x <= menuL + menuSize + dp(8) && y >= menuT && y <= menuT + menuSize + dp(8)) {
                listener.onMenu();
                performClick();
                return true;
            }
            if (theme == 1) {
                if (dist(x, y, gearX, gearY) <= iconR * 1.3f) { listener.onGear(); performClick(); return true; }
                if (dist(x, y, profX, profY) <= iconR * 1.3f) { listener.onProfile(); performClick(); return true; }
            }
            if (dist(x, y, micX, micY) <= micR * 1.45f) {
                listener.onMic();
                performClick();
                return true;
            }
        }
        return true;
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
