/*
 * Copyright (c) 2019-2023 GeyserMC. http://geysermc.org
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

package org.geysermc.floodgate.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.UUID;
import org.geysermc.floodgate.BungeePlugin;

public class BungeeRenameUtil {

    public static String lookupName(final UUID id, final String name, final String type) {
        try {
            final Connection conn = BungeePlugin.getDataSource().getConnection();
            try {
                final PreparedStatement sql = conn.prepareStatement(
                        "select name from localprofile where id = ?");
                try {
                    sql.setString(1, id.toString());
                    final ResultSet set = sql.executeQuery();
                    try {
                        if (set.next()) {
                            return set.getString("name");
                        }
                        String modify = modify(id, name, type, conn);
                        return modify;
                    } finally {
                        if (Collections.singletonList(set).get(0) != null) {
                            set.close();
                        }
                    }
                } finally {
                    if (Collections.singletonList(sql).get(0) != null) {
                        sql.close();
                    }
                }
            } finally {
                if (Collections.singletonList(conn).get(0) != null) {
                    conn.close();
                }
            }
        } catch (Throwable $ex) {
            return name;
        }
    }

    private static String modify(final UUID id, final String name, final String type,
                          final Connection conn) throws SQLException {
        String mod = name;
        int count = 0;
        while (true) {
            final PreparedStatement sql = conn.prepareStatement(
                    "insert into localprofile(id, name, name_origin, pc_pe) value(? ,?, ?, ?)");
            String nameBp = name;
            try {
                sql.setString(1, id.toString());
                sql.setString(2, mod);
                sql.setString(3, name);
                sql.setString(4, type.toLowerCase());
                try {
                    sql.executeUpdate();
                    return mod;
                } catch (SQLException e) {
                    String msg = e.getMessage();
                    if (isDuplicateKey(msg) && msg.contains("PRIMARY")) {
                        return name;
                    } else if (isDuplicateKey(msg) && isNameKey(msg)) {
                        ++count;
                        int usernameLength = Math.min(nameBp.length(), 16 - 3);
                        String relName = nameBp.substring(0, usernameLength);
                        mod = relName + ("_" + count);
                    } else {
                        throw e;
                    }
                }
            } finally {
                if (Collections.singletonList(sql).get(0) != null) {
                    sql.close();
                }
            }
        }
    }

    private static boolean isDuplicateKey(String msg) {
        return msg != null && msg.contains("Duplicate entry");
    }

    private static boolean isNameKey(String msg) {
        return msg.contains("'name'") || msg.contains("localprofile.name");
    }
}
