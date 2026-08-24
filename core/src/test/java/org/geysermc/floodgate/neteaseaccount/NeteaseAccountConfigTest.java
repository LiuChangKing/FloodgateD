package org.geysermc.floodgate.neteaseaccount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NeteaseAccountConfigTest {
    @TempDir
    Path tempDirectory;

    @Test
    void suppliesTheBedrockIdentityEndpointToExistingConfigurations() throws Exception {
        writeConfig("bedrock:\n"
                + "  key: \"bedrock-secret\"\n");

        NeteaseAccountConfig.UidEndpoint endpoint =
                NeteaseAccountConfig.load(tempDirectory).bedrockIdentityEndpoint();

        assertEquals("http://gasproxy.mc.netease.com:60002/uuid-xuid-from-uid-name", endpoint.url());
        assertEquals("bedrock-secret", endpoint.key());
        assertTrue(endpoint.configured());
    }

    private void writeConfig(String content) throws Exception {
        Files.write(tempDirectory.resolve("netease-account.yml"), content.getBytes(StandardCharsets.UTF_8));
    }

}
