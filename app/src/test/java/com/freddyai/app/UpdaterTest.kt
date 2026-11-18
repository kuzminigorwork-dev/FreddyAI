package com.freddyai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(Updater.newer("2.2.1", "2.2.0"))
        assertTrue(Updater.newer("v2.10.0", "2.9.9"))
        assertTrue(Updater.newer("3", "2.9"))
        assertFalse(Updater.newer("2.2.0", "2.2.0"))
        assertFalse(Updater.newer("2.1.9", "2.2.0"))
        assertFalse(Updater.newer("v2.2.0-beta", "2.2.0"))
    }

    @Test fun releaseWithApkIsParsed() {
        val r = Updater.parse("""{"tag_name":"v2.3.0","body":"Новое","html_url":"https://github.com/x","draft":false,"prerelease":false,
            "assets":[{"name":"notes.txt","browser_download_url":"https://a/n","size":1},{"name":"FreddyAI-2.3.0.apk","browser_download_url":"https://a/f.apk","size":4242}]}""")!!
        assertEquals("2.3.0", r.version); assertEquals("https://a/f.apk", r.apkUrl); assertEquals(4242L, r.size); assertEquals("Новое", r.notes)
    }

    @Test fun draftsAndReleasesWithoutApkAreIgnored() {
        assertNull(Updater.parse("""{"tag_name":"v9","draft":true,"assets":[{"name":"a.apk"}]}"""))
        assertNull(Updater.parse("""{"tag_name":"v9","assets":[{"name":"a.zip"}]}"""))
    }
}
