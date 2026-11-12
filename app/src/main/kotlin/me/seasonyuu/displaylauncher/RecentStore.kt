package me.seasonyuu.displaylauncher

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.launcherPreferences by preferencesDataStore("launcher_preferences")

class RecentStore(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.launcherPreferences
    private val records = stringPreferencesKey("recent_v1")
    private val count = intPreferencesKey("recent_count")
    val state = store.data.map { prefs ->
        RecentSettings(decode(prefs[records]), (prefs[count] ?: 3).coerceIn(0, 20))
    }.retryWhen { _, _ ->
        emit(RecentSettings(error = "最近使用读取失败，正在重试")); delay(2000); true
    }.stateIn(scope, SharingStarted.Eagerly, RecentSettings())

    suspend fun record(app: AppItem, status: String?) = withContext(Dispatchers.IO) {
        if (RecentRules.accepted(status)) store.edit { prefs ->
            prefs[records] = encode(RecentRules.record(decode(prefs[records]), RecentEntry(app.user, app.packageName, app.component)))
        }
        Unit
    }
    suspend fun adjustCount(delta: Int) { store.edit { it[count] = ((it[count] ?: 3) + delta).coerceIn(0, 20) } }
    suspend fun setCount(value: Int) { store.edit { it[count] = value.coerceIn(0, 20) } }
    suspend fun removePackage(user: Int, packageName: String) = withContext(Dispatchers.IO) {
        store.edit { prefs -> prefs[records] = encode(decode(prefs[records]).filterNot { it.user == user && it.packageName == packageName }) }
    }
    private fun decode(value: String?): List<RecentEntry> {
        if (value == null) return emptyList()
        val array = JSONArray(value)
        return (0 until array.length()).map { array.getJSONObject(it).let { obj ->
            RecentEntry(obj.getInt("user"), obj.getString("package"), obj.getString("component"))
        } }.take(50)
    }
    private fun encode(entries: List<RecentEntry>) = JSONArray().apply {
        entries.forEach { put(JSONObject().put("user", it.user).put("package", it.packageName).put("component", it.component)) }
    }.toString()
}
