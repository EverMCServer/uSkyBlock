package us.talabrek.ultimateskyblock.event;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.vehicle.VehicleBlockCollisionEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.vehicle.VehicleUpdateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;

import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * Handles the internal item-drop protection.
 */
public class ItemDropEvents implements Listener {
    private final uSkyBlock plugin;
    private final boolean visitorsCanDrop;

    public ItemDropEvents(uSkyBlock plugin) {
        this.plugin = plugin;
        visitorsCanDrop = plugin.getConfig().getBoolean("options.protection.visitors.item-drops", true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    @SuppressWarnings("unused")
    public void onDropEvent(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(player.getWorld())) {
            return;
        }
        if (!visitorsCanDrop && !plugin.playerIsOnIsland(player) && !plugin.playerIsInSpawn(player)) {
            event.setCancelled(true);
            plugin.notifyPlayer(player, tr("\u00a7eVisitors can't drop items!"));
            return;
        }
        //addDropInfo(player, event.getItemDrop().getItemStack());
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemDrop(ItemSpawnEvent event){
        if (event.getEntityType() != EntityType.DROPPED_ITEM) 
            return;
            
        IslandInfo isinfo = plugin.getIslandInfo(event.getLocation());
        if(isinfo == null) return;
        String player = isinfo.getLeader();
        addDropInfo(player, event.getEntity().getItemStack());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDispenseItem(BlockDispenseEvent event){
        if (event instanceof BlockDispenseArmorEvent) return;
        
        // workaround for dispense entity...
        Vector v = event.getVelocity();
        if (Math.abs(v.getX())>=1.0 || Math.abs(v.getY())>=1.0 || Math.abs(v.getZ())>=1.0) return;
        
        IslandInfo is = plugin.getIslandInfo(event.getBlock().getLocation());
        if (is == null) return;
        String player = is.getLeader();
        ItemStack item = event.getItem();
        addDropInfo(player, item);
        event.setItem(item);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    @SuppressWarnings("unused")
    public void onDeathEvent(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.getWorldManager().isSkyWorld(player.getWorld())) {
            return;
        }
        if (!visitorsCanDrop && !plugin.playerIsOnIsland(player) && !plugin.playerIsInSpawn(player)) {
            event.setKeepInventory(true);
            return;
        }
        // Take over the drop, since Bukkit don't do this in a Metadatable format.
        for (ItemStack stack : event.getDrops()) {
            addDropInfo(player, stack);
        }
    }

    private void addDropInfo(Player player, ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore();
            if (lore == null) {
                lore = new ArrayList<>();
            }
            String ownerTag = "Owner: "+player.getName();
            if (!lore.contains(ownerTag)) {
                lore.add(ownerTag);
            }
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
    }

    private void addDropInfo(String player, ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore();
            if (lore == null) {
                lore = new ArrayList<>();
            }else{
                String lastLine = lore.get(lore.size() - 1);
                if(lastLine.startsWith("Owner: "))return;
            }
            String ownerTag = "Owner: "+player;
            lore.add(ownerTag);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
    }
    private void clearDropInfo(Item item) {
        ItemStack stack = item.getItemStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore();
            if (lore != null && !lore.isEmpty()) {
                lore.removeIf(line -> line.contains("Owner: "));
                meta.setLore(lore);
                stack.setItemMeta(meta);
                item.setItemStack(stack);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    @SuppressWarnings("unused")
    public void onPickupInventoryEvent(InventoryPickupItemEvent event) {
        // I.e. hoppers...
        clearDropInfo(event.getItem());
        if (!plugin.getWorldManager().isSkyWorld(event.getItem().getWorld())) {
            return;
        }
        if (event.getInventory().getHolder() instanceof HopperMinecart){
            Vehicle v = (Vehicle)event.getInventory().getHolder();
            UUID owner = plugin.getIslandInfo(v.getOrigin()).getLeaderUniqueId();
            UUID leader = plugin.getIslandInfo(event.getInventory().getLocation()).getLeaderUniqueId();
            //check if the same island
            if(owner.equals(leader)){
                return;
            }
            //check if all completed tasks
            if(plugin.getPlayerInfo(owner).checkChallenge("builder5")!=0 && plugin.getPlayerInfo(leader).checkChallenge("builder5") != 0){
                return;
            }
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    @SuppressWarnings("unused")
    public void onPickupEvent(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (event.isCancelled() || !plugin.getWorldManager().isSkyWorld(player.getWorld())) {
            clearDropInfo(event.getItem());
            return;
        }
        if (wasDroppedBy(player, event) || player.hasPermission("usb.mod.bypassprotection")) {
            clearDropInfo(event.getItem());
            return; // Allowed
        }
        // You are on another's island, and the stuff dropped weren't yours.
        event.setCancelled(true);
        plugin.notifyPlayer(player, tr("You cannot pick up other players' loot when you are a visitor!"));
    }

    private boolean wasDroppedBy(Player player, EntityPickupItemEvent event) {
        ItemStack itemStack = event.getItem().getItemStack();
        ItemMeta meta = itemStack.getItemMeta();
        PlayerInfo _pi = plugin.getPlayerInfo(player);
        if (_pi == null) return false;
        IslandInfo _si = _pi.getIslandInfo();
        if (_si == null) return false;
        String name = _si.getLeader();
        if (meta != null) {
            List<String> lore = meta.getLore();
            if (lore != null && !lore.isEmpty()) {
                String lastLine = lore.get(lore.size() - 1).substring(7);
                if(lastLine.equals(name))return true;
                PlayerInfo pi = plugin.getPlayerInfo(lastLine);
                if(pi == null)return false;
                IslandInfo si = plugin.getIslandInfo(pi);
                if(si == null)return false;
                if(si.getTrusteeUUIDs().contains(player.getUniqueId()))return true;
            }
        }
        return false;
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpenEvent(InventoryOpenEvent event){
        if (event.isCancelled() || !plugin.getWorldManager().isSkyWorld(event.getPlayer().getWorld())) {
            return;
        }
        if (event.getInventory().getHolder() instanceof HopperMinecart || event.getInventory().getHolder() instanceof StorageMinecart){
            Vehicle v = (Vehicle)event.getInventory().getHolder();
            UUID owner = plugin.getIslandInfo(v.getOrigin()).getLeaderUniqueId();
            UUID leader = plugin.getIslandInfo(event.getInventory().getLocation()).getLeaderUniqueId();
            //check if the same island
            if(owner.equals(leader)){
                return;
            }
            //check if all completed tasks
            if(plugin.getPlayerInfo(owner).checkChallenge("builder5")!=0 && plugin.getPlayerInfo(leader).checkChallenge("builder5") != 0){
                return;
            }
            if(event.getPlayer() instanceof Player){
                plugin.notifyPlayer(((Player)event.getPlayer()), tr("You cannot open this!"));
            }
            event.setCancelled(true);
            return;
        }
    }
    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleDestroyEvent(VehicleDestroyEvent event){
        if (event.isCancelled() || !plugin.getWorldManager().isSkyWorld(event.getVehicle().getWorld())) {
            return;
        }
        Vehicle v = (Vehicle)event.getVehicle();
        UUID owner = plugin.getIslandInfo(v.getOrigin()).getLeaderUniqueId();
        UUID loc = plugin.getIslandInfo(event.getVehicle().getLocation()).getLeaderUniqueId();
        if (owner.equals(loc)){
            return;
        }else{
            if(event.getAttacker() instanceof Player){
                plugin.notifyPlayer(((Player)event.getAttacker()), tr("You cannot break this!"));
            }
            event.setCancelled(true);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMoveItemEvent(InventoryMoveItemEvent event){
        if (event.isCancelled() || !plugin.getWorldManager().isSkyWorld(event.getInitiator().getLocation().getWorld())) {
            return;
        }
        // first - check HopperMinecart and StorageMinecart
        if (event.getSource().getHolder() instanceof HopperMinecart || event.getSource().getHolder() instanceof StorageMinecart){
            Vehicle v = (Vehicle)event.getSource().getHolder();
            UUID owner = plugin.getIslandInfo(v.getOrigin()).getLeaderUniqueId();
            UUID leader = plugin.getIslandInfo(event.getDestination().getLocation()).getLeaderUniqueId();
            //check if the same island
            if(owner.equals(leader)){
                return;
            }
            //check if all completed tasks
            if(plugin.getPlayerInfo(owner).checkChallenge("builder5")!=0 && plugin.getPlayerInfo(leader).checkChallenge("builder5") != 0){
                return;
            }
            event.setCancelled(true);
            return;
        }
        if (event.getDestination().getHolder() instanceof HopperMinecart || event.getDestination().getHolder() instanceof StorageMinecart){
            Vehicle v = (Vehicle)event.getDestination().getHolder();
            UUID owner = plugin.getIslandInfo(v.getOrigin()).getLeaderUniqueId();
            UUID leader = plugin.getIslandInfo(event.getDestination().getLocation()).getLeaderUniqueId();
            //check if the same island
            if(owner.equals(leader)){
                return;
            }
            //check if all completed tasks
            if(plugin.getPlayerInfo(owner).checkChallenge("builder5")!=0 && plugin.getPlayerInfo(leader).checkChallenge("builder5") != 0){
                return;
            }
            event.setCancelled(true);
            return;
        }
        // then - check item movement between islands 
        Location from = event.getSource().getLocation();
        Location to = event.getDestination().getLocation();
        int fromx = from.getBlockX()-64;
        int tox = to.getBlockX()-64;
        if (fromx<0&&tox<0){
            fromx++;
            tox++;
        }
        int fromz = from.getBlockZ()-64;
        int toz = to.getBlockZ()-64;
        if (fromz<0&&toz<0){
            fromz++;
            toz++;
        }
        if((fromx>=0&&tox<0) ||
            (fromx<0&&tox>=0) ||
            (fromx/128!=tox/128) ||
            (fromz>=0&&toz<0) ||
            (fromz<0&&toz>=0) ||
            (fromz/128!=toz/128)){
                PlayerInfo isfrom = plugin.getPlayerInfo(plugin.getIslandInfo(from).getLeaderUniqueId());
                PlayerInfo isto = plugin.getPlayerInfo(plugin.getIslandInfo(to).getLeaderUniqueId());
                if(isfrom.checkChallenge("builder5")==0 || isto.checkChallenge("builder5") == 0){
                    event.setCancelled(true);
                    return;
                }
            }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlaceEvent(BlockPlaceEvent event) {
        if (event.isCancelled() || !plugin.getWorldManager().isSkyWorld(event.getBlockPlaced().getWorld())) {
            return;
        }
        Block pl = event.getBlockPlaced();
        if (pl.getType() != Material.CHEST &&pl.getType() != Material.TRAPPED_CHEST){
            return;
        }
        int x = pl.getLocation().getBlockX();
        int y = pl.getLocation().getBlockY();
        int z = pl.getLocation().getBlockZ();
        if (
            (x % 128 == 64 && pl.getWorld().getBlockAt(x-1,y,z).getType().equals(pl.getType())) ||
            (x % 128 == 63 && pl.getWorld().getBlockAt(x+1,y,z).getType().equals(pl.getType())) ||
            (z % 128 == 64 && pl.getWorld().getBlockAt(x,y,z-1).getType().equals(pl.getType())) ||
            (z % 128 == 63 && pl.getWorld().getBlockAt(x,y,z+1).getType().equals(pl.getType()))){
                plugin.notifyPlayer(event.getPlayer(), tr("You cannot place this block here!"));
                event.setCancelled(true);
            }
    }
}
