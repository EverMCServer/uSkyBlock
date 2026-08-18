package us.talabrek.ultimateskyblock.command.island;

import com.google.inject.Inject;
import dk.lockfuglsang.minecraft.command.AbstractCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

public class ChallengeTopCommand extends AbstractCommand {
    private final uSkyBlock plugin;

    @Inject
    public ChallengeTopCommand(@NotNull uSkyBlock plugin) {
        super("ctop", "usb.island.ctop", "?page", marktr("display the top of challenge completions"));
        this.plugin = plugin;
        addFeaturePermission("usb.admin.ctop", tr("enables user to force re-calculation of the challenge top list"));
    }

    @Override
    public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
        int page = 1;
        if (args.length == 1 && args[0].matches("\\d*")) {
            page = Integer.parseInt(args[0]);
        }
        if (sender.hasPermission("usb.admin.ctop") || sender.isOp()) {
            plugin.getChallengeRankingLogic().forceRebuild(sender, page);
        } else {
            plugin.getChallengeRankingLogic().showTop(sender, page);
        }
        return true;
    }
}
