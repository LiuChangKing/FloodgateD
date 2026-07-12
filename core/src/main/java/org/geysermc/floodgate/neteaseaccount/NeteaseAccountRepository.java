package org.geysermc.floodgate.neteaseaccount;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class NeteaseAccountRepository {
    private static final DateTimeFormatter READABLE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LEGACY_FLOODGATE_UUID_PREFIX = "00000000-0000-4000-8000-0000";

    private final DataSource dataSource;
    private final String tableName;

    public NeteaseAccountRepository(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = sanitizeIdentifier(tableName);
    }

    public void initialize() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tableName + " ("
                    + "bedrock_uid BIGINT NOT NULL PRIMARY KEY, "
                    + "bedrock_uuid VARCHAR(36) NOT NULL UNIQUE, "
                    + "bedrock_xuid VARCHAR(64) NOT NULL, "
                    + "created_at VARCHAR(19) NOT NULL, "
                    + "updated_at VARCHAR(19) NOT NULL, "
                    + "last_seen_at VARCHAR(19) NOT NULL"
                    + ")");
        }
    }

    public Optional<NeteaseAccountProfile> findByBedrockUid(long bedrockUid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT bedrock_uid, bedrock_uuid, bedrock_xuid FROM " + tableName
                             + " WHERE bedrock_uid = ?")) {
            statement.setLong(1, bedrockUid);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readProfile(resultSet));
            }
        }
    }

    public Optional<NeteaseAccountProfile> findByBedrockUuid(UUID bedrockUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT bedrock_uid, bedrock_uuid, bedrock_xuid FROM " + tableName
                             + " WHERE bedrock_uuid = ?")) {
            statement.setString(1, bedrockUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readProfile(resultSet));
            }
        }
    }

    public void upsertBedrockProfile(long bedrockUid, UUID bedrockUuid, String bedrockXuid) throws SQLException {
        String now = readableNow();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + tableName
                             + " (bedrock_uid, bedrock_uuid, bedrock_xuid, created_at, updated_at, last_seen_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE "
                             + "bedrock_uid = VALUES(bedrock_uid), "
                             + "bedrock_uuid = VALUES(bedrock_uuid), "
                             + "bedrock_xuid = VALUES(bedrock_xuid), "
                             + "updated_at = VALUES(updated_at), "
                             + "last_seen_at = VALUES(last_seen_at)")) {
            statement.setLong(1, bedrockUid);
            statement.setString(2, bedrockUuid.toString());
            statement.setString(3, normalizeXuid(bedrockXuid, bedrockUuid));
            statement.setString(4, now);
            statement.setString(5, now);
            statement.setString(6, now);
            statement.executeUpdate();
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

    public List<LocalProfile> listLocalProfiles(String type) throws SQLException {
        List<LocalProfile> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, name, name_origin, pc_pe FROM localprofile WHERE pc_pe = ? ORDER BY id")) {
            statement.setString(1, type.toLowerCase());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new LocalProfile(
                            UUID.fromString(resultSet.getString("id")),
                            resultSet.getString("name"),
                            resultSet.getString("name_origin"),
                            resultSet.getString("pc_pe")
                    ));
                }
            }
        }
        return result;
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

    public static String deriveBedrockXuid(UUID bedrockUuid) {
        if (bedrockUuid == null) {
            return "";
        }
        String value = bedrockUuid.toString();
        if (value.startsWith(LEGACY_FLOODGATE_UUID_PREFIX) && value.length() >= 36) {
            return value.substring(28);
        }
        return value;
    }

    private static NeteaseAccountProfile readProfile(ResultSet resultSet) throws SQLException {
        return new NeteaseAccountProfile(
                resultSet.getLong("bedrock_uid"),
                UUID.fromString(resultSet.getString("bedrock_uuid")),
                resultSet.getString("bedrock_xuid")
        );
    }

    private static String normalizeXuid(String bedrockXuid, UUID bedrockUuid) {
        if (bedrockXuid == null || bedrockXuid.trim().isEmpty()) {
            return deriveBedrockXuid(bedrockUuid);
        }
        return bedrockXuid.trim();
    }

    private static String sanitizeIdentifier(String value) {
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name: " + value);
        }
        return value;
    }

    private static String readableNow() {
        return LocalDateTime.now().format(READABLE_TIME_FORMAT);
    }
}
