package org.geysermc.floodgate.neteasebind;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.floodgate.SpigotPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.netease.EntryType;
import org.geysermc.floodgate.api.netease.NeteaseBindProfileProperties;
import org.geysermc.floodgate.api.netease.NeteaseAccountApi;
import org.geysermc.floodgate.api.netease.NeteaseAccountBridge;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

public final class NeteaseBindSpigotBridge implements Listener, PluginMessageListener, CommandExecutor, TabCompleter, NeteaseAccountApi {
    private static final String SUCCESS_PREFIX = "&8[&a&l!&8] &a";
    private static final String WARNING_PREFIX = "&8[&e&l!&8] &e";
    private static final String ERROR_PREFIX = "&8[&c&l!&8] &c";
    private static final String LEGACY_FLOODGATE_UUID_MARKER = "00000000-0000-4000-8000";
    private static final String ADMIN_PERMISSION = "floodgate.neteasebind.admin";

    private final SpigotPlugin plugin;
    private final NeteaseBindConfig config;
    private final Map<UUID, EntryType> entryTypes = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> javaUuids = new ConcurrentHashMap<>();
    private final Map<UUID, Long> unboundPromptPendingUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUnboundPromptAt = new ConcurrentHashMap<>();
    private BukkitTask promptTask;
    private volatile boolean dreamEnginePromptWarningLogged;
    private volatile boolean dreamEngineNotifyWarningLogged;

    public NeteaseBindSpigotBridge(SpigotPlugin plugin) {
        this.plugin = plugin;
        try {
            this.config = NeteaseBindConfig.load(plugin.getDataFolder().toPath());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load netease-bind.yml", exception);
        }
    }

    public void enable() {
        if (!config.enabled()) {
            plugin.getLogger().info("Netease bind companion is disabled by netease-bind.yml");
            return;
        }

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BridgeChannel.ID);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BridgeChannel.ID, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getServicesManager().register(NeteaseAccountApi.class, this, plugin, ServicePriority.Normal);
        NeteaseAccountBridge.setInstance(this);
        startUnboundPromptTask();

