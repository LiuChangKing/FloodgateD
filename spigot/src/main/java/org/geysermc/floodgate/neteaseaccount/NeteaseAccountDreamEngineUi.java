package org.geysermc.floodgate.neteaseaccount;

import com.liuchangking.dreamengine.api.CrossPlatformMenu;
import com.liuchangking.dreamengine.api.CrossUI;
import com.liuchangking.dreamengine.api.PlatformAPI;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

final class NeteaseAccountDreamEngineUi {
    private static final String ACTION_STATUS = "status";
    private static final String ICON_STATUS = "textures/ui/icon_map";

    private final NeteaseAccountSpigotBridge bridge;

    NeteaseAccountDreamEngineUi(NeteaseAccountSpigotBridge bridge) {
        this.bridge = bridge;
    }

    void open(Player player) {
        if (PlatformAPI.isJavaPlayer(player)) {
            openJavaMenu(player);
        } else {
            openBedrockMenu(player);
        }
    }

    private void openJavaMenu(Player player) {
        CrossPlatformMenu<String> menu = CrossUI.stringMenu(player)
                .title(color("账号互通关联"))
                .content(color("当前项目已改为网易 UID 自动识别。\n如果同一网易账号的基岩档案已存在，Java 入口会自动继承对应基岩身份。"))
                .buttonAt(13, Material.COMPASS, "查看自动识别状态", lore(
                        "查看当前入口类型、Java UUID",
                        "以及 Java/Bedrock UID 匹配状态"
                ), ACTION_STATUS)
                .onClick(event -> {
                    if (ACTION_STATUS.equals(event.getPayload())) {
                        submitAndClose(event.getPlayer(), new String[]{"status"}, "正在查询账号互通状态...");
                    }
                });
        menu.open(player);
    }

    private void openBedrockMenu(Player player) {
        CrossPlatformMenu<String> menu = CrossUI.stringMenu(player)
                .title(color("账号互通关联"))
                .content(color("当前基岩档案会作为同网易账号 Java 入口的继承身份。\n首次进入后，Java 入口即可自动使用本基岩档案。"));
        buttonWithIcon(menu, "查看自动识别状态", ICON_STATUS, ACTION_STATUS);
        menu.onClick(event -> {
            if (ACTION_STATUS.equals(event.getPayload())) {
                submitAndClose(event.getPlayer(), new String[]{"status"}, "正在查询账号互通状态...");
            }
        });
        menu.open(player);
    }

    private void submitAndClose(Player player, String[] args, String feedbackMessage) {
        player.closeInventory();
        bridge.forwardAccountCommand(player, args, feedbackMessage);
    }

    private static java.util.List<String> lore(String... lines) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String line : lines) {
            result.add(color("&7" + line));
        }
        return result;
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private static void buttonWithIcon(CrossPlatformMenu<String> menu, String label, String icon, String payload) {
        if (tryInvokeButtonWithIcon(menu, label, icon, payload,
                String.class, String.class, String.class, Object.class)) {
            return;
        }
        if (tryInvokeButtonWithIcon(menu, label, icon, payload,
                String.class, String.class, Object.class)) {
            return;
        }
        menu.button(label, payload);
    }

    private static boolean tryInvokeButtonWithIcon(CrossPlatformMenu<String> menu,
                                                   String label,
                                                   String icon,
                                                   String payload,
                                                   Class<?>... parameterTypes) {
        try {
            Method method = menu.getClass().getMethod("buttonWithIcon", parameterTypes);
            if (parameterTypes.length == 4) {
                method.invoke(menu, label, label, icon, payload);
            } else {
                method.invoke(menu, label, icon, payload);
            }
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
