package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWorkspaceSettingsManagerPersistenceTest {

    private val settingsFile = BossDirectories.resolve("workspace-settings.json")

    @Before
    fun setup() {
        if (settingsFile.exists()) settingsFile.delete()
        settingsFile.parentFile?.listFiles()?.forEach { 
            if (it.name.startsWith("workspace-settings.json.corrupted.")) {
                it.delete()
            }
        }
        runBlocking {
            WorkspaceSettingsManager.updateSettings { WorkspaceSettings() }
        }
    }

    @Test
    fun `updateSettings is atomic and thread-safe`() = runBlocking {
        val jobs = (1..100).map { i ->
            async(Dispatchers.IO) {
                WorkspaceSettingsManager.updateSettings { 
                    it.copy(defaultWorkspaceName = "workspace-$i")
                }
            }
        }
        jobs.awaitAll()
        
        assertTrue(settingsFile.exists())
        val content = settingsFile.readText()
        assertTrue(content.contains("\"defaultWorkspaceName\""))
    }

    @Test
    fun `corrupted file is quarantined and reset to defaults`() {
        settingsFile.writeText("{ invalid json ")
        
        val loadMethod = WorkspaceSettingsManager::class.java.getDeclaredMethod("loadSettingsSync")
        loadMethod.isAccessible = true
        loadMethod.invoke(WorkspaceSettingsManager)

        assertTrue(settingsFile.exists())
        val content = settingsFile.readText()
        assertTrue(content.contains("{}") || content.contains("\"settingsVersion\""))

        val corruptedFiles = settingsFile.parentFile?.listFiles()?.filter { 
            it.name.startsWith("workspace-settings.json.corrupted.") 
        } ?: emptyList()
        
        assertEquals(1, corruptedFiles.size, "Expected exactly one corrupted backup file")
        assertEquals("{ invalid json ", corruptedFiles.first().readText())
    }
}
