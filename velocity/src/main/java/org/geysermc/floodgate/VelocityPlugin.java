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

package org.geysermc.floodgate;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.module.CommandModule;
import org.geysermc.floodgate.module.PluginMessageModule;
import org.geysermc.floodgate.module.ProxyCommonModule;
import org.geysermc.floodgate.module.VelocityAddonModule;
import org.geysermc.floodgate.module.VelocityListenerModule;
import org.geysermc.floodgate.module.VelocityPlatformModule;
import org.geysermc.floodgate.util.ReflectionUtils;

public final class VelocityPlugin {
    private static final String DATABASE_BLOCK_MESSAGE = "账号数据服务未就绪，请联系管理员";

    private final FloodgatePlatform platform;
    private static volatile HikariDataSource dataSource;
    private static volatile String startupBlockReason = DATABASE_BLOCK_MESSAGE;
    private static volatile String startupBlockDetails = "Floodgate datasource is not initialized";
    private volatile boolean platformEnabled;

    public static HikariDataSource getDataSource() {
        return dataSource;
    }

    public static HikariDataSource requireDataSource() {
        HikariDataSource current = dataSource;
        String reason = startupBlockReason;
        if (current == null || reason != null || current.isClosed()) {
            throw new IllegalStateException(startupBlockDetails == null
                    ? "Floodgate datasource is unavailable"
                    : startupBlockDetails);
        }
        return current;
    }

    public static String validateDataSourceReady() {
        String reason = startupBlockReason;
        if (reason != null) {
            return reason;
        }

        HikariDataSource current = dataSource;
        if (current == null || current.isClosed()) {
            markDataSourceUnavailable("Floodgate datasource is not initialized");
            return startupBlockReason;
        }

        try (Connection connection = current.getConnection()) {
            if (!connection.isValid(2)) {
                markDataSourceUnavailable("Floodgate datasource validation failed");
                closeDataSource(current);
                return startupBlockReason;
            }
            return null;
        } catch (SQLException exception) {
            markDataSourceUnavailable("Floodgate datasource is unavailable: " + exception.getMessage());
            closeDataSource(current);
            return startupBlockReason;
        }
    }

    @Inject
    public VelocityPlugin(@DataDirectory Path dataDirectory, Injector guice) {
        ReflectionUtils.setPrefix("com.velocitypowered.proxy");

        long ctm = System.currentTimeMillis();
        Injector injector = guice.createChildInjector(
                new ProxyCommonModule(dataDirectory),
                new VelocityPlatformModule(guice)
        );

        platform = injector.getInstance(FloodgatePlatform.class);

        long endCtm = System.currentTimeMillis();
        injector.getInstance(FloodgateLogger.class)
                .translatedInfo("floodgate.core.finish", endCtm - ctm);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        initializeRequiredDataSource();
        platform.enable(
                new CommandModule(),
                new VelocityListenerModule(),
                new VelocityAddonModule(),
                new PluginMessageModule()
        );
        platformEnabled = true;
    }

    private void initializeRequiredDataSource() {
        if (!platform.isForceUserName()) {
            markDataSourceUnavailable("forceusername must be true in the Netease build");
            throw new IllegalStateException(startupBlockDetails);
        }

        HikariDataSource created = new HikariDataSource();
        try {
            created.setDriverClassName("org.mariadb.jdbc.Driver");
            created.setJdbcUrl(platform.getMysqlurl());
            created.setUsername(platform.getMysqluser());
            created.setPassword(platform.getMysqlpass());
            initializeLocalProfileTable(created);
            dataSource = created;
            startupBlockDetails = null;
            startupBlockReason = null;
        } catch (SQLException | RuntimeException exception) {
            closeDataSource(created);
            markDataSourceUnavailable("Failed to initialize required Floodgate datasource: "
                    + exception.getMessage());
            throw new IllegalStateException(startupBlockDetails, exception);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (platformEnabled) {
            platform.disable();
            platformEnabled = false;
        }
        HikariDataSource current = dataSource;
        dataSource = null;
        startupBlockReason = DATABASE_BLOCK_MESSAGE;
        startupBlockDetails = "Proxy is shutting down";
        closeDataSource(current);
    }

    private static void initializeLocalProfileTable(HikariDataSource source) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS localprofile (" +
                             "id VARCHAR(36) NOT NULL PRIMARY KEY, " +
                             "name VARCHAR(64) NOT NULL UNIQUE, " +
                             "name_origin VARCHAR(64) NOT NULL, " +
                             "pc_pe VARCHAR(8) NOT NULL" +
                             ")")) {
            statement.executeUpdate();
        }
    }

    private static void markDataSourceUnavailable(String details) {
        dataSource = null;
        startupBlockDetails = details;
        startupBlockReason = DATABASE_BLOCK_MESSAGE;
    }

    private static void closeDataSource(HikariDataSource source) {
        if (source != null && !source.isClosed()) {
            source.close();
        }
    }
}
