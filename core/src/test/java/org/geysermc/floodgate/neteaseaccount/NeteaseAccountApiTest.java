package org.geysermc.floodgate.neteaseaccount;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.geysermc.floodgate.api.netease.EntryType;
import org.geysermc.floodgate.api.netease.NeteaseAccountApi;
import org.geysermc.floodgate.api.netease.NeteasePlayerProfile;
import org.junit.jupiter.api.Test;

final class NeteaseAccountApiTest {
    @Test
    void exposesNameAndJoinTimesFromPersistentProfile() {
        UUID playerUuid = UUID.fromString("00000000-0000-4000-8000-00007ed64a8d");
        LocalDateTime firstJoinTime = LocalDateTime.of(2026, 6, 30, 10, 55, 17);
        LocalDateTime lastJoinTime = LocalDateTime.of(2026, 7, 14, 8, 30, 45);
        NeteasePlayerProfile profile = new NeteasePlayerProfile(
                playerUuid, "刘畅大王", firstJoinTime, lastJoinTime);
        NeteaseAccountApi api = apiReturning(Optional.of(profile));

        assertEquals(Optional.of(profile), api.getPlayerProfile(playerUuid).join());
        assertEquals(Optional.of("刘畅大王"), api.getPlayerName(playerUuid).join());
        assertEquals(Optional.of(firstJoinTime), api.getFirstJoinTime(playerUuid).join());
        assertEquals(Optional.of(lastJoinTime), api.getLastJoinTime(playerUuid).join());
    }

    @Test
    void returnsEmptyValuesWhenProfileDoesNotExist() {
        UUID playerUuid = UUID.randomUUID();
        NeteaseAccountApi api = apiReturning(Optional.empty());

        assertEquals(Optional.empty(), api.getPlayerName(playerUuid).join());
        assertEquals(Optional.empty(), api.getFirstJoinTime(playerUuid).join());
        assertEquals(Optional.empty(), api.getLastJoinTime(playerUuid).join());
    }

    private static NeteaseAccountApi apiReturning(Optional<NeteasePlayerProfile> profile) {
        return new NeteaseAccountApi() {
            @Override
            public EntryType getEntryType(UUID playerUuid) {
                return EntryType.UNKNOWN;
            }

            @Override
            public Optional<UUID> getOriginalJavaUuid(UUID playerUuid) {
                return Optional.empty();
            }

            @Override
            public CompletableFuture<Optional<NeteasePlayerProfile>> getPlayerProfile(UUID playerUuid) {
                return CompletableFuture.completedFuture(profile);
            }
        };
    }
}
