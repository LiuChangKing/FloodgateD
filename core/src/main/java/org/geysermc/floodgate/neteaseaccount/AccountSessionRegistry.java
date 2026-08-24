package org.geysermc.floodgate.neteaseaccount;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.geysermc.floodgate.api.netease.EntryType;

public final class AccountSessionRegistry<T> {
    private final ConcurrentMap<UUID, Session<T>> sessions = new ConcurrentHashMap<>();

    public Session<T> putIfAbsent(Session<T> session) {
        return sessions.putIfAbsent(session.accountUuid(), session);
    }

    public boolean replace(Session<T> expected, Session<T> replacement) {
        return sessions.replace(expected.accountUuid(), expected, replacement);
    }

    public boolean remove(Session<T> session) {
        return sessions.remove(session.accountUuid(), session);
    }

    public boolean removeOwned(UUID accountUuid, UUID token) {
        Session<T> session = sessions.get(accountUuid);
        return session != null
                && session.token().equals(token)
                && sessions.remove(accountUuid, session);
    }

    public Session<T> get(UUID accountUuid) {
        return sessions.get(accountUuid);
    }

    public Session<T> findOwned(UUID accountUuid, UUID token) {
        Session<T> session = sessions.get(accountUuid);
        return session != null && session.token().equals(token) ? session : null;
    }

    public void clear() {
        sessions.clear();
    }

    public static ConflictAction conflictAction(EntryType incoming, EntryType existing) {
        if (incoming == null) {
            throw new IllegalArgumentException("Incoming entry type cannot be null");
        }
        if (existing == null) {
            return ConflictAction.NONE;
        }
        if (incoming == EntryType.BEDROCK) {
            return ConflictAction.TAKE_OVER_EXISTING;
        }
        if (incoming == EntryType.BOUND_JAVA && existing == EntryType.BEDROCK) {
            return ConflictAction.NOTIFY_BEDROCK_AND_DENY;
        }
        return ConflictAction.DENY;
    }

    public enum ConflictAction {
        NONE,
        TAKE_OVER_EXISTING,
        NOTIFY_BEDROCK_AND_DENY,
        DENY
    }

    public static final class Session<T> {
        private final UUID accountUuid;
        private final UUID token;
        private final EntryType entryType;
        private final T connection;
        private final UUID originalJavaUuid;
        private final Long javaUid;
        private final Long bedrockUid;
        private final String javaLoginName;

        public Session(
                UUID accountUuid,
                UUID token,
                EntryType entryType,
                T connection,
                UUID originalJavaUuid,
                Long javaUid,
                Long bedrockUid,
                String javaLoginName) {
            this.accountUuid = accountUuid;
            this.token = token;
            this.entryType = entryType;
            this.connection = connection;
            this.originalJavaUuid = originalJavaUuid;
            this.javaUid = javaUid;
            this.bedrockUid = bedrockUid;
            this.javaLoginName = javaLoginName;
        }

        public UUID accountUuid() {
            return accountUuid;
        }

        public UUID token() {
            return token;
        }

        public EntryType entryType() {
            return entryType;
        }

        public T connection() {
            return connection;
        }

        public UUID originalJavaUuid() {
            return originalJavaUuid;
        }

        public Long javaUid() {
            return javaUid;
        }

        public Long bedrockUid() {
            return bedrockUid;
        }

        public String javaLoginName() {
            return javaLoginName;
        }
    }
}
