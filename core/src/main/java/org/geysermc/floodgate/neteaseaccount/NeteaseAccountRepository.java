package org.geysermc.floodgate.neteaseaccount;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.geysermc.floodgate.api.netease.NeteasePlayerProfile;

public final class NeteaseAccountRepository {
    private static final DateTimeFormatter READABLE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LEGACY_FLOODGATE_UUID_PREFIX = "00000000-0000-4000-8000-0000";
    private static final String LOGIN_LOG_TABLE_NAME = "netease_account_login_log";
    private static final String JAVA_LOGIN_RECORD_TABLE_NAME = "netease_java_login_record";
    private static final String PLAYER_JOIN_RECORD_TABLE_NAME = "netease_player_join_record";

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
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + LOGIN_LOG_TABLE_NAME + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "java_name VARCHAR(64) NOT NULL, "
                    + "java_uuid VARCHAR(36) NOT NULL, "
                    + "java_uid BIGINT NOT NULL, "
                    + "bedrock_uid BIGINT NOT NULL, "
                    + "reason_code VARCHAR(64) NOT NULL, "
                    + "reason VARCHAR(255) NOT NULL, "
                    + "created_at VARCHAR(19) NOT NULL, "
                    + "PRIMARY KEY (id), "
                    + "INDEX idx_java_uuid (java_uuid), "
                    + "INDEX idx_bedrock_uid (bedrock_uid), "
                    + "INDEX idx_created_at (created_at)"
                    + ") DEFAULT CHARACTER SET utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + JAVA_LOGIN_RECORD_TABLE_NAME + " ("
                    + "java_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "java_name VARCHAR(64) NOT NULL, "
                    + "java_uid BIGINT NOT NULL, "
                    + "bedrock_uid BIGINT NOT NULL, "
                    + "bedrock_uuid VARCHAR(36) NOT NULL, "
                    + "final_name VARCHAR(64) NOT NULL, "
                    + "first_login_at VARCHAR(19) NOT NULL, "
                    + "last_login_at VARCHAR(19) NOT NULL, "
                    + "login_count BIGINT NOT NULL DEFAULT 1, "
                    + "last_ip VARCHAR(45) NOT NULL, "
                    + "INDEX idx_java_uid (java_uid), "
                    + "INDEX idx_bedrock_uid (bedrock_uid), "
                    + "INDEX idx_bedrock_uuid (bedrock_uuid), "
                    + "INDEX idx_last_login_at (last_login_at)"
                    + ") DEFAULT CHARACTER SET utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + PLAYER_JOIN_RECORD_TABLE_NAME + " ("
                    + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "first_join_at VARCHAR(19) NOT NULL, "
                    + "last_join_at VARCHAR(19) NOT NULL, "
                    + "join_count BIGINT NOT NULL DEFAULT 1, "
                    + "INDEX idx_last_join_at (last_join_at)"
                    + ") DEFAULT CHARACTER SET utf8mb4");
            statement.executeUpdate("INSERT IGNORE INTO " + PLAYER_JOIN_RECORD_TABLE_NAME
                    + " (player_uuid, first_join_at, last_join_at, join_count) "
                    + "SELECT bedrock_uuid, created_at, last_seen_at, 0 FROM " + tableName);
        }
    }

    public void recordPlayerJoin(UUID playerUuid) throws SQLException {
        if (playerUuid == null) {
            throw new SQLException("Player UUID cannot be null");
        }
        String now = readableNow();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + PLAYER_JOIN_RECORD_TABLE_NAME
                             + " (player_uuid, first_join_at, last_join_at, join_count) "
                             + "VALUES (?, ?, ?, 1) "
                             + "ON DUPLICATE KEY UPDATE "
                             + "last_join_at = VALUES(last_join_at), "
                             + "join_count = join_count + 1")) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, now);
            statement.setString(3, now);
            statement.executeUpdate();
        }
    }

    public Optional<NeteasePlayerProfile> findPlayerProfile(UUID playerUuid) throws SQLException {
        if (playerUuid == null) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT localprofile.id, localprofile.name, "
                             + PLAYER_JOIN_RECORD_TABLE_NAME + ".first_join_at, "
                             + PLAYER_JOIN_RECORD_TABLE_NAME + ".last_join_at "
                             + "FROM localprofile LEFT JOIN " + PLAYER_JOIN_RECORD_TABLE_NAME
                             + " ON " + PLAYER_JOIN_RECORD_TABLE_NAME + ".player_uuid = localprofile.id "
                             + "WHERE localprofile.id = ?")) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new NeteasePlayerProfile(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("name"),
                        parseTime(resultSet.getString("first_join_at")),
                        parseTime(resultSet.getString("last_join_at"))));
            }
        }
    }

    public void insertBlockedJavaLogin(
            String javaName,
            UUID javaUuid,
            long javaUid,
            long bedrockUid,
            JavaLoginBlockReason reasonCode,
            String reason) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + LOGIN_LOG_TABLE_NAME
                             + " (java_name, java_uuid, java_uid, bedrock_uid, reason_code, reason, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, javaName);
            statement.setString(2, javaUuid.toString());
            statement.setLong(3, javaUid);
            statement.setLong(4, bedrockUid);
            statement.setString(5, reasonCode.code());
            statement.setString(6, reason);
            statement.setString(7, readableNow());
            statement.executeUpdate();
        }
    }

    public void recordSuccessfulJavaLogin(
            String javaName,
            UUID javaUuid,
            long javaUid,
            long bedrockUid,
            UUID bedrockUuid,
            String finalName,
            String lastIp) throws SQLException {
        String now = readableNow();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + JAVA_LOGIN_RECORD_TABLE_NAME
                             + " (java_uuid, java_name, java_uid, bedrock_uid, bedrock_uuid, final_name, "
                             + "first_login_at, last_login_at, login_count, last_ip) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?) "
                             + "ON DUPLICATE KEY UPDATE "
                             + "java_name = VALUES(java_name), "
                             + "java_uid = VALUES(java_uid), "
                             + "bedrock_uid = VALUES(bedrock_uid), "
                             + "bedrock_uuid = VALUES(bedrock_uuid), "
                             + "final_name = VALUES(final_name), "
                             + "last_login_at = VALUES(last_login_at), "
                             + "login_count = login_count + 1, "
                             + "last_ip = VALUES(last_ip)")) {
            statement.setString(1, javaUuid.toString());
            statement.setString(2, javaName);
            statement.setLong(3, javaUid);
            statement.setLong(4, bedrockUid);
            statement.setString(5, bedrockUuid.toString());
            statement.setString(6, finalName);
            statement.setString(7, now);
            statement.setString(8, now);
            statement.setString(9, lastIp);
            statement.executeUpdate();
        }
    }

    public boolean hasAnyProfile() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM " + tableName + " LIMIT 1");
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
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

    public synchronized void upsertBedrockProfile(
            long bedrockUid,
            UUID bedrockUuid,
            String bedrockXuid) throws SQLException {
        if (!NeteaseUidResolver.isValidBedrockUid(bedrockUid)) {
            throw new SQLException("Invalid Bedrock NetEase UID: " + bedrockUid);
        }
        if (bedrockUuid == null) {
            throw new SQLException("Bedrock UUID cannot be null");
        }

        String now = readableNow();
        String uuid = bedrockUuid.toString();
        String xuid = normalizeXuid(bedrockXuid, bedrockUuid);
        try (Connection connection = dataSource.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean existing = validateExistingMapping(connection, bedrockUid, uuid);
                if (existing) {
                    updateExistingProfile(connection, bedrockUid, uuid, xuid, now);
                } else {
                    insertProfile(connection, bedrockUid, uuid, xuid, now);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
    }

    private boolean validateExistingMapping(Connection connection, long bedrockUid, String bedrockUuid)
            throws SQLException {
        boolean found = false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT bedrock_uid, bedrock_uuid FROM " + tableName
                        + " WHERE bedrock_uid = ? OR bedrock_uuid = ? FOR UPDATE")) {
            statement.setLong(1, bedrockUid);
            statement.setString(2, bedrockUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long existingUid = resultSet.getLong("bedrock_uid");
                    String existingUuid = resultSet.getString("bedrock_uuid");
                    if (existingUid != bedrockUid || !bedrockUuid.equalsIgnoreCase(existingUuid)) {
                        throw new SQLException("NetEase account mapping conflict: requested uid="
                                + bedrockUid + ", uuid=" + bedrockUuid + ", existing uid="
                                + existingUid + ", uuid=" + existingUuid);
                    }
                    found = true;
                }
            }
        }
        return found;
    }

    private void insertProfile(
            Connection connection,
            long bedrockUid,
            String bedrockUuid,
            String bedrockXuid,
            String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + tableName
                        + " (bedrock_uid, bedrock_uuid, bedrock_xuid, created_at, updated_at, last_seen_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, bedrockUid);
            statement.setString(2, bedrockUuid);
            statement.setString(3, bedrockXuid);
            statement.setString(4, now);
            statement.setString(5, now);
            statement.setString(6, now);
            statement.executeUpdate();
        }
    }

    private void updateExistingProfile(
            Connection connection,
            long bedrockUid,
            String bedrockUuid,
            String bedrockXuid,
            String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + tableName
                        + " SET bedrock_xuid = ?, updated_at = ?, last_seen_at = ?"
                        + " WHERE bedrock_uid = ? AND bedrock_uuid = ?")) {
            statement.setString(1, bedrockXuid);
            statement.setString(2, now);
            statement.setString(3, now);
            statement.setLong(4, bedrockUid);
            statement.setString(5, bedrockUuid);
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

    private static LocalDateTime parseTime(String value) throws SQLException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, READABLE_TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new SQLException("Invalid readable player time: " + value, exception);
        }
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
