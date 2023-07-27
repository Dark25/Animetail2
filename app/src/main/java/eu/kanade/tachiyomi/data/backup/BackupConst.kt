package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID as ID

object BackupConst {

    private const val NAME = "BackupRestoreServices"
    const val EXTRA_URI = "$ID.$NAME.EXTRA_URI"

    // Filter options
    internal const val BACKUP_CATEGORY = 0x1
    internal const val BACKUP_CATEGORY_MASK = 0x1
    internal const val BACKUP_CHAPTER = 0x2
    internal const val BACKUP_CHAPTER_MASK = 0x2
    internal const val BACKUP_HISTORY = 0x4
    internal const val BACKUP_HISTORY_MASK = 0x4
    internal const val BACKUP_TRACK = 0x8
    internal const val BACKUP_TRACK_MASK = 0x8
    internal const val BACKUP_PREFS = 0x10
    internal const val BACKUP_PREFS_MASK = 0x10
    internal const val BACKUP_EXT_PREFS = 0x20
    internal const val BACKUP_EXT_PREFS_MASK = 0x20
    internal const val BACKUP_EXTENSIONS = 0x40
    internal const val BACKUP_EXTENSIONS_MASK = 0x40
    internal const val BACKUP_ALL = 0x7F

    // SY -->
    internal const val BACKUP_CUSTOM_INFO = 0x14
    internal const val BACKUP_CUSTOM_INFO_MASK = 0x14
    // SY <--
}
