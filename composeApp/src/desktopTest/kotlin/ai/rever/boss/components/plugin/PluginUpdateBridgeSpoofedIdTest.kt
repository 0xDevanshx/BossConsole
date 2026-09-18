package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import ai.rever.boss.plugin.updater.PluginUpdateManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginUpdateBridgeSpoofedIdTest {
    @TempDir
    lateinit var dir: File

    private class FakeRepository(
        private val downloadBytesAction: (String) -> Unit
    ) : PluginRepository {
        override val id = "store"
        override val name = "Store"
        override val isLocal = false
        override val isAvailable = true

        override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> = error("unused")

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> = Result.success(
            PluginInfo(
                pluginId = pluginId,
                displayName = "Test Plugin",
                version = "2.0.0",
                minBossVersion = "1.0.0"
            )
        )

        override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> = listPlugins()

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
            onProgress: ((Float) -> Unit)?
        ): Result<String> {
            downloadBytesAction(targetPath)
            return Result.success(targetPath)
        }
    }

    @AfterEach
    fun tearDown() {
        // Reset the update manager
        val field = PluginStoreSetup::class.java.getDeclaredField("_updateManager")
        field.isAccessible = true
        field.set(PluginStoreSetup, null)
    }

    @Test
    fun `spoofed pluginId in downloaded jar is rejected before unloading existing plugin`() = runBlocking {
        val legitPluginId = "ai.rever.boss.plugin.legit"
        val spoofedPluginId = "ai.rever.boss.plugin.attacker"

        val repo = FakeRepository { targetPath ->
            // Provide a jar that claims to be 'attacker' instead of 'legit'
            PluginJarTestFixtures.writeJar(
                dir = File(targetPath).parentFile,
                fileName = File(targetPath).name,
                pluginId = spoofedPluginId,
                version = "2.0.0"
            )
        }
        val repos = PluginRepositoryManager().apply { addRepository(repo) }
        val updateManager = PluginUpdateManager(
            repositoryManager = repos,
            hostBossVersion = { "1.0.0" },
            hostApiVersion = { "1.0.0" }
        )

        // Inject the updateManager via reflection
        val field = PluginStoreSetup::class.java.getDeclaredField("_updateManager")
        field.isAccessible = true
        field.set(PluginStoreSetup, updateManager)

        val sandboxManager = PluginSandboxManagerImpl()
        val dynamicManager = DynamicPluginManager(
            PanelRegistry(),
            TabRegistry(),
            sandboxManager,
            createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
        )

        try {
            // Initiate update for the legit plugin. The repository will serve a jar that is secretly the spoofed plugin.
            val result = PluginUpdateBridge.performUpdate(legitPluginId, dynamicManager)
            
            // It should fail.
            assertTrue(result.isFailure, "Update should have failed")

            val exception = result.exceptionOrNull()!!
            // The fix causes it to fail with the specific spoof exception.
            // If the fix were missing, it would have proceeded to unload, which in this empty manager
            // would fail with a PluginNotLoadedException (or similar) from DynamicPluginManager.
            assertTrue(
                exception.message?.contains("did not install as $legitPluginId") == true,
                "Expected rejection exception due to spoofed ID, but got: ${exception.message} (${exception.javaClass})"
            )
        } finally {
            dynamicManager.disposeWindow()
            sandboxManager.dispose()
        }
    }
}
