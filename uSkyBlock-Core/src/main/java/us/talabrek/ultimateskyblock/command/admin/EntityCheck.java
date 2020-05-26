package us.talabrek.ultimateskyblock.command.admin;

import dk.lockfuglsang.minecraft.command.CompositeCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EntityCheck extends CompositeCommand {
    private final uSkyBlock plugin;

    public EntityCheck(uSkyBlock plugin) {
        super("entitycheck", "usb.admin.entitycheck", "entitycheck");
        this.plugin = plugin;
    }    
    private int fastpos(int pos){
        pos+=64;
        return (pos<0)?((pos+1)/128):(pos/128);
    }
    @Override
    public boolean execute(final CommandSender sender, String alias, Map<String, Object> data, String... args) {
        sender.sendMessage("entity check!");
        List <Entity> es = plugin.getWorldManager().getWorld().getEntities();
        sender.sendMessage("Total: "+es.size());
        int droped_item = 0;
        int armor_stand = 0;
        HashMap<String, Integer> islandcount = new HashMap<>();
        for (Entity e : es){
            String island = fastpos(e.getLocation().getBlockX()) + "," + fastpos(e.getLocation().getBlockZ());
            if (e.getType() == EntityType.ARMOR_STAND) armor_stand ++;
            else if (e.getType() == EntityType.DROPPED_ITEM) droped_item ++;

            if (islandcount.containsKey(island)){
                islandcount.put(island, islandcount.get(island)+1);
            }else{
                islandcount.put(island, 1);
            }
        }
        sender.sendMessage("Total droped: "+droped_item);
        sender.sendMessage("Total armor: "+armor_stand);
        for (String island : islandcount.keySet()){
            sender.sendMessage("island"+island+": "+islandcount.get(island));
        }

        return true;
    }
}