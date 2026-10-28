package com.sachin.chhotu

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings

/** Ek command ka natija. */
sealed class Outcome {
    data class Say(val text: String) : Outcome()
    data class ConfirmCall(val name: String, val number: String) : Outcome()
    data class NeedPermission(val permission: String, val text: String) : Outcome()
    object AskCloud : Outcome()
}

/**
 * Bina internet ke chalne wale Hindi/Hinglish commands.
 * Jo samajh na aaye, wo online dimaag (CloudBrain) ke paas jata hai.
 */
class CommandRouter(private val context: Context) {

    private val memory = MemoryStore.get(context)

    fun handle(raw: String): Outcome {
        val t = normalize(raw)

        // 1. Yaad rakhna
        if (has(t, "याद रख", "yaad rakh", "yad rakh", "remember")) {
            val fact = strip(raw, "याद रखना", "याद रखो", "याद रख", "yaad rakhna", "yaad rakho", "yaad rakh", "yad rakh", "remember", "कि", "ki", "that").trim()
            if (fact.length < 2) return Outcome.Say("क्या याद रखूँ भाई? जैसे बोलो: याद रख कि मेरी बहन का जन्मदिन 5 मार्च को है।")
            memory.add("fact", fact)
            return Outcome.Say("पक्का याद रख लिया: \"$fact\"। ये कभी नहीं मिटेगा।")
        }
        if (has(t, "क्या याद", "kya yaad", "kya yad", "what do you remember", "याद है")) {
            val facts = memory.recent("fact", 10)
            if (facts.isEmpty()) return Outcome.Say("अभी तक तुमने मुझे कुछ याद रखने को नहीं कहा।")
            return Outcome.Say("मुझे ये याद है: " + facts.joinToString("। ") { it.text })
        }

        // 2. Torch
        if (has(t, "टॉर्च", "टार्च", "torch", "flashlight", "flash light")) {
            val off = has(t, "बंद", "band", "off", "bujha", "बुझा")
            return Outcome.Say(torch(!off))
        }

        // 3. Alarm
        if (has(t, "अलार्म", "alarm")) return alarm(t)

        // 4. Call
        if (has(t, "कॉल", "काल", "call", "फोन लगा", "फ़ोन लगा", "phone laga", "phone kar", "फोन कर", "फ़ोन कर")) {
            val name = strip(raw, "को", "ko", "कॉल करो", "कॉल कर", "कॉल", "काल", "call karo", "call kar", "call", "फ़ोन लगाओ", "फोन लगाओ", "फ़ोन लगा", "फोन लगा", "phone lagao", "phone laga", "फोन करो", "फ़ोन करो", "phone karo", "करो", "karo", "please", "ज़रा", "jara", "zara").trim()
            if (name.isEmpty()) return Outcome.Say("किसको कॉल करूँ भाई?")
            val digits = name.filter { it.isDigit() || it == '+' }
            if (digits.length >= 6) return Outcome.ConfirmCall(digits, digits)
            if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                return Outcome.NeedPermission(android.Manifest.permission.READ_CONTACTS, "कॉन्टैक्ट देखने की इजाज़त दे दो, फिर दोबारा बोलना।")
            val c = findContact(name) ?: return Outcome.Say("\"$name\" नाम का कोई कॉन्टैक्ट नहीं मिला।")
            return Outcome.ConfirmCall(c.first, c.second)
        }

        // 5. Message
        if (has(t, "मैसेज", "मेसेज", "message", "sms", "msg")) {
            var rest = strip(raw, "मैसेज भेजो", "मैसेज करो", "मैसेज", "मेसेज", "message bhejo", "message karo", "message", "sms", "msg", "भेजो", "bhejo")
            var body = ""
            val splitters = listOf(" कि ", " ki ", " that ")
            for (s in splitters) {
                val i = rest.indexOf(s)
                if (i >= 0) { body = rest.substring(i + s.length).trim(); rest = rest.substring(0, i); break }
            }
            val name = strip(rest, "को", "ko").trim()
            if (name.isEmpty()) return Outcome.Say("किसको मैसेज भेजूँ?")
            var number = name.filter { it.isDigit() || it == '+' }
            var shown = name
            if (number.length < 6) {
                if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                    return Outcome.NeedPermission(android.Manifest.permission.READ_CONTACTS, "कॉन्टैक्ट देखने की इजाज़त दे दो, फिर दोबारा बोलना।")
                val c = findContact(name) ?: return Outcome.Say("\"$name\" नाम का कोई कॉन्टैक्ट नहीं मिला।")
                shown = c.first; number = c.second
            }
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(i)
                Outcome.Say("$shown के लिए मैसेज तैयार है, देख कर Send दबा देना।")
            } catch (e: Exception) { Outcome.Say("मैसेज ऐप नहीं खुला।") }
        }