        PluginCommand command = plugin.getCommand(config.commandName());
        if (command == null && !"neteasebind".equalsIgnoreCase(config.commandName())) {
            plugin.getLogger().warning("Command '" + config.commandName()
                    + "' is not declared in plugin.yml; falling back to /neteasebind");
            command = plugin.getCommand("neteasebind");
        }
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        } else {
            plugin.getLogger().warning("Netease bind command is unavailable because plugin.yml has no command entry");
        }
        plugin.getLogger().info("Netease bind companion enabled");
    }

    public void disable() {
        if (promptTask != null) {
            promptTask.cancel();
            promptTask = null;
        }
        plugin.getServer().getServicesManager().unregister(NeteaseAccountApi.class, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BridgeChannel.ID);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, BridgeChannel.ID);
        NeteaseAccountBridge.clearInstance(this);
        entryTypes.clear();
        javaUuids.clear();
        unboundPromptPendingUntil.clear();
        lastUnboundPromptAt.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(error("这个命令只能由玩家执行"));
            return true;
        }

        Player player = (Player) sender;
        if (isOnlineCommand(args) && !player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage(error("你没有权限查看账号互通在线诊断"));
            return true;
        }

        if (args.length == 0 || (args.length == 1
                && (args[0].equalsIgnoreCase("ui") || args[0].equalsIgnoreCase("menu")))) {
            openBindUiOrFallback(player);
            return true;
        }

        forwardBindCommand(player, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> candidates = new ArrayList<>();
            candidates.add("status");
            candidates.add("unbind");
            candidates.add("ui");
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                candidates.add("online");
                candidates.add("list");
                candidates.add("players");
            }
            if (sender instanceof Player) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (!player.getName().equalsIgnoreCase(sender.getName())) {
                        candidates.add(player.getName());
                    }
                }
            }
            return filterPrefix(candidates, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unbind")) {
            return filterPrefix(Collections.singletonList("confirm"), args[1]);
        }
        if (args.length == 2 && isOnlineCommand(args) && sender.hasPermission(ADMIN_PERMISSION)) {
            List<String> candidates = new ArrayList<>();
            candidates.add("all");
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                candidates.add(player.getName());
            }
            return filterPrefix(candidates, args[1]);
        }
        return Collections.emptyList();
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

    private void openBindUiOrFallback(Player player) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("DreamEngine")) {
            try {
                new NeteaseBindDreamEngineUi(this).open(player);
            } catch (LinkageError error) {
                plugin.getLogger().warning("DreamEngine UI classes are unavailable: " + error.getMessage());
                sendFallbackUsage(player);
            }
            return;
        }
        player.sendMessage(warning("当前子服未安装 DreamEngine，无法打开绑定界面"));
        sendFallbackUsage(player);
    }

    private void sendFallbackUsage(Player player) {
        player.sendMessage(warning("Java 玩家请输入 /" + config.commandName() + " 获取验证码"));
        player.sendMessage(warning("基岩玩家请输入 /" + config.commandName() + " <Java玩家名> <验证码> 完成绑定"));
        player.sendMessage(warning("已绑定玩家请输入 /" + config.commandName() + " unbind confirm 解除绑定"));
    }

    void forwardBindCommand(Player player, String[] args) {
        forwardBindCommand(player, args, null);
    }

    void forwardBindCommand(Player player, String[] args, String feedbackMessage) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("command");
                output.writeInt(args.length);
                for (String arg : args) {
                    output.writeUTF(arg);
                }
            }
            player.sendPluginMessage(plugin, BridgeChannel.ID, bytes.toByteArray());
            if (feedbackMessage != null && !feedbackMessage.isEmpty()) {
                notifySuccess(player, "请求已提交", feedbackMessage);
            }
        } catch (IOException exception) {
            notifyError(player, "提交失败", "绑定命令转发失败，请联系管理员");
            plugin.getLogger().warning("Failed to forward Netease bind command: " + exception.getMessage());
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
        if (!BridgeChannel.ID.equals(channel)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            String action = input.readUTF();
            if ("entry_type".equals(action)) {
                UUID playerUuid = UUID.fromString(input.readUTF());
                EntryType entryType = EntryType.valueOf(input.readUTF());
                String javaUuidValue = input.readUTF();
                UUID javaUuid = javaUuidValue.isEmpty() ? null : UUID.fromString(javaUuidValue);
                update(playerUuid, entryType, javaUuid);
                return;
            }
            if (!"bind_prompt_state".equals(action)) {
                return;
            }
            UUID playerUuid = UUID.fromString(input.readUTF());
            boolean unbound = input.readBoolean();
            long pendingExpiresAtMillis = input.readLong();
            updateUnboundPromptState(playerUuid, unbound, pendingExpiresAtMillis);
        } catch (IllegalArgumentException | IOException exception) {
            plugin.getLogger().warning("Failed to read Netease bind bridge message: " + exception.getMessage());
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
            requestNeteaseBindState(onlinePlayer);
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
        return Optional.ofNullable(javaUuids.get(playerUuid));
    }

    public void updateFromForwardedProfile(UUID playerUuid, String entryTypeValue, String javaUuidValue) {
        if (playerUuid == null || entryTypeValue == null || entryTypeValue.isEmpty()) {
            return;
        }
        try {
            EntryType entryType = EntryType.valueOf(entryTypeValue);
            UUID javaUuid = javaUuidValue == null || javaUuidValue.isEmpty()
                    ? null
                    : UUID.fromString(javaUuidValue);
            update(playerUuid, entryType, javaUuid);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ignoring invalid Netease bind profile marker for "
                    + playerUuid + ": " + exception.getMessage());
        }
    }

    private void update(UUID playerUuid, EntryType entryType, UUID javaUuid) {
        entryTypes.put(playerUuid, entryType);
        if (javaUuid == null) {
            javaUuids.remove(playerUuid);
        } else {
            javaUuids.put(playerUuid, javaUuid);
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
            updateFromForwardedProfile(playerUuid, marker.entryType, marker.javaUuid);
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
        for (Object property : iterable) {
            String name = invokeStringProperty(property, "getName", "name");
            if (NeteaseBindProfileProperties.ENTRY_TYPE.equals(name)) {
                entryType = invokeStringProperty(property, "getValue", "value");
            } else if (NeteaseBindProfileProperties.JAVA_UUID.equals(name)) {
                javaUuid = invokeStringProperty(property, "getValue", "value");
            }
        }
        if (entryType == null) {
            return null;
        }
        return new ForwardedProfileMarker(entryType, javaUuid);
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
        unboundPromptPendingUntil.remove(playerUuid);
        lastUnboundPromptAt.remove(playerUuid);
    }

    SpigotPlugin plugin() {
        return plugin;
    }

    private void startUnboundPromptTask() {
        long intervalTicks = unboundPromptIntervalMillis() / 50L;
        promptTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::sendUnboundBindPrompts,
                40L,
                intervalTicks
        );
    }

    private void updateUnboundPromptState(UUID playerUuid, boolean unbound, long pendingExpiresAtMillis) {
        if (playerUuid == null) {
            return;
        }
        if (!unbound) {
            unboundPromptPendingUntil.remove(playerUuid);
            lastUnboundPromptAt.remove(playerUuid);
            return;
        }
        unboundPromptPendingUntil.put(playerUuid, Math.max(0L, pendingExpiresAtMillis));
        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player != null) {
            sendUnboundBindPromptIfNeeded(player);
        }
    }

    private void sendUnboundBindPrompts() {
        for (UUID playerUuid : unboundPromptPendingUntil.keySet()) {
            Player player = plugin.getServer().getPlayer(playerUuid);
            if (player == null) {
                unboundPromptPendingUntil.remove(playerUuid);
                continue;
            }
            sendUnboundBindPromptIfNeeded(player);
        }
    }

    private void sendUnboundBindPromptIfNeeded(Player player) {
        UUID playerUuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long pendingUntil = unboundPromptPendingUntil.get(playerUuid);
        if (pendingUntil == null || pendingUntil > now) {
            return;
        }

        Long lastPromptAt = lastUnboundPromptAt.get(playerUuid);
        if (lastPromptAt != null && now - lastPromptAt < unboundPromptIntervalMillis()) {
            return;
        }

        String prompt = config.unboundBindPromptMessage();
        String title = config.unboundBindTitle();
        String subtitle = config.unboundBindSubtitle();
        if (sendDreamEnginePrompt(player, prompt, title, subtitle)) {
            lastUnboundPromptAt.put(playerUuid, now);
            return;
        }

        String coloredPrompt = color(prompt);
        if (!coloredPrompt.isEmpty()) {
            player.sendMessage(coloredPrompt);
        }

        String coloredTitle = color(title);
        String coloredSubtitle = color(subtitle);
        if (!coloredTitle.isEmpty() || !coloredSubtitle.isEmpty()) {
            player.sendTitle(coloredTitle, coloredSubtitle, 10, 60, 20);
        }
        lastUnboundPromptAt.put(playerUuid, now);
    }

    private boolean sendDreamEnginePrompt(Player player, String prompt, String title, String subtitle) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("DreamEngine")) {
            return false;
        }
        try {
            if (prompt != null && !prompt.isEmpty()) {
                MessageUtil.sendJsonMessage(player, prompt);
            }
            if ((title != null && !title.isEmpty()) || (subtitle != null && !subtitle.isEmpty())) {
                MessageUtil.sendTitle(player, title == null ? "" : title, subtitle == null ? "" : subtitle, 10, 60, 20);
            }
            return true;
        } catch (LinkageError | RuntimeException exception) {
            if (!dreamEnginePromptWarningLogged) {
                dreamEnginePromptWarningLogged = true;
                plugin.getLogger().warning("DreamEngine prompt API is unavailable: " + exception.getMessage());
            }
            return false;
        }
    }

    private void requestNeteaseBindState(Player player) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("request_state");
                output.writeUTF(player.getUniqueId().toString());
            }
            player.sendPluginMessage(plugin, BridgeChannel.ID, bytes.toByteArray());
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to request Netease bind state: " + exception.getMessage());
        }
    }

    private long unboundPromptIntervalMillis() {
        return Math.max(1L, config.unboundPromptIntervalSeconds()) * 1000L;
    }

    private static final class ForwardedProfileMarker {
        private final String entryType;
        private final String javaUuid;

        private ForwardedProfileMarker(String entryType, String javaUuid) {
            this.entryType = entryType;
            this.javaUuid = javaUuid;
        }
    }
}
