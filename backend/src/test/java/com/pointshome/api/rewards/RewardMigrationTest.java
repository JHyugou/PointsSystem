package com.pointshome.api.rewards;

import java.sql.DriverManager;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RewardMigrationTest {
    @Test
    void existingPaidReceiptsAndPointBalancesSurviveUpgrade(@TempDir Path folder) throws Exception {
        String url = "jdbc:h2:file:" + folder.resolve("reward-upgrade").toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        Flyway.configure().dataSource(url, "sa", "").target("7").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO app_user(id,role,email,display_name,password_hash,created_at) VALUES('parent','GUARDIAN','p@example.com','Parent','hash',1)");
            s.executeUpdate("INSERT INTO app_user(id,role,username,display_name,password_hash,created_at) VALUES('child','CHILD','child','Child','hash',1)");
            s.executeUpdate("INSERT INTO point_account(child_id,balance) VALUES('child',42)");
            s.executeUpdate("INSERT INTO reward_item(id,child_id,created_by,name,cost,created_at) VALUES('gift','child','parent','Existing reward',8,1)");
            s.executeUpdate("INSERT INTO point_action(id,child_id,actor_id,request_id,kind,amount,status,created_at,applied_at) VALUES('paid','child','child','00000000-0000-0000-0000-000000000001','REWARD_REDEMPTION',-8,'APPLIED',1,1)");
            s.executeUpdate("INSERT INTO reward_redemption(id,child_id,reward_item_id,action_id,reward_name,cost,status,requested_at) VALUES('receipt','child','gift','paid','Existing reward',8,'REQUESTED',1)");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT action_id,request_id,reward_kind,status FROM reward_redemption WHERE id='receipt'")) {
                assertTrue(r.next()); assertEquals("paid",r.getString(1));
                assertEquals("00000000-0000-0000-0000-000000000001",r.getString(2));
                assertEquals("STANDARD",r.getString(3)); assertEquals("REQUESTED",r.getString(4));
            }
            try (var r = s.executeQuery("SELECT balance FROM point_account WHERE child_id='child'")) {
                assertTrue(r.next()); assertEquals(42L,r.getLong(1));
            }
            assertThrows(java.sql.SQLException.class, () -> s.executeUpdate(
                    "INSERT INTO reward_item(id,child_id,created_by,name,cost,created_at,reward_kind) VALUES('invalid','child','parent','Missing duration',1,1,'APP_TIME')"));
        }
    }
}
