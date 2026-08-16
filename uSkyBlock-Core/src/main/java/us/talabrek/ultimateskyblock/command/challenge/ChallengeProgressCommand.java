package us.talabrek.ultimateskyblock.command.challenge;

import com.google.inject.Inject;
import dk.lockfuglsang.minecraft.command.AbstractCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.progress.PlayerProgress;
import us.talabrek.ultimateskyblock.progress.ProgressLogic;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * Adds progress for testing purposes (admin).
 */
public class ChallengeProgressCommand extends AbstractCommand {
    private final ProgressLogic progressLogic;

    @Inject
    public ChallengeProgressCommand(@NotNull ProgressLogic progressLogic) {
        super("progress|p", "usb.mod.challenges", "challenge", marktr("add progress for testing (admin)"));
        this.progressLogic = progressLogic;
    }

    @Override
    public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(tr("§cCommand only available for players."));
            return false;
        }
        if (args == null || args.length == 0) {
            player.sendMessage(tr("§eUsage: §f/c progress <key> [amount]"));
            return false;
        }
        String key = args[0];
        double amount = 1.0;
        if (args.length > 1) {
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(tr("§4Invalid amount: {0}", args[1]));
                return false;
            }
        }
        progressLogic.addToProgress(player, key, amount);
        PlayerProgress progress = progressLogic.getProgress(player);
        player.sendMessage(tr("§eProgress for §a{0}§e: §a{1}§e (total §a{2}§e)",
            key, progress.getProgress(key), progress.getTotalProgress(key)));
        return true;
    }
}
