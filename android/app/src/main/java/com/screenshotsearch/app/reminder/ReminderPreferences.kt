package com.screenshotsearch.app.reminder

import android.content.Context
import androidx.core.content.edit

class ReminderPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var enabled: Boolean
        get() = preferences.getBoolean(ENABLED_KEY, false)
        set(value) = preferences.edit { putBoolean(ENABLED_KEY, value) }

    /**
     * Son bildirim turunda secilen kayit kimlikleri.
     *
     * Ana ekran bunlari birebir gosterir; bildirimin vaadi ile uygulamanin gosterdigi
     * icerik ayni olsun diye. Bos liste "henuz tur yok" demektir.
     */
    var lastRoundIds: List<Long>
        get() = preferences.getString(ROUND_IDS_KEY, null)
            ?.split(",")
            ?.mapNotNull(String::toLongOrNull)
            .orEmpty()
        set(value) = preferences.edit {
            putString(ROUND_IDS_KEY, value.joinToString(","))
        }

    /** Son bildirim turunun zamani; tur bayatlayinca ana ekran politikaya doner. */
    var lastRoundAt: Long
        get() = preferences.getLong(ROUND_AT_KEY, 0L)
        set(value) = preferences.edit { putLong(ROUND_AT_KEY, value) }

    private companion object {
        const val PREFERENCES_NAME = "screenshot_search_reminders"
        const val ENABLED_KEY = "weekly_reminder_enabled"
        const val ROUND_IDS_KEY = "last_round_ids"
        const val ROUND_AT_KEY = "last_round_at"
    }
}
