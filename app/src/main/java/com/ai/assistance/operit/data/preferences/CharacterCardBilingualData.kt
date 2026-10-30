package com.ai.assistance.operit.data.preferences

import android.content.Context

/**
 * 默认角色卡提示词的双语数据
 */
object CharacterCardBilingualData {

    // Chhotu Master: the default character is "Chhotu", Sachin's warm Hindi/Hinglish assistant.
    // These defaults are used for every UI language so the persona never falls back to Operit.

    /** 获取默认角色卡描述 */
    @Suppress("UNUSED_PARAMETER")
    fun getDefaultDescription(context: Context): String {
        return "Chhotu - Sachin ka apna Hindi/Hinglish voice assistant (online + offline)"
    }

    /** 获取默认角色设定 */
    @Suppress("UNUSED_PARAMETER")
    fun getDefaultCharacterSetting(context: Context): String {
        return CHHOTU_CHARACTER_SETTING
    }

    /** 获取默认其他内容（聊天） */
    @Suppress("UNUSED_PARAMETER")
    fun getDefaultOtherContentChat(context: Context): String {
        return CHHOTU_SAFETY_RULES
    }

    /** 获取默认其他内容（语音） */
    @Suppress("UNUSED_PARAMETER")
    fun getDefaultOtherContentVoice(context: Context): String {
        return CHHOTU_VOICE_RULES + "\n\n" + CHHOTU_SAFETY_RULES
    }

    const val CHHOTU_CHARACTER_SETTING: String =
        "Tum Chhotu ho - Sachin ke apne, garamjoshi bhare (warm), samajhdar aur thode mazakiya AI assistant. " +
            "Tum Sachin se dost ki tarah baat karte ho, izzat ke saath, 'aap' bolkar. " +
            "Language rule: jis bhasha mein user bole usi mein jawab do - Hindi (Devanagari) mein bole to Hindi mein, " +
            "Hinglish (Roman Hindi) mein bole to Hinglish mein, English mein bole to English mein. " +
            "Jawab chhote, saaf aur bolne layak rakho (1-3 vakya), jab tak user detail na maange. " +
            "Agar koi baat pata nahi to seedha bolo 'mujhe pakka nahi pata', kuch bhi mat banao."

    const val CHHOTU_VOICE_RULES: String =
        "Voice mode: tum bol kar jawab de rahe ho. Sirf 1-2 chhote, natural vakya bolo. " +
            "Markdown, bullet, emoji, code, table, link mat likho - sirf saaf bolne layak text. " +
            "Numbers aur time ko bolne layak likho. Ek baar mein ek hi sawal poochho."

    const val CHHOTU_SAFETY_RULES: String =
        "Suraksha niyam (hamesha): call karna, SMS/WhatsApp ya koi message bhejna, payment/UPI/paise bhejna, " +
            "koi file delete karna, app install/uninstall karna, ya phone ki system settings badalna - " +
            "inme se kuch bhi karne se PEHLE user se saaf poochho ('Kya main ... kar doon? Haan ya na?') " +
            "aur sirf saaf 'haan' milne par hi karo. Kabhi bhi apne aap paise mat bhejo. " +
            "Passwords, OTP, PIN kabhi mat maango aur kisi ko mat bhejo. Memory kabhi delete mat karo."

    /** Short system prompt used when Chhotu answers with the on-device (offline) model. */
    const val CHHOTU_OFFLINE_SYSTEM_PROMPT: String =
        "Tum Chhotu ho, Sachin ke warm aur helpful assistant. Abhi tum phone par offline chal rahe ho. " +
            "Jis bhasha mein user bole (Hindi, Hinglish ya English) usi mein jawab do. " +
            "Hamesha 1-2 chhote, saaf, bolne layak vakya. Markdown ya emoji nahi. " +
            "Pata na ho to seedha bolo. Call, message, payment, delete, install jaise kaam se pehle user se 'haan' poochho."

    /**
     * 获取角色描述标签
     */
    fun getCharacterDescriptionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "角色描述："
        } else {
            "Character Description:"
        }
    }

    /**
     * 获取性格特征标签
     */
    fun getPersonalityLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "性格特征："
        } else {
            "Personality:"
        }
    }

    /**
     * 获取场景设定标签
     */
    fun getScenarioLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "场景设定："
        } else {
            "Scenario Setting:"
        }
    }

    /**
     * 获取对话示例标签
     */
    fun getDialogueExampleLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "对话示例："
        } else {
            "Dialogue Examples:"
        }
    }

    /**
     * 获取系统提示词标签
     */
    fun getSystemPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "系统提示词："
        } else {
            "System Prompt:"
        }
    }

    /**
     * 获取历史指令标签
     */
    fun getPostHistoryInstructionsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "历史指令："
        } else {
            "Post-History Instructions:"
        }
    }

    /**
     * 获取备用问候语标签
     */
    fun getAlternateGreetingsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "备用问候语："
        } else {
            "Alternate Greetings:"
        }
    }

    /**
     * 获取深度提示词标签
     */
    fun getDepthPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "深度提示词："
        } else {
            "Depth Prompt:"
        }
    }

    /**
     * 获取世界书标签名称模板
     */
    fun getWorldBookTagName(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "世界书: $characterName"
        } else {
            "World Book: $characterName"
        }
    }

    /**
     * 获取世界书标签描述模板
     */
    fun getWorldBookTagDescription(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "为角色'$characterName'自动生成的世界书。"
        } else {
            "World book auto-generated for character '$characterName'."
        }
    }

    /**
     * 获取来源标签
     */
    fun getSourceLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "来源：酒馆角色卡\n"
        } else {
            "Source: Tavern Character Card\n"
        }
    }

    /**
     * 获取作者标签
     */
    fun getAuthorLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者："
        } else {
            "Author:"
        }
    }

    /**
     * 获取作者备注标签
     */
    fun getAuthorNotesLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者备注：\n\n"
        } else {
            "Author Notes:\n\n"
        }
    }

    /**
     * 获取版本标签
     */
    fun getVersionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "版本："
        } else {
            "Version:"
        }
    }

    /**
     * 获取原始标签标签
     */
    fun getOriginalTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "原始标签："
        } else {
            "Original Tags:"
        }
    }

    /**
     * 获取格式标签
     */
    fun getFormatLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "格式："
        } else {
            "Format:"
        }
    }

    /**
     * 获取标签标签
     */
    fun getTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "标签："
        } else {
            "Tags:"
        }
    }

    /**
     * 获取等标签
     */
    fun getEtAlLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "等"
        } else {
            " et al."
        }
    }

    /**
     * 获取未找到标签
     */
    fun getNotFoundLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "未找到"
        } else {
            "not found"
        }
    }

    /**
     * 检查是否为中文语言环境
     */
    private fun isChineseLocale(context: Context): Boolean {
        val locale = context.resources.configuration.locales.get(0)
        return locale.language == "zh" || locale.language == "zho"
    }
}
