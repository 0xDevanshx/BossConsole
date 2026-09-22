package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeymapSettingsManagerPersistenceTest {

    private val settingsFile = BossDirectories.resolve("keymap-settings.json")

    @Before
    fun setup() {
        if (settingsFile.exists()) settingsFile.delete()
        settingsFile.parentFile?.listFiles()?.forEach { 
            if (it.name.startsWith("keymap-settings.json.corrupted.")) {
                it.delete()
            }
        }
    }

    @Test
    fun `saveSettings is atomic and thread-safe`() = runBlocking {
        // Trigger init block
        val current = KeymapSettingsManager.currentSettings.value

        val jobs = (1..100).map { i ->
            async(Dispatchers.IO) {
                // Update bindings by triggering save
                val newSettings = KeymapSettingsManager.currentSettings.value.copy(
                    presetName = "Preset $i"
                )
                KeymapSettingsManager.updateSettings(newSettings)
            }
        }
        jobs.awaitAll()
        
        assertTrue(settingsFile.exists())
        val content = settingsFile.readText()
        assertTrue(content.contains("\"Preset "))
    }

    @Test
    fun `corrupted file is quarantined and reset to defaults`() {
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeText("{ invalid json ")
        
        val loadMethod = KeymapSettingsManager::class.java.getDeclaredMethod("loadSettingsSync")
        loadMethod.isAccessible = true
        loadMethod.invoke(KeymapSettingsManager)

        assertTrue(settingsFile.exists())
        val content = settingsFile.readText()
        assertTrue(content.contains("\"customBindings\":") || content.contains("{}"))

        val corruptedFiles = settingsFile.parentFile?.listFiles()?.filter { 
            it.name.startsWith("keymap-settings.json.corrupted.") 
        } ?: emptyList()
        
        assertEquals(1, corruptedFiles.size, "Expected exactly one corrupted backup file")
        assertEquals("{ invalid json ", corruptedFiles.first().readText())
    }
}
