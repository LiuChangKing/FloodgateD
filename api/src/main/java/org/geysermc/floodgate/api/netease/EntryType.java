package org.geysermc.floodgate.api.netease;

public enum EntryType {
    JAVA,
    BEDROCK,
    BOUND_JAVA,
    /** Retained only so mixed-version backend servers can parse legacy forwarded profiles. */
    @Deprecated
    UNRESOLVED_JAVA,
    UNKNOWN
}
