package us.talabrek.ultimateskyblock.command.island;

import dk.lockfuglsang.minecraft.command.AbstractCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.talabrek.ultimateskyblock.api.IslandInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import java.util.Map;

import us.talabrek.ultimateskyblock.handler.VaultHandler;

import static dk.lockfuglsang.minecraft.po.I18nUtil.marktr;
import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

public class BuyHopperLimitCommand extends AbstractCommand {
    private final uSkyBlock plugin;

    public BuyHopperLimitCommand(uSkyBlock plugin) {
        super("hopper", "usb.island.create", "?oper", marktr("Pay to add hopper limits."));
        this.plugin = plugin;
    }

    private int calcPrice(int curlimit){
        int price;
        if(curlimit <= 40) price = 25 * curlimit;
        else if(curlimit >= 90) price = 10000;
        else {
            price = (int)(1000 * Math.pow(1.05,curlimit-40));
            if(price > 10000) price = 10000;
        }
        return price;
    }
    @Override
    public boolean execute(CommandSender sender, String alias, Map<String, Object> data, String... args) {
        Player player = (Player)sender;
        IslandInfo islandInfo = uSkyBlock.getInstance().getIslandInfo(player);
        int curlimit = islandInfo.getHopperLimit();
        if (args.length>0 && args[0].equals("buy")){
            int price = calcPrice(curlimit);
            Economy eco = VaultHandler.getEcon();
            if(eco.has(player,price)){
                EconomyResponse er = eco.withdrawPlayer(player,price);
                if(er.transactionSuccess()){
                    islandInfo.setHopperLimit(curlimit+1);
                    player.sendMessage(tr("\u00a7a\u00a7lBuy Extra Hopper Success!"));
                    player.sendMessage(tr("\u00a77======================"));
                }else{
                    System.out.println("Player="+player.getName()+", er="+er.errorMessage);
                    player.sendMessage(tr("\u00a7c\u00a7lBuy Extra Hopper Failed!"));
                    player.sendMessage(er.errorMessage);
                }
            }else{
                player.sendMessage(tr("\u00a7c\u00a7lBuy Extra Hopper Failed!"));
                player.sendMessage(tr("\u00a7dYou do not have enough money!"));
            }
        }else{
            player.sendMessage(tr("\u00a7b\u00a7lBuy Extra Hopper Limit"));
            player.sendMessage(tr("\u00a77======================"));
        }
        player.sendMessage(tr("\u00a7bCurrent Extra Limit is {0}",islandInfo.getHopperLimit()));
        player.sendMessage(tr("\u00a7bPrice to buy another hopper limit is {0}",calcPrice(islandInfo.getHopperLimit())));
        plugin.execCommand(player, "console:tellraw " + player.getName() + " [{\"text\":\"[点击这里购买] 或使用/is hopper buy购买\",\"color\":\"green\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/is hopper buy\"}}]", false);
        return true;
    }
}
