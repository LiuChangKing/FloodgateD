package org.geysermc.floodgate.neteasebind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class BindingRepository {
    private final DataSource dataSource;
    private final String tableName;

    public BindingRepository(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = sanitizeIdentifier(tableName);
    }

    public void initialize() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                             + "java_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                             + "bedrock_uuid VARCHAR(36) NOT NULL UNIQUE, "
                             + "created_at BIGINT NOT NULL, "
                             + "updated_at BIGINT NOT NULL"
                             + ")")) {
            statement.executeUpdate();
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
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + tableName + " (java_uuid, bedrock_uuid, created_at, updated_at) "
                             + "VALUES (?, ?, ?, ?)")) {
            statement.setString(1, javaUuid.toString());
            statement.setString(2, bedrockUuid.toString());
            statement.setLong(3, now);
            statement.setLong(4, now);
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
}
