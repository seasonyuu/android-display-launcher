package me.seasonyuu.displaylauncher

import org.junit.Assert.*
import org.junit.Test

class AppRulesTest {
    private fun app(component: String = "p/.One", pkg: String = "p", user: Int = 0) =
        AppItem(component, "微信", pkg, "key", AppSearch.fields("微信", "com.tencent.mm", "wei xin"), user)
    @Test fun searchNormalizationAndAllTerms() {
        for (q in listOf("微信", "weixin", "wx", "ＷＸ", "  WeiXin  ", "tencent", "wx tencent", "wx\tMM", "　"))
            assertTrue(q, AppSearch.matches(app(), AppSearch.tokens(q)))
        for (q in listOf("unknown", "wx unknown", "wei xx")) assertFalse(q, AppSearch.matches(app(), AppSearch.tokens(q)))
        val english = app().copy(searchIndex = AppSearch.fields("Google Chrome", "com.android.chrome", "google chrome"))
        assertTrue(AppSearch.matches(english, AppSearch.tokens("ＣＨＲＯＭＥ android")))
    }
    @Test fun alphabeticalOrderUsesFullPinyinAcrossChineseAndEnglish() {
        val names = listOf("支付宝" to "zhi fu bao", "相机" to "xiang ji", "微信" to "wei xin",
            "百度" to "bai du", "阿里" to "a li", "chrome" to "chrome", "Alipay" to "Alipay")
        val apps = names.map { (label, latin) ->
            AppItem("$label/.Main", label, "p", "", AppSearch.fields(label, "p", latin), 0)
        }
        assertEquals(listOf("阿里", "Alipay", "百度", "chrome", "微信", "相机", "支付宝"),
            apps.sortedWith(AppSearch.alphabeticalOrder).map { it.label })
        val same = app("p/.Z")
        assertEquals(listOf("p/.A", "p/.Z"), listOf(same, same.copy(component = "p/.A"))
            .sortedWith(AppSearch.alphabeticalOrder).map { it.component })
    }
    @Test fun onlyAcceptedLaunchesCount() {
        listOf("confirmed", "unverified", "mismatch").forEach { assertTrue(RecentRules.accepted(it)) }
        listOf("failed", "timeout", "denied", "", null).forEach { assertFalse(RecentRules.accepted(it)) }
    }
    @Test fun latestComponentWinsWithoutMergingUsers() {
        val old = RecentEntry(0, "p", "p/.One")
        val other = RecentEntry(1, "p", "p/.One")
        val latest = RecentEntry(0, "p", "p/.Two")
        assertEquals(listOf(latest, other), RecentRules.record(listOf(old, other), latest))
    }
    @Test fun hiddenMissingComponentsAndCountDoNotTruncateHistory() {
        val entries = (1..55).map { RecentEntry(0, "p$it", "p$it/.Main") }
            .fold(emptyList<RecentEntry>()) { list, entry -> RecentRules.record(list, entry) }
        assertEquals(50, entries.size)
        val apps = entries.map { app(it.component, it.packageName) }
        assertEquals(3, RecentRules.visible(apps, RecentSettings(entries)).size)
        assertEquals(0, RecentRules.visible(apps, RecentSettings(entries, 0)).size)
        assertEquals(20, RecentRules.visible(apps, RecentSettings(entries, 20)).size)
        assertEquals(50, entries.size)
        assertTrue(RecentRules.visible(listOf(app("p/.Two")), RecentSettings(listOf(RecentEntry(0, "p", "p/.One")))).isEmpty())
    }
}
