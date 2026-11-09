package com.didi.dimina.ui.container

import android.app.ActivityManager
import android.graphics.Bitmap
import com.didi.dimina.bean.MiniProgramTaskAppearance

/** The Recents card of a mini program that owns its task (multi-task mode). */
internal object MiniProgramTaskDescription {
    /** The host's label, else the mini program's name; null leaves the card title unchanged. */
    fun label(appearance: MiniProgramTaskAppearance?, name: String): String? =
        appearance?.label?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim().takeIf { it.isNotEmpty() }

    /** TaskDescription rejects a translucent primary color; 0 means the system default. */
    fun primaryColor(color: Int): Int = if (color == 0) 0 else color or OPAQUE

    /**
     * The Bitmap constructor is deprecated since API 28 but still the only public way to give a
     * task a runtime-drawn icon: TaskDescription.Builder (API 33) accepts drawable resources only.
     */
    @Suppress("DEPRECATION")
    fun build(label: String, icon: Bitmap?, primaryColor: Int): ActivityManager.TaskDescription =
        ActivityManager.TaskDescription(label, icon, primaryColor)

    private const val OPAQUE = -0x1000000 // 0xFF000000
}
