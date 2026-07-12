package org.geysermc.floodgate.neteaseaccount;

import java.util.UUID;

public final class LocalProfile {
    private final UUID id;
    private final String name;
    private final String nameOrigin;
    private final String type;

    public LocalProfile(UUID id, String name, String nameOrigin, String type) {
        this.id = id;
        this.name = name;
        this.nameOrigin = nameOrigin;
        this.type = type;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String nameOrigin() {
        return nameOrigin;
    }

    public String type() {
        return type;
    }

    public String displayName() {
        return nameOrigin == null || nameOrigin.isEmpty() ? name : nameOrigin;
    }
}
