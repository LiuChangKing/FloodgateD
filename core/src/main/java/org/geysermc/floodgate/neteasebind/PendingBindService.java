package org.geysermc.floodgate.neteasebind;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingBindService {
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;
    private final Map<String, PendingBind> byCode = new ConcurrentHashMap<>();
    private final Map<UUID, String> codeByJavaUuid = new ConcurrentHashMap<>();

    public PendingBindService(Duration ttl) {
        this.ttl = ttl;
    }

    public PendingBind create(UUID javaUuid, String javaName) {
        cleanup();
        Instant expiresAt = Instant.now().plus(ttl);
        String oldCode = codeByJavaUuid.get(javaUuid);
        if (oldCode != null) {
            PendingBind existing = byCode.get(oldCode);
            if (existing != null) {
                PendingBind refreshed = new PendingBind(existing.code(), javaUuid, javaName, expiresAt);
                byCode.put(existing.code(), refreshed);
                return refreshed;
            }
            codeByJavaUuid.remove(javaUuid);
        }

        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (byCode.containsKey(code));

        PendingBind pending = new PendingBind(code, javaUuid, javaName, expiresAt);
        byCode.put(code, pending);
        codeByJavaUuid.put(javaUuid, code);
        return pending;
    }

    public Optional<PendingBind> consume(String code) {
        cleanup();
        PendingBind pending = byCode.remove(code);
        if (pending == null) {
            return Optional.empty();
        }
        codeByJavaUuid.remove(pending.javaUuid());
        return Optional.of(pending);
    }

    public Optional<PendingBind> peek(String code) {
        cleanup();
        return Optional.ofNullable(byCode.get(code));
    }

    public boolean hasActiveBind(UUID javaUuid) {
        return activeBindExpiresAtMillis(javaUuid) > 0L;
    }

    public long activeBindExpiresAtMillis(UUID javaUuid) {
        cleanup();
        String code = codeByJavaUuid.get(javaUuid);
        if (code == null) {
            return 0L;
        }
        PendingBind pending = byCode.get(code);
        return pending == null ? 0L : pending.expiresAtMillis();
    }

    public void removeByJavaUuid(UUID javaUuid) {
        String code = codeByJavaUuid.remove(javaUuid);
        if (code != null) {
            byCode.remove(code);
        }
    }

    private void cleanup() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, PendingBind>> iterator = byCode.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingBind> entry = iterator.next();
            if (entry.getValue().expired(now)) {
                codeByJavaUuid.remove(entry.getValue().javaUuid());
                iterator.remove();
            }
        }
    }
}
