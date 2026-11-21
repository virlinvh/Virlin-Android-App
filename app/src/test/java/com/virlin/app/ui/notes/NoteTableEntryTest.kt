package com.virlin.app.ui.notes

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class NoteTableEntryTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            NoteTableEntryBar(NoteTableEntry(onInsert = { r, c -> events += "insert:${r}x$c" }, onCancel = { events += "cancel" }))
        }
        compose.waitForIdle()
    }

    @Test fun typedRowsAndColumnsInsertOnDone() {
        show()
        compose.onNodeWithTag("notes_table_rows").performTextReplacement("4")
        compose.onNodeWithTag("notes_table_cols").performTextReplacement("5")
        compose.onNodeWithTag("notes_table_insert").performClick()
        compose.onNodeWithTag("notes_table_cols").performImeAction()
        assertEquals(listOf("insert:4x5", "insert:4x5"), events)
    }

    @Test fun valuesAreClampedAndEmptyDisablesInsert() {
        show()
        compose.onNodeWithTag("notes_table_rows").performTextReplacement("99")
        compose.onNodeWithTag("notes_table_cols").performTextReplacement("40")
        compose.onNodeWithTag("notes_table_insert").performClick()
        compose.onNodeWithTag("notes_table_cols").performTextReplacement("")
        compose.onNodeWithTag("notes_table_insert").assertIsNotEnabled()
        compose.onNodeWithTag("notes_table_cancel").performClick()
        assertEquals(listOf("insert:${NoteDocTable.MAX_ROWS}x${NoteDocTable.MAX_COLS}", "cancel"), events)
    }

    // ---------------------------------------------------------------- view model

    private val ids = SequentialIdProvider()
    private val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))), FakeClock(Instant.EPOCH), ids)

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    @Test fun insertedTableGrowsFromItsEdgesAndUndoes() = editorTest {
        val vm = NotesViewModel("task-table", "Note", actions, ids)
        val first = vm.state.value.blocks.single().id
        vm.onFocusChanged(first, true)
        vm.onTool(NoteTool.TABLE)
        assertEquals(NoteDialog.Table, vm.state.value.dialog)
        vm.insertTable(2, 3)
        assertEquals(NoteDialog.TableTools, vm.state.value.dialog)
        val block = vm.state.value.blocks.first { it.type == NoteDocBlockType.TABLE }
        assertEquals(NoteDocTable.blank(2, 3), block.table)
        assertEquals(NoteCellRef(block.id, 0, 0), vm.state.value.cellFocusRequest)

        vm.onTableCellChanged(block.id, 0, 0, "Name")
        vm.addTableRow(block.id, atTop = false)
        vm.addTableColumn(block.id)
        vm.addTableRow(block.id, atTop = true)
        val grown = vm.state.value.blocks.first { it.id == block.id }.table!!
        assertEquals(4 to 4, grown.rows to grown.cols)
        assertEquals("Name", grown.cell(1, 0))

        vm.undo()
        assertEquals(3 to 4, vm.state.value.blocks.first { it.id == block.id }.table!!.let { it.rows to it.cols })
    }

    @Test fun boldAndColourApplyToTheFocusedCellNotTheBlocksAround() = editorTest {
        val vm = NotesViewModel("task-table-style", "Note", actions, ids)
        val first = vm.state.value.blocks.single().id
        vm.onTextChanged(first, "Above", TextRange(5))
        vm.onFocusChanged(first, true)
        vm.insertTable(2, 2)
        val table = vm.state.value.blocks.first { it.type == NoteDocBlockType.TABLE }.id
        vm.onTableCellEdited(table, 1, 1, "Price", TextRange(5))
        vm.onTableCellEdited(table, 1, 1, "Price", TextRange(0, 5))
        vm.onTool(NoteTool.BOLD)
        vm.pickColor(0xFFD32F2FL, highlight = false)
        val cellRuns = vm.state.value.blocks.first { it.id == table }.table!!.runsAt(1, 1)
        assertEquals(listOf(NoteDocRun(0, 5, setOf(NoteDocMark.BOLD), color = 0xFFD32F2FL)), cellRuns)
        val above = vm.state.value.blocks.first { it.id == first }
        assertEquals("Above", above.text)
        assertEquals(emptyList<NoteDocRun>(), above.runs)
        assertEquals("Table cell", vm.toolState().paragraphLabel)

        // A collapsed caret makes Bold a pending style for what is typed next in the cell.
        vm.onTableCellEdited(table, 0, 0, "", TextRange.Zero)
        vm.onTool(NoteTool.ITALIC)
        vm.onTableCellEdited(table, 0, 0, "Q", TextRange(1))
        assertEquals(listOf(NoteDocRun(0, 1, setOf(NoteDocMark.ITALIC))),
            vm.state.value.blocks.first { it.id == table }.table!!.runsAt(0, 0))

        // Focusing a line again leaves the table and closes its tools.
        vm.onFocusChanged(first, true)
        assertNull(vm.state.value.activeCell)
        assertNull(vm.state.value.dialog)
    }

    @Test fun tableToolsChangeTheFocusedCellsTableAndUndo() = editorTest {
        val vm = NotesViewModel("task-table-tools", "Note", actions, ids)
        vm.onFocusChanged(vm.state.value.blocks.single().id, true)
        vm.insertTable(2, 2)
        val id = vm.state.value.blocks.first { it.type == NoteDocBlockType.TABLE }.id
        fun table() = vm.state.value.blocks.first { it.id == id }.table!!
        vm.onTableCellEdited(id, 0, 0, "A", TextRange(1))
        vm.tableInsertRow(below = false)
        assertEquals(3, table().rows)
        assertEquals("A", table().cell(1, 0))
        assertEquals(NoteCellRef(id, 0, 0), vm.state.value.activeCell)
        vm.tableInsertColumn(right = true)
        assertEquals(3, table().cols)
        assertEquals(NoteCellRef(id, 0, 1), vm.state.value.activeCell)
        vm.tableDeleteColumn()
        vm.tableDeleteRow()
        assertEquals(2 to 2, table().rows to table().cols)
        assertEquals("A", table().cell(0, 0))

        vm.tableToggleHeaderRow()
        vm.tableToggleGridLines()
        vm.tableShadeCell(TABLE_SHADES.first().first)
        vm.tableStepColumnWidth(+1)
        val tools = vm.tableToolState()!!
        assertEquals(true, tools.headerRow)
        assertEquals(false, tools.gridLines)
        assertEquals(TABLE_SHADES.first().first, tools.fill)
        assertEquals(TABLE_DEFAULT_COL_WIDTH + TABLE_COL_WIDTH_STEP, tools.columnWidth)
        vm.tableFitColumns()
        assertNull(vm.tableToolState()!!.columnWidth)

        vm.undo()
        assertEquals(TABLE_DEFAULT_COL_WIDTH + TABLE_COL_WIDTH_STEP, table().widthAt(tools.col))
        vm.onTableCellFocused(id, 0, 0)
        vm.tableDelete()
        assertEquals(0, vm.state.value.blocks.count { it.type == NoteDocBlockType.TABLE })
        assertNull(vm.state.value.dialog)
    }

    @Test fun tableToolOpensTheCreateStripOrTheToolsForAFocusedCell() = editorTest {
        val vm = NotesViewModel("task-table-tool", "Note", actions, ids)
        val first = vm.state.value.blocks.single().id
        vm.onFocusChanged(first, true)
        vm.onTool(NoteTool.TABLE)
        assertEquals(NoteDialog.Table, vm.state.value.dialog)
        vm.onTool(NoteTool.TABLE)
        assertNull(vm.state.value.dialog)
        vm.insertTable(1, 1)
        val id = vm.state.value.blocks.first { it.type == NoteDocBlockType.TABLE }.id
        vm.onTool(NoteTool.TABLE)
        assertNull(vm.state.value.dialog)
        vm.onTool(NoteTool.TABLE)
        assertEquals(NoteDialog.TableTools, vm.state.value.dialog)
        // A cell focused from outside the table opens its tools.
        vm.closeDialog()
        vm.onFocusChanged(first, true)
        vm.onTableCellFocused(id, 0, 0)
        assertEquals(NoteDialog.TableTools, vm.state.value.dialog)
    }

    @Test fun tableGrowthStopsAtTheSupportedSize() = editorTest {
        val vm = NotesViewModel("task-table-max", "Note", actions, ids)
        vm.onFocusChanged(vm.state.value.blocks.single().id, true)
        vm.insertTable(NoteDocTable.MAX_ROWS, NoteDocTable.MAX_COLS)
        val id = vm.state.value.blocks.first { it.type == NoteDocBlockType.TABLE }.id
        vm.addTableRow(id, atTop = false)
        vm.addTableColumn(id)
        val table = vm.state.value.blocks.first { it.id == id }.table!!
        assertEquals(NoteDocTable.MAX_ROWS to NoteDocTable.MAX_COLS, table.rows to table.cols)
    }
}
