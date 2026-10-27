package com.worktrack.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real migration scripts on a real MySQL 8 server. Skipped (not failed) when Docker is not available;
 * CI has Docker, so it runs there. Uses plain Flyway: no Spring context needed.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMySqlMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36").withDatabaseName("worktrack");

    private Flyway flyway(String target, boolean baselineOnMigrate) {
        var config = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            config.target(target);
        }
        if (baselineOnMigrate) {
            config.baselineOnMigrate(true).baselineVersion("1");
        }
        return config.load();
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection c = connection(); ResultSet rs = c.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @Test
    @DisplayName("a clean database migrates from nothing to the latest version, and a second run does nothing")
    void cleanMigrate() throws Exception {
        Flyway flyway = flyway(null, false);
        flyway.clean();

        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(5);
        for (String table : new String[]{"users", "leave_requests", "daily_work_logs", "notifications", "audit_events"}) {
            assertThat(tableExists(table)).as(table).isTrue();
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate(); // checksums of applied scripts still match
        for (MigrationInfo info : flyway.info().all()) {
            assertThat(info.getState().isApplied()).as(info.getVersion() + " applied").isTrue();
        }
    }

    @Test
    @DisplayName("upgrading from V2 keeps existing rows and adds the newer tables")
    void upgradeKeepsData() throws Exception {
        Flyway old = flyway("2", false);
        old.clean();
        old.migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO users (full_name,email,password_hash,department,job_title,joining_date,status,role,created_at,updated_at)"
                    + " VALUES ('Old User','old@example.com','x','Ops','Analyst','2024-01-01','ACTIVE','EMPLOYEE',NOW(6),NOW(6))");
        }
        assertThat(tableExists("notifications")).isFalse();

        var result = flyway(null, false).migrate();

        assertThat(result.success).isTrue();
        assertThat(tableExists("notifications")).isTrue();
        assertThat(tableExists("audit_events")).isTrue();
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM users WHERE email='old@example.com'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a database created before Flyway (no history table) is baselined at 1 and receives only the later scripts")
    void baselineExistingDatabase() throws Exception {
        Flyway first = flyway("1", false);
        first.clean();
        first.migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("DROP TABLE flyway_schema_history"); // what a Hibernate-created database looks like
        }

        var result = flyway(null, true).migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(4); // V2..V5
        assertThat(tableExists("audit_events")).isTrue();
    }
}
