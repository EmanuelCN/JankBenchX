package com.android.benchmark.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.android.benchmark.R
import com.android.benchmark.results.FrameStats
import com.android.benchmark.results.FrameStats.Frame
import com.android.benchmark.results.JankBreakdown
import com.android.benchmark.results.RunStore
import com.android.benchmark.results.RunStore.Measurement
import com.android.benchmark.results.RunStore.Status
import com.android.benchmark.ui.BenchmarkTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BenchmarkScreensTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store: RunStore get() = RunStore.get(context)

    @After
    fun removeTestData() {
        store.close()
        context.deleteDatabase("BenchmarkResults")
    }

    @Test
    @Config(qualifiers = "notnight")
    fun launchFromHomeCancelAndOpenTheSavedResult() {
        val runIntent = Robolectric.buildActivity(HomeActivity::class.java).setup().use { home ->
            text("Configure JankBenchX").assertIsDisplayed()
            text("JankBenchX").assertDoesNotExist()
            text("Benchmark").assertIsSelected()
            choice(5).assertIsSelected()
            val three = choice(3).assertHasClickAction()
            val bounds = three.getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
            three.performClick().assertIsSelected()
            three.performClick().assertIsSelected()

            val workload = findWorkload(BenchmarkTest.UI_LIST).assertIsOn()
            assertEquals(Role.Switch, workload.fetchSemanticsNode().config[SemanticsProperties.Role])
            val workloadBounds = workload.getUnclippedBoundsInRoot()
            assertTrue(workloadBounds.bottom - workloadBounds.top >= 48.dp)
            workload.performClick().assertIsOff()
            runButton().performClick()
            shadowOf(home.get()).nextStartedActivity.also { intent ->
                assertEquals(ComponentName(home.get(), RunLocalBenchmarksActivity::class.java), intent.component)
                val tests = intent.getStringArrayExtra(RunLocalBenchmarksActivity.TESTS)!!.toList()
                assertEquals(BenchmarkTest.workloads().size - 2, tests.size)
                assertFalse(BenchmarkTest.UI_LIST.name in tests)
                assertEquals(3, intent.getIntExtra(RunLocalBenchmarksActivity.REPETITIONS, 0))
            }
        }

        val resultIntent = Robolectric.buildActivity(RunLocalBenchmarksActivity::class.java, runIntent).setup().use { runner ->
            waitForDisk()
            val buttons = ArrayList<View>()
            runner.get().window.decorView.findViewsWithText(buttons, "Cancel", View.FIND_VIEWS_WITH_TEXT)
            val cancel = buttons.filterIsInstance<Button>().single()
            assertTrue(runner.get().onBackPressedDispatcher.hasEnabledCallbacks())
            assertTrue(cancel.performClick())
            waitForDisk()
            shadowOf(runner.get()).nextStartedActivity.also { intent ->
                assertNotNull(intent)
                assertEquals(ComponentName(runner.get(), ResultsActivity::class.java), intent.component)
                val run = store.details(intent.getLongExtra(ResultsActivity.RUN_ID, 0)).run()
                assertEquals(Status.CANCELLED, run.status())
                assertNull(run.score())
            }
        }

        Robolectric.buildActivity(ResultsActivity::class.java, resultIntent).setup().use {
            waitForDisk()
            text("Cancelled").assertIsDisplayed()
            compose.onNode(hasText("Unavailable") and hasAnySibling(hasText("Lower is better", substring = true)),
                useUnmergedTree = true).assertIsDisplayed()
            scrollTo("Run details").performClick()
            scrollTo("Kernel " + System.getProperty("os.version")).assertIsDisplayed()
            scrollTo("Workload details")
        }
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { home ->
            choice(3).assertIsSelected()
            findWorkload(BenchmarkTest.UI_LIST).assertIsOff()
            text("History").performClick()
            waitForDisk()
            text("Kernel " + System.getProperty("os.version")).assertExists()
            text("Cancelled").performClick()
            assertEquals(resultIntent.getLongExtra(ResultsActivity.RUN_ID, 0),
                shadowOf(home.get()).nextStartedActivity.getLongExtra(ResultsActivity.RUN_ID, 0))
        }
    }

    @Test
    @Config(sdk = [26], qualifiers = "w320dp-h720dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun historyDrawsWhileLoadingAndOpensAnExistingResult() {
        val id = saveRun(BenchmarkTest.PHOTO_GALLERY, 60, 7_000_000)
        val unblock = CountDownLatch(1)
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { home ->
            RunStore.DISK.execute { unblock.await(5, TimeUnit.SECONDS) }
            try {
                text("History").performClick().assertIsSelected()
                text("Loading runs").assertIsDisplayed()
                compose.onRoot().captureToImage()
            } finally {
                unblock.countDown()
            }
            waitForDisk()
            text("Complete").performClick()
            assertEquals(id, shadowOf(home.get()).nextStartedActivity.getLongExtra(ResultsActivity.RUN_ID, 0))
        }
    }

    @Test
    fun resultSummarizesCompletedFramesAndKeepsChartsAndPasses() {
        val id = store.start(listOf(BenchmarkTest.LIST, BenchmarkTest.OVERDRAW), 1)
        val capture = Measurement(
            listOf(Frame(5_000_000, 8_000_000), Frame(6_000_000, 8_000_000), Frame(10_000_000, 8_000_000)),
            0, 0, 0, 60f, 120f, null, JankBreakdown(3, 1, 1, 0), emptyList(),
        )
        assertTrue(store.saveStep(id, 0, capture))
        store.finish(id, Status.CANCELLED, "Cancelled by the user.")
        withResult(id) {
            compose.onNodeWithContentDescription("Back").assertHasClickAction()
            text("120 Hz").assertIsDisplayed()
            text("60.0–120.0 Hz").assertDoesNotExist()
            text("Scoring version", substring = true).assertDoesNotExist()
            scrollTo("Frame performance")
            text("Jank frames").assertExists()
            text("Bad frames").assertExists()
            scrollTo("Frame statistics").performClick()
            text("Deadline overrun", unmerged = true).assertExists()
            text("Compositor classification", unmerged = true).assertExists()
            text("3 of 3 frames classified.", substring = true, unmerged = true).assertExists()
            scrollTo("Frame statistics").performClick()
            scrollTo("95th-percentile frame time").performClick()
            text("33.3 ms", unmerged = true).assertExists()
            scrollTo("95th-percentile frame time").performClick()
            val workload = context.getString(BenchmarkTest.LIST.canonical().title)
            scrollTo(workload)
            text(String.format(Locale.getDefault(), "%.2f%%", 100.0 / 3), unmerged = true).assertExists()
            text(String.format(Locale.getDefault(), "%.2f%%", 200.0 / 3), unmerged = true).assertExists()
            scrollTo("Incomplete")
            scrollTo(workload).performClick()
            scrollTo("Pass 1").performClick()
            text("Frame duration and deadline", unmerged = true).assertExists()
            text("Score inputs", unmerged = true).assertExists()
        }
    }

    @Test
    fun historyOpensTwoRunComparisonAndDisplaysFrameMetrics() {
        val ids = listOf(90, 60, 120).map { hz ->
            saveRun(BenchmarkTest.LIST, hz, 10_000_000)
        }
        val intent = Robolectric.buildActivity(HomeActivity::class.java).setup().use { home ->
            text("History").performClick()
            waitForDisk()
            text("Compare runs").performClick()
            shadowOf(home.get()).nextStartedActivity.also {
                assertEquals(ComponentName(home.get(), CompareRunsActivity::class.java), it.component)
            }
        }
        Robolectric.buildActivity(CompareRunsActivity::class.java, intent).setup().use {
            waitForDisk()
            waitForDisk()
            scrollTo(ResultLabels.refresh(120f, 120f))
            scrollTo(ResultLabels.refresh(60f, 60f))
            scrollTo("Workload comparison")
            text("Run conditions differ").assertDoesNotExist()
            scrollTo(context.getString(BenchmarkTest.LIST.canonical().title)).performClick()
            text("P99 frame time", unmerged = true).assertExists()
            compose.onAllNodes(hasText(ResultLabels.ms(10.0)), useUnmergedTree = true).fetchSemanticsNodes().also {
                assertTrue(it.size >= 2)
            }
            scrollTo("Change Run A").performClick()
            text("Choose Run A").assertIsDisplayed()
            text("Selected", unmerged = true).assertExists()
            assertTrue(compose.onAllNodes(hasText("Kernel " + System.getProperty("os.version")) and hasAnyAncestor(isDialog()),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            text("Run #${ids[1]}").assertDoesNotExist()
            text("Run #${ids[0]}").performClick()
            waitForDisk()
            scrollTo(ResultLabels.refresh(90f, 90f))
            text(ResultLabels.refresh(120f, 120f)).assertDoesNotExist()
        }
    }

    @Test
    fun oldTvRunsAndPhotoGalleryHaveSeparateComparisonRows() {
        saveRun(BenchmarkTest.UI_TV_BROWSE, 120, 7_000_000)
        saveRun(BenchmarkTest.PHOTO_GALLERY, 120, 7_000_000)
        Robolectric.buildActivity(CompareRunsActivity::class.java).setup().use {
            waitForDisk()
            waitForDisk()
            scrollTo("Workload comparison")
            scrollTo(context.getString(R.string.ui_tv_browse))
            scrollTo(context.getString(R.string.photo_gallery_name))
        }
    }

    @Test
    fun savedTvSelectionTransfersToThePhoneGallery() {
        context.getSharedPreferences("benchmark_settings", Context.MODE_PRIVATE).edit()
            .putStringSet("selected_workloads", setOf(BenchmarkTest.UI_TV_BROWSE.name)).apply()
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { home ->
            findWorkload(BenchmarkTest.PHOTO_GALLERY).assertIsOn()
            runButton().performClick()
            assertEquals(listOf(BenchmarkTest.PHOTO_GALLERY.name),
                shadowOf(home.get()).nextStartedActivity.getStringArrayExtra(RunLocalBenchmarksActivity.TESTS)!!.toList())
        }
    }

    @Test
    fun unifiedCatalogUsesNewerEntriesAndSelectsEverySupportedWorkload() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { home ->
            assertEquals(32, BenchmarkTest.workloads().size)
            for (test in listOf(BenchmarkTest.UI_LIST, BenchmarkTest.UI_TRANSITION, BenchmarkTest.COMPOSE_FEED,
                BenchmarkTest.UI_INSETS)) findWorkload(test).assertIsOn()
            runButton().performClick()
            val chosen = shadowOf(home.get()).nextStartedActivity
                .getStringArrayExtra(RunLocalBenchmarksActivity.TESTS)!!.toList()
            assertEquals(31, chosen.size)
            assertFalse(BenchmarkTest.LIST.name in chosen)
            assertFalse(BenchmarkTest.DETAIL_TRANSITION.name in chosen)
            assertFalse(BenchmarkTest.UI_WIDE_GAMUT.name in chosen)
        }
    }

    @Test
    @Config(qualifiers = "widecg")
    fun p3IsAvailableWhenAndroidSupportsWideGamutRendering() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { home ->
            findWorkload(BenchmarkTest.UI_WIDE_GAMUT).assertIsOn()
            runButton().performClick()
            val chosen = shadowOf(home.get()).nextStartedActivity
                .getStringArrayExtra(RunLocalBenchmarksActivity.TESTS)!!.toList()
            assertTrue(BenchmarkTest.UI_WIDE_GAMUT.name in chosen)
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun clearAndSelectAllSupportedWorkloadsWithDarkTheme() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { home ->
            val enabledWorkloads = BenchmarkTest.workloads().filter { it != BenchmarkTest.UI_WIDE_GAMUT }
            for (test in enabledWorkloads) findWorkload(test).assertIsOn()
            scrollTo("Clear all").performClick()
            for (test in enabledWorkloads) findWorkload(test).assertIsOff()
            runButton().assertIsNotEnabled()
            findWorkload(BenchmarkTest.UI_TRANSITION).performClick().assertIsOn()
            scrollTo(context.getString(R.string.all_workloads)).performClick()
            runButton().performClick()
            val chosen = shadowOf(home.get()).nextStartedActivity
                .getStringArrayExtra(RunLocalBenchmarksActivity.TESTS)!!.toList()
            assertEquals(31, chosen.size)
            assertFalse(BenchmarkTest.UI_WIDE_GAMUT.name in chosen)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h720dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fourDigitScoresAndFullPercentagesFitAtPhoneWidth() {
        val id = store.start(listOf(BenchmarkTest.UI_TRANSITION), 1)
        val capture = Measurement(listOf(Frame(10_000_000, 8_000_000), Frame(11_000_000, 8_000_000)),
            0, 0, 0, 120f, 120f, null)
        assertTrue(store.saveStep(id, 0, capture))
        store.finish(id, Status.COMPLETE, null)
        val score = FrameStats.measure(capture.frames()).score
        assertTrue(score > 1_000 && score < 10_000)
        withResult(id) {
            val workload = context.getString(BenchmarkTest.UI_TRANSITION.title)
            scrollTo(workload)
            val node = compose.onNode(hasText(ResultLabels.score(score)) and hasAnySibling(hasText("Score")),
                useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            val layout = layouts.single()
            assertEquals(1, layout.lineCount)
            val lineWidth = layout.getLineRight(0) - layout.getLineLeft(0)
            assertTrue("Score needs $lineWidth px, measured ${layout.size.width} px", lineWidth <= layout.size.width)
            text("100.00%", unmerged = true).assertDoesNotExist()
            assertTrue(compose.onAllNodes(hasText("100%"), useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
        }
    }

    @Test
    @Config(sdk = [35])
    fun deleteConfirmationKeepsRefreshPolicyAndRemovesOnlyTheConfirmedRun() {
        val kept = saveRun(BenchmarkTest.PHOTO_GALLERY, 120, 7_000_000)
        val deleted = saveRun(BenchmarkTest.UI_TRANSITION, 120, 7_000_000)
        withResult(deleted) {
            text("Delete").performClick()
            text("Delete this result?").assertIsDisplayed()
            val window = ShadowDialog.getLatestDialog().window!!
            assertFalse(window.isFrameRatePowerSavingsBalanced)
            assertEquals(0, window.attributes.preferredDisplayModeId)
            assertEquals(0f, window.attributes.preferredRefreshRate, 0f)
            text("Keep result").performClick()
            assertNotNull(store.details(deleted))

            text("Delete").performClick()
            compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
            waitForDisk()
            assertNull(store.details(deleted))
            assertNotNull(store.details(kept))
        }
    }

    @Test
    fun tallDisclosuresOpenAndCloseMoreGradually() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { home ->
            home.get().setContent {
                BenchmarkTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        var compact by remember { mutableStateOf(false) }
                        Disclosure("Compact", compact, { compact = !compact }, Modifier.testTag("compact")) {
                            Spacer(Modifier.height(120.dp))
                        }
                        var tall by remember { mutableStateOf(false) }
                        Disclosure("Tall", tall, { tall = !tall }, Modifier.testTag("tall")) {
                            Spacer(Modifier.height(1_000.dp))
                        }
                    }
                }
            }
            fun height(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                .let { it.bottom - it.top }

            val compactClosed = height("compact")
            val tallClosed = height("tall")
            compose.mainClock.autoAdvance = false
            try {
                text("Compact").performClick()
                compose.mainClock.advanceTimeBy(330)
                assertTrue(height("compact") > compactClosed + 115.dp)
                text("Compact").performClick()
                compose.mainClock.advanceTimeBy(500)

                text("Tall").performClick()
                compose.mainClock.advanceTimeBy(330)
                assertTrue(height("tall") < tallClosed + 950.dp)
                compose.mainClock.advanceTimeBy(600)
                assertTrue(height("tall") > tallClosed + 1_000.dp)

                text("Tall").performClick()
                compose.mainClock.advanceTimeBy(300)
                assertTrue(height("tall") > tallClosed + 100.dp)
                compose.mainClock.advanceTimeBy(600)
                assertEquals(tallClosed, height("tall"))
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    private fun text(label: String, substring: Boolean = false, unmerged: Boolean = false) =
        compose.onNodeWithText(label, substring = substring, useUnmergedTree = unmerged)

    private fun scrollTo(label: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        return text(label)
    }

    private fun runButton() = compose.onNodeWithContentDescription("Run Benchmark")

    private fun findWorkload(test: BenchmarkTest) = scrollTo(context.getString(test.title))

    private fun choice(count: Int) = compose.onNodeWithContentDescription(
        context.resources.getQuantityString(R.plurals.repetition_count, count, count),
    )

    private fun waitForDisk() {
        RunStore.DISK.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun saveRun(test: BenchmarkTest, hz: Int, frameNs: Long): Long {
        val id = store.start(listOf(test), 1)
        assertTrue(store.saveStep(id, 0, Measurement(listOf(Frame(frameNs, (1_000_000_000.0 / hz).let(Math::round))),
            0, 0, 0, hz.toFloat(), hz.toFloat(), null)))
        store.finish(id, Status.COMPLETE, null)
        return id
    }

    private fun withResult(id: Long, body: () -> Unit) {
        val intent = Intent(context, ResultsActivity::class.java).putExtra(ResultsActivity.RUN_ID, id)
        Robolectric.buildActivity(ResultsActivity::class.java, intent).setup().use {
            waitForDisk()
            body()
        }
    }
}
