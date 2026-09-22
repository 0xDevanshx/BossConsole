package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserZoomSettingsManagerPersistenceTest {

    private val settingsFile = BossDirectories.resolve("browser-zoom-settings.json")

    @Before
    fun setup() {
        if (settingsFile.exists()) settingsFile.delete()
        settingsFile.parentFile?.listFiles()?.forEach { 
            if (it.name.startsWith("browser-zoom-settings.json.corrupted.")) {
                it.delete()
            }
        }
        BrowserZoomSettingsManager.clearAllSettings()
    }

    @Test
    fun `saveSettingsSync is atomic and thread-safe`() = runBlocking {
        val jobs = (1..100).map { i ->
            async(Dispatchers.IO) {
                BrowserZoomSettingsManager.setZoomForDomain("domain$i.com", 1.5)
                BrowserZoomSettingsManager.saveSettingsSync()
            }
        }
        jobs.awaitAll()
        
        assertTrue(settingsFile.exists())
        val content = settingsFile.readText()
        assertTrue(content.contains("domain100.com"))
    }

    @Test
    fun `saveSettings is atomic and thread-safe`() = runBlocking {
        val jobs = (1..100).map { i ->
            async(Dispatchers.IO) {
                BrowserZoomSettingsManager.setZoomForDomain("domain$i.com", 1.5)
                BrowserZoomSettingsManager.saveSettings()
            }
        }
        jobs.awaitAll()
        
        assertTrue(settingsFile.exists())
        val content = settingsFile.readText()
        assertTrue(content.contains("domain100.com"))
    }

    @Test
    fun `corrupted file is quarantined and reset to defaults`() {
        settingsFile.writeText("{ invalid json ")
        
        val loadMethod = BrowserZoomSettingsManager::class.java.getDeclaredMethod("loadSettings")
        loadMethod.isAccessible = true
        loadMethod.invoke(BrowserZoomSettingsManager)

        assertTrue(settingsFile.exists())
        val content = settingsFile.readText()
        assertTrue(content.contains("\"domainSettings\":") || content.contains("{}"))

        val corruptedFiles = settingsFile.parentFile?.listFiles()?.filter { 
            it.name.startsWith("browser-zoom-settings.json.corrupted.") 
        } ?: emptyList()
        
        assertEquals(1, corruptedFiles.size, "Expected exactly one corrupted backup file")
        assertEquals("{ invalid json ", corruptedFiles.first().readText())
    }
}
