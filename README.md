# Priya AI — Android voice assistant (Gemini Live)

Female voice mein baat karne wala assistant: phone control (Accessibility), YouTube par gana, echo-cancel,
background mein hamesha sunna, aur 30 MB text memory (vaade/notes).

## Termux mein APK banana

```bash
pkg update -y
pkg install -y openjdk-21 aapt2 apksigner d8 zip unzip python
termux-setup-storage                      # ek baar, storage allow karo

cd ~
cp /sdcard/Download/PriyaAI.zip .         # zip jahan save ki ho wahan se
unzip -o PriyaAI.zip && cd PriyaAI
bash build.sh
```

Ban jaane par `PriyaAI.apk` isi folder mein milegi (aur `/sdcard/Download/PriyaAI.apk` mein bhi):

```bash
termux-open PriyaAI.apk                   # install (Unknown sources allow karna hoga)
```

`build.sh` khud `android.jar` dhundhta hai (`$PREFIX/share/aapt/android.jar`, `~/android-sdk`, `tools/android.jar`)
aur na mile to download karne ki koshish karta hai. Koi package "not found" bataye to `pkg search d8` / `pkg search aapt2`
chalao. Koi bhi API 30-34 ka `android.jar` `tools/android.jar` naam se rakh do to wahi use hoga.
Compile error aaye to poora error text mujhe bhej do, main fix kar dunga.

## Pehli baar setup (app khulne ke baad)

1. Hamburger menu (teen line) > **API (Gemini)** > key paste karo > **Save** > **Connection test karo**.
2. Menu > **Permissions** > "Saari runtime permissions ek saath do".
3. **Accessibility** card > Accessibility settings > Priya AI ON karo.
   Android 13+ par switch grey ho to: App settings (button usi page par hai) > upar ⋮ > **Allow restricted settings**, phir ON karo.
4. **Display over other apps** ON karo (background se apps/YouTube kholne ke liye) aur **Battery: Unrestricted** allow karo.
5. Main screen par **mic** dabao. "Priya sun rahi hai…" dikhe to bolo: *"Priya, YouTube par Tum Hi Ho chalao"*.

## UI designs (menu mein teeno)

Menu > UI DESIGN mein teen tiles: **Hologram**, **Cyber**, **Anime** — tap karte hi design badal jata hai
(aapke diye hue screenshots ke avatar + animated rings/waves/mic). App icon bhi aapki sphere image se bana hai.

## Kaise kaam karta hai

- **Model**: default `gemini-3.8-live`. Na chale to khud `gemini-3.1-flash-live-preview` aur
  `gemini-2.5-flash-native-audio-preview-12-2025` try karta hai; jo chala wo yaad rakhta hai. Model naam API page par badal sakte ho.
- **Voice**: female voices — Aoede (default), Kore, Leda, Zephyr, Autonoe, Sulafat.
- **Echo cancel**: mic `VOICE_COMMUNICATION` source par + AcousticEchoCanceler + NoiseSuppressor + AGC, aur Priya bolte waqt
  mic stream band (Settings mein "Echo guard"). Isse Priya apni awaaz nahi sunti.
- **Always listening**: foreground service + wake lock. Mic local VAD se filter hota hai — sirf jab koi bol raha ho tabhi
  audio Gemini ko jata hai (200 ms pre-roll ke saath), isliye battery/data/API kharcha kam rehta hai.
  Settings mein background listening OFF kar sakte ho (tab sirf app khula ho tab sunegi).
- **Memory (30 MB)**: `notes.txt` (vaade/facts — `remember` tool se) + `conversation.log` (transcript), dono plain text.
  Limit par sabse purani conversation hat jati hai, notes last mein. Session start par recent notes Priya ko mil jate hain,
  aur purani baat ke liye wo `recall` se poori memory search karti hai. Menu > Memory mein search/clear hai.
- **Phone control tools**: app kholna, YouTube play, home/back/recents/notifications/scroll, screen par tap, type, screen padhna,
  call (confirm ke baad), volume/mute, media next/prev, torch, Wi-Fi/Bluetooth settings, web search.

## Dhyan dene wali baatein

- YouTube par "pehla result tap" Accessibility se hota hai (content-description se video pehchanta hai). YouTube update ke baad
  kabhi na chale to Priya fallback tap karti hai; phir bhi na chale to batao, pattern badal denge.
- Android 14+ background se microphone service start nahi hone deta: Priya ko app khol kar mic dabake chalu karo;
  uske baad wo background mein chalti rahegi. Phone restart/process kill ke baad dobara mic dabana padega.
- "Hamesha sunna" ka matlab hai ki TV/doosre logon ki awaaz par bhi Priya jawab de sakti hai. Mic sensitivity Settings mein kam kar sakte ho.
- API key SharedPreferences mein is phone par save hoti hai (app-private). Key kisi ko share mat karo.
- Signing key `priya-release.jks` pehli build par ban jati hai (password `priyaai123`); update install karne ke liye wahi file rakhna.

## Files

```
AndroidManifest.xml          permissions, services, accessibility config
src/com/priya/ai/
  MainActivity.java          main screen + hamburger menu (3 designs, options)
  ThemeView.java             3 animated UI designs (canvas)
  PanelActivity.java         Permissions / API / Memory / Settings pages
  PriyaService.java          background service: mic -> Gemini Live -> speaker, reconnect, memory
  GeminiLiveClient.java      Gemini Live WebSocket protocol + tool declarations
  MiniWebSocket.java         RFC 6455 WebSocket client (no libraries)
  AudioEngine.java           echo-cancelled mic, VAD gate, playback
  PriyaAccessibilityService  tap/type/scroll/read-screen/global actions
  ActionExecutor.java        tool implementations (open app, YouTube, call...)
  MemoryStore.java           30 MB text memory
  Prefs.java, PriyaBus.java, Ui.java
assets/                      avatar images (av_0..2.webp), icon.webp
res/                         launcher icons, accessibility config, strings
build.sh, align.py           Termux build (aapt2 + javac + d8 + apksigner)
```
