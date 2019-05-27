package us.talabrek.ultimateskyblock.command.island;

import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dk.lockfuglsang.minecraft.command.AbstractCommand;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.talabrek.ultimateskyblock.api.IslandInfo;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Map;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

public class TogglePvpCommand extends AbstractCommand  {
    private final uSkyBlock plugin;

    public TogglePvpCommand(uSkyBlock plugin) {
        super("togglepvp", "usb.island.create", marktr("Toggle PVP (allow/deny) of your island"));
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
        Player player = (Player)sender;
        IslandInfo islandInfo = uSkyBlock.getInstance().getIslandInfo(player);
        ProtectedRegion rg = WorldGuardHandler.getIslandRegion(islandInfo.getName()+"island");
        if(rg.getFlag(Flags.PVP)!=null&&rg.getFlag(Flags.PVP).toString()=="ALLOW"){
            rg.setFlag(Flags.PVP,null);
            player.sendMessage(tr("\u00a7bPVP is now denied on your island!"));
        }else{
            rg.setFlag(Flags.PVP,StateFlag.State.ALLOW);
            player.sendMessage(tr("\u00a7bPVP is now allowed on your island!"));
            player.sendMessage(tr("\u00a7bPlease be careful that in order to protect visitors, you can't attack them. But they can harm island members."));
        }
        return true;
    }
}
