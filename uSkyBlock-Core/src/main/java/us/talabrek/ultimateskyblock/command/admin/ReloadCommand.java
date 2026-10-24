package us.talabrek.ultimateskyblock.command.admin;

import com.google.inject.Inject;
import dk.lockfuglsang.minecraft.command.AbstractCommand;
import dk.lockfuglsang.minecraft.po.I18nUtil;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.handbook.HandbookLogic;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;

/**
 * Reloads the config-files for USB.
 */
public class ReloadCommand extends AbstractCommand {

    private final HandbookLogic handbookLogic;

    @Inject
    public ReloadCommand(@NotNull HandbookLogic handbookLogic) {
        super("reload", "usb.admin.reload", marktr("reload configuration from file."));
        this.handbookLogic = handbookLogic;
    }

    @Override
    public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
        uSkyBlock.getInstance().reloadConfig();
        // handbook.yml is content, not a FileUtil-managed config, so it has to be re-read explicitly.
        handbookLogic.reload();
        sender.sendMessage(I18nUtil.tr("\u00a7eConfiguration reloaded from file."));
        return true;
    }
}
