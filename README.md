# Priya AI v35 — Vivek ji ki apni AI assistant (Gemini Live)

Female voice mein baat karne wali assistant: phone control (Accessibility), YouTube/WhatsApp/Gmail, echo-cancel, hamesha sunna,
30 MB text memory, 3 avatar (3D / portrait), skills, schedules, Termux coding, faceless video — aur ab **khud ko upgrade** karna.

## Termux mein APK banana (pehli baar)

```bash
pkg update -y
pkg install -y openjdk-21 aapt2 apksigner d8 zip unzip python
termux-setup-storage                      # ek baar, storage allow karo

cd ~
cp /sdcard/Download/PriyaAI-v35.zip .
unzip -o PriyaAI-v35.zip && cd PriyaAI    # folder ka naam PriyaAI hi rakho (self-upgrade yahin se kaam karta hai)
bash build.sh
termux-open PriyaAI.apk                   # install (Unknown sources allow karna hoga)
```

Agar `openjdk-21` na mile to `pkg install -y openjdk-17`. `build.sh` khud sahi `android.jar` dhundhta hai (khaali/kharab jar skip karta hai)
aur zaroorat par download karta hai; kuch na mile to koi API 30-34 ka `android.jar` `tools/android.jar` naam se rakh do.
Compile error aaye to poora error text Priya ko / mujhe bhej do.

**Purana app hata kar naya lagana zaroori nahi** — signing key (`priya-release.jks`) wahi hai, to naya APK purane ke upar update ho jata hai
(memory, API key, voiceprint bachi rehti hai). Har build ka versionCode pichle se bada hota hai.

## Pehli baar setup

1. Menu (teen line) > **API (Gemini)** > key paste > Save > Connection test.
2. Menu > **Permissions** > "Saari runtime permissions ek saath do"; Accessibility ON; Display over other apps ON; Battery Unrestricted.
3. Menu > Settings > **Owner naam** = Vivek (default). Priya sabko "Vivek ji" hi bolegi.
4. **Voice Lock**: Menu > Settings > Voice Lock > "Meri awaaz enroll karo (5 lines)". Enroll ke baad Priya SIRF aapki awaaz par chalti hai;
   baaki ko bolti hai: *"Main sirf Vivek ji ki baat sunti hoon. Kripya aap apna kaam khud karein."* Aapki awaaz ka voiceprint dheere-dheere khud adapt hota hai.
   (Enroll se pehle lock kuch nahi kar sakta — isliye Priya pehli baar yaad dilati hai.)
5. Mic dabao aur bolo: *"Priya, YouTube par Tum Hi Ho chalao"*.

## v35 mein naya

| Cheez | Kya hua |
|---|---|
| **Khud ko upgrade** | "Priya, apne aap ko upgrade karo / ye feature jodo" bolo. Menu > 🔧 Self-upgrade mein poori jaankari. |
| **Turant rok-tok** | Priya bol rahi ho aur aap bolein to ~0.3 sec mein chup. Settings > Rok-tok: Dheere / Normal / **Turant** (default). Echo se khud ruk jaye to Normal karo. |
| **Fast reply** | Chhoti baat par jawab jaldi (end-of-speech pehle 560 ms tha, ab ~400 ms). |
| **Kaam karte hue baat** | Lamba kaam `background_task` se peeche chalta hai, Priya aapse baat karti rehti hai; khatam hone par report. "Ruko / cancel" bolo to `cancel_background`. |
| **Gehri soch** | Mushkil sawaal par `deep_think` (plan → solve → khud check → jawab). |
| **Lip-sync** | 3D avatar aur Portrait mein ab sirf muh khulna nahi, awaaz ke hisaab se hothon ki shape (chaura / gol) bhi badalti hai. (Awaaz ka andaza hai, phoneme-exact nahi.) |
| **Awaaz / behaviour** | Prompt naya: saans, ruk-ruk kar bolna, halki hansi, mood ke hisaab se pitch; insaan jaisa, kabhi robot jaisa nahi. |
| **Hamesha "Vivek"** | Purane text mein "Sachin" tha — ab har cheez Settings ke naam se chalti hai. |
| **Kabhi mana nahi** | Kaam ko steps mein todna, kami ho to research/Termux se khud poora karna (faceless video jaisa kaam bhi). |

## Self-upgrade kaise kaam karta hai

Priya ka source Termux mein `~/PriyaAI` mein hona chahiye (upar waale setup se wahin hai). Aap bolte hain *"Priya, apne aap ko upgrade karo, ... jodo"*, phir:

1. `self_status` — Termux/tools/source check.  2. `self_search` / `self_read` — sahi file dhundhna.
3. Ek line mein plan batana.  4. `self_edit` / `self_write` — code badalna (pehle backup snapshot).  5. `self_diff` — kya badla.
6. `self_build` — background mein build (error aaye to padh kar max 3 baar fix).
7. Build theek ho to Priya batati hai kya badla aur **aapki "haan" ka intezaar** karti hai.  8. "Haan" ke baad hi `self_install` — app restart hoti hai.
9. Kuch bigde to bolo *"purana wapas lao"* → `self_rollback` (backup wapas + naye version number se rebuild).

