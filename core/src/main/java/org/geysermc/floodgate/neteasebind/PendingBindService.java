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
        String oldCode = codeByJavaUuid.remove(javaUuid);
        if (oldCode != null) {
            byCode.remove(oldCode);
        }

        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (byCode.containsKey(code));

        PendingBind pending = new PendingBind(code, javaUuid, javaName, Instant.now().plus(ttl));
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
