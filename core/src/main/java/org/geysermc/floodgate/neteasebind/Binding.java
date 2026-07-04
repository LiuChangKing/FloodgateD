package org.geysermc.floodgate.neteasebind;

import java.util.UUID;

public final class Binding {
    private final UUID javaUuid;
    private final UUID bedrockUuid;

    public Binding(UUID javaUuid, UUID bedrockUuid) {
        this.javaUuid = javaUuid;
        this.bedrockUuid = bedrockUuid;
    }

    public UUID javaUuid() {
        return javaUuid;
    }

    public UUID bedrockUuid() {
        return bedrockUuid;
    }
}