        // 6. Settings
        val act = has(t, "खोल", "khol", "open", "सेटिंग", "setting", "चालू", "chalu", "बंद", "band", " on", " off", "दिखा", "dikha")
        if (act && has(t, "वाईफाई", "वाई फाई", "वाईफ़ाई", "wifi", "wi-fi", "wi fi")) {
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            return openSettings(action, "वाई-फ़ाई की सेटिंग खोल दी।")
        }
        if (act && has(t, "ब्लूटूथ", "bluetooth")) return openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "ब्लूटूथ की सेटिंग खोल दी।")
        if (act && has(t, "इंटरनेट", "मोबाइल डेटा", "data", "internet")) {
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            return openSettings(action, "इंटरनेट की सेटिंग खोल दी।")
        }
        if (act && has(t, "आवाज़", "आवाज", "volume", "वॉल्यूम")) {
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            return openSettings(action, "आवाज़ की सेटिंग खोल दी।")
        }
        if (has(t, "सेटिंग", "setting")) return openSettings(Settings.ACTION_SETTINGS, "सेटिंग खोल दी।")

        // 7. Global actions (Accessibility)
        if (has(t, "वापस", "wapas", "vapas", "go back", "पीछे")) return global(AccessibilityService.GLOBAL_ACTION_BACK, "वापस चला गया।")
        if (has(t, "होम", "home screen", "home par", "home pe", "go home")) return global(AccessibilityService.GLOBAL_ACTION_HOME, "होम पर आ गए।")
        if (has(t, "नोटिफिकेशन", "नोटिफ़िकेशन", "notification")) return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "नोटिफ़िकेशन खोल दिए।")
        if (has(t, "recent", "रीसेंट", "हाल के ऐप")) return global(AccessibilityService.GLOBAL_ACTION_RECENTS, "हाल के ऐप दिखा दिए।")

        // 8. App kholna
        if (has(t, "खोल", "खोलो", "kholo", "khol", "open", "चालू", "chalu", "start", "चला")) {
            val name = strip(raw, "खोलना", "खोलो", "खोल", "kholna", "kholo", "khol", "open", "चालू करो", "चालू", "chalu karo", "chalu", "start", "चलाओ", "चला", "ऐप", "app", "करो", "karo", "को", "ko", "please", "ज़रा", "zara").trim()
            if (name.isNotEmpty()) return openApp(name)
        }

        return Outcome.AskCloud
    }

    // ---------- helpers ----------

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[।,.!?]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun has(t: String, vararg words: String) = words.any { t.contains(it.lowercase()) }

    private fun strip(s: String, vararg words: String): String {
        var out = " " + s.replace(Regex("[।,.!?]"), " ") + " "
        for (w in words.sortedByDescending { it.length }) {
            out = out.replace(Regex("(?i)(^|\\s)" + Regex.escape(w) + "(?=\\s|$)"), " ")
        }
        return out.replace(Regex("\\s+"), " ").trim()
    }

    private val appAliases = mapOf(
        "व्हाट्सएप" to "whatsapp", "वाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "whatsapp" to "whatsapp",
        "यूट्यूब" to "youtube", "youtube" to "youtube",
        "इंस्टाग्राम" to "instagram", "insta" to "instagram", "इंस्टा" to "instagram",
        "कैमरा" to "camera", "camera" to "camera",
        "गैलरी" to "gallery", "फोटो" to "photos", "फ़ोटो" to "photos",
        "क्रोम" to "chrome", "गूगल" to "google",
        "फेसबुक" to "facebook", "फ़ेसबुक" to "facebook",
        "जीमेल" to "gmail", "मैप" to "maps", "मैप्स" to "maps",
        "प्ले स्टोर" to "play store", "प्लेस्टोर" to "play store",
        "कैलकुलेटर" to "calculator", "घड़ी" to "clock", "क्लॉक" to "clock",
        "फोन पे" to "phonepe", "फ़ोनपे" to "phonepe", "फोनपे" to "phonepe",
        "पेटीएम" to "paytm", "स्पॉटिफाई" to "spotify", "टेलीग्राम" to "telegram",
        "सेटिंग्स" to "settings", "कॉन्टैक्ट" to "contacts", "म्यूजिक" to "music"
    )

    private fun openApp(spoken: String): Outcome {
        val key = spoken.lowercase().trim()
        val target = appAliases[key] ?: appAliases.entries.firstOrNull { key.contains(it.key) }?.value ?: key
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == target }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(target) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(target.replace(" ", "")) }
            ?: return Outcome.Say("\"$spoken\" नाम का ऐप नहीं मिला भाई।")
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return Outcome.Say("ये ऐप खुल नहीं रहा।")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return Outcome.Say("${match.loadLabel(pm)} खोल दिया।")
    }

    private fun findContact(name: String): Pair<String, String>? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        context.contentResolver.query(
            uri, proj,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0) to c.getString(1)
        }
        return null
    }

    private fun torch(on: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "इस फ़ोन में फ़्लैश नहीं मिला।"
            cm.setTorchMode(id, on)
            if (on) "टॉर्च जला दी।" else "टॉर्च बंद कर दी।"
        } catch (e: Exception) {
            "टॉर्च में दिक़्क़त: ${e.message}"
        }
    }

    private fun alarm(t: String): Outcome {
        val nums = Regex("\\d+").findAll(hindiDigits(t)).map { it.value.toInt() }.toList()
        if (nums.isEmpty()) return Outcome.Say("कितने बजे का अलार्म लगाऊँ? जैसे बोलो: सुबह 6 बजे का अलार्म लगाओ।")
        var hour = nums[0]
        var minute = if (nums.size > 1) nums[1] else 0
        if (has(t, "साढ़े", "sadhe", "saadhe")) minute = 30
        if (has(t, "सवा", "sava", "sawa")) minute = 15
        if (has(t, "पौने", "paune")) { minute = 45; hour = (hour - 1 + 24) % 24 }
        val pm = has(t, "शाम", "shaam", "sham", "रात", "raat", "rat ", "दोपहर", "dopahar", "pm")
        if (pm && hour in 1..11) hour += 12
        if (hour > 23 || minute > 59) return Outcome.Say("ये टाइम समझ नहीं आया।")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Chhotu")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(i)
            Outcome.Say(String.format("%d:%02d का अलार्म लगा दिया।", hour, minute))
        } catch (e: Exception) { Outcome.Say("अलार्म ऐप नहीं मिला।") }
    }

    private fun hindiDigits(s: String): String {
        val dev = "०१२३४५६७८९"
        return s.map { ch -> val i = dev.indexOf(ch); if (i >= 0) ('0' + i) else ch }.joinToString("")
    }

    private fun openSettings(action: String, ok: String): Outcome = try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Outcome.Say(ok)
    } catch (e: Exception) {
        Outcome.Say("ये सेटिंग नहीं खुली।")
    }

    private fun global(action: Int, ok: String): Outcome {
        if (!ChhotuAccessibilityService.isOn())
            return Outcome.Say("इसके लिए Accessibility में Chhotu चालू करना होगा। ऊपर ⚙ में जाकर 'Accessibility चालू करो' दबाओ।")
        return Outcome.Say(if (ChhotuAccessibilityService.global(action)) ok else "ये नहीं हो पाया।")
    }
}
