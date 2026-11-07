package cc.cardkey.enrollment

import android.content.SharedPreferences

internal class EnrollmentFailingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
    var reject: (String?, String?) -> Boolean = { _, _ -> false }
    var failures = 0
        private set

    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        var rejected = false
        return object : SharedPreferences.Editor by editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                rejected = rejected || reject(key, value)
                editor.putString(key, value)
                return this
            }
            override fun commit(): Boolean {
                if (!rejected) return editor.commit()
                failures++
                return false
            }
        }
    }
}
