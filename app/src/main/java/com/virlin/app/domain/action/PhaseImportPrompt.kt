package com.virlin.app.domain.action

/** User-selected AI instructions shared by every bulk-import information button. */
object PhaseImportPrompt {
    val text = """
        Extract ONLY the explicitly named phases from the information below for Virlin bulk import.

        STRUCTURE ONLY — do not extract anything inside the phases.

        RULES:

        1. Include only explicitly named phases, such as "Phase 0", "Phase 1", "Phase 2", etc.
        2. Preserve the exact original phase number, title, capitalization, and source order.
        3. Do not include milestones, sections, headings, tasks, subtasks, steps, categories, topics, or any other children inside a phase.
        4. Do not create or infer phases from descriptive text.
        5. Do not paraphrase or rename phase titles.
        6. Include all phases, including later phases. Do not stop after the first few.
        7. Ignore phase descriptions, objectives, deliverables, gates, requirements, examples, tables, documents, implementation details, and all content beneath each phase.
        8. If the same phase appears more than once, include it only once.
        9. Maximum 100 phase nodes.
        10. If there are more than 100 phases, ask me to split the source. Do not silently truncate.

        OUTPUT:
        Return exactly one valid JSON object, without Markdown fences, commentary, or surrounding quotation marks.

        {
          "version": 2,
          "items": [
            {
              "name": "PHASE 0 Architecture & Baseline"
            },
            {
              "name": "PHASE 1 Application Shell"
            }
          ]
        }

        Use only:

        - "name"

        Do not include "children", descriptions, IDs, metadata, or destination fields.

        FINAL CHECK:
        Before responding, silently verify:

        - Every node is an explicitly named phase in the source.
        - No content inside a phase has been extracted.
        - All phases are included in original order.
        - Phase names are preserved exactly.
        - The JSON is valid and contains exactly one object.

        INFORMATION:
        [PASTE YOUR CONTENT HERE]
    """.trimIndent()
}
