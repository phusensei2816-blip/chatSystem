package com.example.chatsystem

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.ds by preferencesDataStore("session")

class Store(private val ctx: Context) {
    private val URL = stringPreferencesKey("url"); private val TOKEN = stringPreferencesKey("token")
    private val UID = longPreferencesKey("uid"); private val NAME = stringPreferencesKey("name")
    private val DEV = stringPreferencesKey("device")

    /** device_id sinh một lần, giữ nguyên kể cả khi đăng xuất. */
    suspend fun deviceId(): String {
        ctx.ds.data.first()[DEV]?.let { return it }
        val id = UUID.randomUUID().toString()
        ctx.ds.edit { it[DEV] = id }
        return id
    }

    suspend fun load(): Session? {
        val p = ctx.ds.data.first()
        val token = p[TOKEN] ?: return null
        return Session(p[URL] ?: return null, token, p[UID] ?: return null, p[NAME] ?: return null, deviceId())
    }

    suspend fun save(s: Session) = ctx.ds.edit {
        it[URL] = s.baseUrl; it[TOKEN] = s.token; it[UID] = s.userId; it[NAME] = s.username
    }

    suspend fun clear() = ctx.ds.edit { it.remove(TOKEN); it.remove(UID); it.remove(NAME) }
}
