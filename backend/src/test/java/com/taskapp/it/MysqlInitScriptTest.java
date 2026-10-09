package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * AC15: {@code docker/mysql-init/01-app-user.sh} creates the restricted app user on a fresh volume.
 * The MySQL entrypoint executes init scripts that look executable and sources the others; Docker Desktop
 * for Mac can make a 644 bind mount look executable, so the script must work both ways.
 */
class MysqlInitScriptTest {

    static final Path SCRIPT = Path.of("docker/mysql-init/01-app-user.sh");
    static final String APP_USER = "taskapp_app";
    static final String APP_PASSWORD = "app'pw\\1";

    @Test
    void scriptIsExecutableWithShebang() throws Exception {
        assertThat(Files.readAllLines(SCRIPT).get(0)).isEqualTo("#!/bin/sh");
        assertThat(Files.getPosixFilePermissions(SCRIPT)).contains(PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);
    }

    @ParameterizedTest(name = "mode {0} -> entrypoint {1} it")
    @CsvSource({"0755, running", "0644, sourcing"})
    void createsAppUserWithDataOnlyGrants(String mode, String entrypointAction) throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("taskapp")
                .withUsername("taskapp")
                .withPassword("migration-pw")
                .withEnv("APP_DB_USER", APP_USER)
                .withEnv("APP_DB_PASSWORD", APP_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath(SCRIPT, Integer.parseInt(mode, 8)),
                        "/docker-entrypoint-initdb.d/01-app-user.sh")) {
            mysql.start();

            assertThat(mysql.getLogs())
                    .contains(entrypointAction + " /docker-entrypoint-initdb.d/01-app-user.sh")
                    .contains("01-app-user.sh: creating/updating user 'taskapp_app'");

            try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), APP_USER, APP_PASSWORD);
                    Statement st = c.createStatement()) {
                List<String> grants = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SHOW GRANTS")) {
                    while (rs.next()) {
                        grants.add(rs.getString(1));
                    }
                }
                assertThat(grants).containsExactly(
                        "GRANT USAGE ON *.* TO `taskapp_app`@`%`",
                        "GRANT SELECT, INSERT, UPDATE, DELETE ON `taskapp`.* TO `taskapp_app`@`%`");
                assertThatThrownBy(() -> st.execute("CREATE TABLE x (id int)"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("CREATE command denied to user 'taskapp_app'@");
            }
        }
    }
}
