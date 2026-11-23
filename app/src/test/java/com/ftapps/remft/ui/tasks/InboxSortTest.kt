package com.ftapps.remft.ui.tasks

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class InboxSortTest {
    @get:Rule
    val compose = createComposeRule()

    private val t = TestTasks()
    private val watchers = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        watchers.cancel()
        t.close()
    }

    private fun insert(id: String, title: String, priority: String = "none", updated: String? = null, description: String? = null, status: String = "inbox") {
        val ws = runBlocking { t.repository.workspaceId() }
        fun sql(value: String?) = value?.let { "'$it'" } ?: "NULL"
        t.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tasks (id, workspace_id, title, description, status, priority, created_at, updated_at) VALUES " +
                "('$id', '$ws', '$title', ${sql(description)}, '$status', '$priority', '2026-11-01T00:00:00.000Z', ${sql(updated)})",
        )
    }

    private fun seed() {
        insert("01A", "banana", priority = "low", updated = "2026-11-03T10:00:00.000Z")
        insert("01C", "Apple", priority = "high", updated = "2026-11-01T10:00:00.000Z", description = "fruit")
        insert("01B", "cherry", priority = "high")
        insert("01E", "apple", updated = "2026-11-03T10:00:00.000Z", description = "fruit")
        insert("01D", "Échalote", priority = "urgent", updated = "2026-11-05T10:00:00.000Z")
        insert("01F", "Planned", status = "open")
    }

    private fun idleUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    private fun create(store: ViewModelStore): InboxViewModel {
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { InboxViewModel(t.environment) } })[InboxViewModel::class]
        watchers.launch { vm.state.collect {} }
        return vm
    }

    private fun ids(vm: InboxViewModel): List<String>? = (vm.state.value.load as? ListLoad.Ready)?.items?.map { it.id }

    private fun awaitOrder(vm: InboxViewModel, expected: List<String>) {
        idleUntil { ids(vm) == expected }
    }

    private fun storedSort(): String? = runBlocking { t.settings.get("remft_inbox_sort") }

    @Test
    fun theDefaultOrderIsTheOldInboxQueryOrder() {
        seed()
        val query = runBlocking { t.holder.db.tasksDao().watchInbox(t.repository.workspaceId()).first().map { it.id } }
        assertEquals(listOf("01E", "01D", "01C", "01B", "01A"), query)
        val vm = create(ViewModelStore())
        awaitOrder(vm, query)
        assertEquals("added", vm.state.value.sort.id)
        assertTrue(vm.state.value.sort.descending)
        assertEquals(null, storedSort())
    }

    @Test
    fun everyOptionAndItsReverseOrdersTheInbox() {
        seed()
        val vm = create(ViewModelStore())
        awaitOrder(vm, listOf("01E", "01D", "01C", "01B", "01A"))
        val expected = mapOf(
            "added" to listOf("01E", "01D", "01C", "01B", "01A"),
            "title" to listOf("01C", "01E", "01A", "01B", "01D"),
            "priority" to listOf("01D", "01C", "01B", "01A", "01E"),
            "updated" to listOf("01D", "01E", "01A", "01C", "01B"),
        )
        for ((id, order) in expected) {
            vm.chooseSort(com.ftapps.remft.domain.tasks.InboxSort.choices.first { it.id == id })
            awaitOrder(vm, order)
            vm.reverseSort()
            awaitOrder(vm, order.reversed())
            vm.reverseSort()
            awaitOrder(vm, order)
        }
    }

    @Test
    fun theSortIsKeptAcrossViewModelRecreationAndLeavesHomesSortAlone() {
        seed()
        val first = ViewModelStore()
        val vm = create(first)
        awaitOrder(vm, listOf("01E", "01D", "01C", "01B", "01A"))
        vm.chooseSort(com.ftapps.remft.domain.tasks.InboxSort.choices.first { it.id == "priority" })
        idleUntil { storedSort() == "priority:desc" }
        vm.reverseSort()
        idleUntil { storedSort() == "priority:asc" }
        first.clear()
        val again = create(ViewModelStore())
        awaitOrder(again, listOf("01E", "01A", "01B", "01C", "01D"))
        assertEquals("priority", again.state.value.sort.id)
        assertFalse(again.state.value.sort.descending)
        assertEquals(null, runBlocking { t.settings.get("remft_tasks_sort") })
    }

    @Test
    fun searchFiltersAndTheSortStillApplies() {
        seed()
        runBlocking { t.settings.set("remft_inbox_sort", "added:asc") }
        val vm = create(ViewModelStore())
        awaitOrder(vm, listOf("01A", "01B", "01C", "01D", "01E"))
        vm.setQuery("fruit")
        awaitOrder(vm, listOf("01C", "01E"))
        assertEquals(5, vm.state.value.total)
        vm.reverseSort()
        awaitOrder(vm, listOf("01E", "01C"))
    }

    private fun show() {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides t.environment) {
                    InboxTasksScreen(bottomPadding = 0.dp, onOpenSettings = {})
                }
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun shownOrder(vararg ids: String): List<String> =
        ids.filter { count("capture-row-$it") == 1 }.sortedBy { compose.onNodeWithTag("capture-row-$it").getBoundsInRoot().top.value }

    @Test
    fun theMenuReportsTheSelectedOptionAndReordersTheRows() {
        seed()
        show()
        waitFor { count("capture-row-01A") == 1 }
        assertEquals(listOf("01E", "01D", "01C", "01B", "01A"), shownOrder("01A", "01B", "01C", "01D", "01E"))
        compose.onNodeWithTag("inbox-sort").performClick()
        waitFor { count("sort-added") == 1 }
        compose.onNodeWithTag("sort-added").assertIsSelected()
        compose.onNodeWithTag("sort-title").assertIsNotSelected()
        compose.onNodeWithTag("sort-priority").assertIsNotSelected()
        compose.onNodeWithTag("sort-updated").assertIsNotSelected()
        compose.onNodeWithTag("sort-reverse").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.onNodeWithTag("sort-title").performClick()
        waitFor { storedSort() == "title:asc" }
        waitFor { shownOrder("01A", "01B", "01C", "01D", "01E") == listOf("01C", "01E", "01A", "01B", "01D") }
        compose.onNodeWithTag("inbox-sort").performClick()
        waitFor { compose.onAllNodes(hasTestTag("sort-title") and isSelected()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("sort-added").assertIsNotSelected()
        compose.onNodeWithTag("sort-reverse").performClick()
        waitFor { storedSort() == "title:desc" }
        compose.onNodeWithTag("inbox-sort").performClick()
        waitFor { count("sort-reverse") == 1 }
        compose.onNodeWithTag("sort-reverse").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNodeWithTag("sort-title").assertIsSelected()
    }

    @Test
    fun theSortWorksWithTheInboxSearch() {
        seed()
        runBlocking { t.settings.set("remft_inbox_sort", "title:desc") }
        show()
        waitFor { count("capture-row-01A") == 1 }
        compose.onNodeWithTag("inbox-search-toggle").performClick()
        compose.onNodeWithTag("inbox-search").performTextInput("apple")
        waitFor { count("capture-row-01A") == 0 }
        assertEquals(listOf("01E", "01C"), shownOrder("01A", "01B", "01C", "01D", "01E"))
        compose.onNodeWithTag("inbox-sort").performClick()
        waitFor { count("sort-reverse") == 1 }
        compose.onNodeWithTag("sort-reverse").performClick()
        waitFor { shownOrder("01C", "01E") == listOf("01C", "01E") }
    }

    @Test
    fun longPressOnTheSortButtonShowsItsName() {
        show()
        waitFor { count("inbox-sort") == 1 }
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Sort")))).fetchSemanticsNodes().size)
        compose.onNodeWithTag("inbox-sort").performTouchInput { longClick() }
        compose.waitUntil(5_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Sort")))).fetchSemanticsNodes().size == 1
        }
        assertEquals(0, count("sort-added"))
    }

    private fun layoutOf(node: androidx.compose.ui.semantics.SemanticsNode): TextLayoutResult? {
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val results = mutableListOf<TextLayoutResult>()
        action(results)
        return results.firstOrNull()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun atDoubleFontTheMenuClipsNothing() {
        RuntimeEnvironment.setFontScale(2f)
        show()
        waitFor { count("inbox-sort") == 1 }
        compose.onNodeWithTag("inbox-sort").performClick()
        waitFor { count("sort-reverse") == 1 }
        val tags = listOf("sort-added", "sort-title", "sort-priority", "sort-updated", "sort-reverse")
        val labels = mutableListOf<String>()
        for (tag in tags) {
            val item = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(tag, item.height >= 48f)
            val texts = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(hasTestTag(tag)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertEquals(tag, 1, texts.size)
            val node = texts.single()
            val layout = layoutOf(node)!!
            labels += layout.layoutInput.text.text
            assertFalse(tag, layout.didOverflowHeight)
            assertFalse(tag, (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            val text = node.boundsInRoot
            val scaledLine = with(Density(node.layoutInfo.density.density, 2f)) { 20.sp.toPx() }
            assertTrue("$tag $text $item", text.height >= scaledLine - 0.5f)
            assertTrue("$tag $text $item", text.top >= item.top - 0.5f && text.bottom <= item.bottom + 0.5f)
            assertTrue("$tag $text $item", text.left >= item.left - 0.5f && text.right <= item.right + 0.5f)
        }
        assertEquals(listOf("Date added", "Title", "Priority", "Last edited", "Reverse order"), labels)
    }
}
