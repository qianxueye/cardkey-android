package cc.cardkey.enrollment

import android.content.SharedPreferences
import androidx.core.content.edit

class EnrollmentSettings(private val preferences: SharedPreferences) {
    fun load(): String = preferences.getString("ca_origin", null)?.let {
        runCatching { enrollmentOrigin(it) }.getOrNull()
    }.orEmpty()

    fun save(address: String) {
        preferences.edit {
            if (address.isEmpty()) remove("ca_origin") else putString("ca_origin", address)
        }
    }
}
