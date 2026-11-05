package com.priya.ai;

/** Common face of the GL avatars (the 3D head {@link Maya3DHeadView} and the older {@link LivePortraitView}). */
public interface AvatarGL {
    /** Follow the avatar choice (0 Hologram, 1 Cyber, 2 Anime). */
    void setStyle(int style);

    /** Main screen: the full picture's rect in the view's pixels. */
    void setImageRect(float l, float t, float r, float b);

    /** Called on the main thread if GL breaks on this device (callers fall back). */
    void setOnGlFailed(Runnable r);
}
