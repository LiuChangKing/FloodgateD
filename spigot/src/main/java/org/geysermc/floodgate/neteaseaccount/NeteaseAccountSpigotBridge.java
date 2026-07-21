package org.geysermc.floodgate.neteaseaccount;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import com.liuchangking.dreamengine.utils.MessageUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.geysermc.floodgate.SpigotPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.netease.EntryType;
import org.geysermc.floodgate.api.netease.NeteaseAccountProfileProperties;
import org.geysermc.floodgate.api.netease.NeteaseAccountApi;
import org.geysermc.floodgate.api.netease.NeteaseAccountBridge;
import org.geysermc.floodgate.api.netease.NeteasePlayerProfile;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

public final class NeteaseAccountSpigotBridge implements Listener, PluginMessageListener, CommandExecutor, TabCompleter, NeteaseAccountApi {
    private static final String SUCCESS_PREFIX = "&8[&a&l!&8] &a";
    private static final String WARNING_PREFIX = "&8[&e&l!&8] &e";
    private static final String ERROR_PREFIX = "&8[&c&l!&8] &c";
    private static final String LEGACY_FLOODGATE_UUID_MARKER = "00000000-0000-4000-8000";
    private static final String ADMIN_PERMISSION = "floodgate.neteaseaccount.admin";

    private final SpigotPlugin plugin;
    private final NeteaseAccountConfig config;
    private final Map<UUID, EntryType> entryTypes = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> javaUuids = new ConcurrentHashMap<>();
    private final Map<UUID, Long> javaUids = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bedrockUids = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Optional<NeteasePlayerProfile>>> profileQueries =
            new ConcurrentHashMap<>();
    private volatile boolean dreamEngineNotifyWarningLogged;

    public NeteaseAccountSpigotBridge(SpigotPlugin plugin) {
        this.plugin = plugin;
        try {
            this.config = NeteaseAccountConfig.load(plugin.getDataFolder().toPath());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load netease-account.yml", exception);
        }
    }

    public void enable() {
        if (!config.enabled()) {
            plugin.getLogger().info("Netease account companion is disabled by netease-account.yml");
            return;
        }

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, AccountBridgeChannel.ID);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, AccountBridgeChannel.ID, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getServicesManager().register(NeteaseAccountApi.class, this, plugin, ServicePriority.Normal);
        NeteaseAccountBridge.setInstance(this);

