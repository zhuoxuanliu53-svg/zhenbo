package app.jingting.localplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Only the explicit emulator reinstall script supplies an expected exported URI. */
@RunWith(AndroidJUnit4::class)
class ReinstallScanTest {
    @Test fun exportedAudioCanBeScannedAfterReinstall() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val expectedUri = args.getString("expectedExportUri")
        val expectedTitle = args.getString("expectedTitle")
        Assume.assumeTrue("Run tools/verify_reinstall.py to supply an exported file", !expectedUri.isNullOrBlank() && !expectedTitle.isNullOrBlank())
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.graph
        assertTrue("Audio permission must be granted after reinstall", graph.media.permissions().audio)
        graph.media.refresh()
        val media = graph.dao.mediaByIds(listOf(expectedUri!!)).singleOrNull()
        assertNotNull("Previously exported audio must be rediscovered", media)
        assertEquals(expectedTitle, media!!.title)
        assertEquals(expectedUri, media.uri)
        assertTrue(media.available)
        assertFalse(media.isVideo)
        assertTrue(graph.media.canRead(media.uri))
    }
}
