package com.tingyun.smartmistakebook.core.database

/**
 * Numeric mastery for a catalog row: the **weakest** conservative mastery among
 * the knowledge points the row's practice unit is bound to. Same projection and
 * same learner resolution as the view's `mastery_id`, but it keeps the number
 * instead of collapsing it to a status.
 *
 * Why it exists: `LEAST_MASTERED` used to sort on
 * `library_catalog.retrievability`, and the view defines that column as
 * `NULL AS retrievability` — every row compared equal, so the sort silently
 * fell through to `updated_at DESC` (KD-10). Retrievability is *derived* from
 * stability plus "now", which is why the view cannot expose it; the projection's
 * mastery bound is the fact that actually exists, and it is already what the
 * `mastery_id` facet and the planner's KC-drop rule read.
 *
 * This is the copy used by `RoomLibrarySearchStore`'s runtime-built FTS query.
 * `LibraryQueryDao` carries a second, private copy for its two `@Query`
 * annotations, because Room rejects a query assembled from a cross-file
 * constant (verified: both positional and `value =` concatenation fail KSP with
 * "No property named value was found in annotation Query"), while a same-file
 * constant is accepted. Two authored copies is one more than ideal, so all
 * three sort sites are pinned by behavior in
 * `LibraryLeastMasteredSortInstrumentedTest` — that test, not textual sharing,
 * is what keeps them from drifting apart.
 *
 * The correlation reads the view alias `catalog`; both call sites scope the
 * catalog that way, and the `CASE :sort` guard means SQLite only evaluates the
 * subquery when the sort actually is `LEAST_MASTERED`. `NULL` (no mastery
 * evidence) sorts first, matching the view's status ordering, which puts
 * `unknown` first.
 */
internal const val LEAST_MASTERED_MASTERY_SQL: String =
    "(" +
        "SELECT MIN(mastery.lower_bound_independent_correct) " +
        "FROM learner_knowledge_mastery_state AS mastery " +
        "INNER JOIN practice_unit_knowledge_binding AS binding " +
        "ON binding.knowledge_node_id = mastery.knowledge_node_id " +
        "AND binding.practice_unit_id = catalog.practice_unit_id " +
        "AND binding.basis_revision_id = catalog.problem_revision_id " +
        "WHERE mastery.projection_name = 'study-experience-v1' " +
        "AND mastery.learner_id = catalog.memory_learner_id" +
        ")"
