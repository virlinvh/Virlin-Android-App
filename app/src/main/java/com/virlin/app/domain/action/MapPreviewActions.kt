package com.virlin.app.domain.action

import com.virlin.app.domain.mappreview.FormattedText
import com.virlin.app.domain.mappreview.MapNodePreview
import com.virlin.app.domain.mappreview.MapPreviewImage
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock

/**
 * The product-facing operations for mind-map node previews.
 *
 * Every rule that matters lives here rather than in the editor, because the UI is not a safety
 * boundary: the seven-image cap, the 180-character cap, the refusal to store a meaningless empty
 * preview and the refusal to let one node's preview be written under another node's id all hold
 * however the call arrives. The UI only hides affordances; this is what actually enforces them.
 *
 * Composed into [DefaultVirlinActions] in the same way as `StructureActions` and `NoteDocActions`,
 * so there is no second facade for the Agent or any future input layer to find.
 */
internal class MapPreviewActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
) {

    suspend fun load(ownerId: String): MapNodePreview? =
        if (ownerId.isBlank()) null else repository.getMapNodePreview(ownerId)

    suspend fun loadAll(): Map<String, MapNodePreview> = repository.allMapNodePreviews()

    /**
     * Saves one node's preview atomically.
     *
     * The description and the ordered image metadata commit in a single transaction, so the map can
     * never show a description from one save beside images from another. A preview whose
     * description and image list are both empty is **deleted** rather than stored: an empty row
     * would make a node report that it has a preview when it has nothing to show.
     */
    suspend fun save(
        ownerId: String,
        description: FormattedText,
        images: List<MapPreviewImage>,
        expanded: Boolean = false,
    ): ActionResult<MapNodePreview?> {
        if (ownerId.isBlank()) return ActionResult.Rejected(DomainError.InvalidPreview)
        if (images.size > MapNodePreview.MAX_IMAGES) {
            return ActionResult.Rejected(DomainError.InvalidPreview)
        }
        // Two images may not share an id: ordering and deletion both key on it.
        if (images.map { it.id }.distinct().size != images.size) {
            return ActionResult.Rejected(DomainError.InvalidPreview)
        }
        if (images.any { it.relativePath.isBlank() }) {
            return ActionResult.Rejected(DomainError.InvalidPreview)
        }

        val trimmed = description.normalized().let {
            if (it.text.length <= MapNodePreview.MAX_DESCRIPTION) it
            else FormattedText(it.text.take(MapNodePreview.MAX_DESCRIPTION), it.runs).normalized()
        }
        val now = clock.now()
        val preview = MapNodePreview(
            ownerId = ownerId,
            description = trimmed,
            images = images,
            expanded = expanded,
            updatedAt = now,
        )
        return runCatching {
            repository.transaction {
                if (preview.isEmpty) deleteMapNodePreview(ownerId) else saveMapNodePreview(preview)
            }
            ActionResult.Success(if (preview.isEmpty) null else preview)
        }.getOrElse { ActionResult.Failure(it) }
    }

    /**
     * Clears a node's preview completely.
     *
     * This removes the description and every image reference. It never touches the Task or
     * WorkStream the node projects, never archives or cancels it, and never touches its Task Page.
     * Managed bytes are removed by the caller that owns the Context, after this commits, so a
     * failed database write can never leave the record pointing at deleted files.
     */
    suspend fun clear(ownerId: String): ActionResult<Unit> {
        if (ownerId.isBlank()) return ActionResult.Rejected(DomainError.InvalidPreview)
        return runCatching {
            repository.transaction { deleteMapNodePreview(ownerId) }
            ActionResult.Success(Unit)
        }.getOrElse { ActionResult.Failure(it) }
    }

    /** Persists only the collapsed/expanded preference, leaving content untouched. */
    suspend fun setExpanded(ownerId: String, expanded: Boolean): ActionResult<Unit> {
        val existing = repository.getMapNodePreview(ownerId)
            ?: return ActionResult.Rejected(DomainError.InvalidPreview)
        return runCatching {
            repository.transaction { saveMapNodePreview(existing.copy(expanded = expanded)) }
            ActionResult.Success(Unit)
        }.getOrElse { ActionResult.Failure(it) }
    }

    /**
     * Whether one more image may be added. The editor asks this rather than counting itself, so the
     * add tile and the action layer can never disagree about the limit.
     */
    fun canAddImage(current: Int): Boolean = current < MapNodePreview.MAX_IMAGES
}
