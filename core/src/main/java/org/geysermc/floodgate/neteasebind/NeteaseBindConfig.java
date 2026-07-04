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
    private final boolean enabled;
    private final String bindServer;
    private final String commandName;
    private final String commandAliases;
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
            String commandName,
            String commandAliases,
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
        this.commandName = commandName;
        this.commandAliases = commandAliases;
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
                getScalar(values, "bind-server", "bind-lobby").trim(),
                getScalar(values, "command.name", "neteasebind").trim(),
                join(getList(values, "command.aliases", singletonList("nbind"))),
                getScalar(values, "binding.table", "java_bedrock_bindings").trim(),
                Long.parseLong(getScalar(values, "binding.code-expire-seconds", "300").trim()),
                Long.parseLong(getScalar(values, "binding.login-check-timeout-millis", "3000").trim()),
                Boolean.parseBoolean(getScalar(values, "binding.use-origin-bedrock-name", "true").trim()),
                getScalar(values, "messages.kick-after-bind", "绑定完成，请重新进入服务器"),
                getScalar(values, "messages.unbound-redirect", "请先在绑定服完成 Java 与基岩账号绑定"),
                getScalar(values, "messages.already-bound", "你的 Java 账号已经绑定过基岩账号"),
                getScalar(values, "messages.bedrock-already-bound", "这个基岩账号已经被绑定"),
                getScalar(values, "messages.bind-system-unavailable", "账号互通服务暂时不可用，请稍后重试"),
                getScalar(values, "messages.bind-system-timeout", "账号互通验证响应超时，请稍后重试"),
                getScalar(values, "messages.login-task-rejected", "账号互通服务繁忙，请稍后重试")
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

    private static List<String> getList(Map<String, List<String>> values, String key, List<String> fallback) {
        List<String> list = values.get(key);
        return list == null ? fallback : list;
    }

    private static List<String> singletonList(String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        return list;
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static String defaultConfigText() {
        return "# 梦想之城账号互通配置\n\n"
                + "enabled: true\n"
                + "bind-server: bind-lobby\n\n"
                + "command:\n"
                + "  name: neteasebind\n"
                + "  aliases:\n"
                + "    - nbind\n\n"
                + "binding:\n"
                + "  table: java_bedrock_bindings\n"
                + "  code-expire-seconds: 300\n"
                + "  login-check-timeout-millis: 3000\n"
                + "  use-origin-bedrock-name: true\n\n"
                + "messages:\n"
                + "  kick-after-bind: \"绑定完成，请重新进入服务器\"\n"
                + "  unbound-redirect: \"请先在绑定服完成 Java 与基岩账号绑定\"\n"
                + "  already-bound: \"你的 Java 账号已经绑定过基岩账号\"\n"
                + "  bedrock-already-bound: \"这个基岩账号已经被绑定\"\n"
                + "  bind-system-unavailable: \"账号互通服务暂时不可用，请稍后重试\"\n"
                + "  bind-system-timeout: \"账号互通验证响应超时，请稍后重试\"\n"
                + "  login-task-rejected: \"账号互通服务繁忙，请稍后重试\"\n";
    }

    public boolean enabled() {
        return enabled;
    }

    public String bindServer() {
        return bindServer;
    }

    public String commandName() {
        return commandName;
    }

    public String commandAliases() {
        return commandAliases;
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
