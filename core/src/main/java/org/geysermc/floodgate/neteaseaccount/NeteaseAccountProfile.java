package org.geysermc.floodgate.neteaseaccount;

import java.util.UUID;

public final class NeteaseAccountProfile {
    private final long bedrockUid;
    private final UUID bedrockUuid;
    private final String bedrockXuid;

    public NeteaseAccountProfile(long bedrockUid, UUID bedrockUuid, String bedrockXuid) {
        this.bedrockUid = bedrockUid;
        this.bedrockUuid = bedrockUuid;
        this.bedrockXuid = bedrockXuid;
    }

    public long bedrockUid() {
        return bedrockUid;
    }

    public UUID bedrockUuid() {
        return bedrockUuid;
    }

    public String bedrockXuid() {
        return bedrockXuid;
    }
}
