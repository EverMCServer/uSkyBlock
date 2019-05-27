package us.talabrek.ultimateskyblock.command.island;

import dk.lockfuglsang.minecraft.command.AbstractCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.talabrek.ultimateskyblock.api.IslandInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

public class Farewellcommand extends AbstractCommand {
    private final uSkyBlock plugin;

    public Farewellcommand(uSkyBlock plugin) {
        super("farewell", "usb.island.create", "?msg", marktr("Change farewell message of your island"));
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
        Player player = (Player)sender;
        IslandInfo islandInfo = uSkyBlock.getInstance().getIslandInfo(player);
        String str="";
        if(args.length < 1){
            str=tr("§d** You are leaving §b{0}''s §disland.",islandInfo.getLeader());
        }
        else
            for (String arg:args){
                str+=arg+" ";
            }
        String cmd = "console:rg flag -w world_skyland " + islandInfo.getName() + "island farewell "+str;
        uSkyBlock.getInstance().execCommand(player, cmd,false);

        str = str.replaceAll("&(?=[0-9a-fA-FkKlLmMnNoOrR])", "\u00a7");
        player.sendMessage(tr("\u00a7bYour island farewell message has changed to: \u00a7r")+str);
        return true;
    }
}
