package com.priya.ai;

/** Tiny in-process state holder: service writes, UI reads every frame. */
public final class PriyaBus {
    public static final int OFF = 0, CONNECTING = 1, LISTENING = 2, THINKING = 3, SPEAKING = 4, ERROR = 5;

    public static volatile int state = OFF;
    public static volatile float micLevel = 0f;   // 0..1
    public static volatile float outLevel = 0f;   // 0..1  (how loud her voice is right now = jaw opening)
    /** Mouth shape estimate from her voice (0..1): spread lips / teeth (aa, e, s, sh) and rounded lips (o, u). */
    public static volatile float mouthWide = 0f, mouthRound = 0f;
    /** Voice spectrum at the playhead (VoiceBands shares, ~80 ms window): 80-400 Hz, F1 400-1100, F2 1100-2800,
     *  fricatives 2800-7000. The 3D head maps them to A / O-U / E-I / M-B-P mouth shapes. */
    public static volatile float band0 = 0f, band1 = 0f, band2 = 0f, band3 = 0f;
    public static volatile boolean userTalking = false;
    public static volatile String status = "Mic dabao aur Maya se baat karo";
    public static volatile String userText = "";
    public static volatile String priyaText = "";
    public static volatile String echoInfo = "";
    /** True while one of Priya's own screens is on top (then the floating face hides). */
    public static volatile boolean appVisible = false;

    private PriyaBus() {}

    public static boolean running() {
        return state != OFF && state != ERROR;
    }
}
