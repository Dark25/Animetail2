package eu.kanade.tachiyomi.ui.player.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import eu.kanade.tachiyomi.ui.player.cast.components.SubtitleSettings
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat

class CastSubtitlePreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun fontSize() = preferenceStore.getFloat("pref_cast_subtitle_size", 20f)
    fun textColor() = preferenceStore.getInt("pref_cast_subtitle_color", Color.White.toArgb())
    fun backgroundColor() = preferenceStore.getInt("pref_cast_subtitle_background", Color.Transparent.toArgb())
    fun shadowRadius() = preferenceStore.getFloat("pref_cast_subtitle_shadow", 2f)

    fun saveTextTrackStyle(settings: SubtitleSettings) {
        try {
            preferenceStore.getFloat("pref_cast_subtitle_size", 20f).set(settings.fontSize.value)
            preferenceStore.getInt("pref_cast_subtitle_color", Color.White.toArgb()).set(settings.textColor.toArgb())
            preferenceStore.getInt("pref_cast_subtitle_background", Color.Transparent.toArgb())
                .set(settings.backgroundColor.toArgb())
            preferenceStore.getFloat("pref_cast_subtitle_shadow", 2f).set(settings.shadowRadius.value)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Error saving subtitle settings: ${e.message}" }
        }
    }
}
