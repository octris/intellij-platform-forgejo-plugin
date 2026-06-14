package octris.forgejo.vcs

import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.vcs.log.ui.table.GraphTableModel
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import com.intellij.vcs.log.ui.table.column.VcsLogCustomColumn
import octris.forgejo.ForgejoBundle
import octris.forgejo.api.ForgejoCommitStatus
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Adds a "Forgejo CI" column to the VCS log that shows the Forgejo Actions / commit-status
 * result for each commit, registered via the `com.intellij.vcsLogCustomColumn` EP.
 *
 * The actual status comes from [ForgejoCommitStatusService], which loads it off the EDT
 * and caches it; [getValue] is called during painting and must not block.
 */
class ForgejoActionsColumn : VcsLogCustomColumn<ForgejoCommitStatus> {

    override val id: String = "Forgejo.Actions"

    override val localizedName: String
        get() = ForgejoBundle.message("column.name")

    override val isDynamic: Boolean = true

    override fun getValue(model: GraphTableModel, row: Int): ForgejoCommitStatus {
        val project = model.logData.project
        // Returns a lightweight placeholder (carrying the real hash) if details aren't loaded yet.
        val metadata = model.getCommitMetadata(row) ?: return ForgejoCommitStatus.UNKNOWN
        val hash = metadata.id.asString()
        return ForgejoCommitStatusService.getInstance(project).getStatus(hash)
    }

    override fun getStubValue(model: GraphTableModel): ForgejoCommitStatus = ForgejoCommitStatus.UNKNOWN

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer =
        object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int,
            ) {
                val status = value as? ForgejoCommitStatus ?: ForgejoCommitStatus.UNKNOWN
                icon = status.icon
                if (status != ForgejoCommitStatus.UNKNOWN) {
                    append(status.displayName)
                }
            }
        }
}
