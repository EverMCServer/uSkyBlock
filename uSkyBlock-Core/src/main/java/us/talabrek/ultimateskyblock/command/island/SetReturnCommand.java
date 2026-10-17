package us.talabrek.ultimateskyblock.command.island;

import com.google.inject.Inject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.LocationUtil;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;

/**
 * Sets the island's return point, i.e. the location entities are sent to when they leave the end through an end
 * portal. The command asks for a second confirmation because the point is stored persistently and is used by every
 * entity returning through the portal.
 */
public class SetReturnCommand extends RequireIslandCommand {

    private static final long CONFIRM_TIMEOUT_MILLIS = 60_000L;

    private final Map<UUID, PendingReturnPoint> pending = new ConcurrentHashMap<>();

    @Inject
    public SetReturnCommand(@NotNull uSkyBlock plugin) {
        super(plugin, "setreturn", "usb.island.setreturn", marktr("set the island return point"));
    }

    @Override
    protected boolean doExecute(String alias, Player player, PlayerInfo pi, IslandInfo island, Map<String, Object> data,
                                String... args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("confirm")) {
            return confirm(player, island);
        }
        if (sub.equals("cancel")) {
            pending.remove(player.getUniqueId());
            player.sendMessage(Component.text("已取消设置岛屿返回点。", NamedTextColor.YELLOW));
            return true;
        }

        if (!island.isLeader(player)) {
            player.sendMessage(Component.text("只有岛主可以设置岛屿返回点。", NamedTextColor.RED));
            return true;
        }
        if (!plugin.getWorldManager().isSkyWorld(player.getWorld()) || !plugin.playerIsOnOwnIsland(player)) {
            player.sendMessage(Component.text("你需要站在自己的主世界岛屿上才能设置返回点。", NamedTextColor.RED));
            return true;
        }

        Location location = player.getLocation();
        Location target = new Location(location.getWorld(), location.getBlockX() + 0.5, location.getBlockY(),
            location.getBlockZ() + 0.5);
        if (!LocationUtil.isSafeLocation(target)) {
            player.sendMessage(Component.text("当前位置不是一个安全的返回点。", NamedTextColor.RED));
            return true;
        }

        pending.put(player.getUniqueId(), new PendingReturnPoint(island.getName(), target, System.currentTimeMillis()));
        Component message = Component.text("将把本岛返回点设置为 ", NamedTextColor.YELLOW)
            .append(Component.text(format(target), NamedTextColor.AQUA))
            .append(Component.text("（你所在方块的中心）。确认请点击 ", NamedTextColor.YELLOW))
            .append(Component.text("[确认]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/is setreturn confirm")))
            .append(Component.text("，或输入 /is setreturn confirm；取消请点击 ", NamedTextColor.YELLOW))
            .append(Component.text("[取消]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/is setreturn cancel")))
            .append(Component.text("。60 秒内有效。", NamedTextColor.GRAY));
        player.sendMessage(message);
        return true;
    }

    private boolean confirm(Player player, IslandInfo island) {
        PendingReturnPoint pendingPoint = pending.remove(player.getUniqueId());
        if (pendingPoint == null) {
            player.sendMessage(Component.text("没有待确认的返回点，请先执行 /is setreturn。", NamedTextColor.RED));
            return true;
        }
        if (System.currentTimeMillis() - pendingPoint.createdAt() > CONFIRM_TIMEOUT_MILLIS) {
            player.sendMessage(Component.text("确认已超时（60 秒），请重新执行 /is setreturn。", NamedTextColor.RED));
            return true;
        }
        if (!island.isLeader(player) || !island.getName().equals(pendingPoint.islandName())) {
            player.sendMessage(Component.text("只有岛主可以设置岛屿返回点。", NamedTextColor.RED));
            return true;
        }
        island.setReturnPoint(pendingPoint.location());
        player.sendMessage(Component.text("本岛返回点已设置为 " + format(pendingPoint.location()) + "。",
            NamedTextColor.GREEN));
        return true;
    }

    private static String format(Location location) {
        return String.format(Locale.ROOT, "(%.1f, %d, %.1f)", location.getX(), location.getBlockY(),
            location.getZ());
    }

    private record PendingReturnPoint(String islandName, Location location, long createdAt) {
    }
}
