/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package org.geysermc.floodgate.pluginmessage;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.geysermc.floodgate.SpigotPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.netease.EntryType;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.neteaseaccount.NeteaseAccountSpigotBridge;
import org.geysermc.floodgate.pluginmessage.PluginMessageChannel.Result;

@RequiredArgsConstructor
public class SpigotPluginMessageRegistration implements PluginMessageRegistration {
    private static final int ENTRY_TYPE_LOOKUP_RETRIES = 40;

    private final JavaPlugin plugin;
    private final FloodgateApi api;

    @Override
    public void register(PluginMessageChannel channel) {
        Messenger messenger = plugin.getServer().getMessenger();

        messenger.registerIncomingPluginChannel(
                plugin,
                channel.getIdentifier(),
                (channel1, player, message) -> handleIncomingMessage(
                        channel,
                        player,
                        Arrays.copyOf(message, message.length),
                        ENTRY_TYPE_LOOKUP_RETRIES));

        messenger.registerOutgoingPluginChannel(plugin, channel.getIdentifier());
    }

    private void handleIncomingMessage(
            PluginMessageChannel channel,
            Player player,
            byte[] message,
            int retriesRemaining
    ) {
        if (!player.isOnline()) {
            return;
        }

        FloodgatePlayer floodgatePlayer = api.getPlayer(player.getUniqueId());
        Result result;
        if (floodgatePlayer == null) {
            EntryType entryType = getConfirmedEntryType(player);
            if (entryType != EntryType.BEDROCK) {
                if (entryType == EntryType.UNKNOWN && api.isFloodgatePlayer(player.getUniqueId())) {
                    if (retriesRemaining > 0) {
                        plugin.getServer().getScheduler().runTaskLater(
                                plugin,
                                () -> handleIncomingMessage(
                                        channel, player, message, retriesRemaining - 1),
                                1L);
                    }
                    return;
                }
                player.kickPlayer("Only Floodgate players can send floodgate messages!");
                return;
            }

            if (!channel.supportsServerCallWithoutPlayer()) {
                return;
            }
            result = channel.handleServerCallWithoutPlayer(
                    message, player.getUniqueId(), player.getName());
        } else {
            result = channel.handleServerCall(message, floodgatePlayer);
        }

        if (!result.isAllowed() && result.getReason() != null) {
            player.kickPlayer(result.getReason());
        }
    }

    private EntryType getConfirmedEntryType(Player player) {
        if (!(plugin instanceof SpigotPlugin)) {
            return EntryType.UNKNOWN;
        }
        NeteaseAccountSpigotBridge bridge = ((SpigotPlugin) plugin).getNeteaseAccountBridge();
        return bridge == null ? EntryType.UNKNOWN : bridge.getConfirmedEntryType(player);
    }
}
