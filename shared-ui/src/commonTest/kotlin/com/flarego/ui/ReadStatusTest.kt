package com.flarego.ui
import kotlin.test.*
class ReadStatusTest {
    @Test fun cacheAndRefreshAreNotPresentedAsFreshData() {
        assertEquals("本地缓存 · 更新于 11/07 10:30",readStatusText(true,false,"11/07 10:30"))
        assertEquals("后台更新中 · 更新于 11/07 10:30",readStatusText(true,true,"11/07 10:30"))
        assertEquals("已同步 · 更新于 11/07 10:30",readStatusText(false,false,"11/07 10:30"))
    }
}
