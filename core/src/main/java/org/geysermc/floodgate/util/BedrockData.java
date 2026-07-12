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

package org.geysermc.floodgate.util;

public final class BedrockData implements Cloneable {
    public static final int EXPECTED_LENGTH = 12;
    public static final int MXZC_EXPECTED_LENGTH = 13;

    private final String version;
    private final String username;
    private final String xuid;
    private final int deviceOs;
    private final String languageCode;
    private final int uiProfile;
    private final int inputMode;
    private final String ip;
    private final LinkedPlayer linkedPlayer;
    private final boolean fromProxy;
    private final int subscribeId;
    private final String verifyCode;
    private final long neteaseUid;
    private final int dataLength;

    public static BedrockData of(
            String version,
            String username,
            String xuid,
            int deviceOs,
            String languageCode,
            int uiProfile,
            int inputMode,
            String ip,
            LinkedPlayer linkedPlayer,
            boolean fromProxy,
            int subscribeId,
            String verifyCode) {
        return new BedrockData(version, username, xuid, deviceOs, languageCode, uiProfile,
                inputMode, ip, linkedPlayer, fromProxy, subscribeId, verifyCode, -1L,
                EXPECTED_LENGTH);
    }

    public static BedrockData of(
            String version,
            String username,
            String xuid,
            int deviceOs,
            String languageCode,
            int uiProfile,
            int inputMode,
            String ip,
            int subscribeId,
            String verifyCode) {
        return of(version, username, xuid, deviceOs, languageCode, uiProfile, inputMode, ip,
                null, false, subscribeId, verifyCode);
    }

    public static BedrockData of(
            String version,
            String username,
            String xuid,
            int deviceOs,
            String languageCode,
            int uiProfile,
            int inputMode,
            String ip,
            int subscribeId,
            String verifyCode,
            long neteaseUid) {
        return new BedrockData(version, username, xuid, deviceOs, languageCode, uiProfile,
                inputMode, ip, null, false, subscribeId, verifyCode, neteaseUid,
                neteaseUid > 0 ? MXZC_EXPECTED_LENGTH : EXPECTED_LENGTH);
    }

    public static BedrockData fromString(String data) {
        String[] split = data.split("\0", -1);
        if (split.length != EXPECTED_LENGTH && split.length != MXZC_EXPECTED_LENGTH) {
            return emptyData(split.length);
        }

        LinkedPlayer linkedPlayer = LinkedPlayer.fromString(split[8]);
        long neteaseUid = split.length == MXZC_EXPECTED_LENGTH ? parseLong(split[12]) : -1L;
        return new BedrockData(
                split[0],
                split[1],
                split[2],
                Integer.parseInt(split[3]),
                split[4],
                Integer.parseInt(split[5]),
                Integer.parseInt(split[6]),
                split[7],
                linkedPlayer,
                "1".equals(split[9]),
                Integer.parseInt(split[10]),
                split[11],
                neteaseUid,
                split.length
        );
    }

    private static BedrockData emptyData(int dataLength) {
        return new BedrockData(null, null, null, -1, null, -1, -1, null,
                null, false, -1, null, -1L, dataLength);
    }

    public boolean hasPlayerLink() {
        return linkedPlayer != null;
    }

    @Override
    public String toString() {
        String result = version + '\0'
                + username + '\0'
                + xuid + '\0'
                + deviceOs + '\0'
                + languageCode + '\0'
                + uiProfile + '\0'
                + inputMode + '\0'
                + ip + '\0'
                + (linkedPlayer != null ? linkedPlayer.toString() : "null") + '\0'
                + (fromProxy ? 1 : 0) + '\0'
                + subscribeId + '\0'
                + verifyCode;
        if (neteaseUid > 0) {
            result += '\0' + String.valueOf(neteaseUid);
        }
        return result;
    }

    @Override
    public BedrockData clone() throws CloneNotSupportedException {
        return (BedrockData) super.clone();
    }

    public String getVersion() {
        return version;
    }

    public String getUsername() {
        return username;
    }

    public String getXuid() {
        return xuid;
    }

    public int getDeviceOs() {
        return deviceOs;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public int getUiProfile() {
        return uiProfile;
    }

    public int getInputMode() {
        return inputMode;
    }

    public String getIp() {
        return ip;
    }

    public LinkedPlayer getLinkedPlayer() {
        return linkedPlayer;
    }

    public boolean isFromProxy() {
        return fromProxy;
    }

    public int getSubscribeId() {
        return subscribeId;
    }

    public String getVerifyCode() {
        return verifyCode;
    }

    public long getNeteaseUid() {
        return neteaseUid;
    }

    public int getDataLength() {
        return dataLength;
    }

    private static long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private BedrockData(
            String version,
            String username,
            String xuid,
            int deviceOs,
            String languageCode,
            int uiProfile,
            int inputMode,
            String ip,
            LinkedPlayer linkedPlayer,
            boolean fromProxy,
            int subscribeId,
            String verifyCode,
            long neteaseUid,
            int dataLength) {
        this.version = version;
        this.username = username;
        this.xuid = xuid;
        this.deviceOs = deviceOs;
        this.languageCode = languageCode;
        this.uiProfile = uiProfile;
        this.inputMode = inputMode;
        this.ip = ip;
        this.linkedPlayer = linkedPlayer;
        this.fromProxy = fromProxy;
        this.subscribeId = subscribeId;
        this.verifyCode = verifyCode;
        this.neteaseUid = neteaseUid;
        this.dataLength = dataLength;
    }
}
