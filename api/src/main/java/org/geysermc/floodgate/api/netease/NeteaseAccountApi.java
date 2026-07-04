package org.geysermc.floodgate.api.netease;

import java.util.Optional;
import java.util.UUID;

public interface NeteaseAccountApi {
    EntryType getEntryType(UUID playerUuid);

    Optional<UUID> getOriginalJavaUuid(UUID playerUuid);

    default boolean isRealBedrockEntry(UUID playerUuid) {
        return getEntryType(playerUuid) == EntryType.BEDROCK;
    }

    default boolean isBoundJavaEntry(UUID playerUuid) {
        return getEntryType(playerUuid) == EntryType.BOUND_JAVA;
    }
}
