package org.geysermc.floodgate.neteasebind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NeteaseBindConfig {
    private static final String COMMAND_NAME = "neteasebind";
    private static final String COMMAND_ALIASES = "nbind";
    private static final String SUCCESS_PREFIX = "&8[&a&l!&8] &a";
    private static final String WARNING_PREFIX = "&8[&e&l!&8] &e";
    private static final String ERROR_PREFIX = "&8[&c&l!&8] &c";

    private final boolean enabled;
    private final String bindServer;
    private final String bindingTable;
    private final long codeExpireSeconds;
    private final long loginCheckTimeoutMillis;
    private final boolean useOriginBedrockName;
    private final String kickAfterBindMessage;
    private final String unboundRedirectMessage;
    private final String alreadyBoundMessage;
    private final String bedrockAlreadyBoundMessage;
    private final String bindSystemUnavailableMessage;
    private final String bindSystemTimeoutMessage;
    private final String loginTaskRejectedMessage;

    public NeteaseBindConfig(
            boolean enabled,
            String bindServer,
            String bindingTable,
            long codeExpireSeconds,
            long loginCheckTimeoutMillis,
            boolean useOriginBedrockName,
            String kickAfterBindMessage,
            String unboundRedirectMessage,
            String alreadyBoundMessage,
            String bedrockAlreadyBoundMessage,
            String bindSystemUnavailableMessage,
            String bindSystemTimeoutMessage,
            String loginTaskRejectedMessage) {
        this.enabled = enabled;
        this.bindServer = bindServer;
        this.bindingTable = bindingTable;
        this.codeExpireSeconds = codeExpireSeconds;
        this.loginCheckTimeoutMillis = loginCheckTimeoutMillis;
        this.useOriginBedrockName = useOriginBedrockName;
        this.kickAfterBindMessage = kickAfterBindMessage;
        this.unboundRedirectMessage = unboundRedirectMessage;
        this.alreadyBoundMessage = alreadyBoundMessage;
        this.bedrockAlreadyBoundMessage = bedrockAlreadyBoundMessage;
        this.bindSystemUnavailableMessage = bindSystemUnavailableMessage;
        this.bindSystemTimeoutMessage = bindSystemTimeoutMessage;
        this.loginTaskRejectedMessage = loginTaskRejectedMessage;
    }

    public static NeteaseBindConfig load(Path dataDirectory) throws IOException {
        Path path = dataDirectory.resolve("netease-bind.yml");
        if (!Files.exists(path)) {
            Files.createDirectories(dataDirectory);
            Files.write(path, defaultConfigText().getBytes(StandardCharsets.UTF_8));
        }

        Map<String, List<String>> values = parseSimpleYaml(path);
        return new NeteaseBindConfig(
                Boolean.parseBoolean(getScalar(values, "enabled", "true").trim()),
                getScalar(values, "bind-server", "neteasebind_login").trim(),
                getScalar(values, "binding.table", "neteasebind").trim(),
                Long.parseLong(getScalar(values, "binding.code-expire-seconds", "300").trim()),
                Long.parseLong(getScalar(values, "binding.login-check-timeout-millis", "3000").trim()),
                Boolean.parseBoolean(getScalar(values, "binding.use-origin-bedrock-name", "true").trim()),
                getScalar(values, "messages.kick-after-bind", SUCCESS_PREFIX + "绑定完成，请重新进入服务器"),
                getScalar(values, "messages.unbound-redirect", WARNING_PREFIX + "请先在绑定服完成 Java 与基岩账号绑定"),
                getScalar(values, "messages.already-bound", WARNING_PREFIX + "你的 Java 账号已经绑定过基岩账号"),
                getScalar(values, "messages.bedrock-already-bound", WARNING_PREFIX + "这个基岩账号已经被绑定"),
                getScalar(values, "messages.bind-system-unavailable", ERROR_PREFIX + "账号互通服务暂时不可用，请稍后重试"),
                getScalar(values, "messages.bind-system-timeout", ERROR_PREFIX + "账号互通验证响应超时，请稍后重试"),
                getScalar(values, "messages.login-task-rejected", ERROR_PREFIX + "账号互通服务繁忙，请稍后重试")
        );
    }

    private static Map<String, List<String>> parseSimpleYaml(Path path) throws IOException {
        Map<String, List<String>> values = new LinkedHashMap<>();
        Map<Integer, String> prefixes = new LinkedHashMap<>();
        String currentListKey = null;

        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String withoutComment = stripComment(rawLine);
            if (withoutComment.trim().isEmpty()) {
                continue;
            }

            int indent = countLeadingSpaces(withoutComment);
            String line = withoutComment.trim();
            if (line.startsWith("- ")) {
                if (currentListKey != null) {
                    List<String> list = values.get(currentListKey);
                    if (list == null) {
                        list = new ArrayList<>();
                        values.put(currentListKey, list);
                    }
                    list.add(unquote(line.substring(2).trim()));
                }
                continue;
            }

            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }

            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            String parent = parentPrefix(prefixes, indent);
            String fullKey = parent.isEmpty() ? key : parent + "." + key;

            removePrefixesAtOrAfter(prefixes, indent);
            currentListKey = null;
            if (value.isEmpty()) {
                prefixes.put(indent, fullKey);
                currentListKey = fullKey;
            } else if (value.startsWith("[") && value.endsWith("]")) {
                values.put(fullKey, parseInlineList(value));
            } else {
                values.put(fullKey, singletonList(unquote(value)));
            }
        }
        return values;
    }

    private static void removePrefixesAtOrAfter(Map<Integer, String> prefixes, int indent) {
        List<Integer> remove = new ArrayList<>();
        for (Integer key : prefixes.keySet()) {
            if (key >= indent) {
                remove.add(key);
            }
        }
        for (Integer key : remove) {
            prefixes.remove(key);
        }
    }

    private static String parentPrefix(Map<Integer, String> prefixes, int indent) {
        String parent = "";
        int parentIndent = -1;
        for (Map.Entry<Integer, String> entry : prefixes.entrySet()) {
            if (entry.getKey() < indent && entry.getKey() > parentIndent) {
                parentIndent = entry.getKey();
                parent = entry.getValue();
            }
        }
        return parent;
    }

    private static List<String> parseInlineList(String value) {
        String content = value.substring(1, value.length() - 1).trim();
        if (content.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String item : content.split(",")) {
            result.add(unquote(item.trim()));
        }
        return result;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || line.charAt(i - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quote = c;
                } else if (quote == c) {
                    quoted = false;
                }
            }
            if (c == '#' && !quoted) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int countLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String getScalar(Map<String, List<String>> values, String key, String fallback) {
        List<String> list = values.get(key);
        return list == null || list.isEmpty() ? fallback : list.get(0);
    }

    private static List<String> singletonList(String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        return list;
    }

    private static String defaultConfigText() {
        return "# 梦想之城账号互通配置\n\n"
                + "enabled: true\n"
                + "bind-server: neteasebind_login\n\n"
                + "binding:\n"
                + "  table: neteasebind\n"
                + "  code-expire-seconds: 300\n"
                + "  login-check-timeout-millis: 3000\n"
                + "  use-origin-bedrock-name: true\n\n"
                + "messages:\n"
                + "  kick-after-bind: \"" + SUCCESS_PREFIX + "绑定完成，请重新进入服务器\"\n"
                + "  unbound-redirect: \"" + WARNING_PREFIX + "请先在绑定服完成 Java 与基岩账号绑定\"\n"
                + "  already-bound: \"" + WARNING_PREFIX + "你的 Java 账号已经绑定过基岩账号\"\n"
                + "  bedrock-already-bound: \"" + WARNING_PREFIX + "这个基岩账号已经被绑定\"\n"
                + "  bind-system-unavailable: \"" + ERROR_PREFIX + "账号互通服务暂时不可用，请稍后重试\"\n"
                + "  bind-system-timeout: \"" + ERROR_PREFIX + "账号互通验证响应超时，请稍后重试\"\n"
                + "  login-task-rejected: \"" + ERROR_PREFIX + "账号互通服务繁忙，请稍后重试\"\n";
    }

    public boolean enabled() {
        return enabled;
    }

    public String bindServer() {
        return bindServer;
    }

    public String commandName() {
        return COMMAND_NAME;
    }

    public String commandAliases() {
        return COMMAND_ALIASES;
    }

    public String bindingTable() {
        return bindingTable;
    }

    public long codeExpireSeconds() {
        return codeExpireSeconds;
    }

    public long loginCheckTimeoutMillis() {
        return loginCheckTimeoutMillis;
    }

    public boolean useOriginBedrockName() {
        return useOriginBedrockName;
    }

    public String kickAfterBindMessage() {
        return kickAfterBindMessage;
    }

    public String unboundRedirectMessage() {
        return unboundRedirectMessage;
    }

    public String alreadyBoundMessage() {
        return alreadyBoundMessage;
    }

    public String bedrockAlreadyBoundMessage() {
        return bedrockAlreadyBoundMessage;
    }

    public String bindSystemUnavailableMessage() {
        return bindSystemUnavailableMessage;
    }

    public String bindSystemTimeoutMessage() {
        return bindSystemTimeoutMessage;
    }

    public String loginTaskRejectedMessage() {
        return loginTaskRejectedMessage;
    }
}
