package org.geysermc.floodgate.neteaseaccount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class NeteaseUidResolverTest {
    @Test
    void parsesOnlyTheUidInsideSuccessfulEntity() {
        OptionalLong uid = NeteaseUidResolver.parseUid(
                "{\"code\":0,\"uid\":123,\"entity\":{\"uid\":\"2148346433\"}}",
                NeteaseUidResolver.UidType.BEDROCK);

        assertTrue(uid.isPresent());
        assertEquals(2148346433L, uid.getAsLong());
    }

    @Test
    void rejectsWrongSideAndUnsuccessfulResponses() {
        assertFalse(NeteaseUidResolver.parseUid(
                "{\"code\":0,\"entity\":{\"uid\":2148346433}}",
                NeteaseUidResolver.UidType.JAVA).isPresent());
        assertFalse(NeteaseUidResolver.parseUid(
                "{\"code\":1,\"entity\":{\"uid\":123}}",
                NeteaseUidResolver.UidType.JAVA).isPresent());
        assertFalse(NeteaseUidResolver.parseUid(
                "{\"code\":0,\"uid\":123}",
                NeteaseUidResolver.UidType.JAVA).isPresent());
    }

    @Test
    void convertsBetweenJavaAndBedrockUidWithoutLosingBits() {
        long javaUid = 864981L;
        long bedrockUid = NeteaseUidResolver.toBedrockUid(javaUid);

        assertEquals(javaUid | 0x80000000L, bedrockUid);
        assertEquals(javaUid, NeteaseUidResolver.toJavaUid(bedrockUid));
        assertThrows(IllegalArgumentException.class, () -> NeteaseUidResolver.toBedrockUid(0L));
        assertThrows(IllegalArgumentException.class, () -> NeteaseUidResolver.toJavaUid(1L));
    }

    @Test
    void permitsOnlyOfficialSideSpecificEndpointsOverPlainHttp() throws Exception {
        NeteaseUidResolver.validateEndpointUrl(
                new URL("http://gasproxy.mc.netease.com:60003/uid-from-uuid"),
                NeteaseUidResolver.UidType.BEDROCK);
        NeteaseUidResolver.validateEndpointUrl(
                new URL("https://account.example.com/uid-from-uuid"),
                NeteaseUidResolver.UidType.JAVA);

        assertThrows(IOException.class, () -> NeteaseUidResolver.validateEndpointUrl(
                new URL("http://example.com:60003/uid-from-uuid"),
                NeteaseUidResolver.UidType.BEDROCK));
        assertThrows(IOException.class, () -> NeteaseUidResolver.validateEndpointUrl(
                new URL("http://gasproxy.mc.netease.com:60003/uid-from-uuid"),
                NeteaseUidResolver.UidType.JAVA));
        assertThrows(IOException.class, () -> NeteaseUidResolver.validateEndpointUrl(
                new URL("https://account.example.com/uid-from-uuid?uuid=leak"),
                NeteaseUidResolver.UidType.JAVA));
    }
}
