package org.geysermc.floodgate.neteaseaccount;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NeteaseAccountConfig {
    private static final String CONFIG_FILE_NAME = "netease-account.yml";
    private static final String COMMAND_NAME = "neteaseaccount";
    private static final String COMMAND_ALIASES = "neteasebind,naccount,nbind";
    private static final String WARNING_PREFIX = "&8[&e&l!&8] &e";
    private static final String ERROR_PREFIX = "&8[&c&l!&8] &c";

    private final boolean enabled;
    private final String accountTable;
    private final long uidQueryTimeoutMillis;
    private final long loginCheckTimeoutMillis;
    private final long unresolvedPromptIntervalSeconds;
    private final boolean bootstrapLocalProfileOnStartup;
    private final UidEndpoint bedrockEndpoint;
    private final UidEndpoint javaEndpoint;
    private final String unresolvedJavaTitle;
    private final String unresolvedJavaNotify;
    private final String unresolvedJavaSubtitle;
    private final List<String> unresolvedJavaKickLines;
    private final String linkedJavaLoginBlockedNotifyMessage;
    private final String accountSystemUnavailableMessage;
    private final String accountSystemTimeoutMessage;
    private final String loginTaskRejectedMessage;

    public NeteaseAccountConfig(
            boolean enabled,
            String accountTable,
            long uidQueryTimeoutMillis,
            long loginCheckTimeoutMillis,
            long unresolvedPromptIntervalSeconds,
            boolean bootstrapLocalProfileOnStartup,
            UidEndpoint bedrockEndpoint,
            UidEndpoint javaEndpoint,
            String unresolvedJavaTitle,
            String unresolvedJavaNotify,
            String unresolvedJavaSubtitle,
            List<String> unresolvedJavaKickLines,
            String linkedJavaLoginBlockedNotifyMessage,
            String accountSystemUnavailableMessage,
            String accountSystemTimeoutMessage,
            String loginTaskRejectedMessage) {
        this.enabled = enabled;
        this.accountTable = accountTable;
        this.uidQueryTimeoutMillis = uidQueryTimeoutMillis;
        this.loginCheckTimeoutMillis = loginCheckTimeoutMillis;
        this.unresolvedPromptIntervalSeconds = unresolvedPromptIntervalSeconds;
        this.bootstrapLocalProfileOnStartup = bootstrapLocalProfileOnStartup;
        this.bedrockEndpoint = bedrockEndpoint;
        this.javaEndpoint = javaEndpoint;
        this.unresolvedJavaTitle = unresolvedJavaTitle;
        this.unresolvedJavaNotify = unresolvedJavaNotify;
        this.unresolvedJavaSubtitle = unresolvedJavaSubtitle;
        this.unresolvedJavaKickLines = new ArrayList<>(unresolvedJavaKickLines);
        this.linkedJavaLoginBlockedNotifyMessage = linkedJavaLoginBlockedNotifyMessage;
        this.accountSystemUnavailableMessage = accountSystemUnavailableMessage;
        this.accountSystemTimeoutMessage = accountSystemTimeoutMessage;
        this.loginTaskRejectedMessage = loginTaskRejectedMessage;
    }

    public static NeteaseAccountConfig load(Path dataDirectory) throws IOException {
        Path path = dataDirectory.resolve(CONFIG_FILE_NAME);
        if (!Files.exists(path)) {
            Files.createDirectories(dataDirectory);
            Files.write(path, defaultConfigText().getBytes(StandardCharsets.UTF_8));
        }

        Map<String, List<String>> values = parseSimpleYaml(path);
        long uidTimeout = Long.parseLong(getScalar(values, "uid-query-timeout-millis", "10000").trim());
        long loginTimeout = Long.parseLong(getScalar(
                values,
                "login-check-timeout-millis",
                String.valueOf(Math.max(3000L, uidTimeout + 5000L))
        ).trim());
        String notify = getScalar(
                values,
                "messages.unresolved-java-notify",
                WARNING_PREFIX + "请使用同一网易账号的基岩版进入本服一次，完成后重新使用 Java 版进入。"
        );
        return new NeteaseAccountConfig(
                Boolean.parseBoolean(getScalar(values, "enabled", "true").trim()),
                getScalar(values, "account.table", "netease_account_profile").trim(),
                uidTimeout,
                loginTimeout,
                Long.parseLong(getScalar(values, "messages.unresolved-prompt-interval-seconds", "10").trim()),
                Boolean.parseBoolean(getScalar(values, "bootstrap-localprofile-on-startup", "true").trim()),
                new UidEndpoint(
                        getScalar(values, "bedrock.uid_from_uuid",
                                "http://gasproxy.mc.netease.com:60003/uid-from-uuid").trim(),
                        getScalar(values, "bedrock.key", "").trim()
                ),
                new UidEndpoint(
                        getScalar(values, "java.uid_from_uuid",
                                "http://gasproxy.mc.netease.com:60004/uid-from-uuid").trim(),
                        getScalar(values, "java.key", "").trim()
                ),
                getScalar(values, "messages.unresolved-java-title", "请先用基岩版进入一次"),
                notify,
                getScalar(values, "messages.unresolved-java-subtitle", "请使用同一网易账号的基岩版进入本服一次"),
                getList(values, "messages.unresolved-java-kick", defaultUnresolvedJavaKickLines()),
                getScalar(
                        values,
                        "messages.linked-java-login-blocked-notify",
                        getScalar(values, "messages.bound-java-login-blocked-notify",
                                WARNING_PREFIX + "您关联的 Java 账号 %java_name% 进入服务器，已被阻止。")
                ),
                getScalar(
                        values,
                        "messages.account-system-unavailable",
                        getScalar(values, "messages.bind-system-unavailable",
                                ERROR_PREFIX + "账号互通服务暂时不可用，请稍后重试")
                ),
                getScalar(
                        values,
                        "messages.account-system-timeout",
                        getScalar(values, "messages.bind-system-timeout",
                                ERROR_PREFIX + "账号互通验证响应超时，请稍后重试")
                ),
                getScalar(values, "messages.login-task-rejected",
                        ERROR_PREFIX + "账号互通服务繁忙，请稍后重试")
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

    private static List<String> getList(
            Map<String, List<String>> values,
            String key,
            List<String> fallback) {
        List<String> list = values.get(key);
        return list == null || list.isEmpty() ? fallback : new ArrayList<>(list);
    }

    private static List<String> defaultUnresolvedJavaKickLines() {
        List<String> lines = new ArrayList<>();
        lines.add("&8&m--------------------------------");
        lines.add("&6&l账号互通需要初始化");
        lines.add("");
        lines.add("&7检测到您的网易账号尚未在本服建立 &b基岩版档案&7。");
        lines.add("&f请使用 &e相同的网易账号&f，通过以下任一客户端进入本服一次：");
        lines.add("&a1. &b网易基岩互通版");
        lines.add("&a2. &b网易手游我的世界");
        lines.add("");
        lines.add("&7基岩版成功进入后请退出，再重新使用 &eJava 版&7登录。");
        lines.add("&8&m--------------------------------");
        return lines;
    }

    private static List<String> singletonList(String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        return list;
    }

    private static String defaultConfigText() {
        return "# 梦想之城网易账号自动互通配置\n\n"
                + "enabled: true\n"
                + "uid-query-timeout-millis: 10000\n"
                + "login-check-timeout-millis: 15000\n"
                + "bootstrap-localprofile-on-startup: true\n\n"
                + "account:\n"
                + "  table: netease_account_profile\n\n"
                + "bedrock:\n"
                + "  uid_from_uuid: \"http://gasproxy.mc.netease.com:60003/uid-from-uuid\"\n"
                + "  key: \"<bedrock app key>\"\n\n"
                + "java:\n"
                + "  uid_from_uuid: \"http://gasproxy.mc.netease.com:60004/uid-from-uuid\"\n"
                + "  key: \"<java app key>\"\n\n"
                + "messages:\n"
                + "  unresolved-prompt-interval-seconds: 10\n"
                + "  unresolved-java-title: \"请先用基岩版进入一次\"\n"
                + "  unresolved-java-notify: \"" + WARNING_PREFIX
                + "请使用同一网易账号的基岩版进入本服一次，完成后重新使用 Java 版进入。\"\n"
                + "  unresolved-java-subtitle: \"请使用同一网易账号的基岩版进入本服一次\"\n"
                + "  # Java 尚无基岩档案时，由 Velocity 在登录阶段直接断开并显示以下内容\n"
                + "  unresolved-java-kick:\n"
                + "    - \"&8&m--------------------------------\"\n"
                + "    - \"&6&l账号互通需要初始化\"\n"
                + "    - \"\"\n"
                + "    - \"&7检测到您的网易账号尚未在本服建立 &b基岩版档案&7。\"\n"
                + "    - \"&f请使用 &e相同的网易账号&f，通过以下任一客户端进入本服一次：\"\n"
                + "    - \"&a1. &b网易基岩互通版\"\n"
                + "    - \"&a2. &b网易手游我的世界\"\n"
                + "    - \"\"\n"
                + "    - \"&7基岩版成功进入后请退出，再重新使用 &eJava 版&7登录。\"\n"
                + "    - \"&8&m--------------------------------\"\n"
                + "  linked-java-login-blocked-notify: \"" + WARNING_PREFIX
                + "您关联的 Java 账号 %java_name% 进入服务器，已被阻止。\"\n"
                + "  account-system-unavailable: \"" + ERROR_PREFIX + "账号互通服务暂时不可用，请稍后重试\"\n"
                + "  account-system-timeout: \"" + ERROR_PREFIX + "账号互通验证响应超时，请稍后重试\"\n"
                + "  login-task-rejected: \"" + ERROR_PREFIX + "账号互通服务繁忙，请稍后重试\"\n";
    }

    public boolean enabled() {
        return enabled;
    }

    public String commandName() {
        return COMMAND_NAME;
    }

    public String commandAliases() {
        return COMMAND_ALIASES;
    }

    public String accountTable() {
        return accountTable;
    }

    public long uidQueryTimeoutMillis() {
        return uidQueryTimeoutMillis;
    }

    public long loginCheckTimeoutMillis() {
        return loginCheckTimeoutMillis;
    }

    public long unresolvedPromptIntervalSeconds() {
        return unresolvedPromptIntervalSeconds;
    }

    public boolean bootstrapLocalProfileOnStartup() {
        return bootstrapLocalProfileOnStartup;
    }

    public UidEndpoint bedrockEndpoint() {
        return bedrockEndpoint;
    }

    public UidEndpoint javaEndpoint() {
        return javaEndpoint;
    }

    public String unresolvedJavaTitle() {
        return unresolvedJavaTitle;
    }

    public String unresolvedJavaNotify() {
        return unresolvedJavaNotify;
    }

    public String unresolvedJavaSubtitle() {
        return unresolvedJavaSubtitle;
    }

    public String unresolvedJavaKickMessage() {
        return String.join("\n", unresolvedJavaKickLines);
    }

    public String linkedJavaLoginBlockedNotifyMessage() {
        return linkedJavaLoginBlockedNotifyMessage;
    }

    public String accountSystemUnavailableMessage() {
        return accountSystemUnavailableMessage;
    }

    public String accountSystemTimeoutMessage() {
        return accountSystemTimeoutMessage;
    }

    public String loginTaskRejectedMessage() {
        return loginTaskRejectedMessage;
    }

    public static final class UidEndpoint {
        private final String url;
        private final String key;

        public UidEndpoint(String url, String key) {
            this.url = url;
            this.key = key;
        }

        public String url() {
            return url;
        }

        public String key() {
            return key;
        }

        public boolean configured() {
            return url != null
                    && !url.trim().isEmpty()
                    && key != null
                    && !key.trim().isEmpty()
                    && !key.contains("<");
        }
    }
}
