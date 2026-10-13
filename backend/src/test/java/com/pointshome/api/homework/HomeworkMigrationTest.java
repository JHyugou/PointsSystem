package com.pointshome.api.homework;

import java.sql.DriverManager;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HomeworkMigrationTest {
    @Test
    void convertsExistingNumericReviewsAndKeepsTheirPointRules(@TempDir Path folder) throws Exception {
        String path = folder.resolve("homework").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE";
        Flyway.configure().dataSource(url, "sa", "").target("5").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (id, role, email, display_name, password_hash, created_at)
                    VALUES ('parent', 'GUARDIAN', 'parent@example.com', 'Parent', 'hash', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app_user (id, role, username, display_name, password_hash, created_at)
                    VALUES ('child', 'CHILD', 'child', 'Child', 'hash', 1)
                    """);
            statement.executeUpdate("INSERT INTO point_account (child_id, balance) VALUES ('child', 0)");
            statement.executeUpdate("""
                    INSERT INTO point_item (id, child_id, created_by, name, points, created_at)
                    VALUES ('reward', 'child', 'parent', 'Homework', 3, 1)
                    """);
            for (int score : new int[]{95, 70, 40}) {
                statement.executeUpdate("""
                        INSERT INTO homework_submission
                            (id, child_id, submitted_by, subject, status, parent_score, created_at)
                        VALUES ('old-%d', 'child', 'parent', 'Math', 'REVIEWED', %d, 1)
                        """.formatted(score, score));
            }
            statement.executeUpdate("""
                    INSERT INTO homework_score_rule
                        (child_id, excellent_item_id, updated_by, updated_at)
                    VALUES ('child', 'reward', 'parent', 1)
                    """);
        }

        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT parent_rating FROM homework_submission ORDER BY parent_score DESC
                    """)) {
                rows.next(); assertEquals("GOOD", rows.getString(1));
                rows.next(); assertEquals("AVERAGE", rows.getString(1));
                rows.next(); assertEquals("POOR", rows.getString(1));
            }
            try (var rows = statement.executeQuery("""
                    SELECT good_item_id FROM homework_rating_rule WHERE child_id = 'child'
                    """)) {
                rows.next(); assertEquals("reward", rows.getString(1));
            }
        }
    }
}
