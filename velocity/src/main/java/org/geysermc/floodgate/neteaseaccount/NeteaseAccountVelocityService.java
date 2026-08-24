package org.geysermc.floodgate.neteaseaccount;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.GameProfile.Property;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.URL;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
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
import org.geysermc.floodgate.api.netease.NeteasePlayerProfile;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

public final class NeteaseAccountVelocityService implements NeteaseAccountApi, SimpleCommand {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String SUCCESS_PREFIX = "&8[&a&l!&8] &a";
    private static final String WARNING_PREFIX = "&8[&e&l!&8] &e";
    private static final String ERROR_PREFIX = "&8[&c&l!&8] &c";
    private static final String LEGACY_FLOODGATE_UUID_MARKER = "00000000-0000-4000-8000";
    private static final String BEDROCK_TAKEOVER_JAVA_DISCONNECT =
            "&8[&e&l!&8] &e你的基岩账号已从基岩端登录，Java 入口会话已下线";
    private static final String BEDROCK_RECONNECT_DISCONNECT =
            "&8[&e&l!&8] &e同一网易账号已建立新的基岩连接，旧连接已自动断开";
    private static final String JAVA_WHILE_BEDROCK_DENIED =
            "&8[&c&l!&8] &c你的同网易账号基岩身份已在线，不能同时从 Java 入口进入";
    private static final String ACCOUNT_ACTIVE_DENIED =
            "&8[&c&l!&8] &c该网易账号正在登录或已经在线，请稍后重试";
    private static final String BEDROCK_TAKEOVER_TIMEOUT =
            "&8[&c&l!&8] &c基岩身份接管超时，请稍后重新进入服务器";
    private static final String DEFAULT_ADMIN_KICK_REASON =
            "管理员正在清理异常登录会话，请重新进入服务器。";
    private static final long KICK_VERIFICATION_INTERVAL_MILLIS = 100L;

