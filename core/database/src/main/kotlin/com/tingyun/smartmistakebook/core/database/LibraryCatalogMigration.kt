package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val LIBRARY_CATALOG_VIEW_MIGRATION_29_30 = object : Migration(29, 30) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            // Room validates the view SQL verbatim against the exported schema,
            // so this text must match the 30.json library_catalog createSql exactly.
            "CREATE VIEW `library_catalog` AS SELECT" +
                "\n            entry.entry_id," +
                "\n            entry.problem_id," +
                "\n            revision.revision_id AS problem_revision_id," +
                "\n            entry.practice_unit_id," +
                "\n            problem.subject," +
                "\n            unit.title," +
                "\n            revision.problem_markdown," +
                "\n            entry.accepted_at_epoch_millis AS created_at_epoch_millis," +
                "\n            entry.updated_at_epoch_millis AS updated_at_epoch_millis," +
                "\n            memory.next_review_at_epoch_millis," +
                "\n            memory.retrievability," +
                "\n            (" +
                "\n                SELECT" +
                "\n                    CASE" +
                "\n                        WHEN COUNT(*) = 0 THEN 'unknown'" +
                "\n                        WHEN SUM(CASE WHEN mastery.status = 'CONFLICTED' THEN 1 ELSE 0 END) > 0" +
                "\n                            THEN 'conflicted'" +
                "\n                        WHEN SUM(CASE WHEN mastery.status = 'STALE' THEN 1 ELSE 0 END) > 0" +
                "\n                            THEN 'stale'" +
                "\n                        WHEN SUM(CASE WHEN mastery.status = 'LEARNING' THEN 1 ELSE 0 END) > 0" +
                "\n                            THEN 'learning'" +
                "\n                        WHEN SUM(CASE WHEN mastery.status = 'MASTERED' THEN 1 ELSE 0 END) =" +
                "\n                            COUNT(*) THEN 'mastered'" +
                "\n                        ELSE 'unknown'" +
                "\n                    END" +
                "\n                FROM learner_knowledge_mastery_state AS mastery" +
                "\n                INNER JOIN practice_unit_knowledge_binding AS binding" +
                "\n                    ON binding.knowledge_node_id = mastery.knowledge_node_id" +
                "\n                   AND binding.practice_unit_id = entry.practice_unit_id" +
                "\n                   AND binding.basis_revision_id = revision.revision_id" +
                "\n                WHERE mastery.projection_name = 'study-experience-v1'" +
                "\n                  AND mastery.learner_id = 'learner:local'" +
                "\n            ) AS mastery_id," +
                "\n            (" +
                "\n                SELECT GROUP_CONCAT(classification.display_name, CHAR(31))" +
                "\n                FROM problem_classification_binding AS classification" +
                "\n                WHERE classification.problem_id = entry.problem_id" +
                "\n                  AND classification.basis_revision_id = revision.revision_id" +
                "\n                  AND classification.dimension = 'CHAPTER'" +
                "\n            ) AS chapter_labels," +
                "\n            (" +
                "\n                SELECT GROUP_CONCAT(classification.display_name, CHAR(31))" +
                "\n                FROM problem_classification_binding AS classification" +
                "\n                WHERE classification.problem_id = entry.problem_id" +
                "\n                  AND classification.basis_revision_id = revision.revision_id" +
                "\n                  AND classification.dimension = 'KNOWLEDGE'" +
                "\n            ) AS knowledge_labels" +
                "\n        FROM error_book_entry AS entry" +
                "\n        JOIN practice_unit AS unit" +
                "\n            ON unit.practice_unit_id = entry.practice_unit_id" +
                "\n        JOIN problem AS problem" +
                "\n            ON problem.problem_id = entry.problem_id" +
                "\n        JOIN problem_revision AS revision" +
                "\n            ON revision.revision_id = entry.current_revision_id" +
                "\n           AND revision.problem_id = entry.problem_id" +
                "\n        LEFT JOIN problem_memory_state AS memory" +
                "\n            ON memory.practice_unit_id = entry.practice_unit_id" +
                "\n        WHERE entry.status = 'ACTIVE'",
        )
    }
}
