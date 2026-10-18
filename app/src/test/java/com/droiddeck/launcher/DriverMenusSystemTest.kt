package com.droiddeck.launcher

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.SystemVulkanComponentStore
import com.droiddeck.launcher.gpu.SystemVulkanManifest
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.DriverRow
import com.droiddeck.launcher.ui.GPU_MODE_PAIR
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DriverMenusSystemTest {
    private lateinit var activity: Activity
    private val gpu = GpuInfo("Mali-G715", 0, GpuInfo.Family.MALI, "Tensor", false)

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setLinuxDriver(activity, "")
        SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
    }

    @After fun tearDown() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        File(activity.filesDir, "system_vulkan").deleteRecursively()
        File(activity.filesDir, "components/system-vulkan").deleteRecursively()
        activity.finish()
    }

    @Test fun legacyRuntimeHasAnHonestOriginAndIsSelectedBeforeFirstLaunch() {
        val digest = "a".repeat(64)
        legacy(digest)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)

        menus.refreshDrivers()

        val state = menus.state()
        val row = state.systemRows.single()
        assertEquals(SystemVulkanComponentStore.rowId(digest), state.linuxSelected)
        assertEquals(activity.getString(R.string.gpu_system_runtime_title, "Mali"), row.name)
        assertEquals(DriverRow.LEGACY, row.tag)
        assertTrue(row.detail.contains(activity.getString(R.string.gpu_system_legacy_detail)))
        assertFalse(row.removable)
        assertEquals(row.name, state.runtime.name)
        assertEquals(listOf(TurnipDriver.AUTO), state.androidRows.map { it.id })
        assertEquals(activity.getString(R.string.gpu_android_system_display), state.display.name)
        assertEquals(activity.getString(R.string.gpu_provided_by_android), state.display.status)
    }

    @Test fun selectingAnInstalledSystemBundleKeepsMatchedPairMode() {
        val digest = "b".repeat(64)
        legacy(digest)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        menus.chooseMode(GPU_MODE_PAIR)

        menus.selectDriver(SystemVulkanComponentStore.rowId(digest), linux = true)

        assertEquals(GPU_MODE_PAIR, menus.state().mode)
        assertEquals(SystemVulkanComponentStore.rowId(digest), menus.state().linuxSelected)
        val reopened = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        reopened.refreshDrivers()
        assertEquals(GPU_MODE_PAIR, reopened.state().mode)
    }

    @Test fun matchedSystemModeUsesAndroidDisplayAndPersistsAcrossRecreation() {
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
        SessionPrefs.setAndroidDriver(activity, TurnipDriver.BUNDLED.first())
        SessionPrefs.setLinuxDriver(activity, "manual-runtime")
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)

        menus.chooseMode(GPU_MODE_PAIR)
        val reopened = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        reopened.refreshDrivers()

        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
        assertEquals("", SessionPrefs.linuxDriver(activity))
        assertEquals(GPU_MODE_PAIR, reopened.state().mode)
        assertEquals(TurnipDriver.AUTO, reopened.state().androidSelected)
    }

    @Test fun refreshingMaliMigratesBundledTurnipButRetainsManualImportedSelection() {
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
        SessionPrefs.setAndroidDriver(activity, TurnipDriver.BUNDLED.first())
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        menus.refreshDrivers()
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))

        SessionPrefs.setAndroidDriver(activity, "imported-driver")
        menus.refreshDrivers()
        assertEquals("imported-driver", SessionPrefs.androidDriver(activity))
    }

    @Test fun automaticSystemPolicyRefreshesReadyBuildsAndOnlyBootstrapsWhenMissing() {
        assertEquals(SystemAutoAction.REFRESH_AVAILABLE,
            systemAutoAction(true, true, true, false, true, false))
        assertEquals(SystemAutoAction.REFRESH_AVAILABLE,
            systemAutoAction(true, true, true, true, true, true))
        assertEquals(SystemAutoAction.USE_INSTALLED,
            systemAutoAction(true, true, false, false, true, false))
        assertEquals(SystemAutoAction.INSTALL_PINNED,
            systemAutoAction(true, false, true, false, true, false))
        assertEquals(SystemAutoAction.WAIT_FOR_NETWORK,
            systemAutoAction(true, false, true, true, true, false))
    }

    @Test fun anEmptySystemInstallIsNotPresentedAsAnInstalledBundle() {
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        menus.refreshDrivers()

        assertTrue(menus.state().systemRows.isEmpty())
        assertEquals(activity.getString(R.string.gpu_system_missing), menus.state().runtime.status)
        assertTrue(menus.state().androidDownloads.isEmpty())
    }

    @Test fun refreshFailureSurvivesReloadingTheCachedRows() {
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        val catalog = DriverMenus::class.java.getDeclaredField("systemCatalog").apply { isAccessible = true }
        catalog.set(menus, SystemVulkanComponentStore.Catalog(emptyList(), listOf("droid-deck/droiddeck"), 0))

        menus.refreshReleaseRows()
        menus.refreshReleaseRows()

        assertEquals(activity.getString(R.string.upd_couldnt_check, "droid-deck/droiddeck"), menus.releaseStatus)
    }

    @Test fun componentLabelsDistinguishVersionsFromCommitBuilds() {
        assertEquals("Mali runtime component · Build 2eec15a5",
            systemComponentTitle(activity, "Mali", "2eec15a54924"))
        assertEquals("Mali runtime component · Version 2026.10.18",
            systemComponentTitle(activity, "Mali", "2026.10.18"))
        assertNull(systemComponentPublished(activity, "not-a-date"))
        assertTrue(systemComponentPublished(activity, "2026-10-18T09:48:00Z")!!.startsWith("Published "))
    }

    private fun legacy(digest: String) {
        val directory = File(activity.filesDir, "system_vulkan/$digest").apply { mkdirs() }
        File(directory, "icd.json").writeText("{}")
        SystemVulkanManifest.required("mali").forEach { path ->
            File(directory, path).apply { parentFile!!.mkdirs(); writeText("payload") }
        }
        File(directory, "run-proton.py").setExecutable(true)
    }
}
