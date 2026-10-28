package com.sachin.chhotu

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Phone control ki chaabi. Sirf user ke bolne par global actions chalata hai. */
class ChhotuAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: ChhotuAccessibilityService? = null
            private set

        fun isOn(): Boolean = instance != null

        fun global(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }
}
