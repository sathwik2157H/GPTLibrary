package com.gptlibrary.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * Initializes the isolated test database ('gptlibrary_test') if it does not yet exist,
 * by connecting to the PostgreSQL server's maintenance database ('postgres') before
 * the test DataSource and Flyway migrations are initialized.
 */
@Slf4j
public class TestDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /**
     * Strict database identifier allowlist: must start with a letter or underscore,
     * followed by letters, digits, or underscores, and cannot exceed 63 characters (PostgreSQL identifier limit).
     */
    public static final Pattern VALID_DB_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    public static boolean isValidDatabaseName(String dbName) {
        return dbName != null
                && !dbName.isBlank()
                && dbName.length() <= 63
                && VALID_DB_NAME_PATTERN.matcher(dbName).matches();
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment env = applicationContext.getEnvironment();
        String activeProfiles = String.join(",", env.getActiveProfiles());
        if (!activeProfiles.contains("test")) {
            return;
        }

        String host = env.getProperty("DB_HOST", "localhost");
        String port = env.getProperty("DB_PORT", "5432");
        String user = env.getProperty("DB_USER", "gptuser");
        String password = env.getProperty("DB_PASSWORD", "gptpassword");
        String testDbName = env.getProperty("DB_TEST_NAME", TestDatabaseSafetyGuard.ISOLATED_TEST_DB_NAME);

        if (!isValidDatabaseName(testDbName)) {
            throw new IllegalArgumentException(
                    String.format("Invalid test database name: '%s'. Database name must match [A-Za-z_][A-Za-z0-9_]* and be at most 63 characters.", testDbName)
            );
        }

        if (TestDatabaseSafetyGuard.PROTECTED_DEV_DB_NAME.equalsIgnoreCase(testDbName)) {
            throw new IllegalStateException(
                    String.format("CRITICAL TEST SAFETY VIOLATION: Test database name cannot be the development database '%s'!", testDbName)
            );
        }

        String maintenanceUrl = String.format("jdbc:postgresql://%s:%s/postgres", host, port);

        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection(maintenanceUrl, user, password);
                 Statement stmt = conn.createStatement()) {

                try (ResultSet rs = stmt.executeQuery(
                        String.format("SELECT 1 FROM pg_database WHERE datname = '%s'", testDbName))) {
                    if (!rs.next()) {
                        log.info("Test database '{}' does not exist. Creating it now...", testDbName);
                        stmt.execute(String.format("CREATE DATABASE %s", testDbName));
                        log.info("Test database '{}' successfully created.", testDbName);
                    } else {
                        log.debug("Test database '{}' already exists.", testDbName);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not check/create test database '{}' via maintenance connection (URL: {}): {}",
                    testDbName, maintenanceUrl, e.getMessage());
        }
    }
}
