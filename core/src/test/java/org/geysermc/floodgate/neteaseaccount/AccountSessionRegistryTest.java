package org.geysermc.floodgate.neteaseaccount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.geysermc.floodgate.api.netease.EntryType;
import org.junit.jupiter.api.Test;

final class AccountSessionRegistryTest {
    @Test
    void allowsOnlyOneOwnerForTheSameAccountUuid() {
        AccountSessionRegistry<Object> registry = new AccountSessionRegistry<>();
        UUID accountUuid = UUID.randomUUID();
        AccountSessionRegistry.Session<Object> first = session(accountUuid, EntryType.BOUND_JAVA);
        AccountSessionRegistry.Session<Object> second = session(accountUuid, EntryType.BEDROCK);

        assertNull(registry.putIfAbsent(first));
        assertSame(first, registry.putIfAbsent(second));
        assertSame(first, registry.get(accountUuid));
    }

    @Test
    void staleDisconnectCannotRemoveReplacementSession() {
        AccountSessionRegistry<Object> registry = new AccountSessionRegistry<>();
        UUID accountUuid = UUID.randomUUID();
        AccountSessionRegistry.Session<Object> javaSession = session(accountUuid, EntryType.BOUND_JAVA);
        AccountSessionRegistry.Session<Object> bedrockSession = session(accountUuid, EntryType.BEDROCK);

        registry.putIfAbsent(javaSession);
        assertTrue(registry.replace(javaSession, bedrockSession));
        assertFalse(registry.remove(javaSession));
        assertSame(bedrockSession, registry.get(accountUuid));
        assertTrue(registry.remove(bedrockSession));
        assertNull(registry.get(accountUuid));
    }

    @Test
    void tokenLookupCannotCrossLoginAttempts() {
        AccountSessionRegistry<Object> registry = new AccountSessionRegistry<>();
        AccountSessionRegistry.Session<Object> owner = session(UUID.randomUUID(), EntryType.BEDROCK);
        registry.putIfAbsent(owner);

        assertSame(owner, registry.findOwned(owner.accountUuid(), owner.token()));
        assertNull(registry.findOwned(owner.accountUuid(), UUID.randomUUID()));
        assertEquals(EntryType.BEDROCK, owner.entryType());
    }

    @Test
    void reconstructedDisconnectRemovesSessionWithMatchingToken() {
        AccountSessionRegistry<Object> registry = new AccountSessionRegistry<>();
        AccountSessionRegistry.Session<Object> owner = session(UUID.randomUUID(), EntryType.BEDROCK);
        registry.putIfAbsent(owner);

        assertTrue(registry.removeOwned(owner.accountUuid(), owner.token()));
        assertNull(registry.get(owner.accountUuid()));
    }

    @Test
    void staleDisconnectTokenCannotRemoveReplacementSession() {
        AccountSessionRegistry<Object> registry = new AccountSessionRegistry<>();
        UUID accountUuid = UUID.randomUUID();
        AccountSessionRegistry.Session<Object> oldSession = session(accountUuid, EntryType.BOUND_JAVA);
        AccountSessionRegistry.Session<Object> replacement = session(accountUuid, EntryType.BEDROCK);
        registry.putIfAbsent(oldSession);
        assertTrue(registry.replace(oldSession, replacement));

        assertFalse(registry.removeOwned(accountUuid, oldSession.token()));
        assertSame(replacement, registry.get(accountUuid));
        assertTrue(registry.removeOwned(accountUuid, replacement.token()));
    }

    @Test
    void resolvesCrossPlatformConflictsBeforeVelocityDuplicateChecks() {
        assertEquals(
                AccountSessionRegistry.ConflictAction.TAKE_OVER_EXISTING,
                AccountSessionRegistry.conflictAction(EntryType.BEDROCK, EntryType.BOUND_JAVA));
        assertEquals(
                AccountSessionRegistry.ConflictAction.TAKE_OVER_EXISTING,
                AccountSessionRegistry.conflictAction(EntryType.BEDROCK, EntryType.BEDROCK));
        assertEquals(
                AccountSessionRegistry.ConflictAction.NOTIFY_BEDROCK_AND_DENY,
                AccountSessionRegistry.conflictAction(EntryType.BOUND_JAVA, EntryType.BEDROCK));
        assertEquals(
                AccountSessionRegistry.ConflictAction.NONE,
                AccountSessionRegistry.conflictAction(EntryType.BEDROCK, null));
    }

    private static AccountSessionRegistry.Session<Object> session(UUID accountUuid, EntryType entryType) {
        return new AccountSessionRegistry.Session<>(
                accountUuid,
                UUID.randomUUID(),
                entryType,
                new Object(),
                null,
                null,
                2148346433L,
                null);
    }
}
