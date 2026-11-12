package me.seasonyuu.displaylauncher

import java.text.Normalizer
import java.util.Locale

data class AppItem(
    val component: String, val label: String, val packageName: String,
    val iconKey: String, val searchIndex: List<String>, val user: Int,
)

object AppSearch {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim()
    fun tokens(query: String): List<String> = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun matches(app: AppItem, tokens: List<String>): Boolean = tokens.all { token -> app.searchIndex.any { token in it } }
    fun fields(label: String, packageName: String, transliterated: String): List<String> {
        val syllables = normalize(transliterated).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return listOf(normalize(label), normalize(packageName), syllables.joinToString(""), syllables.joinToString("") { it.take(1) })
    }
}

data class RecentEntry(val user: Int, val packageName: String, val component: String)
data class RecentSettings(val entries: List<RecentEntry> = emptyList(), val count: Int = 3, val error: String? = null)
object RecentRules {
    fun accepted(status: String?) = status in setOf("confirmed", "unverified", "mismatch")
    fun record(entries: List<RecentEntry>, entry: RecentEntry): List<RecentEntry> =
        (listOf(entry) + entries.filterNot { it.user == entry.user && it.packageName == entry.packageName }).take(50)
    fun visible(apps: List<AppItem>, settings: RecentSettings): List<AppItem> {
        val byComponent = apps.associateBy { it.user to it.component }
        return settings.entries.mapNotNull { byComponent[it.user to it.component] }.take(settings.count.coerceIn(0, 20))
    }
}
