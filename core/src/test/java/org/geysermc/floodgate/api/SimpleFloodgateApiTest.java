package org.geysermc.floodgate.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.geysermc.floodgate.api.netease.EntryType;
import org.geysermc.floodgate.api.netease.NeteaseAccountApi;
import org.geysermc.floodgate.api.netease.NeteaseAccountBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class SimpleFloodgateApiTest {
    private NeteaseAccountApi installedAccountApi;

    @AfterEach
    void restoreEmptyAccountApi() {
        NeteaseAccountBridge.clearInstance(installedAccountApi);
    }

    @Test
    void boundJavaPlayerIsNotReportedAsFloodgatePlayer() {
        UUID unifiedUuid = UUID.fromString("00000000-0000-4000-8000-0000fb01f37b");
        installEntryType(EntryType.BOUND_JAVA);

        assertFalse(new SimpleFloodgateApi().isFloodgatePlayer(unifiedUuid));
    }

    @Test
    void bedrockEntryIsReportedAsFloodgatePlayer() {
        UUID bedrockUuid = UUID.fromString("00000000-0000-4000-8000-0000cf1a926c");
        installEntryType(EntryType.BEDROCK);

        assertTrue(new SimpleFloodgateApi().isFloodgatePlayer(bedrockUuid));
    }

    @Test
    void javaEntryIsNotReportedAsFloodgatePlayer() {
        installEntryType(EntryType.JAVA);

        assertFalse(new SimpleFloodgateApi().isFloodgatePlayer(UUID.randomUUID()));
    }

    @Test
    void legacyUuidFallbackRemainsAvailableWhenEntryTypeIsUnknown() {
        UUID legacyBedrockUuid = UUID.fromString("00000000-0000-4000-8000-000012345678");
        installEntryType(EntryType.UNKNOWN);

        assertTrue(new SimpleFloodgateApi().isFloodgatePlayer(legacyBedrockUuid));
        assertFalse(new SimpleFloodgateApi().isFloodgatePlayer(UUID.randomUUID()));
    }

    private void installEntryType(EntryType entryType) {
        installedAccountApi = new NeteaseAccountApi() {
            @Override
            public EntryType getEntryType(UUID playerUuid) {
                return entryType;
            }

            @Override
            public Optional<UUID> getOriginalJavaUuid(UUID playerUuid) {
                return Optional.empty();
            }
        };
        NeteaseAccountBridge.setInstance(installedAccountApi);
    }
}
