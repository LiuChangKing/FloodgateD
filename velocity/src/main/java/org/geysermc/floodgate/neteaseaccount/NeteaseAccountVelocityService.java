package org.geysermc.floodgate.neteaseaccount;

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
import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
import org.geysermc.floodgate.api.netease.NeteaseAccountApi;
import org.geysermc.floodgate.api.netease.NeteaseAccountBridge;
import org.geysermc.floodgate.api.netease.NeteaseAccountProfileProperties;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

public final class NeteaseAccountVelocityService implements NeteaseAccountApi {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String SUCCESS_PREFIX = "&8[&a&l!&8] &a";
    private static final String WARNING_PREFIX = "&8[&e&l!&8] &e";
    private static final String ERROR_PREFIX = "&8[&c&l!&8] &c";
    private static final String LEGACY_FLOODGATE_UUID_MARKER = "00000000-0000-4000-8000";

    private final ProxyServer proxy;
    private final ProxyFloodgateApi floodgateApi;
    private final FloodgateLogger logger;
    private final NeteaseAccountConfig config;
    private final NeteaseUidResolver uidResolver;
    private final MinecraftChannelIdentifier bridgeChannel = MinecraftChannelIdentifier.from(AccountBridgeChannel.ID);
    private final Map<UUID, UUID> boundJavaByBedrockUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Long> javaUidByBedrockUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bedrockUidByPlayerUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Long> unresolvedJavaUidByJavaUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> unresolvedJavaSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Component> deniedJavaLogins = new ConcurrentHashMap<>();
    private final ExecutorService loginExecutor = Executors.newFixedThreadPool(4, new NamedThreadFactory("NeteaseAccount-Login-"));
    private final ExecutorService commandExecutor = Executors.newFixedThreadPool(2, new NamedThreadFactory("NeteaseAccount-Command-"));
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("NeteaseAccount-Timeout-"));
    private volatile NeteaseAccountRepository repository;
    private volatile long lastRepositoryFailureLog;

    @Inject
    public NeteaseAccountVelocityService(
            ProxyServer proxy,
            ProxyFloodgateApi floodgateApi,
            FloodgateLogger logger,
            @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.floodgateApi = floodgateApi;
        this.logger = logger;
        this.config = loadConfig(dataDirectory);
        this.uidResolver = new NeteaseUidResolver(config.uidQueryTimeoutMillis());

        if (config.enabled()) {
            if (getRepository() == null) {
                throw new IllegalStateException("Netease account profile requires the Floodgate datasource at startup");
            }
            proxy.getChannelRegistrar().register(bridgeChannel);
            NeteaseAccountBridge.setInstance(this);
            logger.info("Netease account auto-unify enabled. unresolved-java-server={}, table={}.",
                    config.unresolvedJavaServer(), config.accountTable());
            if (config.bootstrapLocalProfileOnStartup()) {
                commandExecutor.execute(this::bootstrapLocalProfiles);
            }
        } else {
            logger.info("Netease account auto-unify is disabled by netease-account.yml");
        }
    }

    private NeteaseAccountConfig loadConfig(Path dataDirectory) {
        try {
            return NeteaseAccountConfig.load(dataDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load netease-account.yml", exception);
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
            logger.warn("Netease account login executor rejected {}", event.getUsername());
            failLogin(loginCheck, config.loginTaskRejectedMessage(), "login task rejected");
        }
    }

    private void processGameProfileRequest(GameProfileRequestEvent event, LoginCheck loginCheck) {
        NeteaseAccountRepository repository = getRepository();
        if (repository == null) {
            failLogin(loginCheck, config.accountSystemUnavailableMessage(), "database unavailable");
            return;
        }

        UUID currentUuid = event.getGameProfile().getId();
        try {
            if (isBedrockLogin(repository, currentUuid)) {
                processBedrockLogin(event, repository, currentUuid, loginCheck);
            } else {
                processJavaLogin(event, repository, currentUuid, loginCheck);
            }
        } catch (SQLException exception) {
            logger.error("Failed to process Netease account login for {}", exception, event.getUsername());
            failLogin(loginCheck, config.accountSystemUnavailableMessage(), "database query failed");
        } catch (RuntimeException exception) {
            logger.error("Unexpected Netease account login error for {}", exception, event.getUsername());
            failLogin(loginCheck, config.accountSystemUnavailableMessage(), "unexpected login check error");
        } finally {
            completeLoginCheck(loginCheck);
        }
    }

    private boolean isBedrockLogin(NeteaseAccountRepository repository, UUID currentUuid) throws SQLException {
        if (isKnownFloodgatePlayer(currentUuid)) {
            return true;
        }
        return repository.findLocalProfile(currentUuid, "pe").isPresent();
    }

    private void processBedrockLogin(
            GameProfileRequestEvent event,
            NeteaseAccountRepository repository,
            UUID bedrockUuid,
            LoginCheck loginCheck) throws SQLException {
        if (!loginCheck.isOpen()) {
            return;
        }

        FloodgatePlayer floodgatePlayer = floodgateApi.getPlayerWithoutNeteaseAccountFilter(bedrockUuid);
        String xuid = floodgatePlayer == null || floodgatePlayer.getXuid() == null
                ? NeteaseAccountRepository.deriveBedrockXuid(bedrockUuid)
                : floodgatePlayer.getXuid();
        repository.ensureLocalProfile(bedrockUuid, event.getGameProfile().getName(), "pe");

        OptionalLong forwardedBedrockUid = forwardedBedrockUid(floodgatePlayer);
        OptionalLong bedrockUid = forwardedBedrockUid.isPresent()
                ? forwardedBedrockUid
                : queryUid(config.bedrockEndpoint(), bedrockUuid, "Bedrock");
        if (bedrockUid.isPresent()) {
            repository.upsertBedrockProfile(bedrockUid.getAsLong(), bedrockUuid, xuid);
            bedrockUidByPlayerUuid.put(bedrockUuid, bedrockUid.getAsLong());
            logger.info("Updated Bedrock Netease profile: uuid={}, uid={}, xuid={}, source={}",
                    bedrockUuid, bedrockUid.getAsLong(), xuid,
                    forwardedBedrockUid.isPresent() ? "geyser-forwarded" : "uid-from-uuid");
            disconnectResolvedUnresolvedJavaSessions(bedrockUid.getAsLong(), bedrockUuid);
        } else {
            logger.warn("Bedrock player {} ({}) entered, but Netease uid-from-uuid returned no uid. "
                    + "Java auto-unify will not be available until this profile is updated.",
                    event.getGameProfile().getName(), bedrockUuid);
        }

        UUID oldJavaUuid = boundJavaByBedrockUuid.remove(bedrockUuid);
        if (oldJavaUuid != null) {
            javaUidByBedrockUuid.remove(bedrockUuid);
            proxy.getPlayer(bedrockUuid).ifPresent(existingPlayer -> existingPlayer.disconnect(
                    Component.text("你的基岩账号已从基岩端登录，Java 入口会话已下线")));
        }
        unresolvedJavaSessions.remove(bedrockUuid);
        unresolvedJavaUidByJavaUuid.remove(bedrockUuid);

        GameProfile oldProfile = event.getGameProfile();
        event.setGameProfile(new GameProfile(
                oldProfile.getId(),
                oldProfile.getName(),
                withNeteaseProfileProperties(
                        oldProfile.getProperties(),
                        EntryType.BEDROCK,
                        null,
                        null,
                        bedrockUid.isPresent() ? bedrockUid.getAsLong() : null)
        ));
    }

    private void disconnectResolvedUnresolvedJavaSessions(long bedrockUid, UUID bedrockUuid) {
        List<UUID> resolvedJavaUuids = new ArrayList<>();
        for (UUID javaUuid : unresolvedJavaSessions.keySet()) {
            Long expectedBedrockUid = bedrockUidByPlayerUuid.get(javaUuid);
            if (expectedBedrockUid != null && expectedBedrockUid == bedrockUid) {
                resolvedJavaUuids.add(javaUuid);
            }
        }

        for (UUID javaUuid : resolvedJavaUuids) {
            unresolvedJavaSessions.remove(javaUuid);
            unresolvedJavaUidByJavaUuid.remove(javaUuid);
            bedrockUidByPlayerUuid.remove(javaUuid);
            proxy.getPlayer(javaUuid).ifPresent(javaPlayer -> {
                javaPlayer.disconnect(message(config.resolvedJavaKickMessage()));
                logger.info("Disconnected unresolved Java session {} because Bedrock uuid {} "
                                + "completed the first-entry account profile for uid={}",
                        javaUuid, bedrockUuid, bedrockUid);
            });
        }
    }

    private void processJavaLogin(
            GameProfileRequestEvent event,
            NeteaseAccountRepository repository,
            UUID javaUuid,
            LoginCheck loginCheck) throws SQLException {
        OptionalLong javaUid = queryUid(config.javaEndpoint(), javaUuid, "Java");
        if (!loginCheck.isOpen()) {
            return;
        }
        if (!javaUid.isPresent()) {
            failLogin(loginCheck, config.accountSystemUnavailableMessage(), "Java uid-from-uuid returned no uid");
            return;
        }

        long bedrockUid = toBedrockUid(javaUid.getAsLong());
        Optional<NeteaseAccountProfile> accountProfile = repository.findByBedrockUid(bedrockUid);
        if (!loginCheck.isOpen()) {
            return;
        }

        if (!accountProfile.isPresent()) {
            markUnresolvedJava(event, repository, javaUuid, javaUid.getAsLong(), bedrockUid);
            return;
        }

        UUID bedrockUuid = accountProfile.get().bedrockUuid();
        Optional<Player> onlineBedrock = proxy.getPlayer(bedrockUuid);
        if (onlineBedrock.isPresent()) {
            Component denyMessage = Component.text("你的同网易账号基岩身份已在线，不能同时从 Java 入口进入");
            deniedJavaLogins.put(javaUuid, denyMessage);
            sendLinkedJavaLoginBlockedNotify(onlineBedrock.get(), event.getUsername());
            logger.info("Denied Java login {} because Bedrock uuid {} is already online",
                    javaUuid, bedrockUuid);
            return;
        }

        Optional<LocalProfile> bedrockLocalProfile = repository.findLocalProfile(bedrockUuid, "pe");
        if (!loginCheck.isOpen()) {
            return;
        }
        if (!bedrockLocalProfile.isPresent()) {
            logger.warn("Netease account profile uid={} points to Bedrock uuid {}, but localprofile has no PE record",
                    bedrockUid, bedrockUuid);
            markUnresolvedJava(event, repository, javaUuid, javaUid.getAsLong(), bedrockUid);
            return;
        }

        String finalName = bedrockLocalProfile.get().name();
        GameProfile oldProfile = event.getGameProfile();
        event.setGameProfile(new GameProfile(
                bedrockUuid,
                finalName,
                withNeteaseProfileProperties(
                        oldProfile.getProperties(),
                        EntryType.BOUND_JAVA,
                        javaUuid,
                        javaUid.getAsLong(),
                        bedrockUid)
        ));

        boundJavaByBedrockUuid.put(bedrockUuid, javaUuid);
        javaUidByBedrockUuid.put(bedrockUuid, javaUid.getAsLong());
        bedrockUidByPlayerUuid.put(bedrockUuid, bedrockUid);
        unresolvedJavaSessions.remove(javaUuid);
        unresolvedJavaUidByJavaUuid.remove(javaUuid);
        deniedJavaLogins.remove(javaUuid);
        logger.info("Auto-unified Java login: javaName={}, javaUuid={}, javaUid={}, bedrockUid={}, "
                        + "bedrockUuid={}, finalName={}",
                event.getUsername(), javaUuid, javaUid.getAsLong(), bedrockUid, bedrockUuid, finalName);
    }

    private void markUnresolvedJava(
            GameProfileRequestEvent event,
            NeteaseAccountRepository repository,
            UUID javaUuid,
            long javaUid,
            long bedrockUid) throws SQLException {
        unresolvedJavaSessions.put(javaUuid, Boolean.TRUE);
        unresolvedJavaUidByJavaUuid.put(javaUuid, javaUid);
        bedrockUidByPlayerUuid.put(javaUuid, bedrockUid);
        repository.ensureLocalProfile(javaUuid, event.getUsername(), "pc");

        GameProfile oldProfile = event.getGameProfile();
        event.setGameProfile(new GameProfile(
                javaUuid,
                oldProfile.getName(),
                withNeteaseProfileProperties(
                        oldProfile.getProperties(),
                        EntryType.UNRESOLVED_JAVA,
                        javaUuid,
                        javaUid,
                        bedrockUid)
        ));
        logger.info("Java login needs Bedrock first-entry: javaName={}, javaUuid={}, javaUid={}, bedrockUid={}",
                event.getUsername(), javaUuid, javaUid, bedrockUid);
    }

    private OptionalLong queryUid(NeteaseAccountConfig.UidEndpoint endpoint, UUID uuid, String side) {
        try {
            return uidResolver.resolve(endpoint, uuid);
        } catch (IOException exception) {
            logger.warn("{} Netease uid-from-uuid query failed for {}: {}",
                    side, uuid, exception.getMessage());
            return OptionalLong.empty();
        }
    }

    @SuppressWarnings("deprecation")
    private OptionalLong forwardedBedrockUid(FloodgatePlayer player) {
        if (player == null) {
            return OptionalLong.empty();
        }
        Object value = player.getProperty(NeteaseAccountProfileProperties.GEYSER_BEDROCK_UID);
        if (value instanceof Number) {
            long uid = ((Number) value).longValue();
            return uid > 0 ? OptionalLong.of(uid) : OptionalLong.empty();
        }
        if (value instanceof String) {
            try {
                long uid = Long.parseLong((String) value);
                return uid > 0 ? OptionalLong.of(uid) : OptionalLong.empty();
            } catch (NumberFormatException ignored) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
    }

    private NeteaseAccountRepository getRepository() {
        NeteaseAccountRepository current = repository;
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
                NeteaseAccountRepository created = new NeteaseAccountRepository(dataSource, config.accountTable());
                created.initialize();
                repository = created;
                logger.info("Netease account profile connected to Floodgate datasource");
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
            logger.warn("Netease account profile is waiting for Floodgate datasource: {}", message);
            lastRepositoryFailureLog = now;
        }
    }

    private void bootstrapLocalProfiles() {
        NeteaseAccountRepository repository = getRepository();
        if (repository == null) {
            logger.warn("Skipped Netease account profile bootstrap because datasource is unavailable");
            return;
        }

        int success = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<LocalProfile> profiles = repository.listLocalProfiles("pe");
            logger.info("Starting Netease account profile bootstrap from localprofile. PE records={}",
                    profiles.size());
            for (LocalProfile profile : profiles) {
                if (repository.findByBedrockUuid(profile.id()).isPresent()) {
                    skipped++;
                    continue;
                }

                OptionalLong bedrockUid = queryUid(config.bedrockEndpoint(), profile.id(), "Bedrock bootstrap");
                if (!bedrockUid.isPresent()) {
                    failed++;
                    logger.warn("Skipped localprofile bootstrap for {} ({}) because uid query returned empty",
                            profile.name(), profile.id());
                    continue;
                }

                repository.upsertBedrockProfile(
                        bedrockUid.getAsLong(),
                        profile.id(),
                        NeteaseAccountRepository.deriveBedrockXuid(profile.id())
                );
                success++;
            }
            logger.info("Netease account profile bootstrap finished. success={}, skipped={}, failed={}",
                    success, skipped, failed);
        } catch (SQLException | RuntimeException exception) {
            logger.warn("Netease account profile bootstrap stopped: {}", exception.getMessage());
        }
    }

    private void onLoginCheckTimeout(LoginCheck loginCheck) {
        if (!loginCheck.isOpen()) {
            return;
        }
        logger.warn("Netease account login check timed out after {}ms for {}",
                config.loginCheckTimeoutMillis(), loginCheck.username());
        failLogin(loginCheck, config.accountSystemTimeoutMessage(), "login check timeout");
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
        FloodgatePlayer player = floodgateApi.getPlayerWithoutNeteaseAccountFilter(uuid);
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
            logger.warn("Failed to read Netease account plugin message: {}", exception.getMessage());
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (!config.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(connection -> sendNeteaseAccountState(player, connection));
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!config.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!unresolvedJavaSessions.containsKey(playerUuid)) {
            return;
        }

        RegisteredServer original = event.getOriginalServer();
        if (original.getServerInfo().getName().equalsIgnoreCase(config.unresolvedJavaServer())) {
            return;
        }

        Optional<RegisteredServer> unresolvedJavaServer = proxy.getServer(config.unresolvedJavaServer());
        if (unresolvedJavaServer.isPresent()) {
            player.sendMessage(message(config.unresolvedRedirectMessage()));
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(unresolvedJavaServer.get()));
        } else {
            player.disconnect(Component.text("账号引导服不存在: " + config.unresolvedJavaServer()));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        UUID javaUuid = boundJavaByBedrockUuid.remove(uuid);
        if (javaUuid != null) {
            unresolvedJavaUidByJavaUuid.remove(javaUuid);
        }
        unresolvedJavaSessions.remove(uuid);
        unresolvedJavaUidByJavaUuid.remove(uuid);
        javaUidByBedrockUuid.remove(uuid);
        bedrockUidByPlayerUuid.remove(uuid);
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
        javaUidByBedrockUuid.clear();
        bedrockUidByPlayerUuid.clear();
        unresolvedJavaUidByJavaUuid.clear();
        unresolvedJavaSessions.clear();
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
        if (unresolvedJavaSessions.containsKey(playerUuid)) {
            return EntryType.UNRESOLVED_JAVA;
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
        if (unresolvedJavaSessions.containsKey(playerUuid)) {
            return Optional.of(playerUuid);
        }
        return Optional.ofNullable(boundJavaByBedrockUuid.get(playerUuid));
    }

    @Override
    public OptionalLong getOriginalJavaUid(UUID playerUuid) {
        if (playerUuid == null) {
            return OptionalLong.empty();
        }
        Long boundJavaUid = javaUidByBedrockUuid.get(playerUuid);
        if (boundJavaUid != null) {
            return OptionalLong.of(boundJavaUid);
        }
        Long unresolvedJavaUid = unresolvedJavaUidByJavaUuid.get(playerUuid);
        return unresolvedJavaUid == null ? OptionalLong.empty() : OptionalLong.of(unresolvedJavaUid);
    }

    @Override
    public OptionalLong getBedrockUid(UUID playerUuid) {
        if (playerUuid == null) {
            return OptionalLong.empty();
        }
        Long bedrockUid = bedrockUidByPlayerUuid.get(playerUuid);
        return bedrockUid == null ? OptionalLong.empty() : OptionalLong.of(bedrockUid);
    }

    private List<Property> withNeteaseProfileProperties(
            List<Property> originalProperties,
            EntryType entryType,
            UUID javaUuid,
            Long javaUid,
            Long bedrockUid) {
        List<Property> properties = new ArrayList<>();
        for (Property property : originalProperties) {
            if (!NeteaseAccountProfileProperties.ENTRY_TYPE.equals(property.getName())
                    && !NeteaseAccountProfileProperties.JAVA_UUID.equals(property.getName())
                    && !NeteaseAccountProfileProperties.JAVA_UID.equals(property.getName())
                    && !NeteaseAccountProfileProperties.BEDROCK_UID.equals(property.getName())) {
                properties.add(property);
            }
        }
        properties.add(new Property(NeteaseAccountProfileProperties.ENTRY_TYPE, entryType.name(), ""));
        if (javaUuid != null) {
            properties.add(new Property(NeteaseAccountProfileProperties.JAVA_UUID, javaUuid.toString(), ""));
        }
        if (javaUid != null) {
            properties.add(new Property(NeteaseAccountProfileProperties.JAVA_UID, String.valueOf(javaUid), ""));
        }
        if (bedrockUid != null) {
            properties.add(new Property(NeteaseAccountProfileProperties.BEDROCK_UID, String.valueOf(bedrockUid), ""));
        }
        return properties;
    }

    private byte[] createEntryTypePayload(
            UUID playerUuid,
            EntryType entryType,
            UUID javaUuid,
            Long javaUid,
            Long bedrockUid) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("entry_type");
            output.writeUTF(playerUuid.toString());
            output.writeUTF(entryType.name());
            output.writeUTF(javaUuid == null ? "" : javaUuid.toString());
            output.writeLong(javaUid == null ? -1L : javaUid);
            output.writeLong(bedrockUid == null ? -1L : bedrockUid);
        }
        return bytes.toByteArray();
    }

    private byte[] createAccountPromptStatePayload(UUID playerUuid) throws IOException {
        boolean unresolved = unresolvedJavaSessions.containsKey(playerUuid);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("account_prompt_state");
            output.writeUTF(playerUuid.toString());
            output.writeBoolean(unresolved);
            output.writeLong(0L);
        }
        return bytes.toByteArray();
    }

    private void handleStateRequest(ServerConnection serverConnection, DataInputStream input) throws IOException {
        UUID requestedUuid = UUID.fromString(input.readUTF());
        Player player = serverConnection.getPlayer();
        if (!requestedUuid.equals(player.getUniqueId())) {
            logger.warn("Ignoring Netease account state request for {} from {}",
                    requestedUuid, player.getUniqueId());
            return;
        }
        sendNeteaseAccountState(player, serverConnection);
    }

    private void sendNeteaseAccountState(Player player, ServerConnection connection) {
        UUID playerUuid = player.getUniqueId();
        EntryType entryType = getEntryType(playerUuid);
        UUID javaUuid = entryType == EntryType.BOUND_JAVA
                ? boundJavaByBedrockUuid.get(playerUuid)
                : entryType == EntryType.UNRESOLVED_JAVA ? playerUuid : null;
        Long javaUid = nullableOptional(getOriginalJavaUid(playerUuid));
        Long bedrockUid = nullableOptional(getBedrockUid(playerUuid));
        try {
            connection.sendPluginMessage(
                    bridgeChannel,
                    createEntryTypePayload(playerUuid, entryType, javaUuid, javaUid, bedrockUid)
            );
            connection.sendPluginMessage(bridgeChannel, createAccountPromptStatePayload(playerUuid));
        } catch (IOException exception) {
            logger.warn("Failed to send Netease account state for {}: {}",
                    player.getUsername(), exception.getMessage());
        }
    }

    private void sendLinkedJavaLoginBlockedNotify(Player bedrockPlayer, String javaName) {
        String notifyMessage = config.linkedJavaLoginBlockedNotifyMessage();
        if (notifyMessage == null || notifyMessage.trim().isEmpty()) {
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
            logger.warn("Netease account command executor rejected {}", player.getUsername());
        }
    }

    private void handleCommandNow(Player player, String[] args) {
        NeteaseAccountRepository repository = getRepository();
        if (repository == null) {
            player.sendMessage(error("账号互通服务尚未连接到 Floodgate 数据库，请稍后再试"));
            return;
        }

        try {
            if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("status"))) {
                showStatus(player, repository);
            } else if (isOnlineCommand(args)) {
                showOnlinePlayers(player, args);
            } else {
                player.sendMessage(warning("当前账号互通已改为网易 UID 自动识别，无需验证码或手动解绑"));
                player.sendMessage(warning("用法: /" + config.commandName() + " status 或 /"
                        + config.commandName() + " online [all|玩家名]"));
            }
        } catch (SQLException exception) {
            player.sendMessage(error("账号互通服务数据库错误，请联系管理员"));
            logger.error("Netease account command failed", exception);
        } catch (RuntimeException exception) {
            player.sendMessage(error("账号互通命令处理失败，请联系管理员"));
            logger.error("Unexpected Netease account command error", exception);
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
        int unresolvedJavaCount = 0;
        int unknownCount = 0;
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
                case UNRESOLVED_JAVA:
                    unresolvedJavaCount++;
                    break;
                default:
                    unknownCount++;
                    break;
            }
        }

        requester.sendMessage(success("在线玩家诊断: 总数=" + onlinePlayers.size()
                + " Java=" + javaCount
                + " 基岩=" + bedrockCount
                + " Java继承=" + boundJavaCount
                + " 待基岩首次进入Java=" + unresolvedJavaCount
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
        if (query == null || query.trim().isEmpty()) {
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
        OptionalLong javaUid = getOriginalJavaUid(uuid);
        OptionalLong bedrockUid = getBedrockUid(uuid);
        FloodgatePlayer floodgatePlayer = floodgateApi.getPlayerWithoutNeteaseAccountFilter(uuid);

        requester.sendMessage(success("玩家在线诊断: " + target.getUsername()));
        requester.sendMessage(info("类型: " + formatEntryType(entryType) + " (" + entryType + ")"));
        requester.sendMessage(info("当前 UUID: " + uuid));
        requester.sendMessage(info("原始 Java UUID: " + javaUuid.map(UUID::toString).orElse("-")));
        requester.sendMessage(info("Java UID: " + formatOptionalLong(javaUid)));
        requester.sendMessage(info("Bedrock UID: " + formatOptionalLong(bedrockUid)));
        requester.sendMessage(info("所在子服: " + currentServerName(target)));
        requester.sendMessage(info("等待基岩首次进入: "
                + (unresolvedJavaSessions.containsKey(uuid) ? "是" : "否")));
        requester.sendMessage(info("FloodgatePlayer: " + (floodgatePlayer != null)));

        if (floodgatePlayer == null) {
            return;
        }
        requester.sendMessage(info("DeviceOs=" + floodgatePlayer.getDeviceOs()
                + " UiProfile=" + floodgatePlayer.getUiProfile()
                + " InputMode=" + floodgatePlayer.getInputMode()));
        requester.sendMessage(info("BedrockName=" + floodgatePlayer.getUsername()
                + " JavaName=" + floodgatePlayer.getJavaUsername()
                + " CorrectName=" + floodgatePlayer.getCorrectUsername()
                + " Xuid=" + floodgatePlayer.getXuid()));
        requester.sendMessage(info("JavaUUID=" + floodgatePlayer.getJavaUniqueId()
                + " CorrectUUID=" + floodgatePlayer.getCorrectUniqueId()
                + " Linked=" + floodgatePlayer.isLinked()
                + " FromProxy=" + floodgatePlayer.isFromProxy()
                + " Lang=" + floodgatePlayer.getLanguageCode()));
    }

    private void showStatus(Player player, NeteaseAccountRepository repository) throws SQLException {
        UUID uuid = player.getUniqueId();
        EntryType entryType = getEntryType(uuid);
        Optional<UUID> javaUuid = getOriginalJavaUuid(uuid);
        OptionalLong javaUid = getOriginalJavaUid(uuid);
        OptionalLong bedrockUid = getBedrockUid(uuid);

        player.sendMessage(success("当前账号互通状态"));
        player.sendMessage(info("当前身份: " + formatEntryType(entryType)));
        player.sendMessage(info("当前 UUID: " + uuid));
        player.sendMessage(info("原始 Java UUID: " + javaUuid.map(UUID::toString).orElse("-")));
        player.sendMessage(info("Java UID: " + formatOptionalLong(javaUid)));
        player.sendMessage(info("Bedrock UID: " + formatOptionalLong(bedrockUid)));

        if (entryType == EntryType.BOUND_JAVA) {
            Optional<LocalProfile> localProfile = repository.findLocalProfile(uuid, "pe");
            player.sendMessage(success("已自动继承基岩档案: "
                    + (localProfile.isPresent() ? localProfile.get().name() : uuid.toString())));
            return;
        }

        if (entryType == EntryType.BEDROCK) {
            Optional<NeteaseAccountProfile> accountProfile = repository.findByBedrockUuid(uuid);
            Optional<LocalProfile> localProfile = repository.findLocalProfile(uuid, "pe");
            player.sendMessage(success("基岩档案名: "
                    + (localProfile.isPresent() ? localProfile.get().name() : player.getUsername())));
            player.sendMessage(accountProfile.isPresent()
                    ? success("此基岩档案已可供同网易账号 Java 入口自动继承")
                    : warning("此基岩档案暂未写入网易 UID，可能是 UID 查询接口暂时失败"));
            return;
        }

        if (entryType == EntryType.UNRESOLVED_JAVA) {
            player.sendMessage(warning("尚未找到同网易账号的基岩档案"));
            player.sendMessage(warning("请先使用同一网易账号的基岩版进入本服一次，然后重新使用 Java 版进入"));
            return;
        }

        player.sendMessage(warning("当前是普通 Java 身份，未触发网易账号自动继承"));
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
                return "Java继承基岩档案";
            case UNRESOLVED_JAVA:
                return "待基岩首次进入Java";
            default:
                return "未知";
        }
    }

    private static long toBedrockUid(long javaUid) {
        return javaUid | 0x80000000L;
    }

    public static long toJavaUid(long bedrockUid) {
        return bedrockUid & 0x7fffffffL;
    }

    private static Long nullableOptional(OptionalLong optional) {
        return optional.isPresent() ? optional.getAsLong() : null;
    }

    private static String formatOptionalLong(OptionalLong optional) {
        return optional.isPresent() ? String.valueOf(optional.getAsLong()) : "-";
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
