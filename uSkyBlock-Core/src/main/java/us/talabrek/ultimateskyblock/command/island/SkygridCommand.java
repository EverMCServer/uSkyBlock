package us.talabrek.ultimateskyblock.command.island;

import dk.lockfuglsang.minecraft.command.AbstractCommand;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import us.talabrek.ultimateskyblock.api.IslandInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import java.util.Map;
import java.util.Random;

import us.talabrek.ultimateskyblock.handler.VaultHandler;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

public class SkygridCommand extends AbstractCommand {
    private final uSkyBlock plugin;

    public SkygridCommand(uSkyBlock plugin) {
        super("grid", "usb.island.create", "?oper", marktr("Skygrid related commands."));
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
        Player player = (Player)sender;
        IslandInfo islandInfo = uSkyBlock.getInstance().getIslandInfo(player);
        if (islandInfo == null){
            player.sendMessage(tr("\u00a74No Island. \u00a7eUse \u00a7b/is create\u00a7e to get one"));
            return true;
        }
        if (args.length == 0){
            player.sendMessage(tr("\u00a7aSkyGrid World"));
            player.sendMessage(tr("\u00a7bPrice to enter SkyGrid World is {0}",islandInfo.getSkygridEnterCount()*1000));
            plugin.execCommand(player, "console:tellraw " + player.getName() + " [{\"text\":\"" + tr("[Click here to go] or use /is grid go") + "\",\"color\":\"green\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/is grid go\"}}]", false);
        }
        if (args.length>0 && args[0].equals("go")){
            if(plugin.getConfig().getInt("skygrid.regen",0)==1){
                player.sendMessage(tr("\u00a7cSkygrid is regenerating, please wait until it''s finished."));
                return true;
            }
            PlayerInventory pi = player.getInventory();
            for(ItemStack x : pi.getContents()){
                if(x != null){
                    player.sendMessage(tr("\u00a7cYou have to empty your inventory before entering the SkyGrid world."));
                    return true;
                }
            }
            for(ItemStack x : pi.getArmorContents()){
                if(x != null){
                    player.sendMessage(tr("\u00a7cYou have to empty your inventory before entering the SkyGrid world."));
                    return true;
                }
            }
            int price = islandInfo.getSkygridEnterCount()*1000;
            Economy eco = VaultHandler.getEcon();
            if(eco.has(player,price)){
                EconomyResponse er = eco.withdrawPlayer(player,price);
                if(er.transactionSuccess()){
                    islandInfo.setSkygridEnterCount();
                    player.sendMessage(tr("\u00a7aSending you to SkyGrid..."));
                    
                    int y = 65;
                    Random rd = new Random();
                    int x = rd.nextInt(50) * 4 - 99;
                    int z = rd.nextInt(50) * 4 - 99;
                    Location loc = new Location(plugin.getWorldManager().getGridWorld(),x+0.5,y,z+0.5);
                    player.teleport(loc);
                    pi.setItemInMainHand(new ItemStack(Material.COOKED_BEEF,12));
                }else{
                    System.out.println("Player="+player.getName()+", er="+er.errorMessage);
                    player.sendMessage(tr("\u00a7cFailed to enter SkyGrid!"));
                    player.sendMessage(er.errorMessage);
                }
            }else{
                player.sendMessage(tr("\u00a7cFailed to enter SkyGrid!"));
                player.sendMessage(tr("\u00a7dYou do not have enough money!"));
            }
        }
        return true;
    }
}
