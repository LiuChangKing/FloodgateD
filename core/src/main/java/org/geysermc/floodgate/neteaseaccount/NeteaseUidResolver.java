package org.geysermc.floodgate.neteaseaccount;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class NeteaseUidResolver {
    private static final String METHOD = "POST";
    private static final String DEFAULT_SIGNATURE_PATH = "/uid-from-uuid";
    private static final Pattern SUCCESS_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*0");
    private static final Pattern UID_PATTERN = Pattern.compile("\"uid\"\\s*:\\s*\"?(\\d+)\"?");

    private final int timeoutMillis;

    public NeteaseUidResolver(long timeoutMillis) {
        this.timeoutMillis = (int) Math.max(1000L, Math.min(Integer.MAX_VALUE, timeoutMillis));
    }

    public OptionalLong resolve(NeteaseAccountConfig.UidEndpoint endpoint, UUID uuid) throws IOException {
        if (endpoint == null || !endpoint.configured()) {
            throw new IOException("NetEase uid-from-uuid endpoint or key is not configured");
        }
        String body = "{\"uuid\":\"" + uuid + "\"}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        URL url = new URL(endpoint.url());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(METHOD);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Netease-Server-Sign", sign(url, endpoint.key(), body));
        connection.setFixedLengthStreamingMode(bodyBytes.length);

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
        return parseUid(response);
    }

    private static OptionalLong parseUid(String response) {
        if (response == null || !SUCCESS_CODE_PATTERN.matcher(response).find()) {
            return OptionalLong.empty();
        }
        Matcher matcher = UID_PATTERN.matcher(response);
        if (!matcher.find()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
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
            int read;
            while ((read = in.read(buffer)) >= 0) {
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
}
