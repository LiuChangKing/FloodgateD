package org.geysermc.floodgate.neteasebind;

import com.liuchangking.dreamengine.api.CrossPlatformMenu;
import com.liuchangking.dreamengine.api.CrossUI;
import com.liuchangking.dreamengine.api.PlatformAPI;
import com.liuchangking.dreamengine.ui.ElementsForm;
import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

final class NeteaseBindDreamEngineUi {
    private static final String ACTION_GENERATE = "generate";
    private static final String ACTION_STATUS = "status";
    private static final String ACTION_BIND_FORM = "bind_form";
    private static final String ACTION_UNBIND_PAGE = "unbind_page";
    private static final String ACTION_UNBIND_CONFIRM = "unbind_confirm";
    private static final String ACTION_BACK = "back";

    private final NeteaseBindSpigotBridge bridge;

    NeteaseBindDreamEngineUi(NeteaseBindSpigotBridge bridge) {
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
                .title("账号管理")
                .content("生成验证码后，请使用你的网易基岩账号确认绑定。\n如果已经完成绑定，可以在这里解除绑定。")
                .buttonAt(11, Material.PAPER, "生成绑定验证码", Arrays.asList(
                        "点击后生成一枚临时验证码",
                        "让基岩账号输入 Java 名和验证码完成绑定"
                ), ACTION_GENERATE)
                .buttonAt(13, Material.COMPASS, "查看绑定状态", Arrays.asList(
                        "查询当前账号的入口类型",
                        "以及是否已经完成互通绑定"
                ), ACTION_STATUS)
                .buttonAt(15, Material.BARRIER, "解除账号绑定", Arrays.asList(
                        "解除后 Java 入口账号需要重新绑定",
                        "点击后进入二次确认页面"
                ), ACTION_UNBIND_PAGE)
                .onClick(event -> {
                    if (ACTION_GENERATE.equals(event.getPayload())) {
                        bridge.forwardBindCommand(event.getPlayer(), new String[0], "正在生成绑定验证码...");
                    } else if (ACTION_STATUS.equals(event.getPayload())) {
                        bridge.forwardBindCommand(event.getPlayer(), new String[]{"status"}, "正在查询账号互通状态...");
                    } else if (ACTION_UNBIND_PAGE.equals(event.getPayload())) {
                        event.getPlayer().sendMessage("请在确认页面中再次点击确认解除绑定");
                        openUnbindConfirm(event.getPlayer());
                    }
                });
        menu.open(player);
    }

    private void openBedrockMenu(Player player) {
        CrossPlatformMenu<String> menu = CrossUI.stringMenu(player)
                .title("账号管理")
                .content("绑定时请输入 Java 玩家名和验证码。\n如果当前账号已经绑定，也可以在这里解除绑定。")
                .button("填写绑定信息", ACTION_BIND_FORM)
                .button("查看绑定状态", ACTION_STATUS)
                .button("解除账号绑定", ACTION_UNBIND_PAGE)
                .onClick(event -> {
                    if (ACTION_BIND_FORM.equals(event.getPayload())) {
                        openBedrockConfirmBindForm(event.getPlayer());
                    } else if (ACTION_STATUS.equals(event.getPayload())) {
                        bridge.forwardBindCommand(event.getPlayer(), new String[]{"status"}, "正在查询账号互通状态...");
                    } else if (ACTION_UNBIND_PAGE.equals(event.getPayload())) {
                        event.getPlayer().sendMessage("请在确认页面中再次点击确认解除绑定");
                        openUnbindConfirm(event.getPlayer());
                    }
                });
        menu.open(player);
    }

    private void openBedrockConfirmBindForm(Player player) {
        String[] javaName = {""};
        String[] code = {""};
        ElementsForm.builder()
                .title("账号绑定")
                .label("请输入 Java 玩家名和验证码。验证码由 Java 玩家在绑定服生成。")
                .input("Java 玩家名", "", value -> javaName[0] = value == null ? "" : value.trim())
                .input("验证码", "", value -> code[0] = value == null ? "" : value.trim())
                .onSubmit(response -> Bukkit.getScheduler().runTask(bridge.plugin(), () -> {
                    if (javaName[0].isEmpty() || code[0].isEmpty()) {
                        player.sendMessage("Java 玩家名和验证码不能为空");
                        return;
                    }
                    bridge.forwardBindCommand(player, new String[]{javaName[0], code[0]}, "已提交绑定信息，正在校验...");
                }))
                .open(player);
    }

    private void openUnbindConfirm(Player player) {
        CrossPlatformMenu<String> menu = CrossUI.stringMenu(player)
                .title("解除绑定")
                .content("解除绑定后，Java 入口账号将不再使用当前基岩身份进入服务器。\n如果你是绑定后的 Java 玩家，确认后会被踢出并需要重新进入。")
                .buttonAt(11, Material.REDSTONE_BLOCK, "确认解除绑定", Arrays.asList(
                        "此操作会删除 Java 与基岩账号的绑定关系"
                ), ACTION_UNBIND_CONFIRM)
                .buttonAt(15, Material.ARROW, "返回", ACTION_BACK)
                .onClick(event -> {
                    if (ACTION_UNBIND_CONFIRM.equals(event.getPayload())) {
                        bridge.forwardBindCommand(event.getPlayer(), new String[]{"unbind", "confirm"}, "已提交解绑请求，正在处理...");
                    } else if (ACTION_BACK.equals(event.getPayload())) {
                        open(event.getPlayer());
                    }
                });
        menu.open(player);
    }
}
