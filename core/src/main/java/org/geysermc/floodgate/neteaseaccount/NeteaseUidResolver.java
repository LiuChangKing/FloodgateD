package org.geysermc.floodgate.neteaseaccount;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class NeteaseUidResolver {
    private static final String METHOD = "POST";
    private static final String DEFAULT_SIGNATURE_PATH = "/uid-from-uuid";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final String OFFICIAL_HTTP_HOST = "gasproxy.mc.netease.com";
    private static final long JAVA_UID_MAX = 0x7fffffffL;
    private static final long BEDROCK_UID_MIN = 0x80000000L;
    private static final long BEDROCK_UID_MAX = 0xffffffffL;

    private final int timeoutMillis;

    public NeteaseUidResolver(long timeoutMillis) {
        this.timeoutMillis = (int) Math.max(1000L, Math.min(Integer.MAX_VALUE, timeoutMillis));
    }

    public OptionalLong resolve(
            NeteaseAccountConfig.UidEndpoint endpoint,
            UUID uuid,
            UidType uidType) throws IOException {
        if (endpoint == null || !endpoint.configured()) {
            throw new IOException("NetEase uid-from-uuid endpoint or key is not configured");
        }
        if (uuid == null) {
            throw new IOException("NetEase uid-from-uuid requires a UUID");
        }

        String body = "{\"uuid\":\"" + uuid + "\"}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        URL url = new URL(endpoint.url());
        validateEndpointUrl(url, uidType);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(METHOD);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Netease-Server-Sign", sign(url, endpoint.key(), body));
        connection.setFixedLengthStreamingMode(bodyBytes.length);

        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bodyBytes);
            }

            int status = connection.getResponseCode();
            String response = readResponse(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IOException("NetEase uid-from-uuid returned HTTP " + status + ": " + response);
            }
            return parseUid(response, uidType);
        } finally {
            connection.disconnect();
        }
    }

    static OptionalLong parseUid(String response, UidType uidType) {
        if (response == null || response.trim().isEmpty() || uidType == null) {
            return OptionalLong.empty();
        }
        try {
            JsonElement rootElement = JsonParser.parseString(response);
            if (!rootElement.isJsonObject()) {
                return OptionalLong.empty();
            }
            JsonObject root = rootElement.getAsJsonObject();
            JsonElement code = root.get("code");
            JsonElement entity = root.get("entity");
            if (code == null || !code.isJsonPrimitive() || code.getAsInt() != 0
                    || entity == null || !entity.isJsonObject()) {
                return OptionalLong.empty();
            }
            JsonElement uidElement = entity.getAsJsonObject().get("uid");
            if (uidElement == null || !uidElement.isJsonPrimitive()) {
                return OptionalLong.empty();
            }
            long uid = uidElement.getAsLong();
            return uidType.isValid(uid) ? OptionalLong.of(uid) : OptionalLong.empty();
        } catch (RuntimeException exception) {
            return OptionalLong.empty();
        }
    }

    static void validateEndpointUrl(URL url, UidType uidType) throws IOException {
        if (url == null || uidType == null) {
            throw new IOException("NetEase uid-from-uuid endpoint and type are required");
        }
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("Unsupported uid-from-uuid protocol: " + protocol);
        }
        if (url.getHost() == null || url.getHost().isEmpty()
                || url.getUserInfo() != null
                || url.getQuery() != null
                || url.getRef() != null
                || !DEFAULT_SIGNATURE_PATH.equals(url.getPath())) {
            throw new IOException("Invalid NetEase uid-from-uuid endpoint URL");
        }
        if ("http".equalsIgnoreCase(protocol)) {
            int expectedPort = uidType == UidType.BEDROCK ? 60003 : 60004;
            if (!OFFICIAL_HTTP_HOST.equalsIgnoreCase(url.getHost()) || url.getPort() != expectedPort) {
                throw new IOException("Insecure HTTP is only allowed for the official NetEase "
                        + uidType.name().toLowerCase(Locale.ROOT) + " uid-from-uuid endpoint");
            }
        }
    }

    public static boolean isValidJavaUid(long uid) {
        return uid > 0L && uid <= JAVA_UID_MAX;
    }

    public static boolean isValidBedrockUid(long uid) {
        return uid >= BEDROCK_UID_MIN && uid <= BEDROCK_UID_MAX;
    }

    public static long toBedrockUid(long javaUid) {
        if (!isValidJavaUid(javaUid)) {
            throw new IllegalArgumentException("Invalid Java NetEase UID: " + javaUid);
        }
        return javaUid | BEDROCK_UID_MIN;
    }

    public static long toJavaUid(long bedrockUid) {
        if (!isValidBedrockUid(bedrockUid)) {
            throw new IllegalArgumentException("Invalid Bedrock NetEase UID: " + bedrockUid);
        }
        return bedrockUid & JAVA_UID_MAX;
    }

    private static String sign(URL url, String key, String body) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((METHOD + signaturePath(url) + body).getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (Exception exception) {
            throw new IOException("Failed to sign NetEase uid-from-uuid request", exception);
        }
    }

    private static String signaturePath(URL url) {
        String path = url.getPath();
        return path == null || path.isEmpty() ? DEFAULT_SIGNATURE_PATH : path;
    }

    private static String readResponse(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("NetEase uid-from-uuid response exceeds 64 KiB");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    public enum UidType {
        JAVA,
        BEDROCK;

        private boolean isValid(long uid) {
            return this == JAVA ? isValidJavaUid(uid) : isValidBedrockUid(uid);
        }
    }
}
