package org.geysermc.floodgate.neteasebind;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.GameProfile.Property;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.geysermc.floodgate.VelocityPlugin;
import org.geysermc.floodgate.api.ProxyFloodgateApi;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.api.netease.EntryType;
import org.geysermc.floodgate.api.netease.NeteaseBindProfileProperties;
import org.geysermc.floodgate.api.netease.NeteaseAccountApi;
import org.geysermc.floodgate.api.netease.NeteaseAccountBridge;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

public final class NeteaseBindVelocityService implements NeteaseAccountApi {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String SUCCESS_PREFIX = "&8[&a&l!&8] &a";
    private static final String WARNING_PREFIX = "&8[&e&l!&8] &e";
    private static final String ERROR_PREFIX = "&8[&c&l!&8] &c";
    private static final String CODE_PATTERN = "\\d{6}";
    private static final String LEGACY_FLOODGATE_UUID_MARKER = "00000000-0000-4000-8000";
    private final ProxyServer proxy;
    private final ProxyFloodgateApi floodgateApi;
    private final FloodgateLogger logger;
    private final NeteaseBindConfig config;
    private final PendingBindService pendingBinds;
    private final MinecraftChannelIdentifier bridgeChannel = MinecraftChannelIdentifier.from(BridgeChannel.ID);
    private final Map<UUID, UUID> boundJavaByBedrockUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> unboundJavaSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Component> deniedJavaLogins = new ConcurrentHashMap<>();
    private final ExecutorService loginExecutor = Executors.newFixedThreadPool(4, new NamedThreadFactory("NeteaseBind-Login-"));
    private final ExecutorService commandExecutor = Executors.newFixedThreadPool(2, new NamedThreadFactory("NeteaseBind-Command-"));
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("NeteaseBind-Timeout-"));
    private volatile BindingRepository repository;
    private volatile long lastRepositoryFailureLog;

    @Inject
    public NeteaseBindVelocityService(
            ProxyServer proxy,
            ProxyFloodgateApi floodgateApi,
            FloodgateLogger logger,
            @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.floodgateApi = floodgateApi;
        this.logger = logger;
        this.config = loadConfig(dataDirectory);
        this.pendingBinds = new PendingBindService(Duration.ofSeconds(config.codeExpireSeconds()));

        if (config.enabled()) {
            if (getRepository() == null) {
                throw new IllegalStateException("Netease bind requires the Floodgate datasource at startup");
            }
            proxy.getChannelRegistrar().register(bridgeChannel);
            NeteaseAccountBridge.setInstance(this);
            logger.info("Netease bind enabled. bind-server={}, table={}. Commands are handled by the Paper companion.",
                    config.bindServer(), config.bindingTable());
        } else {
            logger.info("Netease bind is disabled by netease-bind.yml");
        }
    }

    private NeteaseBindConfig loadConfig(Path dataDirectory) {
        try {
            return NeteaseBindConfig.load(dataDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load netease-bind.yml", exception);
        }
    }

    @Subscribe(order = PostOrder.LATE)
    public void onGameProfileRequest(GameProfileRequestEvent event, Continuation continuation) {
        if (!config.enabled()) {
            continuation.resume();
            return;
        }

        LoginCheck loginCheck = new LoginCheck(event.getGameProfile().getId(), event.getUsername(), continuation);
        ScheduledFuture<?> timeoutFuture = timeoutExecutor.schedule(
                () -> onLoginCheckTimeout(loginCheck),
                Math.max(1L, config.loginCheckTimeoutMillis()),
                TimeUnit.MILLISECONDS);
        loginCheck.setTimeoutFuture(timeoutFuture);

        try {
            loginExecutor.execute(() -> processGameProfileRequest(event, loginCheck));
        } catch (RejectedExecutionException exception) {
            logger.warn("Netease bind login executor rejected {}", event.getUsername());
            failLogin(loginCheck, config.loginTaskRejectedMessage(), "login task rejected");
        }
    }

    private void processGameProfileRequest(GameProfileRequestEvent event, LoginCheck loginCheck) {
        BindingRepository repository = getRepository();
        if (repository == null) {
            failLogin(loginCheck, config.bindSystemUnavailableMessage(), "database unavailable");
            return;
        }

        UUID currentUuid = event.getGameProfile().getId();
        try {
            Optional<LocalProfile> peProfile = repository.findLocalProfile(currentUuid, "pe");
            if (!loginCheck.isOpen()) {
                return;
            }
            if (peProfile.isPresent()) {
                UUID boundJavaUuid = boundJavaByBedrockUuid.remove(currentUuid);
                if (boundJavaUuid != null) {
                    proxy.getPlayer(currentUuid).ifPresent(javaPlayer -> {
                        javaPlayer.disconnect(Component.text("你的绑定基岩账号已从基岩端登录，你已被下线"));
                        logger.info("Disconnected bound Java session {} because Bedrock {} logged in",
                                boundJavaUuid, currentUuid);
                    });
                }
                GameProfile oldProfile = event.getGameProfile();
                event.setGameProfile(new GameProfile(
                        oldProfile.getId(),
                        oldProfile.getName(),
                        withNeteaseProfileProperties(oldProfile.getProperties(), EntryType.BEDROCK, null)
                ));
                return;
            }

            UUID javaUuid = currentUuid;
            Optional<Binding> binding = repository.findBindingByJavaUuid(javaUuid);
            if (!loginCheck.isOpen()) {
                return;
            }
            if (!binding.isPresent()) {
                unboundJavaSessions.put(javaUuid, Boolean.TRUE);
                repository.ensureLocalProfile(javaUuid, event.getUsername(), "pc");
                return;
            }

            UUID bedrockUuid = binding.get().bedrockUuid();
            Optional<Player> onlineBedrockPlayer = proxy.getPlayer(bedrockUuid);
            if (!loginCheck.isOpen()) {
                return;
            }
            if (onlineBedrockPlayer.isPresent() && !boundJavaByBedrockUuid.containsKey(bedrockUuid)) {
                Component denyMessage = Component.text("你的绑定基岩账号已在线，不能同时从 Java 入口进入");
                deniedJavaLogins.put(javaUuid, denyMessage);
                sendBoundJavaLoginBlockedNotify(onlineBedrockPlayer.get(), event.getUsername());
                logger.info("Denied Java login {} because bound Bedrock {} is already online", javaUuid, bedrockUuid);
                return;
            }

            Optional<LocalProfile> bedrockProfile = repository.findLocalProfile(bedrockUuid, "pe");
            if (!loginCheck.isOpen()) {
                return;
            }
            if (!bedrockProfile.isPresent()) {
                logger.warn("Binding for Java {} points to Bedrock {}, but localprofile has no PE record",
                        javaUuid, bedrockUuid);
                unboundJavaSessions.put(javaUuid, Boolean.TRUE);
                return;
            }

            String bedrockName = config.useOriginBedrockName()
                    ? bedrockProfile.get().displayName()
                    : bedrockProfile.get().name();
            GameProfile oldProfile = event.getGameProfile();
            event.setGameProfile(new GameProfile(
                    bedrockUuid,
                    bedrockName,
                    withNeteaseProfileProperties(oldProfile.getProperties(), EntryType.BOUND_JAVA, javaUuid)
            ));
            boundJavaByBedrockUuid.put(bedrockUuid, javaUuid);
            deniedJavaLogins.remove(javaUuid);
            unboundJavaSessions.remove(javaUuid);
        } catch (SQLException exception) {
            logger.error("Failed to process Netease bind login for {}", exception, event.getUsername());
            failLogin(loginCheck, config.bindSystemUnavailableMessage(), "database query failed");
        } catch (RuntimeException exception) {
            logger.error("Unexpected Netease bind login error for {}", exception, event.getUsername());
            failLogin(loginCheck, config.bindSystemUnavailableMessage(), "unexpected login check error");
        } finally {
            completeLoginCheck(loginCheck);
        }
    }

    private BindingRepository getRepository() {
        BindingRepository current = repository;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (repository != null) {
                return repository;
            }
            HikariDataSource dataSource;
            try {
                dataSource = VelocityPlugin.requireDataSource();
            } catch (IllegalStateException exception) {
                logRepositoryFailure(exception.getMessage());
                return null;
            }
            try {
                BindingRepository created = new BindingRepository(dataSource, config.bindingTable());
                created.initialize();
                repository = created;
                logger.info("Netease bind connected to Floodgate datasource");
                return created;
            } catch (SQLException | RuntimeException exception) {
                logRepositoryFailure(exception.getMessage());
                return null;
            }
        }
    }

    private void logRepositoryFailure(String message) {
        long now = System.currentTimeMillis();
        if (now - lastRepositoryFailureLog > 30_000L) {
            logger.warn("Netease bind is waiting for Floodgate datasource: {}", message);
            lastRepositoryFailureLog = now;
        }
    }

    private void onLoginCheckTimeout(LoginCheck loginCheck) {
        if (!loginCheck.isOpen()) {
            return;
        }
        logger.warn("Netease bind login check timed out after {}ms for {}",
                config.loginCheckTimeoutMillis(), loginCheck.username());
        failLogin(loginCheck, config.bindSystemTimeoutMessage(), "login check timeout");
    }

    private void failLogin(LoginCheck loginCheck, String javaMessage, String reason) {
        if (!loginCheck.isOpen()) {
            return;
        }
        deniedJavaLogins.put(loginCheck.originalUuid(), message(javaMessage));
        logger.warn("Denying login {} because {}", loginCheck.originalUuid(), reason);
        completeLoginCheck(loginCheck);
    }

    private boolean isKnownFloodgatePlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        FloodgatePlayer player = floodgateApi.getPlayerWithoutNeteaseBindFilter(uuid);
        return player != null || floodgateApi.isFloodgateId(uuid) || isLegacyFloodgateUuid(uuid);
    }

    private static boolean isLegacyFloodgateUuid(UUID uuid) {
        return uuid != null && uuid.toString().contains(LEGACY_FLOODGATE_UUID_MARKER);
    }

    private void completeLoginCheck(LoginCheck loginCheck) {
        if (!loginCheck.complete()) {
            return;
        }
        ScheduledFuture<?> timeoutFuture = loginCheck.timeoutFuture();
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        loginCheck.continuation().resume();
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onLogin(LoginEvent event) {
        Component deniedMessage = deniedJavaLogins.remove(event.getPlayer().getUniqueId());
        if (deniedMessage != null) {
            event.setResult(ResultedEvent.ComponentResult.denied(deniedMessage));
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!config.enabled() || !event.getIdentifier().equals(bridgeChannel)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }
        ServerConnection serverConnection = (ServerConnection) event.getSource();

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String action = input.readUTF();
            if ("request_state".equals(action)) {
                handleStateRequest(serverConnection, input);
                return;
            }
            if (!"command".equals(action)) {
                return;
            }
            int length = input.readInt();
            if (length < 0 || length > 8) {
                return;
            }
            String[] args = new String[length];
            for (int i = 0; i < length; i++) {
                args[i] = input.readUTF();
            }
            handleCommand(serverConnection.getPlayer(), args);
        } catch (IllegalArgumentException | IOException exception) {
            logger.warn("Failed to read Netease bind plugin message: {}", exception.getMessage());
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (!config.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(connection -> {
            sendNeteaseBindState(player, connection);
        });
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!config.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!unboundJavaSessions.containsKey(playerUuid)) {
            return;
        }

        RegisteredServer original = event.getOriginalServer();
        if (original.getServerInfo().getName().equalsIgnoreCase(config.bindServer())) {
            return;
        }

        Optional<RegisteredServer> bindServer = proxy.getServer(config.bindServer());
        if (bindServer.isPresent()) {
            player.sendMessage(message(config.unboundRedirectMessage()));
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(bindServer.get()));
        } else {
            player.disconnect(Component.text("绑定服不存在: " + config.bindServer()));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        unboundJavaSessions.remove(uuid);
        boundJavaByBedrockUuid.remove(uuid);
        deniedJavaLogins.remove(uuid);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        NeteaseAccountBridge.clearInstance(this);
        proxy.getChannelRegistrar().unregister(bridgeChannel);
        loginExecutor.shutdown();
        commandExecutor.shutdown();
        timeoutExecutor.shutdown();
        try {
            if (!loginExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                loginExecutor.shutdownNow();
            }
            if (!commandExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                commandExecutor.shutdownNow();
            }
            if (!timeoutExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            loginExecutor.shutdownNow();
            commandExecutor.shutdownNow();
            timeoutExecutor.shutdownNow();
        }
        boundJavaByBedrockUuid.clear();
        unboundJavaSessions.clear();
        deniedJavaLogins.clear();
    }

    @Override
    public EntryType getEntryType(UUID playerUuid) {
        if (playerUuid == null) {
            return EntryType.UNKNOWN;
        }
        if (boundJavaByBedrockUuid.containsKey(playerUuid)) {
            return EntryType.BOUND_JAVA;
        }
        if (isKnownFloodgatePlayer(playerUuid)) {
            return EntryType.BEDROCK;
        }
        return EntryType.JAVA;
    }

    @Override
    public Optional<UUID> getOriginalJavaUuid(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(boundJavaByBedrockUuid.get(playerUuid));
    }

    private List<Property> withNeteaseProfileProperties(
            List<Property> originalProperties,
            EntryType entryType,
            UUID javaUuid) {
        List<Property> properties = new ArrayList<>();
        for (Property property : originalProperties) {
            if (!NeteaseBindProfileProperties.ENTRY_TYPE.equals(property.getName())
                    && !NeteaseBindProfileProperties.JAVA_UUID.equals(property.getName())) {
                properties.add(property);
            }
        }
        properties.add(new Property(NeteaseBindProfileProperties.ENTRY_TYPE, entryType.name(), ""));
        if (javaUuid != null) {
            properties.add(new Property(NeteaseBindProfileProperties.JAVA_UUID, javaUuid.toString(), ""));
        }
        return properties;
    }

    private byte[] createEntryTypePayload(UUID playerUuid, EntryType entryType, UUID javaUuid) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("entry_type");
            output.writeUTF(playerUuid.toString());
            output.writeUTF(entryType.name());
            output.writeUTF(javaUuid == null ? "" : javaUuid.toString());
        }
        return bytes.toByteArray();
    }

    private byte[] createBindPromptStatePayload(UUID playerUuid) throws IOException {
        boolean unbound = unboundJavaSessions.containsKey(playerUuid);
        long pendingExpiresAtMillis = unbound ? pendingBinds.activeBindExpiresAtMillis(playerUuid) : 0L;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("bind_prompt_state");
            output.writeUTF(playerUuid.toString());
            output.writeBoolean(unbound);
            output.writeLong(pendingExpiresAtMillis);
        }
        return bytes.toByteArray();
    }

    private void handleStateRequest(ServerConnection serverConnection, DataInputStream input) throws IOException {
        UUID requestedUuid = UUID.fromString(input.readUTF());
        Player player = serverConnection.getPlayer();
        if (!requestedUuid.equals(player.getUniqueId())) {
            logger.warn("Ignoring Netease bind state request for {} from {}",
                    requestedUuid, player.getUniqueId());
            return;
        }
        sendNeteaseBindState(player, serverConnection);
    }

    private void sendNeteaseBindState(Player player, ServerConnection connection) {
        EntryType entryType = getEntryType(player.getUniqueId());
        UUID javaUuid = entryType == EntryType.BOUND_JAVA ? boundJavaByBedrockUuid.get(player.getUniqueId()) : null;
        try {
            connection.sendPluginMessage(bridgeChannel, createEntryTypePayload(player.getUniqueId(), entryType, javaUuid));
            connection.sendPluginMessage(bridgeChannel, createBindPromptStatePayload(player.getUniqueId()));
        } catch (IOException exception) {
            logger.warn("Failed to send Netease bind state for {}: {}",
                    player.getUsername(), exception.getMessage());
        }
    }

    private void sendBindPromptState(Player player) {
        player.getCurrentServer().ifPresent(connection -> {
            try {
                connection.sendPluginMessage(bridgeChannel, createBindPromptStatePayload(player.getUniqueId()));
            } catch (IOException exception) {
                logger.warn("Failed to send Netease bind prompt state for {}: {}",
                        player.getUsername(), exception.getMessage());
            }
        });
    }

    private void sendBoundJavaLoginBlockedNotify(Player bedrockPlayer, String javaName) {
        String notifyMessage = config.boundJavaLoginBlockedNotifyMessage();
        if (notifyMessage == null || notifyMessage.isBlank()) {
            return;
        }
        bedrockPlayer.sendMessage(message(notifyMessage
                .replace("%java_name%", javaName)
                .replace("%bedrock_name%", bedrockPlayer.getUsername())));
    }

    private void handleCommand(Player player, String[] args) {
        try {
            commandExecutor.execute(() -> handleCommandNow(player, args));
        } catch (RejectedExecutionException exception) {
            player.sendMessage(error("账号互通服务繁忙，请稍后重试"));
            logger.warn("Netease bind command executor rejected {}", player.getUsername());
        }
    }

    private void handleCommandNow(Player player, String[] args) {
        BindingRepository repository = getRepository();
        if (repository == null) {
            player.sendMessage(error("账号互通服务尚未连接到 Floodgate 数据库，请稍后再试"));
            return;
        }

        try {
            if (args.length == 0) {
                createJavaBindRequest(player, repository);
            } else if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
                showStatus(player, repository);
            } else if (isOnlineCommand(args)) {
                showOnlinePlayers(player, args);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("unbind") && args[1].equalsIgnoreCase("confirm")) {
                unbind(player, repository);
            } else if (args.length == 2) {
                confirmBedrockBind(player, args[0], args[1], repository);
            } else {
                player.sendMessage(warning("用法: /" + config.commandName() + " 或 /"
                        + config.commandName() + " <Java玩家名> <验证码> 或 /"
                        + config.commandName() + " status 或 /"
                        + config.commandName() + " online [玩家名] 或 /"
                        + config.commandName() + " unbind confirm"));
            }
        } catch (SQLException exception) {
            player.sendMessage(error("账号互通服务数据库错误，请联系管理员"));
            logger.error("Netease bind command failed", exception);
        } catch (RuntimeException exception) {
            player.sendMessage(error("账号互通命令处理失败，请联系管理员"));
            logger.error("Unexpected Netease bind command error", exception);
        }
    }

    private boolean isOnlineCommand(String[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return false;
        }
        return args[0].equalsIgnoreCase("online")
                || args[0].equalsIgnoreCase("list")
                || args[0].equalsIgnoreCase("players");
    }

    private void showOnlinePlayers(Player requester, String[] args) {
        if (args.length > 2) {
            requester.sendMessage(warning("用法: /" + config.commandName() + " online [all|玩家名]"));
            return;
        }

        if (args.length == 2 && !"all".equalsIgnoreCase(args[1])) {
            Optional<Player> target = findOnlinePlayer(args[1]);
            if (!target.isPresent()) {
                requester.sendMessage(warning("未找到在线玩家: " + args[1]));
                return;
            }
            sendOnlinePlayerDetail(requester, target.get());
            return;
        }

        Collection<Player> onlinePlayers = proxy.getAllPlayers();
        List<Player> sortedPlayers = new ArrayList<>(onlinePlayers);
        sortedPlayers.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                left.getUsername(), right.getUsername()));

        int javaCount = 0;
        int bedrockCount = 0;
        int boundJavaCount = 0;
        int unknownCount = 0;
        int unboundJavaCount = 0;
        for (Player onlinePlayer : sortedPlayers) {
            EntryType entryType = getEntryType(onlinePlayer.getUniqueId());
            switch (entryType) {
                case JAVA:
                    javaCount++;
                    break;
                case BEDROCK:
                    bedrockCount++;
                    break;
                case BOUND_JAVA:
                    boundJavaCount++;
                    break;
                default:
                    unknownCount++;
                    break;
            }
            if (unboundJavaSessions.containsKey(onlinePlayer.getUniqueId())) {
                unboundJavaCount++;
            }
        }

        requester.sendMessage(success("在线玩家诊断: 总数=" + onlinePlayers.size()
                + " Java=" + javaCount
                + " 基岩=" + bedrockCount
                + " 绑定Java=" + boundJavaCount
                + " 未绑定Java引导=" + unboundJavaCount
                + " 未知=" + unknownCount));

        if (sortedPlayers.isEmpty()) {
            requester.sendMessage(warning("当前没有在线玩家"));
            return;
        }

        Map<String, List<String>> playersByServer = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Player onlinePlayer : sortedPlayers) {
            playersByServer.computeIfAbsent(currentServerName(onlinePlayer), key -> new ArrayList<>())
                    .add(onlinePlayer.getUsername());
        }

        for (Map.Entry<String, List<String>> entry : playersByServer.entrySet()) {
            List<String> names = entry.getValue();
            names.sort(String.CASE_INSENSITIVE_ORDER);
            requester.sendMessage(plainInfo("[" + entry.getKey() + "] (" + names.size() + "): "
                    + String.join(", ", names)));
        }
    }

    private Optional<Player> findOnlinePlayer(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        Optional<Player> direct = proxy.getPlayer(query);
        if (direct.isPresent()) {
            return direct;
        }
        for (Player onlinePlayer : proxy.getAllPlayers()) {
            if (onlinePlayer.getUsername().equalsIgnoreCase(query)) {
                return Optional.of(onlinePlayer);
            }
        }
        return Optional.empty();
    }

    private void sendOnlinePlayerDetail(Player requester, Player target) {
        UUID uuid = target.getUniqueId();
        EntryType entryType = getEntryType(uuid);
        Optional<UUID> javaUuid = getOriginalJavaUuid(uuid);
        FloodgatePlayer floodgatePlayer = floodgateApi.getPlayerWithoutNeteaseBindFilter(uuid);

        requester.sendMessage(success("玩家在线诊断: " + target.getUsername()));
        requester.sendMessage(info("类型: " + formatEntryType(entryType) + " (" + entryType + ")"));
        requester.sendMessage(info("UUID: " + uuid));
        requester.sendMessage(info("绑定 Java UUID: " + javaUuid.map(UUID::toString).orElse("-")));
        requester.sendMessage(info("所在子服: " + currentServerName(target)));
        requester.sendMessage(info("未绑定 Java 引导: "
                + (unboundJavaSessions.containsKey(uuid) ? "是" : "否")));
        requester.sendMessage(info("FloodgatePlayer: " + (floodgatePlayer != null)));

        if (floodgatePlayer == null) {
            return;
        }
        requester.sendMessage(info("DeviceOs=" + floodgatePlayer.getDeviceOs()
                + " UiProfile=" + floodgatePlayer.getUiProfile()
                + " InputMode=" + floodgatePlayer.getInputMode()));
        requester.sendMessage(info("BedrockName=" + floodgatePlayer.getUsername()
                + " JavaName=" + floodgatePlayer.getJavaUsername()
                + " CorrectName=" + floodgatePlayer.getCorrectUsername()));
        requester.sendMessage(info("JavaUUID=" + floodgatePlayer.getJavaUniqueId()
                + " CorrectUUID=" + floodgatePlayer.getCorrectUniqueId()
                + " Linked=" + floodgatePlayer.isLinked()
                + " FromProxy=" + floodgatePlayer.isFromProxy()
                + " Lang=" + floodgatePlayer.getLanguageCode()));
    }

    private String currentServerName(Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("-");
    }

    private static String formatEntryType(EntryType entryType) {
        if (entryType == null) {
            return "未知";
        }
        switch (entryType) {
            case JAVA:
                return "Java";
            case BEDROCK:
                return "基岩";
            case BOUND_JAVA:
                return "绑定Java";
            default:
                return "未知";
        }
    }

    private void createJavaBindRequest(Player player, BindingRepository repository) throws SQLException {
        UUID uuid = player.getUniqueId();
        Optional<Binding> boundBedrock = repository.findBindingByBedrockUuid(uuid);
        if (boundJavaByBedrockUuid.containsKey(uuid) || boundBedrock.isPresent()) {
            player.sendMessage(warning("当前账号已经完成绑定"));
            player.sendMessage(warning("输入 /" + config.commandName() + " status 查看绑定状态"));
            player.sendMessage(warning("输入 /" + config.commandName() + " unbind confirm 解除绑定"));
            return;
        }
        if (repository.findLocalProfile(uuid, "pe").isPresent()) {
            player.sendMessage(warning("请让 Java 玩家执行 /" + config.commandName() + " 发起绑定"));
            return;
        }
        if (repository.isJavaBound(uuid)) {
            player.sendMessage(message(config.alreadyBoundMessage()));
            return;
        }

        repository.ensureLocalProfile(uuid, player.getUsername(), "pc");
        PendingBind pending = pendingBinds.create(uuid, player.getUsername());
        sendBindPromptState(player);
        player.sendMessage(success("你的绑定验证码是: " + pending.code()));
        player.sendMessage(success("请让要绑定的基岩玩家在游戏内输入 /"
                + config.commandName() + " " + player.getUsername() + " " + pending.code()));
    }

    private void confirmBedrockBind(Player player, String javaName, String code, BindingRepository repository)
            throws SQLException {
        UUID bedrockUuid = player.getUniqueId();
        Optional<LocalProfile> bedrockProfile = repository.findLocalProfile(bedrockUuid, "pe");
        if (!bedrockProfile.isPresent()) {
            player.sendMessage(warning("只有基岩玩家可以输入验证码完成绑定"));
            return;
        }
        if (!code.matches(CODE_PATTERN)) {
            player.sendMessage(warning("验证码格式不正确，请输入 6 位数字验证码"));
            return;
        }
        if (repository.isBedrockBound(bedrockUuid)) {
            player.sendMessage(message(config.bedrockAlreadyBoundMessage()));
            return;
        }

        Optional<PendingBind> pendingOptional = pendingBinds.peek(code);
        if (!pendingOptional.isPresent()) {
            player.sendMessage(warning("验证码不存在或已过期"));
            return;
        }

        PendingBind pending = pendingOptional.get();
        if (!pending.javaName().equalsIgnoreCase(javaName)) {
            player.sendMessage(warning("Java 玩家名与验证码不匹配"));
            return;
        }
        if (repository.isJavaBound(pending.javaUuid())) {
            player.sendMessage(message(config.alreadyBoundMessage()));
            return;
        }

        Optional<PendingBind> consumed = pendingBinds.consume(code);
        if (!consumed.isPresent()) {
            player.sendMessage(warning("验证码已被使用或已过期，请重新生成"));
            return;
        }
        try {
            repository.createBinding(consumed.get().javaUuid(), bedrockUuid);
        } catch (SQLIntegrityConstraintViolationException exception) {
            player.sendMessage(warning("绑定关系已经存在，请输入 /" + config.commandName() + " status 查看状态"));
            return;
        }
        unboundJavaSessions.remove(consumed.get().javaUuid());
        player.sendMessage(success("绑定成功: " + consumed.get().javaName() + " -> " + bedrockProfile.get().displayName()));
        proxy.getPlayer(pending.javaUuid()).ifPresent(javaPlayer -> {
            sendBindPromptState(javaPlayer);
            javaPlayer.disconnect(message(config.kickAfterBindMessage()));
        });
    }

    private void showStatus(Player player, BindingRepository repository) throws SQLException {
        UUID uuid = player.getUniqueId();
        UUID onlineJavaUuid = boundJavaByBedrockUuid.get(uuid);
        if (onlineJavaUuid != null) {
            player.sendMessage(success("当前身份: 已绑定 Java 玩家，正在使用基岩 UUID 登录"));
            player.sendMessage(success("绑定 Java UUID: " + onlineJavaUuid));
            player.sendMessage(success("当前基岩 UUID: " + uuid));
            Optional<Binding> binding = repository.findBindingByJavaUuid(onlineJavaUuid);
            if (binding.isPresent()) {
                Optional<LocalProfile> boundProfile = repository.findLocalProfile(binding.get().bedrockUuid(), "pe");
                boundProfile.ifPresent(profile -> player.sendMessage(success("绑定基岩账号: " + profile.displayName())));
            }
            return;
        }

        Optional<LocalProfile> peProfile = repository.findLocalProfile(uuid, "pe");
        Optional<Binding> bedrockBinding = repository.findBindingByBedrockUuid(uuid);
        if (peProfile.isPresent()) {
            player.sendMessage(success("当前身份: 基岩玩家 " + peProfile.get().displayName()));
            player.sendMessage((bedrockBinding.isPresent() ? success(
                    "绑定状态: 已被 Java 账号绑定") : warning(
                    "绑定状态: 未被 Java 账号绑定")));
            if (bedrockBinding.isPresent()) {
                player.sendMessage(success("绑定 Java UUID: " + bedrockBinding.get().javaUuid()));
            }
            return;
        }

        Optional<Binding> binding = repository.findBindingByJavaUuid(uuid);
        if (!binding.isPresent()) {
            player.sendMessage(warning("当前身份: Java 玩家，未绑定基岩账号"));
            return;
        }

        Optional<LocalProfile> boundProfile = repository.findLocalProfile(binding.get().bedrockUuid(), "pe");
        player.sendMessage(success("当前身份: Java 玩家，已绑定 "
                + (boundProfile.isPresent() ? boundProfile.get().displayName() : binding.get().bedrockUuid().toString())));
    }

    private void unbind(Player player, BindingRepository repository) throws SQLException {
        UUID uuid = player.getUniqueId();
        UUID javaUuid = boundJavaByBedrockUuid.get(uuid);
        Optional<Binding> binding = javaUuid == null
                ? repository.findBindingByBedrockUuid(uuid)
                : repository.findBindingByJavaUuid(javaUuid);

        if (!binding.isPresent()) {
            binding = repository.findBindingByJavaUuid(uuid);
        }
        if (!binding.isPresent()) {
            player.sendMessage(warning("当前账号没有可解除的绑定关系"));
            return;
        }

        Binding removed = binding.get();
        boolean boundJavaOnline = boundJavaByBedrockUuid.containsKey(removed.bedrockUuid());
        boolean deleted = repository.deleteBindingByJavaUuid(removed.javaUuid());
        boundJavaByBedrockUuid.remove(removed.bedrockUuid());
        pendingBinds.removeByJavaUuid(removed.javaUuid());
        if (!deleted) {
            player.sendMessage(warning("绑定关系已经不存在"));
            return;
        }

        player.sendMessage(success("已解除 Java UUID " + removed.javaUuid() + " 与当前基岩账号的绑定"));
        proxy.getPlayer(removed.bedrockUuid()).ifPresent(javaPlayer -> {
            if (boundJavaOnline) {
                javaPlayer.disconnect(warning("解绑完成，请重新进入服务器"));
            }
        });
    }
    private static Component message(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    private static Component success(String text) {
        return message(SUCCESS_PREFIX + text);
    }

    private static Component warning(String text) {
        return message(WARNING_PREFIX + text);
    }

    private static Component info(String text) {
        return message("&8[&a&l!&8] &7" + text);
    }

    private static Component plainInfo(String text) {
        return message("&7" + text);
    }

    private static Component error(String text) {
        return message(ERROR_PREFIX + text);
    }

    private static final class LoginCheck {
        private final UUID originalUuid;
        private final String username;
        private final Continuation continuation;
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile ScheduledFuture<?> timeoutFuture;

        private LoginCheck(UUID originalUuid, String username, Continuation continuation) {
            this.originalUuid = originalUuid;
            this.username = username;
            this.continuation = continuation;
        }

        private UUID originalUuid() {
            return originalUuid;
        }

        private String username() {
            return username;
        }

        private Continuation continuation() {
            return continuation;
        }

        private boolean isOpen() {
            return !completed.get();
        }

        private boolean complete() {
            return completed.compareAndSet(false, true);
        }

        private ScheduledFuture<?> timeoutFuture() {
            return timeoutFuture;
        }

        private void setTimeoutFuture(ScheduledFuture<?> timeoutFuture) {
            this.timeoutFuture = timeoutFuture;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        private final String prefix;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
