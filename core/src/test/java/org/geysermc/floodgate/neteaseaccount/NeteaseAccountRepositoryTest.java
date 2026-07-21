package org.geysermc.floodgate.neteaseaccount;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NeteaseAccountRepositoryTest {
    @Test
    void derivesLegacyFloodgateXuidSuffix() {
        UUID uuid = UUID.fromString("00000000-0000-4000-8000-00007ed64a8d");

        assertEquals("7ed64a8d", NeteaseAccountRepository.deriveBedrockXuid(uuid));
    }

    @Test
    void preservesFullNonLegacyUuidAsFallbackXuid() {
        UUID uuid = UUID.fromString("c8f16b3b-07ab-3c9b-994f-eb3e34fae44e");

        assertEquals(uuid.toString(), NeteaseAccountRepository.deriveBedrockXuid(uuid));
    }
}
