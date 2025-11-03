package com.example.camerax_mlkit

import android.content.Context
import androidx.core.content.edit   // 👈 KTX
import java.util.UUID

object SessionIdProvider {
    private const val PREF = "session_id_pref"
    private const val KEY_ID = "sid"
    private const val KEY_EXPIRES_AT = "sid_expires_at"
    /** 세션 ID 유효기간: 24시간 */
    private const val TTL_MILLIS = 24L * 60 * 60 * 1000

    /** 현재 유효한 세션ID를 반환. 만료되면 새로 생성 */
    fun get(context: Context): String {
        val sp = context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)

        val now = System.currentTimeMillis()
        val cur = sp.getString(KEY_ID, null)
        val exp = sp.getLong(KEY_EXPIRES_AT, 0L)
        if (cur != null && now < exp) return cur

        val fresh = UUID.randomUUID().toString()
        sp.edit {
            putString(KEY_ID, fresh)
            putLong(KEY_EXPIRES_AT, now + TTL_MILLIS)
        }
        return fresh
    }

    /** 필요 시 수동 회전(강제 갱신) */
    @Suppress("unused") // 프로젝트에서 직접 호출하지 않으면 린트 억제
    fun rotate(context: Context): String {
        val sp = context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)

        val fresh = UUID.randomUUID().toString()
        sp.edit {
            putString(KEY_ID, fresh)
            putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + TTL_MILLIS)
        }
        return fresh
    }
}
