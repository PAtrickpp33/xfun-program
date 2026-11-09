package com.didi.dimina.bean

import android.graphics.Bitmap
import androidx.annotation.ColorInt

/**
 * How a mini program's own task looks in Recents (the overview screen). Used only when
 * multi-task is enabled, where every mini program runs in a task of its own.
 *
 * @property label card title; null or blank falls back to [MiniProgram.name].
 * @property icon card icon; null shows the host app's launcher icon.
 * @property color card header color on Android versions that draw one; 0 keeps the default.
 *   Android requires an opaque color, so any alpha is replaced with full opacity.
 */
data class MiniProgramTaskAppearance(
    val label: String? = null,
    val icon: Bitmap? = null,
    @ColorInt val color: Int = 0,
)
