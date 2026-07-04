package org.geysermc.floodgate.neteasebind;

import java.time.Instant;
import java.util.UUID;

public final class PendingBind {
    private final String code;
    private final UUID javaUuid;
    private final String javaName;
    private final Instant expiresAt;

    public PendingBind(String code, UUID javaUuid, String javaName, Instant expiresAt) {
        this.code = code;
        this.javaUuid = javaUuid;
        this.javaName = javaName;
        this.expiresAt = expiresAt;
    }

    public String code() {
        return code;
    }

    public UUID javaUuid() {
        return javaUuid;
    }

    public String javaName() {
        return javaName;
    }

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
