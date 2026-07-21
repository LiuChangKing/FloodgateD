package org.geysermc.floodgate.api.netease;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent profile information maintained by the MXZC Floodgate proxy.
 */
public final class NeteasePlayerProfile {
    private final UUID uniqueId;
    private final String name;
    private final LocalDateTime firstJoinTime;
    private final LocalDateTime lastJoinTime;

    public NeteasePlayerProfile(
            UUID uniqueId,
            String name,
            LocalDateTime firstJoinTime,
            LocalDateTime lastJoinTime) {
        this.uniqueId = Objects.requireNonNull(uniqueId, "uniqueId");
        this.name = Objects.requireNonNull(name, "name");
        this.firstJoinTime = firstJoinTime;
        this.lastJoinTime = lastJoinTime;
    }

    public UUID getUniqueId() {
        return uniqueId;
    }

    public String getName() {
        return name;
    }

    /**
     * Returns the first successful proxy login known to Floodgate.
     */
    public Optional<LocalDateTime> getFirstJoinTime() {
        return Optional.ofNullable(firstJoinTime);
    }

    /**
     * Returns the most recent successful proxy login known to Floodgate.
     */
    public Optional<LocalDateTime> getLastJoinTime() {
        return Optional.ofNullable(lastJoinTime);
    }
}
