package com.ai.assistance.operit.ui.permissions

import com.ai.assistance.operit.data.model.AITool

/**
 * Chhotu Master safety rules, applied on top of Operit's tool permission system.
 *
 * 1. Tools that run shell commands, terminals or arbitrary code are OFF by default. The user can
 *    still enable them per tool in Settings > Tool permissions if they really want to.
 * 2. Tools that can call, send messages, pay, delete, install/uninstall apps or change system
 *    settings always show the confirmation dialog. "Always allow" is never remembered for them.
 */
object ChhotuSafetyPolicy {

    private val offByDefaultTools = setOf(
        "execute_shell",
        "execute_hidden_terminal_command",
        "create_terminal_session",
        "execute_in_terminal_session",
        "execute_in_terminal_session_streaming",
        "input_in_terminal_session",
        "close_terminal_session",
        "get_terminal_session_screen",
        "execute_sandbox_script_direct",
        "browser_run_code",
        "browser_evaluate",
        "ffmpeg_execute",
        "write_environment_variable",
        "trigger_tasker_event",
    )

    private val alwaysConfirmTools = setOf(
        // calls / messages / intents that can dial, send SMS, open UPI links
        "execute_intent",
        "send_broadcast",
        "run_ui_subagent",
        "send_notification",
        // install / uninstall
        "install_app",
        "uninstall_app",
        // deleting data
        "delete_file",
        "delete_chat",
        "delete_memory",
        "delete_memory_link",
        "delete_workflow",
        "delete_character_card",
        "delete_model_config",
        // system settings
        "modify_system_setting",
        "write_environment_variable",
        // network uploads of the user's data
        "multipart_request",
        "browser_file_upload",
        "bluetooth_send",
        "share_file",
    )

    /** Package names of payment / UPI / messaging apps that must never be opened silently. */
    private val sensitiveAppPackages = setOf(
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "in.amazon.mShop.android.shopping",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.dreamplug.androidapp",
        "com.mobikwik_new",
        "com.freecharge.android",
        "com.sbi.upi",
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.axis.mobile",
    )

    fun isOffByDefault(toolName: String): Boolean = toolName in offByDefaultTools

    fun mustAlwaysConfirm(tool: AITool): Boolean {
        if (tool.name in alwaysConfirmTools) return true
        if (tool.name.startsWith("delete_") || tool.name.startsWith("uninstall")) return true
        if (tool.name == "start_app" || tool.name == "stop_app") {
            val pkg = tool.parameters.firstOrNull { it.name == "package_name" }?.value?.trim().orEmpty()
            if (pkg in sensitiveAppPackages) return true
        }
        val joinedParams = tool.parameters.joinToString(" ") { it.value }.lowercase()
        if (joinedParams.contains("upi://") ||
            joinedParams.contains("tel:") ||
            joinedParams.contains("smsto:") ||
            joinedParams.contains("sms:") ||
            joinedParams.contains("android.intent.action.call") ||
            joinedParams.contains("android.intent.action.sendto")
        ) {
            return true
        }
        return false
    }
}