        PluginCommand command = plugin.getCommand(config.commandName());
        if (command == null && !"neteaseaccount".equalsIgnoreCase(config.commandName())) {
            plugin.getLogger().warning("Command '" + config.commandName()
                    + "' is not declared in plugin.yml; falling back to /neteaseaccount");
            command = plugin.getCommand("neteaseaccount");
        }
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        } else {
            plugin.getLogger().warning("Netease account command is unavailable because plugin.yml has no command entry");
        }
        plugin.getLogger().info("Netease account companion enabled");
    }

    public void disable() {
        plugin.getServer().getServicesManager().unregister(NeteaseAccountApi.class, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, AccountBridgeChannel.ID);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, AccountBridgeChannel.ID);
        NeteaseAccountBridge.clearInstance(this);
        entryTypes.clear();
        javaUuids.clear();
        javaUids.clear();
        bedrockUids.clear();
        IllegalStateException disabled = new IllegalStateException("Floodgate plugin is disabled");
        for (CompletableFuture<Optional<NeteasePlayerProfile>> query : profileQueries.values()) {
            query.completeExceptionally(disabled);
        }
        profileQueries.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(error("这个命令只能由玩家执行"));
            return true;
        }

        Player player = (Player) sender;
        if (isOnlineCommand(args) && !hasAdminPermission(player)) {
            player.sendMessage(error("你没有权限查看账号互通在线诊断"));
            return true;
        }

        if (args.length == 0 || (args.length == 1
                && (args[0].equalsIgnoreCase("ui") || args[0].equalsIgnoreCase("menu")))) {
            openAccountUiOrFallback(player);
            return true;
        }

        forwardAccountCommand(player, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> candidates = new ArrayList<>();
            candidates.add("status");
            candidates.add("ui");
            if (hasAdminPermission(sender)) {
                candidates.add("online");
                candidates.add("list");
                candidates.add("players");
            }
            return filterPrefix(candidates, args[0]);
        }
        if (args.length == 2 && isOnlineCommand(args) && hasAdminPermission(sender)) {
            List<String> candidates = new ArrayList<>();
            candidates.add("all");
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                candidates.add(player.getName());
            }
            return filterPrefix(candidates, args[1]);
        }
        return Collections.emptyList();
    }

    private static boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION);
    }

    private static boolean isOnlineCommand(String[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return false;
        }
        return args[0].equalsIgnoreCase("online")
                || args[0].equalsIgnoreCase("list")
                || args[0].equalsIgnoreCase("players");
    }

    private List<String> filterPrefix(List<String> candidates, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return candidates;
        }
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(java.util.Locale.ROOT).startsWith(lowerPrefix)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private void openAccountUiOrFallback(Player player) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("DreamEngine")) {
            try {
                new NeteaseAccountDreamEngineUi(this).open(player);
            } catch (LinkageError error) {
                plugin.getLogger().warning("DreamEngine UI classes are unavailable: " + error.getMessage());
                sendFallbackUsage(player);
            }
            return;
        }
        player.sendMessage(warning("当前子服未安装 DreamEngine，无法打开账号互通界面"));
        sendFallbackUsage(player);
    }

    private void sendFallbackUsage(Player player) {
        player.sendMessage(warning("账号互通已改为网易 UID 自动识别，无需验证码或手动关联"));
        player.sendMessage(warning("请输入 /" + config.commandName() + " status 查看当前账号识别状态"));
    }

    void forwardAccountCommand(Player player, String[] args) {
        forwardAccountCommand(player, args, null);
    }

    void forwardAccountCommand(Player player, String[] args, String feedbackMessage) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("command");
                output.writeInt(args.length);
                for (String arg : args) {
                    output.writeUTF(arg);
                }
            }
            player.sendPluginMessage(plugin, AccountBridgeChannel.ID, bytes.toByteArray());
            if (feedbackMessage != null && !feedbackMessage.isEmpty()) {
                notifySuccess(player, "请求已提交", feedbackMessage);
            }
        } catch (IOException exception) {
            notifyError(player, "提交失败", "账号命令转发失败，请联系管理员");
            plugin.getLogger().warning("Failed to forward Netease account command: " + exception.getMessage());
        }
    }

    void notifySuccess(Player player, String title, String message) {
        if (sendDreamEngineNotify(player, true, title, message)) {
            return;
        }
        if (message != null && !message.isEmpty()) {
            player.sendMessage(success(message));
        }
    }

    void notifyError(Player player, String title, String message) {
        if (sendDreamEngineNotify(player, false, title, message)) {
            return;
        }
        if (message != null && !message.isEmpty()) {
            player.sendMessage(error(message));
        }
    }

    private boolean sendDreamEngineNotify(Player player, boolean success, String title, String message) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("DreamEngine")) {
            return false;
        }
        try {
            String coloredTitle = color(title);
            String coloredMessage = color(message);
            if (success) {
                MessageUtil.notifySuccess(player, coloredTitle, coloredMessage);
            } else {
                MessageUtil.notifyError(player, coloredTitle, coloredMessage);
            }
            return true;
        } catch (LinkageError | RuntimeException exception) {
            if (!dreamEngineNotifyWarningLogged) {
                dreamEngineNotifyWarningLogged = true;
                plugin.getLogger().warning("DreamEngine notify API is unavailable: " + exception.getMessage());
            }
            return false;
        }
    }

    String success(String text) {
        return color(SUCCESS_PREFIX + text);
    }

    String warning(String text) {
        return color(WARNING_PREFIX + text);
    }

    String error(String text) {
        return color(ERROR_PREFIX + text);
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!AccountBridgeChannel.ID.equals(channel)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            String action = input.readUTF();
            if ("profile_response".equals(action)) {
                handlePlayerProfileResponse(input);
                return;
            }
            if ("entry_type".equals(action)) {
                UUID playerUuid = UUID.fromString(input.readUTF());
                EntryType entryType = EntryType.valueOf(input.readUTF());
                String javaUuidValue = input.readUTF();
                UUID javaUuid = javaUuidValue.isEmpty() ? null : UUID.fromString(javaUuidValue);
                Long javaUid = input.available() >= Long.BYTES ? normalizeUid(input.readLong()) : null;
                Long bedrockUid = input.available() >= Long.BYTES ? normalizeUid(input.readLong()) : null;
                update(playerUuid, entryType, javaUuid, javaUid, bedrockUid);
                return;
            }
        } catch (IllegalArgumentException | IOException exception) {
            plugin.getLogger().warning("Failed to read Netease account bridge message: " + exception.getMessage());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player onlinePlayer = plugin.getServer().getPlayer(playerUuid);
            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                return;
            }
            requestNeteaseAccountState(onlinePlayer);
            logPlayerPlatform(onlinePlayer);
        }, 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    private void logPlayerPlatform(Player player) {
        UUID playerUuid = player.getUniqueId();
        EntryType entryType = getEntryType(playerUuid);
        Optional<UUID> originalJavaUuid = getOriginalJavaUuid(playerUuid);
        OptionalLong originalJavaUid = getOriginalJavaUid(playerUuid);
        OptionalLong bedrockUid = getBedrockUid(playerUuid);
        FloodgateApi api = FloodgateApi.getInstance();
        boolean apiAvailable = api != null;
        boolean floodgateId = apiAvailable && api.isFloodgateId(playerUuid);
        boolean floodgatePlayerFlag = apiAvailable && api.isFloodgatePlayer(playerUuid);
        FloodgatePlayer floodgatePlayer = apiAvailable ? api.getPlayer(playerUuid) : null;

        StringBuilder message = new StringBuilder("玩家平台诊断: 玩家=")
                .append(player.getName())
                .append(" UUID=").append(playerUuid)
                .append(" EntryType=").append(entryType)
                .append(" OriginalJavaUUID=").append(originalJavaUuid.map(UUID::toString).orElse("-"))
                .append(" JavaUID=").append(formatOptionalLong(originalJavaUid))
                .append(" BedrockUID=").append(formatOptionalLong(bedrockUid))
                .append(" FloodgateApi=").append(apiAvailable)
                .append(" isFloodgatePlayer=").append(floodgatePlayerFlag)
                .append(" isFloodgateId=").append(floodgateId)
                .append(" FloodgatePlayer=").append(floodgatePlayer != null);

        if (floodgatePlayer != null) {
            message.append(" DeviceOs=").append(floodgatePlayer.getDeviceOs())
                    .append(" UiProfile=").append(floodgatePlayer.getUiProfile())
                    .append(" InputMode=").append(floodgatePlayer.getInputMode())
                    .append(" BedrockName=").append(floodgatePlayer.getUsername())
                    .append(" JavaName=").append(floodgatePlayer.getJavaUsername())
                    .append(" CorrectName=").append(floodgatePlayer.getCorrectUsername())
                    .append(" JavaUUID=").append(floodgatePlayer.getJavaUniqueId())
                    .append(" CorrectUUID=").append(floodgatePlayer.getCorrectUniqueId())
                    .append(" Linked=").append(floodgatePlayer.isLinked())
                    .append(" FromProxy=").append(floodgatePlayer.isFromProxy())
                    .append(" Lang=").append(floodgatePlayer.getLanguageCode());
        }

        plugin.getLogger().info(message.toString());
    }

    @Override
    public EntryType getEntryType(UUID playerUuid) {
        if (playerUuid == null) {
            return EntryType.UNKNOWN;
        }
        refreshFromForwardedProfile(playerUuid);
        EntryType entryType = entryTypes.get(playerUuid);
        EntryType legacyEntryType = getLegacyFloodgateEntryType(playerUuid);
        if (entryType != null) {
            if (entryType == EntryType.JAVA && legacyEntryType == EntryType.BEDROCK) {
                return EntryType.BEDROCK;
            }
            return entryType;
        }
        return legacyEntryType;
    }

    @Override
    public Optional<UUID> getOriginalJavaUuid(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        refreshFromForwardedProfile(playerUuid);
        UUID javaUuid = javaUuids.get(playerUuid);
        if (javaUuid != null) {
            return Optional.of(javaUuid);
        }
        return entryTypes.get(playerUuid) == EntryType.UNRESOLVED_JAVA
                ? Optional.of(playerUuid)
                : Optional.empty();
    }

    @Override
    public OptionalLong getOriginalJavaUid(UUID playerUuid) {
        if (playerUuid == null) {
            return OptionalLong.empty();
        }
        refreshFromForwardedProfile(playerUuid);
        Long javaUid = javaUids.get(playerUuid);
        return javaUid == null ? OptionalLong.empty() : OptionalLong.of(javaUid);
    }

    @Override
    public OptionalLong getBedrockUid(UUID playerUuid) {
        if (playerUuid == null) {
            return OptionalLong.empty();
        }
        refreshFromForwardedProfile(playerUuid);
        Long bedrockUid = bedrockUids.get(playerUuid);
        return bedrockUid == null ? OptionalLong.empty() : OptionalLong.of(bedrockUid);
    }

    @Override
    public CompletableFuture<Optional<NeteasePlayerProfile>> getPlayerProfile(UUID playerUuid) {
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        CompletableFuture<Optional<NeteasePlayerProfile>> future = new CompletableFuture<>();
        UUID requestId = UUID.randomUUID();
        profileQueries.put(requestId, future);
        Runnable queryTask = () -> sendPlayerProfileQuery(requestId, playerUuid, future);
        try {
            if (plugin.getServer().isPrimaryThread()) {
                queryTask.run();
            } else {
                plugin.getServer().getScheduler().runTask(plugin, queryTask);
            }
        } catch (RuntimeException exception) {
            profileQueries.remove(requestId, future);
            future.completeExceptionally(exception);
        }
        return future;
    }

    private void sendPlayerProfileQuery(
            UUID requestId,
            UUID playerUuid,
            CompletableFuture<Optional<NeteasePlayerProfile>> future) {
        if (future.isDone()) {
            return;
        }

        Player carrier = plugin.getServer().getPlayer(playerUuid);
        if (carrier == null || !carrier.isOnline()) {
            carrier = null;
            for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                carrier = onlinePlayer;
                break;
            }
        }
        if (carrier == null) {
            profileQueries.remove(requestId, future);
            future.completeExceptionally(
                    new IllegalStateException("No online player is available to query the proxy"));
            return;
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("profile_query");
                output.writeUTF(requestId.toString());
                output.writeUTF(playerUuid.toString());
            }
            carrier.sendPluginMessage(plugin, AccountBridgeChannel.ID, bytes.toByteArray());
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (profileQueries.remove(requestId, future)) {
                    future.completeExceptionally(
                            new IllegalStateException("Netease player profile query timed out"));
                }
            }, 200L);
        } catch (IOException | RuntimeException exception) {
            profileQueries.remove(requestId, future);
            future.completeExceptionally(exception);
        }
    }

    private void handlePlayerProfileResponse(DataInputStream input) throws IOException {
        UUID requestId = UUID.fromString(input.readUTF());
        CompletableFuture<Optional<NeteasePlayerProfile>> future = profileQueries.remove(requestId);
        if (future == null) {
            return;
        }

        try {
            int status = input.readUnsignedByte();
            if (status == 0) {
                future.complete(Optional.empty());
                return;
            }
            if (status == 2) {
                future.completeExceptionally(new IllegalStateException(input.readUTF()));
                return;
            }
            if (status != 1) {
                throw new IOException("Unknown Netease player profile response status: " + status);
            }

            UUID playerUuid = UUID.fromString(input.readUTF());
            String name = input.readUTF();
            LocalDateTime firstJoinTime = parseProfileTime(input.readUTF());
            LocalDateTime lastJoinTime = parseProfileTime(input.readUTF());
            future.complete(Optional.of(new NeteasePlayerProfile(
                    playerUuid, name, firstJoinTime, lastJoinTime)));
        } catch (IllegalArgumentException | IOException exception) {
            future.completeExceptionally(exception);
        }
    }

    private static LocalDateTime parseProfileTime(String value) {
        return value == null || value.isEmpty() ? null : LocalDateTime.parse(value);
    }

    public void updateFromForwardedProfile(
            UUID playerUuid,
            String entryTypeValue,
            String javaUuidValue,
            String javaUidValue,
            String bedrockUidValue) {
        if (playerUuid == null || entryTypeValue == null || entryTypeValue.isEmpty()) {
            return;
        }
        try {
            EntryType entryType = EntryType.valueOf(entryTypeValue);
            UUID javaUuid = javaUuidValue == null || javaUuidValue.isEmpty()
                    ? null
                    : UUID.fromString(javaUuidValue);
            update(playerUuid, entryType, javaUuid, parseUid(javaUidValue), parseUid(bedrockUidValue));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ignoring invalid Netease account profile marker for "
                    + playerUuid + ": " + exception.getMessage());
        }
    }

    public void updateFromForwardedProfile(UUID playerUuid, String entryTypeValue, String javaUuidValue) {
        updateFromForwardedProfile(playerUuid, entryTypeValue, javaUuidValue, null, null);
    }

    private void update(UUID playerUuid, EntryType entryType, UUID javaUuid, Long javaUid, Long bedrockUid) {
        entryTypes.put(playerUuid, entryType);
        if (javaUuid == null) {
            javaUuids.remove(playerUuid);
        } else {
            javaUuids.put(playerUuid, javaUuid);
        }
        if (javaUid == null) {
            javaUids.remove(playerUuid);
        } else {
            javaUids.put(playerUuid, javaUid);
        }
        if (bedrockUid == null) {
            bedrockUids.remove(playerUuid);
        } else {
            bedrockUids.put(playerUuid, bedrockUid);
        }
    }

    private void refreshFromForwardedProfile(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        ForwardedProfileMarker marker = readForwardedProfileMarker(player);
        if (marker != null) {
            updateFromForwardedProfile(playerUuid, marker.entryType, marker.javaUuid, marker.javaUid, marker.bedrockUid);
        }
    }

    private ForwardedProfileMarker readForwardedProfileMarker(Player player) {
        Object profile = invokeNoArgs(player, "getPlayerProfile");
        if (profile == null) {
            profile = invokeNoArgs(player, "getProfile");
        }
        if (profile == null) {
            return null;
        }

        Object properties = invokeNoArgs(profile, "getProperties");
        Iterable<?> iterable = asIterable(properties);
        if (iterable == null) {
            return null;
        }

        String entryType = null;
        String javaUuid = null;
        String javaUid = null;
        String bedrockUid = null;
        for (Object property : iterable) {
            String name = invokeStringProperty(property, "getName", "name");
            if (NeteaseAccountProfileProperties.ENTRY_TYPE.equals(name)) {
                entryType = invokeStringProperty(property, "getValue", "value");
            } else if (NeteaseAccountProfileProperties.JAVA_UUID.equals(name)) {
                javaUuid = invokeStringProperty(property, "getValue", "value");
            } else if (NeteaseAccountProfileProperties.JAVA_UID.equals(name)) {
                javaUid = invokeStringProperty(property, "getValue", "value");
            } else if (NeteaseAccountProfileProperties.BEDROCK_UID.equals(name)) {
                bedrockUid = invokeStringProperty(property, "getValue", "value");
            }
        }
        if (entryType == null) {
            return null;
        }
        return new ForwardedProfileMarker(entryType, javaUuid, javaUid, bedrockUid);
    }

    private Iterable<?> asIterable(Object properties) {
        if (properties instanceof Iterable) {
            return (Iterable<?>) properties;
        }
        Object values = invokeNoArgs(properties, "values");
        if (values instanceof Iterable) {
            return (Iterable<?>) values;
        }
        return null;
    }

    private Object invokeNoArgs(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | SecurityException exception) {
            return null;
        }
    }

    private String invokeStringProperty(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArgs(target, methodName);
            if (value instanceof String) {
                return (String) value;
            }
        }
        return null;
    }

    private EntryType getLegacyFloodgateEntryType(UUID playerUuid) {
        return isLegacyFloodgatePlayer(playerUuid) ? EntryType.BEDROCK : EntryType.JAVA;
    }

    private boolean isLegacyFloodgatePlayer(UUID playerUuid) {
        FloodgateApi api = FloodgateApi.getInstance();
        if (api == null || playerUuid == null) {
            return false;
        }
        for (FloodgatePlayer player : api.getPlayers()) {
            if (playerUuid.equals(player.getCorrectUniqueId()) || playerUuid.equals(player.getJavaUniqueId())) {
                return true;
            }
        }
        return api.isFloodgateId(playerUuid) || isLegacyFloodgateUuid(playerUuid);
    }

    private static boolean isLegacyFloodgateUuid(UUID playerUuid) {
        return playerUuid != null && playerUuid.toString().contains(LEGACY_FLOODGATE_UUID_MARKER);
    }

    private void remove(UUID playerUuid) {
        entryTypes.remove(playerUuid);
        javaUuids.remove(playerUuid);
        javaUids.remove(playerUuid);
        bedrockUids.remove(playerUuid);
    }

    SpigotPlugin plugin() {
        return plugin;
    }

    private void requestNeteaseAccountState(Player player) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("request_state");
                output.writeUTF(player.getUniqueId().toString());
            }
            player.sendPluginMessage(plugin, AccountBridgeChannel.ID, bytes.toByteArray());
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to request Netease account state: " + exception.getMessage());
        }
    }

    private static Long normalizeUid(long value) {
        return value < 0L ? null : value;
    }

    private static Long parseUid(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0L ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatOptionalLong(OptionalLong optional) {
        return optional.isPresent() ? String.valueOf(optional.getAsLong()) : "-";
    }

    private static final class ForwardedProfileMarker {
        private final String entryType;
        private final String javaUuid;
        private final String javaUid;
        private final String bedrockUid;

        private ForwardedProfileMarker(String entryType, String javaUuid, String javaUid, String bedrockUid) {
            this.entryType = entryType;
            this.javaUuid = javaUuid;
            this.javaUid = javaUid;
            this.bedrockUid = bedrockUid;
        }
    }
}
