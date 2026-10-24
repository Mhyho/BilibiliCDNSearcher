package io.github.mhyho.bilicdn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlRewriterTest {
    private val upos = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/32/70/1/1_nb3-1-30216.m4s?e=x&os=cosbv&upsig=abc"
    private val akam = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/32/70/1/1_nb3-1-30216.m4s?e=x&hdnts=exp=1~hmac=2"
    private val mcdn = "https://xy1x2x3x4xy.mcdn.bilivideo.cn:4483/upgcxcode/32/70/1/1_nb3-1-30216.m4s?e=x"
    private val szbdyd = "https://abc.szbdyd.com/upgcxcode/32/70/1/1_nb3-1-30216.m4s?xy_usource=upos-sz-mirrorcos.bilivideo.com"
    private val ip = "http://1.2.3.4:8080/upgcxcode/32/70/1/1.m4s"
    private val live = "https://cn-gotcha01.bilivideo.com/live-bvc/123.flv"
    private val target = "upos-sz-mirrorali.bilivideo.com"

    @Test
    fun replaceHostKeepsPathAndQuery() {
        assertEquals(
            "https://$target/upgcxcode/32/70/1/1_nb3-1-30216.m4s?e=x&os=cosbv&upsig=abc",
            UrlRewriter.replaceHost(upos, target),
        )
        assertEquals("https://$target/a", UrlRewriter.replaceHost("https://h.example:443/a", target))
    }

    @Test
    fun classifiesPcdn() {
        assertTrue(UrlRewriter.isPcdn(mcdn))
        assertTrue(UrlRewriter.isPcdn(szbdyd))
        assertTrue(UrlRewriter.isPcdn(ip))
        assertFalse(UrlRewriter.isPcdn(upos))
        assertFalse(UrlRewriter.isPcdn(akam))
        assertTrue(UrlRewriter.isSwappable(szbdyd))
        assertFalse(UrlRewriter.isSwappable(mcdn))
    }

    @Test
    fun inactiveConfigChangesNothing() {
        val p = UrlRewriter.plan(upos, listOf(akam), CdnConfig())
        assertEquals(upos, p.base)
        assertEquals(listOf(akam), p.backups)
    }

    @Test
    fun selectedHostBecomesBaseAndOriginalsStayAsBackup() {
        val p = UrlRewriter.plan(upos, listOf(akam), CdnConfig.of(target, false))
        assertEquals(UrlRewriter.replaceHost(upos, target), p.base)
        assertEquals(listOf(upos, akam), p.backups)
    }

    @Test
    fun akamaiTargetPrefersUrlWithHdnts() {
        val host = "upos-hz-mirrorakam.akamaized.net"
        val p = UrlRewriter.plan(upos, listOf(akam), CdnConfig.of(host, false))
        assertEquals(akam, p.base)
        assertEquals(listOf(upos), p.backups)
    }

    @Test
    fun pcdnBaseUsesNormalBackupAsSource() {
        val p = UrlRewriter.plan(mcdn, listOf(upos), CdnConfig.of(target, false))
        assertEquals(UrlRewriter.replaceHost(upos, target), p.base)
        assertEquals(listOf(upos, mcdn), p.backups)
    }

    @Test
    fun onlyMcdnLeavesBaseUnchanged() {
        val p = UrlRewriter.plan(mcdn, emptyList(), CdnConfig.of(target, true))
        assertEquals(mcdn, p.base)
    }

    @Test
    fun blockPcdnWithoutHostPromotesNormalAndDropsPcdn() {
        val p = UrlRewriter.plan(mcdn, listOf(upos, akam), CdnConfig.of(null, true))
        assertEquals(upos, p.base)
        assertEquals(listOf(akam), p.backups)
    }

    @Test
    fun liveIsUntouched() {
        val p = UrlRewriter.plan(live, emptyList(), CdnConfig.of(target, true))
        assertEquals(live, p.base)
    }

    @Test
    fun alreadyOnTargetDoesNotDuplicate() {
        val onTarget = UrlRewriter.replaceHost(upos, target)
        val p = UrlRewriter.plan(onTarget, listOf(upos), CdnConfig.of(target, false))
        assertEquals(onTarget, p.base)
        assertEquals(listOf(upos), p.backups)
    }
}
