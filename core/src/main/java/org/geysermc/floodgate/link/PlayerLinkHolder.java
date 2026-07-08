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

package org.geysermc.floodgate.link;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.event.Listener;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.config.FloodgateConfig;
import org.geysermc.floodgate.config.FloodgateConfig.PlayerLinkConfig;
import org.geysermc.floodgate.event.lifecycle.ShutdownEvent;

@Listener
@Singleton
@SuppressWarnings("unchecked")
public final class PlayerLinkHolder {
    @Inject private FloodgateConfig config;
    @Inject private FloodgateLogger logger;

    private PlayerLink instance;

    public @NonNull PlayerLink load() {
        if (instance != null) {
            return instance;
        }
        instance = load0();
        return instance;
    }

    private @NonNull PlayerLink load0() {
        if (config == null) {
            throw new IllegalStateException("Config cannot be null!");
        }

        PlayerLinkConfig linkConfig = config.getPlayerLink();
        if (linkConfig.isEnabled() || linkConfig.isEnableOwnLinking() || linkConfig.isEnableGlobalLinking()) {
            logger.info("Floodgate player linking is disabled in the Netease build; use netease-bind instead");
        }
        return new DisabledPlayerLink();
    }

    @Subscribe
    public void onShutdown(ShutdownEvent ignored) {
        if (instance != null) {
            instance.stop();
        }
    }
}
