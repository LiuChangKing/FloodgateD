package org.geysermc.floodgate.api.netease;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

public interface NeteaseAccountApi {
    EntryType getEntryType(UUID playerUuid);

    Optional<UUID> getOriginalJavaUuid(UUID playerUuid);

    default OptionalLong getOriginalJavaUid(UUID playerUuid) {
        return OptionalLong.empty();
    }

    default OptionalLong getBedrockUid(UUID playerUuid) {
        return OptionalLong.empty();
    }

    default boolean isRealBedrockEntry(UUID playerUuid) {
        return getEntryType(playerUuid) == EntryType.BEDROCK;
    }

    default boolean isBoundJavaEntry(UUID playerUuid) {
        return getEntryType(playerUuid) == EntryType.BOUND_JAVA;
    }
}
