package us.talabrek.ultimateskyblock.enddragon;

import com.google.inject.Inject;
import dk.lockfuglsang.minecraft.command.AbstractCommand;
import dk.lockfuglsang.minecraft.command.CompositeCommand;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;

/**
 * {@code /usb enddragon status|reset|reload|check}.
 */
public class EndDragonAdminCommand extends CompositeCommand {
    private final uSkyBlock plugin;
    private final DragonFightManager manager;
    private final EndDragonConfig config;

    @Inject
    public EndDragonAdminCommand(@NotNull uSkyBlock plugin, @NotNull DragonFightManager manager,
                                 @NotNull EndDragonConfig config) {
        super("enddragon", "usb.admin.enddragon", marktr("manage the ender dragon challenge"));
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;

        add(new AbstractCommand("status", "usb.admin.enddragon", marktr("show the current dragon challenge")) {
            @Override
            public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
                sender.sendMessage(manager.statusLines().toArray(new String[0]));
                return true;
            }
        });

        add(new AbstractCommand("reset", "usb.admin.enddragon", marktr("force reset the dragon challenge")) {
            @Override
            public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
                if (args.length == 0 || !args[0].equalsIgnoreCase("confirm")) {
                    sender.sendMessage("§c[末影龙] 该操作会强制结束当前挑战，且不退款。使用 /usb enddragon reset confirm 确认。");
                    return true;
                }
                if (!manager.hasActive()) {
                    sender.sendMessage("§e[末影龙] 当前没有进行中的挑战。");
                    return true;
                }
                manager.forceReset("管理员命令");
                sender.sendMessage("§a[末影龙] 已开始重置当前挑战（龙/水晶会在清理阶段移除）。");
                return true;
            }
        });

        add(new AbstractCommand("reload", "usb.admin.enddragon", marktr("reload the dragon configuration")) {
            @Override
            public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
                config.reload();
                sender.sendMessage("§a[末影龙] 配置已重读。");
                return true;
            }
        });

        add(new AbstractCommand("check", "usb.admin.enddragon", marktr("check the dragon arena")) {
            @Override
            public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
                World world = plugin.getWorldManager().getEndWorld();
                if (world == null) {
                    sender.sendMessage("§c[末影龙] 末地世界不可用。");
                    return true;
                }
                sender.sendMessage("§e[末影龙] 世界: §f" + world.getName()
                    + " §7(env=" + world.getEnvironment() + ")");
                var region = WorldGuardHandler.getRegion(world, config.altarRegion());
                sender.sendMessage("§e[末影龙] 区域 " + config.altarRegion() + ": "
                    + (region != null ? "§a存在" : "§c不存在"));
                DragonBattle battle = world.getEnderDragonBattle();
                if (battle == null) {
                    sender.sendMessage("§c[末影龙] DragonBattle 为空。");
                    return true;
                }
                sender.sendMessage("§e[末影龙] 首龙已击杀: §f" + battle.hasBeenPreviouslyKilled());
                sender.sendMessage("§e[末影龙] 当前龙: §f" + (battle.getEnderDragon() != null));
                sender.sendMessage("§e[末影龙] 复活阶段: §f" + battle.getRespawnPhase());
                sender.sendMessage("§e[末影龙] 返回门: §f" + battle.getEndPortalLocation());
                sender.sendMessage("§e[末影龙] 柱水晶: §f" + battle.getHealingCrystals().size());
                sender.sendMessage("§e[末影龙] 活动状态: §f" + (manager.hasActive()
                    ? (manager.getActive().dragonUuid() == null ? "SUMMONING" : "FIGHTING")
                    : "IDLE"));
                return true;
            }
        });
    }
}
