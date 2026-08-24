package org.geysermc.floodgate.neteaseaccount;

public enum JavaLoginBlockReason {
    BEDROCK_IDENTITY_LOOKUP_FAILED("网易接口未返回对应的基岩 UUID/XUID");

    private final String description;

    JavaLoginBlockReason(String description) {
        this.description = description;
    }

    public String code() {
        return name();
    }

    public String description() {
        return description;
    }
}
