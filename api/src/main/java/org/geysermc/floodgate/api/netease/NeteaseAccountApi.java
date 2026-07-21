package org.geysermc.floodgate.api.netease;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface NeteaseAccountApi {
    EntryType getEntryType(UUID playerUuid);

    Optional<UUID> getOriginalJavaUuid(UUID playerUuid);

    default OptionalLong getOriginalJavaUid(UUID playerUuid) {
        return OptionalLong.empty();
    }

    default OptionalLong getBedrockUid(UUID playerUuid) {
        return OptionalLong.empty();
    }

    /**
     * Looks up the persistent network profile for a UUID. The returned name is the final name in
     * {@code localprofile.name}, and therefore includes collision renames or later name changes.
     * The query is asynchronous and may access the proxy database.
     *
     * @param playerUuid the current unified player UUID
     * @return the profile when the UUID exists in {@code localprofile}
     */
    default CompletableFuture<Optional<NeteasePlayerProfile>> getPlayerProfile(UUID playerUuid) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Looks up the final in-game name stored in {@code localprofile.name}.
     */
    default CompletableFuture<Optional<String>> getPlayerName(UUID playerUuid) {
        return getPlayerProfile(playerUuid).thenApply(
                profile -> profile.map(NeteasePlayerProfile::getName));
    }

    /**
     * Looks up the first successful proxy login known to Floodgate.
     */
    default CompletableFuture<Optional<LocalDateTime>> getFirstJoinTime(UUID playerUuid) {
        return getPlayerProfile(playerUuid).thenApply(
                profile -> profile.flatMap(NeteasePlayerProfile::getFirstJoinTime));
    }

    /**
     * Looks up the most recent successful proxy login known to Floodgate.
     */
    default CompletableFuture<Optional<LocalDateTime>> getLastJoinTime(UUID playerUuid) {
        return getPlayerProfile(playerUuid).thenApply(
                profile -> profile.flatMap(NeteasePlayerProfile::getLastJoinTime));
    }

    default boolean isRealBedrockEntry(UUID playerUuid) {
        return getEntryType(playerUuid) == EntryType.BEDROCK;
    }

    default boolean isBoundJavaEntry(UUID playerUuid) {
        return getEntryType(playerUuid) == EntryType.BOUND_JAVA;
    }
}
