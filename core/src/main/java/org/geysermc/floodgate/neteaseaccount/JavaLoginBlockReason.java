package org.geysermc.floodgate.neteaseaccount;

public enum JavaLoginBlockReason {
    BEDROCK_PROFILE_NOT_FOUND("netease_account_profile 中没有对应的基岩账户档案"),
    BEDROCK_LOCAL_PROFILE_NOT_FOUND("账户映射存在，但 localprofile 缺少对应的 PE 档案");

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
