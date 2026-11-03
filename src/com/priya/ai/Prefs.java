package com.priya.ai;

import android.content.Context;
import android.content.SharedPreferences;

/** Simple SharedPreferences wrapper. API key is stored only on this phone. */
public final class Prefs {
    /** Gemini Live models, newest first. The service auto-falls-back through this list. */
    public static final String[] MODELS = {
            "gemini-3.8-live",
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025"
    };
    /** Female prebuilt Gemini voices. */
    public static final String[] VOICES = {"Sulafat", "Aoede", "Kore", "Leda", "Zephyr", "Autonoe", "Achernar", "Vindemiatrix"};
    public static final String[] THEME_NAMES = {"Hologram", "Cyber", "Anime"};

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("priya_prefs", Context.MODE_PRIVATE);
    }

    public static String apiKey(Context c) { return sp(c).getString("api_key", ""); }
    public static void setApiKey(Context c, String v) { sp(c).edit().putString("api_key", v == null ? "" : v.trim()).apply(); }

    public static String model(Context c) { return sp(c).getString("model", MODELS[0]); }
    public static void setModel(Context c, String v) { sp(c).edit().putString("model", v.trim()).apply(); }

    public static String lastGoodModel(Context c) { return sp(c).getString("good_model", ""); }
    public static boolean lastGoodFull(Context c) { return sp(c).getBoolean("good_full", true); }
    public static void setLastGood(Context c, String model, boolean full) {
        sp(c).edit().putString("good_model", model).putBoolean("good_full", full).apply();
    }

    public static String voice(Context c) { return sp(c).getString("voice", VOICES[0]); }
    public static void setVoice(Context c, String v) { sp(c).edit().putString("voice", v).apply(); }

    public static int theme(Context c) { return sp(c).getInt("theme", 0); }
    public static void setTheme(Context c, int t) { sp(c).edit().putInt("theme", t).apply(); }

    public static boolean alwaysOn(Context c) { return sp(c).getBoolean("always_on", true); }
    public static void setAlwaysOn(Context c, boolean v) { sp(c).edit().putBoolean("always_on", v).apply(); }

    /** While Priya is speaking the mic is not streamed (no echo loop). */
    public static boolean strictEcho(Context c) { return sp(c).getBoolean("strict_echo", true); }
    public static void setStrictEcho(Context c, boolean v) { sp(c).edit().putBoolean("strict_echo", v).apply(); }

    public static boolean forceSpeaker(Context c) { return sp(c).getBoolean("force_speaker", true); }
    public static void setForceSpeaker(Context c, boolean v) { sp(c).edit().putBoolean("force_speaker", v).apply(); }

    public static String userName(Context c) { return sp(c).getString("user_name", ""); }
    public static void setUserName(Context c, String v) { sp(c).edit().putString("user_name", v == null ? "" : v.trim()).apply(); }

    /** 1..10, higher = picks up quieter voices. */
    public static int sensitivity(Context c) { return sp(c).getInt("sensitivity", 5); }
    public static void setSensitivity(Context c, int v) { sp(c).edit().putInt("sensitivity", v).apply(); }

    /** Owner's voice pitch (Hz) recorded in Settings; 0 = not recorded. */
    public static float voiceF0(Context c) { return sp(c).getFloat("voice_f0", 0f); }
    public static void setVoiceF0(Context c, float v) { sp(c).edit().putFloat("voice_f0", v).apply(); }
    public static boolean voiceLock(Context c) { return sp(c).getBoolean("voice_lock", false); }
    public static void setVoiceLock(Context c, boolean v) { sp(c).edit().putBoolean("voice_lock", v).apply(); }

    public static boolean floatingFace(Context c) { return sp(c).getBoolean("floating_face", true); }
    public static void setFloatingFace(Context c, boolean v) { sp(c).edit().putBoolean("floating_face", v).apply(); }

    public static String lastCrash(Context c) { return sp(c).getString("last_crash", ""); }
    public static void setLastCrash(Context c, String v) { sp(c).edit().putString("last_crash", v == null ? "" : v).commit(); }
}
