package com.tingyun.smartmistakebook.core.visual.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build

enum class TutorVisual3DMode {
    FILAMENT,
    KEY_VIEW_2_5D,
}

data class TutorVisualRenderProfile(
    val threeDimensionalMode: TutorVisual3DMode,
    val targetFrameRate: Int,
)

object TutorVisualRenderProfileResolver {
    fun resolve(context: Context): TutorVisualRenderProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configuration = activityManager.deviceConfigurationInfo
        val supportsGles3 = configuration.reqGlEsVersion >= GLES_3
        val lowPerformance = activityManager.isLowRamDevice ||
            activityManager.memoryClass < MIN_MEMORY_CLASS_MB ||
            !supportsGles3 ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        return if (lowPerformance) {
            TutorVisualRenderProfile(
                threeDimensionalMode = TutorVisual3DMode.KEY_VIEW_2_5D,
                targetFrameRate = 30,
            )
        } else {
            TutorVisualRenderProfile(
                threeDimensionalMode = TutorVisual3DMode.FILAMENT,
                targetFrameRate = 30,
            )
        }
    }

    private const val GLES_3 = 0x00030000
    private const val MIN_MEMORY_CLASS_MB = 192
}
