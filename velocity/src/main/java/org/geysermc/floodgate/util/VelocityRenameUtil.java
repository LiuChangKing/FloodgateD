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
import org.geysermc.floodgate.VelocityPlugin;

public class VelocityRenameUtil {

    public static String lookupName(final UUID id, final String name, final String type) {
        try (Connection conn = VelocityPlugin.getDataSource().getConnection()){
            try (PreparedStatement sql = conn.prepareStatement(
                    "SELECT name FROM localprofile WHERE id = ?")){
                sql.setString(1, id.toString());
                try (ResultSet set = sql.executeQuery()){
                    if (set.next()){
                        return set.getString("name");
                    } else {
                        return modify(id, name, type, conn);
                    }
                }
            }
        } catch (SQLException e) {
            return name;
        }
    }

    private static String modify(final UUID id, final String name, final String type,
                                 final Connection conn) throws SQLException {
        String mod = name;
        int count = 0;

        while (true) {
            try (PreparedStatement sql = conn.prepareStatement(
                    "INSERT INTO localprofile(id, name, name_origin, pc_pe) VALUES (?, ?, ?, ?)")) {
                sql.setString(1, id.toString());
                sql.setString(2, mod);
                sql.setString(3, name);
                sql.setString(4, type.toLowerCase());

                sql.executeUpdate();
                return mod;
            } catch (SQLException e) {
                String msg = e.getMessage();
                if (isDuplicateKey(msg) && msg.contains("PRIMARY")) {
                    // UUID（id）已经存在，说明已有记录，直接返回原始名称即可
                    return name;
                } else if (isDuplicateKey(msg) && isNameKey(msg)) {
                    // 名字冲突，尝试新的名字
                    ++count;
                    int usernameLength = Math.min(name.length(), 13); // 确保不超过16个字符
                    if (count >= 10) {
                        usernameLength = usernameLength - 1;
                    }
                    mod = name.substring(0, usernameLength) + "_" + count;
                } else {
                    e.printStackTrace();
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
