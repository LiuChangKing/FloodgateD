package org.geysermc.floodgate.neteasebind;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.geysermc.floodgate.SpigotPlugin;
import org.geysermc.floodgate.api.netease.EntryType;
import org.geysermc.floodgate.api.netease.NeteaseAccountApi;
import org.geysermc.floodgate.api.netease.NeteaseAccountBridge;

public final class NeteaseBindSpigotBridge implements Listener, PluginMessageListener, CommandExecutor, TabCompleter, NeteaseAccountApi {
    private final SpigotPlugin plugin;
    private final NeteaseBindConfig config;
    private final Map<UUID, EntryType> entryTypes = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> javaUuids = new ConcurrentHashMap<>();

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
        plugin.getServer().getServicesManager().unregister(NeteaseAccountApi.class, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BridgeChannel.ID);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, BridgeChannel.ID);
        NeteaseAccountBridge.clearInstance(this);
        entryTypes.clear();
        javaUuids.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("这个命令只能由玩家执行");
            return true;
        }

        Player player = (Player) sender;
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
        return Collections.emptyList();
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
        player.sendMessage("当前子服未安装 DreamEngine，无法打开绑定界面");
        sendFallbackUsage(player);
    }

    private void sendFallbackUsage(Player player) {
        player.sendMessage("Java 玩家请输入 /" + config.commandName() + " 获取验证码");
        player.sendMessage("基岩玩家请输入 /" + config.commandName() + " <Java玩家名> <验证码> 完成绑定");
        player.sendMessage("已绑定玩家请输入 /" + config.commandName() + " unbind confirm 解除绑定");
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
                player.sendMessage(feedbackMessage);
            }
        } catch (IOException exception) {
            player.sendMessage("绑定命令转发失败，请联系管理员");
            plugin.getLogger().warning("Failed to forward Netease bind command: " + exception.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!BridgeChannel.ID.equals(channel)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            String action = input.readUTF();
            if (!"entry_type".equals(action)) {
                return;
            }
            UUID playerUuid = UUID.fromString(input.readUTF());
            EntryType entryType = EntryType.valueOf(input.readUTF());
            String javaUuidValue = input.readUTF();
            UUID javaUuid = javaUuidValue.isEmpty() ? null : UUID.fromString(javaUuidValue);
            update(playerUuid, entryType, javaUuid);
        } catch (IllegalArgumentException | IOException exception) {
            plugin.getLogger().warning("Failed to read Netease bind entry type message: " + exception.getMessage());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    @Override
    public EntryType getEntryType(UUID playerUuid) {
        return entryTypes.getOrDefault(playerUuid, EntryType.UNKNOWN);
    }

    @Override
    public Optional<UUID> getOriginalJavaUuid(UUID playerUuid) {
        return Optional.ofNullable(javaUuids.get(playerUuid));
    }

    private void update(UUID playerUuid, EntryType entryType, UUID javaUuid) {
        entryTypes.put(playerUuid, entryType);
        if (javaUuid == null) {
            javaUuids.remove(playerUuid);
        } else {
            javaUuids.put(playerUuid, javaUuid);
        }
    }

    private void remove(UUID playerUuid) {
        entryTypes.remove(playerUuid);
        javaUuids.remove(playerUuid);
    }

    SpigotPlugin plugin() {
        return plugin;
    }
}
