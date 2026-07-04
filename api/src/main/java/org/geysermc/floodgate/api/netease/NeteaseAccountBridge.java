package org.geysermc.floodgate.api.netease;

import java.util.Optional;
import java.util.UUID;

public final class NeteaseAccountBridge {
    private static final NeteaseAccountApi EMPTY_API = new NeteaseAccountApi() {
        @Override
        public EntryType getEntryType(UUID playerUuid) {
            return EntryType.UNKNOWN;
        }

        @Override
        public Optional<UUID> getOriginalJavaUuid(UUID playerUuid) {
            return Optional.empty();
        }
    };

    private static volatile NeteaseAccountApi instance = EMPTY_API;

    private NeteaseAccountBridge() {
    }

    public static NeteaseAccountApi getInstance() {
        return instance;
    }

    public static void setInstance(NeteaseAccountApi api) {
        instance = api == null ? EMPTY_API : api;
    }

    public static void clearInstance(NeteaseAccountApi api) {
        if (instance == api) {
            instance = EMPTY_API;
        }
    }
}