    private final ProxyServer proxy;
    private final ProxyFloodgateApi floodgateApi;
    private final FloodgateLogger logger;
    private final NeteaseAccountConfig config;
    private final NeteaseUidResolver uidResolver;
    private final MinecraftChannelIdentifier bridgeChannel = MinecraftChannelIdentifier.from(AccountBridgeChannel.ID);
    private final AccountSessionRegistry<Player> accountSessions = new AccountSessionRegistry<>();
    private final Map<UUID, UUID> boundJavaByBedrockUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Long> javaUidByBedrockUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bedrockUidByPlayerUuid = new ConcurrentHashMap<>();
    private final ExecutorService loginExecutor = boundedExecutor(4, 8, 128, "NeteaseAccount-Login-");
    private final ExecutorService commandExecutor = boundedExecutor(2, 2, 64, "NeteaseAccount-Command-");
    private final ExecutorService recordExecutor = boundedExecutor(1, 2, 1024, "NeteaseAccount-Record-");
    private final ExecutorService queryExecutor = boundedExecutor(2, 4, 256, "NeteaseAccount-Query-");
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("NeteaseAccount-Timeout-"));
    private final CommandMeta commandMeta;
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
        if (config.enabled()) {
            validateEnabledConfig();
        }
        this.uidResolver = new NeteaseUidResolver(config.uidQueryTimeoutMillis());
        this.commandMeta = proxy.getCommandManager().metaBuilder(config.commandName())
                .aliases(config.commandAliases().split(","))
                .build();

        if (config.enabled()) {
            if (getRepository() == null) {
                throw new IllegalStateException("Netease account profile requires the Floodgate datasource at startup");
            }
            proxy.getChannelRegistrar().register(bridgeChannel);
            proxy.getCommandManager().register(commandMeta, this);
            NeteaseAccountBridge.setInstance(this);
            logger.info("Netease account auto-unify enabled. first-time Java profiles are resolved automatically, "
                            + "table={}.",
                    config.accountTable());
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

    public boolean isEnabled() {
        return config.enabled();
    }

    private void validateEnabledConfig() {
        validateEndpoint("Bedrock", config.bedrockEndpoint(), NeteaseUidResolver.UidType.BEDROCK);
        validateBedrockIdentityEndpoint(config.bedrockIdentityEndpoint());
        validateEndpoint("Java", config.javaEndpoint(), NeteaseUidResolver.UidType.JAVA);
    }

    private static void validateBedrockIdentityEndpoint(NeteaseAccountConfig.UidEndpoint endpoint) {
        if (endpoint == null || !endpoint.configured()) {
            throw new IllegalStateException("Bedrock Netease uuid-xuid-from-uid-name endpoint/key "
                    + "is not configured in netease-account.yml");
        }
        try {
            NeteaseUidResolver.validateBedrockIdentityEndpointUrl(new URL(endpoint.url()));
        } catch (IOException exception) {
            throw new IllegalStateException("Bedrock Netease uuid-xuid-from-uid-name endpoint is invalid: "
                    + exception.getMessage(), exception);
        }
    }

    private static void validateEndpoint(
            String side,
            NeteaseAccountConfig.UidEndpoint endpoint,
            NeteaseUidResolver.UidType uidType) {
        if (endpoint == null || !endpoint.configured()) {
            throw new IllegalStateException(side
                    + " Netease uid-from-uuid endpoint/key is not configured in netease-account.yml");
        }
        try {
            NeteaseUidResolver.validateEndpointUrl(new URL(endpoint.url()), uidType);
        } catch (IOException exception) {
            throw new IllegalStateException(side
                    + " Netease uid-from-uuid endpoint is invalid: " + exception.getMessage(), exception);
        }
    }

    @Subscribe(order = PostOrder.LATE)
    public void onGameProfileRequest(GameProfileRequestEvent event, Continuation continuation) {
        if (!config.enabled()) {
            continuation.resume();
            return;
        }

        LoginCheck loginCheck = new LoginCheck(event, continuation);
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
        if (!loginCheck.isOpen()) {
            return;
        }
        NeteaseAccountRepository repository = getRepository();
        if (repository == null) {
            failLogin(loginCheck, config.accountSystemUnavailableMessage(), "database unavailable");
            return;
        }

        UUID currentUuid = event.getGameProfile().getId();
        try {
            LoginDecision decision;
            if (isBedrockLogin(repository, currentUuid)) {
                decision = processBedrockLogin(event, repository, currentUuid, loginCheck);
            } else {
                decision = processJavaLogin(event, repository, currentUuid, loginCheck);
            }
            if (decision != null) {
                if (decision.takeoverSession() == null) {
                    completeLoginCheck(loginCheck, decision);
                } else {
                    beginPreRegistrationTakeover(loginCheck, decision);
                }
            }
        } catch (SQLException exception) {
            logger.error("Failed to process Netease account login for {}", exception, event.getUsername());
            failLogin(loginCheck, config.accountSystemUnavailableMessage(), "database query failed");
        } catch (RuntimeException exception) {
            logger.error("Unexpected Netease account login error for {}", exception, event.getUsername());
            failLogin(loginCheck, config.accountSystemUnavailableMessage(), "unexpected login check error");
        }
    }

    private boolean isBedrockLogin(NeteaseAccountRepository repository, UUID currentUuid) throws SQLException {
        if (isKnownFloodgatePlayer(currentUuid)) {
            return true;
        }
        return repository.findLocalProfile(currentUuid, "pe").isPresent();
    }

    private LoginDecision processBedrockLogin(
            GameProfileRequestEvent event,
            NeteaseAccountRepository repository,
            UUID bedrockUuid,
            LoginCheck loginCheck) throws SQLException {
        if (!loginCheck.isOpen()) {
            return null;
        }

        FloodgatePlayer floodgatePlayer = floodgateApi.getPlayerWithoutNeteaseAccountFilter(bedrockUuid);
        String xuid = floodgatePlayer == null || floodgatePlayer.getXuid() == null
                ? NeteaseAccountRepository.deriveBedrockXuid(bedrockUuid)
                : floodgatePlayer.getXuid();
        repository.ensureLocalProfile(bedrockUuid, event.getGameProfile().getName(), "pe");
        if (!loginCheck.isOpen()) {
            return null;
        }

        OptionalLong forwardedBedrockUid = forwardedBedrockUid(floodgatePlayer);
        OptionalLong bedrockUid = forwardedBedrockUid.isPresent()
                ? forwardedBedrockUid
                : queryUid(config.bedrockEndpoint(), bedrockUuid, NeteaseUidResolver.UidType.BEDROCK, "Bedrock");
        if (!loginCheck.isOpen()) {
            return null;
        }
        if (bedrockUid.isPresent()) {
            repository.upsertBedrockProfile(bedrockUid.getAsLong(), bedrockUuid, xuid);
            logger.info("Updated Bedrock Netease profile: uuid={}, uid={}, xuid={}, source={}",
                    bedrockUuid, bedrockUid.getAsLong(), xuid,
                    forwardedBedrockUid.isPresent() ? "geyser-forwarded" : "uid-from-uuid");
        } else {
            logger.warn("Bedrock player {} ({}) entered, but Netease uid-from-uuid returned no uid. "
                    + "Java auto-unify will not be available until this profile is updated.",
                    event.getGameProfile().getName(), bedrockUuid);
        }
        if (!loginCheck.isOpen()) {
            return null;
        }

        GameProfile oldProfile = event.getGameProfile();
        LoginDecision decision = LoginDecision.allow(new GameProfile(
                oldProfile.getId(),
                oldProfile.getName(),
                withNeteaseProfileProperties(
                        oldProfile.getProperties(),
                        EntryType.BEDROCK,
                        null,
                        null,
                        bedrockUid.isPresent() ? bedrockUid.getAsLong() : null,
                        loginCheck.sessionToken(),
                        null)
        ));
        return applyPreRegistrationConflict(
                decision, EntryType.BEDROCK, bedrockUuid, null, null);
    }

    private LoginDecision processJavaLogin(
            GameProfileRequestEvent event,
            NeteaseAccountRepository repository,
            UUID javaUuid,
            LoginCheck loginCheck) throws SQLException {
        if (!loginCheck.isOpen()) {
            return null;
        }
        OptionalLong javaUid = queryUid(
                config.javaEndpoint(), javaUuid, NeteaseUidResolver.UidType.JAVA, "Java");
        if (!loginCheck.isOpen()) {
            return null;
        }
        if (!javaUid.isPresent()) {
            return LoginDecision.deny(config.accountSystemUnavailableMessage());
        }

        long bedrockUid = NeteaseUidResolver.toBedrockUid(javaUid.getAsLong());
        Optional<NeteaseAccountProfile> accountProfile = repository.findByBedrockUid(bedrockUid);
        if (!loginCheck.isOpen()) {
            return null;
        }

        if (!accountProfile.isPresent()) {
            Optional<NeteaseUidResolver.BedrockIdentity> identity = queryBedrockIdentity(
                    bedrockUid, event.getUsername());
            if (!loginCheck.isOpen()) {
                return null;
            }
            if (!identity.isPresent()) {
                logBlockedJava(repository, event, javaUuid, javaUid.getAsLong(), bedrockUid,
                        JavaLoginBlockReason.BEDROCK_IDENTITY_LOOKUP_FAILED,
                        "GameName=" + event.getUsername());
                return LoginDecision.deny(config.accountSystemUnavailableMessage());
            }

            NeteaseUidResolver.BedrockIdentity resolved = identity.get();
            repository.upsertBedrockProfile(
                    bedrockUid, resolved.floodgateUuid(), resolved.xuid());
            accountProfile = Optional.of(new NeteaseAccountProfile(
                    bedrockUid, resolved.floodgateUuid(), resolved.xuid()));
            logger.info("Created Bedrock Netease profile from Java login: javaName={}, bedrockUid={}, "
                            + "apiUuid={}, xuid={}, floodgateUuid={}",
                    event.getUsername(), bedrockUid, resolved.apiUuid(), resolved.xuid(),
                    resolved.floodgateUuid());
        }

        UUID bedrockUuid = accountProfile.get().bedrockUuid();
        LocalProfile bedrockLocalProfile = repository.ensureLocalProfile(
                bedrockUuid, event.getUsername(), "pe");
        if (!loginCheck.isOpen()) {
            return null;
        }

        String finalName = bedrockLocalProfile.name();
        GameProfile oldProfile = event.getGameProfile();
        GameProfile unifiedProfile = new GameProfile(
                bedrockUuid,
                finalName,
                withNeteaseProfileProperties(
                        oldProfile.getProperties(),
                        EntryType.BOUND_JAVA,
                        javaUuid,
                        javaUid.getAsLong(),
                        bedrockUid,
                        loginCheck.sessionToken(),
                        event.getUsername())
        );
        LoginDecision decision = applyPreRegistrationConflict(
                LoginDecision.allow(unifiedProfile),
                EntryType.BOUND_JAVA,
                bedrockUuid,
                javaUuid,
                event.getUsername());
        if (decision.denialMessage() == null) {
            logger.info("Auto-unified Java login: javaName={}, javaUuid={}, javaUid={}, bedrockUid={}, "
                            + "bedrockUuid={}, finalName={}",
                    event.getUsername(), javaUuid, javaUid.getAsLong(), bedrockUid, bedrockUuid, finalName);
        }
        return decision;
    }

    private LoginDecision applyPreRegistrationConflict(
            LoginDecision allowDecision,
            EntryType incomingType,
            UUID accountUuid,
            UUID originalJavaUuid,
            String javaLoginName) {
        AccountSessionRegistry.Session<Player> existing = activeAccountSession(accountUuid);
        if (existing == null && incomingType == EntryType.BEDROCK) {
            existing = registeredProxySession(accountUuid);
        }
        AccountSessionRegistry.ConflictAction action = AccountSessionRegistry.conflictAction(
                incomingType, existing == null ? null : existing.entryType());
        switch (action) {
            case NONE:
                return allowDecision;
            case TAKE_OVER_EXISTING:
                return allowDecision.withTakeoverSession(existing);
            case NOTIFY_BEDROCK_AND_DENY:
                sendLinkedJavaLoginBlockedNotify(existing.connection(), javaLoginName);
                logger.info("Denied Java login {} before Velocity duplicate check because Bedrock uuid {} is active",
                        originalJavaUuid, accountUuid);
                return LoginDecision.deny(JAVA_WHILE_BEDROCK_DENIED);
            case DENY:
            default:
                return LoginDecision.deny(ACCOUNT_ACTIVE_DENIED);
        }
    }

    private void logBlockedJava(
            NeteaseAccountRepository repository,
            GameProfileRequestEvent event,
            UUID javaUuid,
            long javaUid,
            long bedrockUid,
            JavaLoginBlockReason reasonCode,
            String details) {
        String reason = reasonCode.description();
        if (details != null && !details.isEmpty()) {
            reason += "，" + details;
        }
        try {
            repository.insertBlockedJavaLogin(
                    event.getUsername(), javaUuid, javaUid, bedrockUid, reasonCode, reason);
        } catch (SQLException exception) {
            logger.error("无法将被阻止的 Java 登录写入 MySQL 审计日志", exception);
        }
        logger.warn("已阻止无法自动解析基岩身份的 Java 玩家登录: 玩家={}, JavaUUID={}, "
                        + "JavaUID={}, BedrockUID={}, 错误码={}, 原因={}",
                event.getUsername(), javaUuid, javaUid, bedrockUid, reasonCode.code(), reason);
    }

    private OptionalLong queryUid(
            NeteaseAccountConfig.UidEndpoint endpoint,
            UUID uuid,
            NeteaseUidResolver.UidType uidType,
            String side) {
        try {
            return uidResolver.resolve(endpoint, uuid, uidType);
        } catch (IOException exception) {
            logger.warn("{} Netease uid-from-uuid query failed for {}: {}",
                    side, uuid, exception.getMessage());
            return OptionalLong.empty();
        }
    }

    private Optional<NeteaseUidResolver.BedrockIdentity> queryBedrockIdentity(
            long bedrockUid,
            String currentGameName) {
        try {
            return uidResolver.resolveBedrockIdentity(
                    config.bedrockIdentityEndpoint(), bedrockUid, currentGameName);
        } catch (IOException exception) {
            logger.warn("Bedrock Netease uuid-xuid-from-uid-name query failed for uid={} name={}: {}",
                    bedrockUid, currentGameName, exception.getMessage());
            return Optional.empty();
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
            return NeteaseUidResolver.isValidBedrockUid(uid) ? OptionalLong.of(uid) : OptionalLong.empty();
        }
        if (value instanceof String) {
            try {
                long uid = Long.parseLong((String) value);
                return NeteaseUidResolver.isValidBedrockUid(uid) ? OptionalLong.of(uid) : OptionalLong.empty();
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
                NeteaseAccountRepository created = new NeteaseAccountRepository(
                        dataSource, config.accountTable());
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
            if (repository.hasAnyProfile()) {
                logger.info("Skipped localprofile bootstrap because the Netease account profile table "
                        + "already contains data");
                return;
            }
            List<LocalProfile> profiles = repository.listLocalProfiles("pe");
            logger.info("Starting Netease account profile bootstrap from localprofile. PE records={}",
                    profiles.size());
            for (LocalProfile profile : profiles) {
                if (repository.findByBedrockUuid(profile.id()).isPresent()) {
                    skipped++;
                    continue;
                }

                try {
                    OptionalLong bedrockUid = queryUid(
                            config.bedrockEndpoint(), profile.id(),
                            NeteaseUidResolver.UidType.BEDROCK, "Bedrock bootstrap");
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
                } catch (SQLException | RuntimeException exception) {
                    failed++;
                    logger.warn("Skipped localprofile bootstrap for {} ({}): {}",
                            profile.name(), profile.id(), exception.getMessage());
                }
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

    private void beginPreRegistrationTakeover(LoginCheck loginCheck, LoginDecision decision) {
        AccountSessionRegistry.Session<Player> previous = decision.takeoverSession();
        long startedAtNanos = System.nanoTime();
        if (!schedulePreRegistrationTakeoverCheck(loginCheck, decision, previous, startedAtNanos)) {
            return;
        }

        previous.connection().disconnect(message(takeoverDisconnectMessage(previous)));
        logger.info("正在 Velocity 重复登录检查前接管旧网易账号会话: uuid={}, oldType={}, oldPlayer={}",
                previous.accountUuid(), previous.entryType(), previous.connection().getUsername());
    }

    private boolean schedulePreRegistrationTakeoverCheck(
            LoginCheck loginCheck,
            LoginDecision decision,
            AccountSessionRegistry.Session<Player> previous,
            long startedAtNanos) {
        try {
            timeoutExecutor.schedule(
                    () -> checkPreRegistrationTakeover(loginCheck, decision, previous, startedAtNanos),
                    25L,
                    TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException exception) {
            failLogin(loginCheck, config.accountSystemUnavailableMessage(), "takeover scheduler rejected");
            return false;
        }
    }

    private void checkPreRegistrationTakeover(
            LoginCheck loginCheck,
            LoginDecision decision,
            AccountSessionRegistry.Session<Player> previous,
            long startedAtNanos) {
        if (!loginCheck.isOpen()) {
            return;
        }

        AccountSessionRegistry.Session<Player> currentOwner = accountSessions.get(previous.accountUuid());
        if (currentOwner != null && currentOwner != previous) {
            completeLoginCheck(loginCheck, LoginDecision.deny(ACCOUNT_ACTIVE_DENIED));
            return;
        }

        Optional<Player> registered = proxy.getPlayer(previous.accountUuid());
        if (registered.isPresent() && registered.get() != previous.connection()) {
            completeLoginCheck(loginCheck, LoginDecision.deny(ACCOUNT_ACTIVE_DENIED));
            return;
        }

        boolean previousRemoved = !previous.connection().isActive() && !registered.isPresent();
        if (previousRemoved) {
            if (accountSessions.remove(previous)) {
                clearSessionState(previous.accountUuid());
            }
            completeLoginCheck(loginCheck, decision);
            return;
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        if (elapsedMillis >= config.takeoverTimeoutMillis()) {
            completeLoginCheck(loginCheck, LoginDecision.deny(BEDROCK_TAKEOVER_TIMEOUT));
            logger.warn("Bedrock pre-registration takeover timed out for {} after {}ms",
                    previous.accountUuid(), elapsedMillis);
            return;
        }
        schedulePreRegistrationTakeoverCheck(loginCheck, decision, previous, startedAtNanos);
    }

    private void failLogin(LoginCheck loginCheck, String javaMessage, String reason) {
        if (!loginCheck.isOpen()) {
            return;
        }
        logger.warn("Denying login {} because {}", loginCheck.originalUuid(), reason);
        completeLoginCheck(loginCheck, LoginDecision.deny(javaMessage));
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

    private void completeLoginCheck(LoginCheck loginCheck, LoginDecision decision) {
        if (!loginCheck.complete()) {
            return;
        }
        ScheduledFuture<?> timeoutFuture = loginCheck.timeoutFuture();
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        GameProfile profile = decision.profile() == null
                ? loginCheck.event().getGameProfile()
                : decision.profile();
        if (decision.denialMessage() != null) {
            profile = withInternalProperty(
                    profile,
                    NeteaseAccountProfileProperties.LOGIN_DENIAL,
                    decision.denialMessage());
        }
        loginCheck.event().setGameProfile(profile);
        loginCheck.continuation().resume();
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onLogin(LoginEvent event, Continuation continuation) {
        String denialMessage = profileProperty(event.getPlayer(), NeteaseAccountProfileProperties.LOGIN_DENIAL);
        if (denialMessage != null) {
            event.setResult(ResultedEvent.ComponentResult.denied(message(denialMessage)));
            continuation.resume();
            return;
        }

        AccountSessionRegistry.Session<Player> incoming = sessionFrom(event.getPlayer());
        if (incoming == null) {
            continuation.resume();
            return;
        }
        claimAccountSession(event, continuation, incoming);
    }

    private void claimAccountSession(
            LoginEvent event,
            Continuation continuation,
            AccountSessionRegistry.Session<Player> incoming) {
        while (true) {
            AccountSessionRegistry.Session<Player> existing = accountSessions.putIfAbsent(incoming);
            if (existing == null || existing.token().equals(incoming.token())) {
                activateSession(incoming);
                continuation.resume();
                return;
            }
            if (discardInactiveSession(existing)) {
                continue;
            }

            if (incoming.entryType() == EntryType.BEDROCK
                    && accountSessions.replace(existing, incoming)) {
                existing.connection().disconnect(message(takeoverDisconnectMessage(existing)));
                scheduleTakeoverCheck(event, continuation, incoming, existing, System.nanoTime());
                return;
            }

            if (incoming.entryType() == EntryType.BOUND_JAVA
                    && existing.entryType() == EntryType.BEDROCK) {
                sendLinkedJavaLoginBlockedNotify(existing.connection(), incoming.javaLoginName());
                logger.info("Denied Java login {} because Bedrock uuid {} is already active",
                        incoming.originalJavaUuid(), incoming.accountUuid());
                event.setResult(ResultedEvent.ComponentResult.denied(message(JAVA_WHILE_BEDROCK_DENIED)));
            } else {
                event.setResult(ResultedEvent.ComponentResult.denied(message(ACCOUNT_ACTIVE_DENIED)));
            }
            continuation.resume();
            return;
        }
    }

    private void scheduleTakeoverCheck(
            LoginEvent event,
            Continuation continuation,
            AccountSessionRegistry.Session<Player> incoming,
            AccountSessionRegistry.Session<Player> previous,
            long startedAtNanos) {
        try {
            timeoutExecutor.schedule(
                    () -> checkTakeover(event, continuation, incoming, previous, startedAtNanos),
                    25L,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            accountSessions.remove(incoming);
            event.setResult(ResultedEvent.ComponentResult.denied(message(config.accountSystemUnavailableMessage())));
            continuation.resume();
        }
    }

    private void checkTakeover(
            LoginEvent event,
            Continuation continuation,
            AccountSessionRegistry.Session<Player> incoming,
            AccountSessionRegistry.Session<Player> previous,
            long startedAtNanos) {
        Optional<Player> current = proxy.getPlayer(incoming.accountUuid());
        boolean previousRemoved = !previous.connection().isActive()
                && (!current.isPresent() || current.get() != previous.connection());
        if (previousRemoved) {
            activateSession(incoming);
            continuation.resume();
            return;
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        if (elapsedMillis >= config.takeoverTimeoutMillis()) {
            accountSessions.remove(incoming);
            event.setResult(ResultedEvent.ComponentResult.denied(message(BEDROCK_TAKEOVER_TIMEOUT)));
            logger.warn("Bedrock takeover timed out for {} after {}ms",
                    incoming.accountUuid(), elapsedMillis);
            continuation.resume();
            return;
        }
        scheduleTakeoverCheck(event, continuation, incoming, previous, startedAtNanos);
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
            if ("profile_query".equals(action)) {
                handlePlayerProfileQuery(serverConnection, input);
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

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        EntryType entryType = getEntryType(playerUuid);
        String clientType = clientType(entryType);
        String javaUuid = getOriginalJavaUuid(playerUuid)
                .map(UUID::toString)
                .orElse(entryType == EntryType.JAVA ? playerUuid.toString() : "-");
        String remoteAddress = player.getRemoteAddress().getAddress() == null
                ? player.getRemoteAddress().getHostString()
                : player.getRemoteAddress().getAddress().getHostAddress();

        logger.info("玩家进入代理: 玩家={} UUID={} 客户端={} 账号类型={} "
                        + "JavaUUID={} JavaUID={} BedrockUID={} IP={}",
                player.getUsername(), playerUuid, clientType, entryType,
                javaUuid, formatOptionalLong(getOriginalJavaUid(playerUuid)),
                formatOptionalLong(getBedrockUid(playerUuid)), remoteAddress);

        recordPlayerJoin(playerUuid);
        if (entryType == EntryType.BOUND_JAVA) {
            recordSuccessfulJavaLogin(player, remoteAddress);
        }
    }

    private void recordPlayerJoin(UUID playerUuid) {
        try {
            recordExecutor.execute(() -> {
                NeteaseAccountRepository currentRepository = getRepository();
                if (currentRepository == null) {
                    logger.error("无法记录玩家加入时间: 数据库不可用, UUID={}", playerUuid);
                    return;
                }
                try {
                    currentRepository.recordPlayerJoin(playerUuid);
                } catch (SQLException exception) {
                    logger.error("写入玩家加入时间失败: UUID={}", exception, playerUuid);
                }
            });
        } catch (RejectedExecutionException exception) {
            logger.error("玩家加入时间记录队列已满: UUID={}", playerUuid);
        }
    }

    private void recordSuccessfulJavaLogin(Player player, String remoteAddress) {
        UUID bedrockUuid = player.getUniqueId();
        Optional<UUID> javaUuid = getOriginalJavaUuid(bedrockUuid);
        OptionalLong javaUid = getOriginalJavaUid(bedrockUuid);
        OptionalLong bedrockUid = getBedrockUid(bedrockUuid);
        String javaName = profileProperty(player, NeteaseAccountProfileProperties.JAVA_LOGIN_NAME);
        if (!javaUuid.isPresent() || !javaUid.isPresent() || !bedrockUid.isPresent() || javaName == null) {
            logger.error("无法记录成功的 Java 入口登录: 玩家={} BedrockUUID={} JavaUUID={} "
                            + "JavaUID={} BedrockUID={} JavaName={}",
                    player.getUsername(), bedrockUuid,
                    javaUuid.map(UUID::toString).orElse("-"), formatOptionalLong(javaUid),
                    formatOptionalLong(bedrockUid), javaName == null ? "-" : javaName);
            return;
        }

        String finalName = player.getUsername();
        UUID capturedJavaUuid = javaUuid.get();
        long capturedJavaUid = javaUid.getAsLong();
        long capturedBedrockUid = bedrockUid.getAsLong();
        try {
            recordExecutor.execute(() -> {
                NeteaseAccountRepository currentRepository = getRepository();
                if (currentRepository == null) {
                    logger.error("无法记录成功的 Java 入口登录: 数据库不可用, JavaUUID={}", capturedJavaUuid);
                    return;
                }
                try {
                    currentRepository.recordSuccessfulJavaLogin(
                            javaName, capturedJavaUuid, capturedJavaUid, capturedBedrockUid,
                            bedrockUuid, finalName, remoteAddress);
                } catch (SQLException exception) {
                    logger.error("写入 Java 入口成功记录失败: JavaUUID={} BedrockUID={}",
                            exception, capturedJavaUuid, capturedBedrockUid);
                }
            });
        } catch (RejectedExecutionException exception) {
            logger.error("Java 入口成功记录队列已满: JavaUUID={} BedrockUID={}",
                    capturedJavaUuid, capturedBedrockUid);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        AccountSessionRegistry.Session<Player> session = sessionFrom(event.getPlayer());
        if (session != null && accountSessions.removeOwned(session.accountUuid(), session.token())) {
            clearSessionState(session.accountUuid());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        NeteaseAccountBridge.clearInstance(this);
        proxy.getChannelRegistrar().unregister(bridgeChannel);
        proxy.getCommandManager().unregister(commandMeta);
        loginExecutor.shutdown();
        commandExecutor.shutdown();
        recordExecutor.shutdown();
        queryExecutor.shutdown();
        timeoutExecutor.shutdown();
        try {
            if (!loginExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                loginExecutor.shutdownNow();
            }
            if (!commandExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                commandExecutor.shutdownNow();
            }
            if (!recordExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                recordExecutor.shutdownNow();
            }
            if (!queryExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                queryExecutor.shutdownNow();
            }
            if (!timeoutExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            loginExecutor.shutdownNow();
            commandExecutor.shutdownNow();
            recordExecutor.shutdownNow();
            queryExecutor.shutdownNow();
            timeoutExecutor.shutdownNow();
        }
        boundJavaByBedrockUuid.clear();
        javaUidByBedrockUuid.clear();
        bedrockUidByPlayerUuid.clear();
        accountSessions.clear();
    }

    @Override
    public EntryType getEntryType(UUID playerUuid) {
        if (playerUuid == null) {
            return EntryType.UNKNOWN;
        }
        AccountSessionRegistry.Session<Player> session = accountSessions.get(playerUuid);
        if (session != null) {
            return session.entryType();
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

    @Override
    public OptionalLong getOriginalJavaUid(UUID playerUuid) {
        if (playerUuid == null) {
            return OptionalLong.empty();
        }
        Long boundJavaUid = javaUidByBedrockUuid.get(playerUuid);
        if (boundJavaUid != null) {
            return OptionalLong.of(boundJavaUid);
        }
        return OptionalLong.empty();
    }

    @Override
    public OptionalLong getBedrockUid(UUID playerUuid) {
        if (playerUuid == null) {
            return OptionalLong.empty();
        }
        Long bedrockUid = bedrockUidByPlayerUuid.get(playerUuid);
        return bedrockUid == null ? OptionalLong.empty() : OptionalLong.of(bedrockUid);
    }

    @Override
    public CompletableFuture<Optional<NeteasePlayerProfile>> getPlayerProfile(UUID playerUuid) {
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        CompletableFuture<Optional<NeteasePlayerProfile>> future = new CompletableFuture<>();
        try {
            queryExecutor.execute(() -> {
                NeteaseAccountRepository currentRepository = getRepository();
                if (currentRepository == null) {
                    future.completeExceptionally(
                            new IllegalStateException("Netease account database is unavailable"));
                    return;
                }
                try {
                    future.complete(currentRepository.findPlayerProfile(playerUuid));
                } catch (SQLException exception) {
                    future.completeExceptionally(exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            future.completeExceptionally(
                    new IllegalStateException("Netease account profile query queue is full", exception));
        }
        return future;
    }

    private List<Property> withNeteaseProfileProperties(
            List<Property> originalProperties,
            EntryType entryType,
            UUID javaUuid,
            Long javaUid,
            Long bedrockUid,
            UUID sessionToken,
            String javaLoginName) {
        List<Property> properties = new ArrayList<>();
        for (Property property : originalProperties) {
            if (!NeteaseAccountProfileProperties.ENTRY_TYPE.equals(property.getName())
                    && !NeteaseAccountProfileProperties.JAVA_UUID.equals(property.getName())
                    && !NeteaseAccountProfileProperties.JAVA_UID.equals(property.getName())
                    && !NeteaseAccountProfileProperties.BEDROCK_UID.equals(property.getName())
                    && !NeteaseAccountProfileProperties.SESSION_TOKEN.equals(property.getName())
                    && !NeteaseAccountProfileProperties.LOGIN_DENIAL.equals(property.getName())
                    && !NeteaseAccountProfileProperties.JAVA_LOGIN_NAME.equals(property.getName())) {
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
        properties.add(new Property(
                NeteaseAccountProfileProperties.SESSION_TOKEN, sessionToken.toString(), ""));
        if (javaLoginName != null && !javaLoginName.isEmpty()) {
            properties.add(new Property(NeteaseAccountProfileProperties.JAVA_LOGIN_NAME, javaLoginName, ""));
        }
        return properties;
    }

    private AccountSessionRegistry.Session<Player> sessionFrom(Player player) {
        String entryTypeValue = profileProperty(player, NeteaseAccountProfileProperties.ENTRY_TYPE);
        String tokenValue = profileProperty(player, NeteaseAccountProfileProperties.SESSION_TOKEN);
        if (entryTypeValue == null || tokenValue == null) {
            return null;
        }
        try {
            EntryType entryType = EntryType.valueOf(entryTypeValue);
            if (entryType != EntryType.BEDROCK && entryType != EntryType.BOUND_JAVA) {
                return null;
            }
            UUID javaUuid = parseUuid(profileProperty(player, NeteaseAccountProfileProperties.JAVA_UUID));
            Long javaUid = parseLong(profileProperty(player, NeteaseAccountProfileProperties.JAVA_UID));
            Long bedrockUid = parseLong(profileProperty(player, NeteaseAccountProfileProperties.BEDROCK_UID));
            return new AccountSessionRegistry.Session<>(
                    player.getUniqueId(),
                    UUID.fromString(tokenValue),
                    entryType,
                    player,
                    javaUuid,
                    javaUid,
                    bedrockUid,
                    profileProperty(player, NeteaseAccountProfileProperties.JAVA_LOGIN_NAME));
        } catch (IllegalArgumentException exception) {
            logger.warn("Ignoring invalid Netease account session marker for {}: {}",
                    player.getUniqueId(), exception.getMessage());
            return null;
        }
    }

    private static String profileProperty(Player player, String name) {
        for (Property property : player.getGameProfileProperties()) {
            if (name.equals(property.getName())) {
                return property.getValue();
            }
        }
        return null;
    }

    private void activateSession(AccountSessionRegistry.Session<Player> session) {
        UUID accountUuid = session.accountUuid();
        if (session.entryType() == EntryType.BOUND_JAVA) {
            boundJavaByBedrockUuid.put(accountUuid, session.originalJavaUuid());
            if (session.javaUid() != null) {
                javaUidByBedrockUuid.put(accountUuid, session.javaUid());
            } else {
                javaUidByBedrockUuid.remove(accountUuid);
            }
        } else {
            boundJavaByBedrockUuid.remove(accountUuid);
            javaUidByBedrockUuid.remove(accountUuid);
        }
        if (session.bedrockUid() != null) {
            bedrockUidByPlayerUuid.put(accountUuid, session.bedrockUid());
        } else {
            bedrockUidByPlayerUuid.remove(accountUuid);
        }
    }

    private AccountSessionRegistry.Session<Player> activeAccountSession(UUID accountUuid) {
        while (true) {
            AccountSessionRegistry.Session<Player> existing = accountSessions.get(accountUuid);
            if (existing == null || !discardInactiveSession(existing)) {
                return existing;
            }
        }
    }

    private AccountSessionRegistry.Session<Player> registeredProxySession(UUID accountUuid) {
        Optional<Player> registered = proxy.getPlayer(accountUuid);
        if (!registered.isPresent()) {
            return null;
        }

        Player player = registered.get();
        AccountSessionRegistry.Session<Player> markedSession = sessionFrom(player);
        if (markedSession != null) {
            logger.info("发现未登记但仍占用统一 UUID 的网易账号会话: uuid={}, type={}, player={}",
                    accountUuid, markedSession.entryType(), player.getUsername());
            return markedSession;
        }

        EntryType entryType = isKnownFloodgatePlayer(accountUuid) ? EntryType.BEDROCK : EntryType.UNKNOWN;
        logger.warn("发现缺少会话标记但仍占用统一 UUID 的代理连接: uuid={}, inferredType={}, player={}",
                accountUuid, entryType, player.getUsername());
        return new AccountSessionRegistry.Session<>(
                accountUuid,
                UUID.randomUUID(),
                entryType,
                player,
                null,
                null,
                bedrockUidByPlayerUuid.get(accountUuid),
                null);
    }

    private static String takeoverDisconnectMessage(AccountSessionRegistry.Session<Player> previous) {
        return previous.entryType() == EntryType.BOUND_JAVA
                ? BEDROCK_TAKEOVER_JAVA_DISCONNECT
                : BEDROCK_RECONNECT_DISCONNECT;
    }

    private boolean discardInactiveSession(AccountSessionRegistry.Session<Player> session) {
        if (session.connection().isActive()) {
            return false;
        }
        Optional<Player> registered = proxy.getPlayer(session.accountUuid());
        if (registered.isPresent() && registered.get() == session.connection()) {
            return false;
        }
        if (accountSessions.remove(session)) {
            clearSessionState(session.accountUuid());
            logger.info("Removed stale Netease account session: uuid={}, type={}",
                    session.accountUuid(), session.entryType());
        }
        return true;
    }

    private void clearSessionState(UUID accountUuid) {
        boundJavaByBedrockUuid.remove(accountUuid);
        javaUidByBedrockUuid.remove(accountUuid);
        bedrockUidByPlayerUuid.remove(accountUuid);
    }

    private static UUID parseUuid(String value) {
        return value == null || value.isEmpty() ? null : UUID.fromString(value);
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        long parsed = Long.parseLong(value);
        return parsed < 0L ? null : parsed;
    }

    private GameProfile withInternalProperty(GameProfile profile, String name, String value) {
        List<Property> properties = new ArrayList<>();
        for (Property property : profile.getProperties()) {
            if (!name.equals(property.getName())) {
                properties.add(property);
            }
        }
        properties.add(new Property(name, value, ""));
        return new GameProfile(profile.getId(), profile.getName(), properties);
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

    private void handlePlayerProfileQuery(ServerConnection serverConnection, DataInputStream input)
            throws IOException {
        UUID requestId = UUID.fromString(input.readUTF());
        UUID playerUuid = UUID.fromString(input.readUTF());
        getPlayerProfile(playerUuid).whenComplete((profile, error) -> {
            if (error != null) {
                logger.warn("Failed to query Netease player profile for {}: {}",
                        playerUuid, error.getMessage(), error);
            }
            try {
                serverConnection.sendPluginMessage(
                        bridgeChannel, createPlayerProfileResponse(requestId, profile, error));
            } catch (IOException exception) {
                logger.warn("Failed to send Netease player profile response for {}: {}",
                        playerUuid, exception.getMessage());
            }
        });
    }

    private byte[] createPlayerProfileResponse(
            UUID requestId,
            Optional<NeteasePlayerProfile> profile,
            Throwable error) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("profile_response");
            output.writeUTF(requestId.toString());
            if (error != null) {
                output.writeByte(2);
                output.writeUTF("账号档案查询失败");
            } else if (profile == null || !profile.isPresent()) {
                output.writeByte(0);
            } else {
                NeteasePlayerProfile value = profile.get();
                output.writeByte(1);
                output.writeUTF(value.getUniqueId().toString());
                output.writeUTF(value.getName());
                output.writeUTF(value.getFirstJoinTime().isPresent()
                        ? value.getFirstJoinTime().get().toString() : "");
                output.writeUTF(value.getLastJoinTime().isPresent()
                        ? value.getLastJoinTime().get().toString() : "");
            }
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
                : null;
        Long javaUid = nullableOptional(getOriginalJavaUid(playerUuid));
        Long bedrockUid = nullableOptional(getBedrockUid(playerUuid));
        try {
            connection.sendPluginMessage(
                    bridgeChannel,
                    createEntryTypePayload(playerUuid, entryType, javaUuid, javaUid, bedrockUid)
            );
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
                .replace("%java_name%", javaName == null ? "-" : javaName)
                .replace("%bedrock_name%", bedrockPlayer.getUsername())));
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (source instanceof Player) {
            source.sendMessage(warning("请在子服中使用该命令，以打开账号互通界面"));
            return;
        }
        if (!isOnlineCommand(args) && !isKickCommand(args)) {
            sendConsoleCommandUsage(source);
            return;
        }
        try {
            commandExecutor.execute(() -> {
                if (isOnlineCommand(args)) {
                    showOnlinePlayers(source, args);
                } else {
                    kickOnlinePlayer(source, args);
                }
            });
        } catch (RejectedExecutionException exception) {
            source.sendMessage(error("账号互通服务繁忙，请稍后重试"));
            logger.warn("Netease account console command executor rejected a request");
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        List<String> suggestions = new ArrayList<>();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            for (String value : new String[]{"online", "list", "players", "kick", "kickuuid"}) {
                if (value.startsWith(prefix)) {
                    suggestions.add(value);
                }
            }
            return suggestions;
        }
        if (args.length == 2 && isOnlineCommand(args)) {
            String prefix = args[1].toLowerCase();
            if ("all".startsWith(prefix)) {
                suggestions.add("all");
            }
            for (Player player : proxy.getAllPlayers()) {
                if (player.getUsername().toLowerCase().startsWith(prefix)) {
                    suggestions.add(player.getUsername());
                }
            }
        } else if (args.length == 2 && isKickCommand(args)) {
            String prefix = args[1].toLowerCase();
            boolean uuidOnly = args[0].equalsIgnoreCase("kickuuid");
            for (Player player : proxy.getAllPlayers()) {
                String value = uuidOnly ? player.getUniqueId().toString() : player.getUsername();
                if (value.toLowerCase().startsWith(prefix)) {
                    suggestions.add(value);
                }
            }
        }
        return suggestions;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return !(invocation.source() instanceof Player);
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

    private boolean isKickCommand(String[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return false;
        }
        return args[0].equalsIgnoreCase("kick")
                || args[0].equalsIgnoreCase("kickuuid");
    }

    private void sendConsoleCommandUsage(CommandSource source) {
        source.sendMessage(warning("控制台用法: /" + config.commandName()
                + " online [all|玩家名]"));
        source.sendMessage(warning("控制台用法: /" + config.commandName()
                + " kick <玩家名或UUID> [原因]"));
        source.sendMessage(warning("控制台用法: /" + config.commandName()
                + " kickuuid <统一UUID> [原因]"));
    }

    private void kickOnlinePlayer(CommandSource requester, String[] args) {
        if (args.length < 2 || args[1] == null || args[1].trim().isEmpty()) {
            sendConsoleCommandUsage(requester);
            return;
        }

        Optional<Player> target;
        if (args[0].equalsIgnoreCase("kickuuid")) {
            try {
                target = proxy.getPlayer(UUID.fromString(args[1]));
            } catch (IllegalArgumentException exception) {
                requester.sendMessage(error("UUID 格式无效: " + args[1]));
                return;
            }
        } else {
            target = findOnlinePlayer(args[1]);
        }
        if (!target.isPresent()) {
            requester.sendMessage(warning("未找到代理在线玩家: " + args[1]));
            return;
        }

        Player player = target.get();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getUsername();
        EntryType entryType = getEntryType(playerUuid);
        String serverName = currentServerName(player);
        String reason = kickReason(args);
        long startedAtNanos = System.nanoTime();

        requester.sendMessage(warning("正在临时踢出玩家: " + playerName
                + " UUID=" + playerUuid + " 类型=" + entryType + " 子服=" + serverName));
        logger.info("控制台请求临时踢出网易账号会话: player={}, uuid={}, type={}, server={}, reason={}",
                playerName, playerUuid, entryType, serverName, reason);
        try {
            player.disconnect(error(reason));
        } catch (RuntimeException exception) {
            requester.sendMessage(error("发送断开请求失败: " + exception.getMessage()));
            logger.error("Failed to disconnect Netease account session for {} ({})",
                    exception, playerName, playerUuid);
            return;
        }

        scheduleKickVerification(requester, player, startedAtNanos);
    }

    private static String kickReason(String[] args) {
        if (args.length <= 2) {
            return DEFAULT_ADMIN_KICK_REASON;
        }
        StringBuilder reason = new StringBuilder();
        for (int index = 2; index < args.length; index++) {
            if (args[index] == null || args[index].isEmpty()) {
                continue;
            }
            if (reason.length() > 0) {
                reason.append(' ');
            }
            reason.append(args[index]);
        }
        return reason.length() == 0 ? DEFAULT_ADMIN_KICK_REASON : reason.toString();
    }

    private void scheduleKickVerification(
            CommandSource requester,
            Player target,
            long startedAtNanos) {
        try {
            timeoutExecutor.schedule(
                    () -> verifyPlayerKicked(requester, target, startedAtNanos),
                    KICK_VERIFICATION_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            requester.sendMessage(warning("断开请求已发送，但无法启动结果确认任务"));
            logger.warn("Kick verification scheduler rejected {} ({})",
                    target.getUsername(), target.getUniqueId());
        }
    }

    private void verifyPlayerKicked(
            CommandSource requester,
            Player target,
            long startedAtNanos) {
        UUID playerUuid = target.getUniqueId();
        Optional<Player> registered = proxy.getPlayer(playerUuid);
        boolean targetRemoved = !target.isActive()
                && (!registered.isPresent() || registered.get() != target);
        if (targetRemoved) {
            cleanupDisconnectedSession(target);
            requester.sendMessage(success("已临时踢出玩家: " + target.getUsername()
                    + " UUID=" + playerUuid));
            logger.info("网易账号会话已从代理移除: player={}, uuid={}",
                    target.getUsername(), playerUuid);
            return;
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        if (elapsedMillis >= Math.max(1_000L, config.takeoverTimeoutMillis())) {
            requester.sendMessage(error("已发送断开请求，但玩家仍存在于代理列表: "
                    + target.getUsername() + " UUID=" + playerUuid));
            logger.warn("Player remained registered after kick request: player={}, uuid={}, elapsed={}ms",
                    target.getUsername(), playerUuid, elapsedMillis);
            return;
        }
        scheduleKickVerification(requester, target, startedAtNanos);
    }

    private void cleanupDisconnectedSession(Player target) {
        UUID playerUuid = target.getUniqueId();
        AccountSessionRegistry.Session<Player> current = accountSessions.get(playerUuid);
        if (current != null && current.connection() == target) {
            if (accountSessions.remove(current)) {
                clearSessionState(playerUuid);
            }
            return;
        }
        if (current == null) {
            clearSessionState(playerUuid);
        }
    }

    private void showOnlinePlayers(CommandSource requester, String[] args) {
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
        }

        requester.sendMessage(success("在线玩家诊断: 总数=" + onlinePlayers.size()
                + " Java=" + javaCount
                + " 基岩=" + bedrockCount
                + " Java继承=" + boundJavaCount
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

        requester.sendMessage(info("按服务器分组:"));
        for (Map.Entry<String, List<String>> entry : playersByServer.entrySet()) {
            List<String> names = entry.getValue();
            names.sort(String.CASE_INSENSITIVE_ORDER);
            requester.sendMessage(plainInfo("[" + entry.getKey() + "] (" + names.size() + "): "
                    + String.join(", ", names)));
        }

        List<String> javaPlayers = new ArrayList<>();
        List<String> bedrockPlayers = new ArrayList<>();
        List<String> unknownPlayers = new ArrayList<>();
        for (Player onlinePlayer : sortedPlayers) {
            EntryType entryType = getEntryType(onlinePlayer.getUniqueId());
            if (entryType == EntryType.BEDROCK) {
                bedrockPlayers.add(onlinePlayer.getUsername());
            } else if (entryType == EntryType.JAVA
                    || entryType == EntryType.BOUND_JAVA) {
                javaPlayers.add(onlinePlayer.getUsername());
            } else {
                unknownPlayers.add(onlinePlayer.getUsername());
            }
        }

        requester.sendMessage(info("按平台分组:"));
        sendOnlineGroup(requester, "Java版", javaPlayers);
        sendOnlineGroup(requester, "基岩版", bedrockPlayers);
        sendOnlineGroup(requester, "未知平台", unknownPlayers);
    }

    private void sendOnlineGroup(CommandSource requester, String groupName, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        requester.sendMessage(plainInfo("[" + groupName + "] (" + names.size() + "): "
                + String.join(", ", names)));
    }

    private Optional<Player> findOnlinePlayer(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Optional.empty();
        }
        Optional<Player> direct = proxy.getPlayer(query);
        if (direct.isPresent()) {
            return direct;
        }
        try {
            Optional<Player> byUuid = proxy.getPlayer(UUID.fromString(query));
            if (byUuid.isPresent()) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
            // The query is a player name rather than a UUID.
        }
        for (Player onlinePlayer : proxy.getAllPlayers()) {
            if (onlinePlayer.getUsername().equalsIgnoreCase(query)) {
                return Optional.of(onlinePlayer);
            }
        }
        return Optional.empty();
    }

    private void sendOnlinePlayerDetail(CommandSource requester, Player target) {
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
        requester.sendMessage(info("代理账号检查: 已通过"));
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
            default:
                return "未知";
        }
    }

    private static String clientType(EntryType entryType) {
        if (entryType == EntryType.BEDROCK) {
            return "BEDROCK";
        }
        if (entryType == EntryType.JAVA
                || entryType == EntryType.BOUND_JAVA) {
            return "JAVA";
        }
        return "UNKNOWN";
    }

    public static long toJavaUid(long bedrockUid) {
        return NeteaseUidResolver.toJavaUid(bedrockUid);
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

    private static ExecutorService boundedExecutor(
            int coreThreads,
            int maximumThreads,
            int queueCapacity,
            String threadPrefix) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreThreads,
                maximumThreads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(threadPrefix),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class LoginDecision {
        private final GameProfile profile;
        private final String denialMessage;
        private final AccountSessionRegistry.Session<Player> takeoverSession;

        private LoginDecision(
                GameProfile profile,
                String denialMessage,
                AccountSessionRegistry.Session<Player> takeoverSession) {
            this.profile = profile;
            this.denialMessage = denialMessage;
            this.takeoverSession = takeoverSession;
        }

        private static LoginDecision allow(GameProfile profile) {
            return new LoginDecision(profile, null, null);
        }

        private static LoginDecision deny(String denialMessage) {
            return new LoginDecision(null, denialMessage, null);
        }

        private LoginDecision withTakeoverSession(AccountSessionRegistry.Session<Player> takeoverSession) {
            return new LoginDecision(profile, denialMessage, takeoverSession);
        }

        private GameProfile profile() {
            return profile;
        }

        private String denialMessage() {
            return denialMessage;
        }

        private AccountSessionRegistry.Session<Player> takeoverSession() {
            return takeoverSession;
        }
    }

    private static final class LoginCheck {
        private final GameProfileRequestEvent event;
        private final UUID originalUuid;
        private final String username;
        private final UUID sessionToken = UUID.randomUUID();
        private final Continuation continuation;
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile ScheduledFuture<?> timeoutFuture;

        private LoginCheck(GameProfileRequestEvent event, Continuation continuation) {
            this.event = event;
            this.originalUuid = event.getGameProfile().getId();
            this.username = event.getUsername();
            this.continuation = continuation;
        }

        private GameProfileRequestEvent event() {
            return event;
        }

        private UUID originalUuid() {
            return originalUuid;
        }

        private String username() {
            return username;
        }

        private UUID sessionToken() {
            return sessionToken;
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
