package com.priya.ai;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Android 11+ screenshot through the accessibility service (kept separate so older phones never load it). */
final class Shot {
    private Shot() {}

    static byte[] take(AccessibilityService svc, int maxWidth) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] out = {null};
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override public void onSuccess(AccessibilityService.ScreenshotResult r) {
                            HardwareBuffer hb = null;
                            try {
                                hb = r.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                                if (hw != null) out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                            } catch (Throwable ignored) {
                            } finally {
                                try { if (hb != null) hb.close(); } catch (Throwable ignored) { }
                                latch.countDown();
                            }
                        }
                        @Override public void onFailure(int errorCode) { latch.countDown(); }
                    });
            latch.await(5, TimeUnit.SECONDS);
        } catch (Throwable e) {
            return null;
        }
        Bitmap b = out[0];
        if (b == null) return null;
        try {
            if (b.getWidth() > maxWidth) {
                int h = Math.round(b.getHeight() * (maxWidth / (float) b.getWidth()));
                Bitmap s = Bitmap.createScaledBitmap(b, maxWidth, h, true);
                b.recycle();
                b = s;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 70, bos);
            b.recycle();
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }
}