**Surakshit parte (Priya ko badalne nahi diye jate):** `ActionExecutor`, `HeadlessAgent`, `SelfUpdate`, `PriyaService`, `Owner`, `SpeakerVerifier`,
`TermuxBridge`, `InstallReceiver`, `build.sh`, `selfup.sh`, `align.py`, `priya-release.jks`. In sab ka SHA-256 `.guard.sha256` mein hai;
`selfup.sh` badli hui protected file par build/install **roak deta hai**. Aap khud in files ko badalna chahein to baad mein
`bash ~/PriyaAI/selfup.sh guard-reset` (sirf aap, haath se).
Naye code mein delete / shell / package-install jaisa kuch jodna bhi block hai. Install sirf PackageInstaller se, SHA-256 check ke saath, aur Android khud
us update ko mana kar deta hai jo isi key se sign na ho.

**Zaroori:** Menu > Permissions mein **Install unknown apps (self-upgrade)** ON karo, Termux mein `allow-external-apps = true`
(`~/.termux/termux.properties`) aur Termux:API/RUN_COMMAND permission jo pehle se coding ke liye lagi hai.

## Safety (hamesha laagu)

- Delete / remove / reset / uninstall / format / clear-data — kabhi nahi (code, Termux command, button dabana, sab par block). Owner bhi kahe to pyaar se mana.
- Payment / kharid / subscription / OTP — kabhi nahi. Message/email se pehle recipient verify; scheduled/background kaam sirf diye hue recipients ko.
- Background aur scheduled kaam sirf wahi tool-groups chalate hain jo aapne diye (phone / content / messages / coding); khud naya schedule ya skill nahi banate.
- Priya apni safety files ya apni signing key ko code se chhu bhi nahi sakti (`code_run`/`code_write_file` mein block).

## Kaise kaam karta hai (sankshep)

- **Awaaz (Live) model**: `gemini-3.8-live` → `gemini-3.8-live-extended-thinking` (khud fallback). Purane preview Live models (3.1 Flash Live, 2.5 native-audio) Google 17 Nov 2026 ko band kar raha hai, isliye hata diye.
- **Dimaag (soch, research, background kaam)**: `gemini-4-argon` (Gemini 4 Argon) sabse pehle. Argon Live/awaaz nahi karta, sirf text, aur sirf billing wali API key par chalta hai; na chale to khud `gemini-3.8-flash` par aa jaati hai. Voice **Kore** (female, fixed — sabse natural lagti hai).
- **Echo cancel**: `VOICE_COMMUNICATION` mic + AEC/NS/AGC; bolte waqt mic gate + adaptive barge-in (Priya apni awaaz se nahi rukti, aapki awaaz se turant rukti hai).
- **Hamesha sunna**: foreground service + wake lock; local VAD se sirf bolne par audio Gemini ko jata hai.
- **Memory (30 MB)**: `notes.txt` + `conversation.log` (text); `recall` se poori memory search.
- **Skills**: Menu > Skills — `.md` file import karke naya hunar jodo; Priya `create_skill` se bhi bana sakti hai. GrowthPilot skill pehle se hai.
- **Schedules**: "roz subah 9 baje ..." — headless chalta hai, notification mein result.

## Dhyan dene wali baatein (sach)

- Android 14+ background se microphone service start nahi hone deta: app khol kar mic dabao; uske baad background mein chalti rahegi.
- Self-upgrade mein install ke waqt Android ek "Update" confirm screen dikhata hai; Priya use accessibility se dabati hai (aapke "haan" ke baad). Na dabe to notification par tap karo.
- Voice Lock ek awaaz-pehchaan hai, fingerprint jitna pakka nahi: shor / bimar gala / bahut dheeme bolne par kabhi-kabhi aapko bhi reject kar sakta hai (Strictness Settings mein).
- Sab kuch phone par chalta hai par Gemini ko audio/text Google ke servers par jata hai; API key sirf is phone mein (app-private) hai.
- Signing key `priya-release.jks` ka backup rakho — kho gayi to naye key ka APK purane ke upar install nahi hoga.

## Files

```
AndroidManifest.xml            permissions, services, receivers
src/com/priya/ai/
  MainActivity / PanelActivity   main screen, hamburger menu, Permissions/API/Memory/Settings/Self-upgrade pages
  PriyaService                   mic -> Gemini Live -> speaker, system prompt, tool gate (protected)
  GeminiLiveClient / MiniWebSocket   Live API client + tool declarations
  AudioEngine                    echo-cancelled mic, VAD, barge-in, playback, lip-sync levels/shape
  SpeakerVerifier                voice lock (MFCC voiceprint) (protected)
  ActionExecutor / BrainTools    tools (phone, research, studio, background_task, deep_think ...)
  HeadlessAgent / PriyaJobs / Scheduler / TaskRunnerService   background + scheduled work
  SelfUpdate / InstallReceiver / Owner / TermuxBridge   self-upgrade, installer, owner name, Termux link (protected)
  Avatar3DView / LivePortraitView / FloatingFace / ThemeView   avatars and UIs
assets/   avatar images, persona_extra.md (extra behaviour), knowledge.txt, ai_tools_guide.md
selfup.sh  build.sh  align.py  .guard.sha256  priya-release.jks
```
