package org.geysermc.floodgate.neteaseaccount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void addsTheDefaultSupportGroupToExistingConfigurations() throws Exception {
        writeConfig("messages:\n"
                + "  unresolved-java-kick:\n"
                + "    - \"账号互通需要初始化\"\n"
                + "    - \"&8&m--------------------------------\"\n");

        String message = NeteaseAccountConfig.load(tempDirectory).unresolvedJavaKickMessage();

        assertTrue(message.contains("如遇任何问题，可加入官方 QQ 群咨询解决。"));
        assertTrue(message.contains("QQ群：519586736"));
        assertTrue(message.indexOf("QQ群：519586736") < message.lastIndexOf("--------------------------------"));
    }

    @Test
    void usesTheConfiguredSupportGroupWithoutDuplicatingTheDefault() throws Exception {
        writeConfig("messages:\n"
                + "  support-qq-group: \"123456789\"\n"
                + "  unresolved-java-kick:\n"
                + "    - \"账号互通需要初始化\"\n"
                + "    - \"&e如遇任何问题，可加入官方 QQ 群咨询解决。\"\n"
                + "    - \"&b&lQQ群：519586736\"\n"
                + "    - \"&8&m--------------------------------\"\n");

        String message = NeteaseAccountConfig.load(tempDirectory).unresolvedJavaKickMessage();

        assertTrue(message.contains("QQ群：123456789"));
        assertFalse(message.contains("519586736"));
        assertEquals(1, countOccurrences(message, "如遇任何问题"));
    }

    private void writeConfig(String content) throws Exception {
        Files.write(tempDirectory.resolve("netease-account.yml"), content.getBytes(StandardCharsets.UTF_8));
    }

    private static int countOccurrences(String value, String part) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(part, index)) >= 0) {
            count++;
            index += part.length();
        }
        return count;
    }
}
