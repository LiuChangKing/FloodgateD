package org.geysermc.floodgate.neteasebind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class BindingRepository {
    private static final DateTimeFormatter READABLE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;
    private final String tableName;

    public BindingRepository(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = sanitizeIdentifier(tableName);
    }

    public void initialize() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tableName + " ("
                        + "java_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                        + "bedrock_uuid VARCHAR(36) NOT NULL UNIQUE, "
                        + "created_at VARCHAR(19) NOT NULL, "
                        + "updated_at VARCHAR(19) NOT NULL"
                        + ")");
            }
            migrateReadableTimeColumns(connection);
        }
    }

    public Optional<Binding> findBindingByJavaUuid(UUID javaUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT java_uuid, bedrock_uuid FROM " + tableName + " WHERE java_uuid = ?")) {
            statement.setString(1, javaUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Binding(
                        UUID.fromString(resultSet.getString("java_uuid")),
                        UUID.fromString(resultSet.getString("bedrock_uuid"))
                ));
            }
        }
    }

    public Optional<Binding> findBindingByBedrockUuid(UUID bedrockUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT java_uuid, bedrock_uuid FROM " + tableName + " WHERE bedrock_uuid = ?")) {
            statement.setString(1, bedrockUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Binding(
                        UUID.fromString(resultSet.getString("java_uuid")),
                        UUID.fromString(resultSet.getString("bedrock_uuid"))
                ));
            }
        }
    }

    public boolean isJavaBound(UUID javaUuid) throws SQLException {
        return findBindingByJavaUuid(javaUuid).isPresent();
    }

    public boolean isBedrockBound(UUID bedrockUuid) throws SQLException {
        return findBindingByBedrockUuid(bedrockUuid).isPresent();
    }

    public void createBinding(UUID javaUuid, UUID bedrockUuid) throws SQLException {
        String now = readableNow();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + tableName + " (java_uuid, bedrock_uuid, created_at, updated_at) "
                             + "VALUES (?, ?, ?, ?)")) {
            statement.setString(1, javaUuid.toString());
            statement.setString(2, bedrockUuid.toString());
            statement.setString(3, now);
            statement.setString(4, now);
            statement.executeUpdate();
        }
    }

    public boolean deleteBindingByJavaUuid(UUID javaUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + tableName + " WHERE java_uuid = ?")) {
            statement.setString(1, javaUuid.toString());
            return statement.executeUpdate() > 0;
        }
    }

    public Optional<LocalProfile> findLocalProfile(UUID id, String type) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, name_origin, pc_pe FROM localprofile WHERE id = ? AND pc_pe = ?")) {
            statement.setString(1, id.toString());
            statement.setString(2, type.toLowerCase());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LocalProfile(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("name"),
                        resultSet.getString("name_origin"),
                        resultSet.getString("pc_pe")
                ));
            }
        }
    }

    public void ensureLocalProfile(UUID id, String name, String type) throws SQLException {
        if (findLocalProfile(id, type).isPresent()) {
            return;
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO localprofile(id, name, name_origin, pc_pe) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, id.toString());
            statement.setString(2, name);
            statement.setString(3, name);
            statement.setString(4, type.toLowerCase());
            statement.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException ignored) {
            // Floodgate may have inserted the same profile earlier in the login pipeline.
        }
    }

    private static String sanitizeIdentifier(String value) {
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name: " + value);
        }
        return value;
    }

    private void migrateReadableTimeColumns(Connection connection) throws SQLException {
        migrateReadableTimeColumn(connection, "created_at");
        migrateReadableTimeColumn(connection, "updated_at");
    }

    private void migrateReadableTimeColumn(Connection connection, String columnName) throws SQLException {
        String safeColumn = sanitizeIdentifier(columnName);
        Optional<String> dataType = findColumnDataType(connection, safeColumn);
        if (dataType.isPresent() && isDatabaseNativeTimeType(dataType.get())) {
            return;
        }

        if (!dataType.isPresent() || !isCharacterTimeType(dataType.get())) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + tableName + " MODIFY " + safeColumn + " VARCHAR(19) NOT NULL");
            }
        }
        migrateNumericTimeValues(connection, safeColumn);
    }

    private Optional<String> findColumnDataType(Connection connection, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(resultSet.getString("DATA_TYPE"));
            }
        }
    }

    private static boolean isDatabaseNativeTimeType(String dataType) {
        return "datetime".equalsIgnoreCase(dataType)
                || "timestamp".equalsIgnoreCase(dataType);
    }

    private static boolean isCharacterTimeType(String dataType) {
        return "varchar".equalsIgnoreCase(dataType)
                || "char".equalsIgnoreCase(dataType)
                || "text".equalsIgnoreCase(dataType);
    }

    private void migrateNumericTimeValues(Connection connection, String columnName) throws SQLException {
        Map<String, String> updates = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT java_uuid, " + columnName + " FROM " + tableName
                        + " WHERE " + columnName + " REGEXP '^[0-9]{10,13}$'");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String javaUuid = resultSet.getString("java_uuid");
                String value = resultSet.getString(columnName);
                try {
                    updates.put(javaUuid, readableEpoch(value));
                } catch (NumberFormatException ignored) {
                    // Leave unexpected values untouched instead of blocking startup.
                }
            }
        }

        if (updates.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + tableName + " SET " + columnName + " = ? WHERE java_uuid = ?")) {
            for (Map.Entry<String, String> entry : updates.entrySet()) {
                statement.setString(1, entry.getValue());
                statement.setString(2, entry.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static String readableNow() {
        return LocalDateTime.now().format(READABLE_TIME_FORMAT);
    }

    private static String readableEpoch(String value) {
        String normalized = value.trim();
        long epoch = Long.parseLong(normalized);
        if (normalized.length() <= 10) {
            epoch *= 1000L;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.systemDefault())
                .format(READABLE_TIME_FORMAT);
    }
}
