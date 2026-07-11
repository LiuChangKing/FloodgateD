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

package org.geysermc.floodgate.pluginmessage.channel;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import org.bukkit.Bukkit;
import org.geysermc.floodgate.api.events.ClientPlayerInitializedEvent;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.pluginmessage.PluginMessageChannel;

public class CustomChannel implements PluginMessageChannel {
    @Inject private FloodgateLogger logger;

    @Override
    public String getIdentifier() {
        return "floodgate:custom";
    }

    @Override
    public Result handleProxyCall(
            byte[] data,
            FloodgatePlayer source,
            Identity sourceIdentity
    ) {
        return null;
    }

    @Override
    public Result handleServerCall(byte[] data, FloodgatePlayer source) {
        ByteArrayDataInput in = ByteStreams.newDataInput(data);
        int packetId = in.readInt();
        String packetType = in.readUTF();

        logger.debug("Receive CustomChannel Data: PacketId=" + packetId + " PacketType=" + packetType);

        if (packetId == 113) {
            long runtimeId = in.readLong();
            ClientPlayerInitializedEvent clientPlayerInitializedEvent = new ClientPlayerInitializedEvent(
                    source.getCorrectUniqueId(), runtimeId);
            Bukkit.getServer().getPluginManager().callEvent(clientPlayerInitializedEvent);
        }
        return Result.handled();
    }

}
