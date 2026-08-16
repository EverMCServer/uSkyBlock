package us.talabrek.ultimateskyblock.command.admin;

import com.google.inject.Inject;
import dk.lockfuglsang.minecraft.command.AbstractCommand;
import dk.lockfuglsang.minecraft.command.CompositeCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.progress.PlayerProgress;
import us.talabrek.ultimateskyblock.progress.ProgressLogic;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * Admin command for managing the island progress of any player (resolved to the
 * island leader's shared progress). Virtual progress keys are read-only.
 */
public class AdminProgressCommand extends CompositeCommand {

    private final uSkyBlock plugin;
    private final ProgressLogic progressLogic;

    @Inject
    public AdminProgressCommand(@NotNull uSkyBlock plugin, @NotNull ProgressLogic progressLogic) {
        super("progress|p", "usb.mod.challenges", "player", marktr("Manage progress for a player"));
        this.plugin = plugin;
        this.progressLogic = progressLogic;
        add(new PlayerProgressCommand("add", "key ?amount", marktr("add progress for the player")) {
            @Override
            protected void doExecute(CommandSender sender, PlayerProgress progress, String playerName, String... args) {
                if (args.length < 1) {
                    showUsage(sender, "add", "<key> [amount]");
                    return;
                }
                Double amount = args.length > 1 ? parseAmount(sender, args[1]) : 1.0;
                if (amount == null || rejectVirtual(sender, args[0])) {
                    return;
                }
                progress.addToProgress(args[0], amount);
                showProgress(sender, args[0], playerName, progress);
            }
        });
        add(new PlayerProgressCommand("set", "key value", marktr("set progress for the player")) {
            @Override
            protected void doExecute(CommandSender sender, PlayerProgress progress, String playerName, String... args) {
                if (args.length < 2) {
                    showUsage(sender, "set", "<key> <value>");
                    return;
                }
                Double value = parseAmount(sender, args[1]);
                if (value == null || rejectVirtual(sender, args[0])) {
                    return;
                }
                progress.setProgress(args[0], value);
                showProgress(sender, args[0], playerName, progress);
            }
        });
        add(new PlayerProgressCommand("reset", "key", marktr("reset progress key for the player")) {
            @Override
            protected void doExecute(CommandSender sender, PlayerProgress progress, String playerName, String... args) {
                if (args.length < 1) {
                    showUsage(sender, "reset", "<key>");
                    return;
                }
                if (rejectVirtual(sender, args[0])) {
                    return;
                }
                progress.removeProgress(args[0]);
                sender.sendMessage(tr("§eProgress key §a{0}§e has been reset for §a{1}§e.", args[0], playerName));
            }
        });
        add(new PlayerProgressCommand("resetall", null, marktr("reset all progress for the player")) {
            @Override
            protected void doExecute(CommandSender sender, PlayerProgress progress, String playerName, String... args) {
                progress.reset();
                sender.sendMessage(tr("§eAll progress has been reset for §a{0}§e.", playerName));
            }
        });
        add(new PlayerProgressCommand("show", "?key", marktr("show progress for the player")) {
            @Override
            protected void doExecute(CommandSender sender, PlayerProgress progress, String playerName, String... args) {
                if (args.length > 0) {
                    showProgress(sender, args[0], playerName, progress);
                    return;
                }
                sender.sendMessage(tr("§eProgress of §a{0}§e:", playerName));
                boolean any = false;
                for (String key : progress.getKeys()) {
                    showEntry(sender, key, progress);
                    any = true;
                }
                for (String key : progressLogic.getVirtualKeys()) {
                    sender.sendMessage(tr("§e{0}§e: §a{1}§e §7(virtual)", key, progress.getProgress(key)));
                    any = true;
                }
                if (!any) {
                    sender.sendMessage(tr("§7No progress recorded for §a{0}§e.", playerName));
                }
            }
        });
    }

    @Override
    public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
        if (args.length > 0) {
            PlayerInfo playerInfo = plugin.getPlayerInfo(args[0]);
            if (playerInfo != null) {
                data.put("playerInfo", playerInfo);
            }
            data.put("playerName", args[0]);
        }
        return super.execute(sender, alias, data, args);
    }

    private boolean rejectVirtual(CommandSender sender, String key) {
        if (progressLogic.isVirtual(key)) {
            sender.sendMessage(tr("§c{0} is a virtual progress key and is read-only.", key));
            return true;
        }
        return false;
    }

    private void showProgress(CommandSender sender, String key, String playerName, PlayerProgress progress) {
        if (progressLogic.isVirtual(key)) {
            sender.sendMessage(tr("§eProgress §a{0}§e for §a{1}§e: §a{2}§e §7(virtual)", key, playerName, progress.getProgress(key)));
            return;
        }
        sender.sendMessage(tr("§eProgress §a{0}§e for §a{1}§e: §a{2}§e (total §a{3}§e)",
            key, playerName, progress.getProgress(key), progress.getTotalProgress(key)));
    }

    private static void showEntry(CommandSender sender, String key, PlayerProgress progress) {
        sender.sendMessage(tr("§e{0}§e: §a{1}§e (total §a{2}§e)", key, progress.getProgress(key), progress.getTotalProgress(key)));
    }

    private static Double parseAmount(CommandSender sender, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            sender.sendMessage(tr("§4Invalid amount: {0}", value));
            return null;
        }
    }

    private static void showUsage(CommandSender sender, String sub, String params) {
        sender.sendMessage(tr("§eUsage: §f/usb progress <player> {0} {1}", sub, params));
    }

    private abstract class PlayerProgressCommand extends AbstractCommand {
        public PlayerProgressCommand(String name, String params, String description) {
            super(name, null, params, description);
        }

        @Override
        public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
            PlayerInfo playerInfo = (PlayerInfo) data.get("playerInfo");
            if (playerInfo == null) {
                sender.sendMessage(tr("§4No player named {0} was found!", data.get("player")));
                return true;
            }
            doExecute(sender, progressLogic.getProgress(playerInfo.getUniqueId()), playerInfo.getPlayerName(), args);
            return true;
        }

        protected abstract void doExecute(CommandSender sender, PlayerProgress progress, String playerName, String... args);
    }
}
