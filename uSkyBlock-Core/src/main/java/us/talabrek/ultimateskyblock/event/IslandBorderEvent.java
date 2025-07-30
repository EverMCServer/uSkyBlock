package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dispenser;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.Objects;

@Singleton
public class IslandBorderEvent implements Listener {
    private final uSkyBlock plugin;

    @Inject
    public IslandBorderEvent(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerThrowEgg(PlayerEggThrowEvent event) {
        if (!plugin.getWorldManager().isSkyAssociatedWorld(event.getEgg().getWorld())) {
            return;
        }
        IslandInfo target = plugin.getIslandInfo(event.getEgg().getLocation());
        IslandInfo source = plugin.getIslandInfo(event.getPlayer());
        if (!isBothTrusted(target, source)) {
            event.setHatching(false);
        }
    }

    // NOTE: droppers put item into a chest do not fire this event. Use InventoryMoveItemEvent
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event){ //发射器发射东西
        Block block = event.getBlock();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(block.getWorld())) {
            return;
        }
        if (!(block.getBlockData() instanceof Dispenser dispenser)) {//发射器
            plugin.getLogger().severe("BlockDispenseEvent: block is not Dispenser! " + event.getBlock());
            return;
        }
        IslandInfo ii = plugin.getIslandInfo(block.getLocation());
        IslandInfo ii2 = plugin.getIslandInfo(block.getRelative(((Directional)dispenser.getBlockData()).getFacing()).getLocation());
        if (!isBothTrusted(ii, ii2)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDispenseArmor(BlockDispenseArmorEvent event){ //发射器穿戴盔甲
        Block block = event.getBlock();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(block.getWorld())) {
            return;
        }
        IslandInfo islandInfo = plugin.getIslandInfo(block.getLocation());
        if (event.getTargetEntity() instanceof Player p) { //对面诗人
            PlayerInfo playerInfo = plugin.getPlayerInfo(p);
            IslandInfo islandInfo1 = playerInfo.getIslandInfo();
            if (!isBothTrusted(islandInfo, islandInfo1)) {
                event.setCancelled(true);
            }
            return;
        } else { //对面部诗人
            Location loc = event.getTargetEntity().getLocation();
            Location ori = event.getBlock().getLocation();
            IslandInfo ii2 = plugin.getIslandInfo(loc);//对面
            if (!isBothTrusted(islandInfo, ii2)) {
                event.setCancelled(true);
                return;
            }
            ii2 = plugin.getIslandInfo(ori);
            if (!isBothTrusted(islandInfo, ii2)) {
                event.setCancelled(true);
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) { //漏斗
        if (event.getSource().getLocation() == null || event.getDestination().getLocation() == null) {
            return;
        }
        if (!plugin.getWorldManager().isSkyAssociatedWorld(event.getSource().getLocation().getWorld())) {
            return;
        }
        IslandInfo ii = plugin.getIslandInfo(event.getSource().getLocation());
        IslandInfo ii2 = plugin.getIslandInfo(event.getDestination().getLocation());
        event.setCancelled(!isBothTrusted(ii, ii2));
        if (!event.isCancelled()) {
            if (event.getDestination().getHolder() instanceof Entity entity) {
                ii2 = plugin.getIslandInfo(entity.getLocation());
                event.setCancelled(!isBothTrusted(ii, ii2));
            } else if (event.getSource().getHolder() instanceof Entity entity) {
                ii = plugin.getIslandInfo(entity.getLocation());
                event.setCancelled(!isBothTrusted(ii, ii2));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) { //漏斗捡起
        Inventory inv = event.getInventory();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(inv.getLocation().getWorld())) {
            return;
        }
        if (inv.getHolder() instanceof Entity entity) {
            IslandInfo ii = plugin.getIslandInfo(inv.getLocation());
            IslandInfo ii2 = plugin.getIslandInfo(entity.getLocation());
            if (!isBothTrusted(ii, ii2)) {
                event.setCancelled(true);
                return;
            }
        }
        IslandInfo ii = plugin.getIslandInfo(inv.getLocation());
        IslandInfo ii2 = plugin.getIslandInfo(event.getItem().getLocation());
        if (isBothTrusted(ii, ii2)) {
            ItemDropEvents.clearDropInfo(event.getItem());
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {//矿车
        if (!plugin.getWorldManager().isSkyAssociatedWorld(event.getVehicle().getWorld())) {
            return;
        }
        if (!(event.getVehicle() instanceof Minecart)) {
            return;
        }
        IslandInfo ii = plugin.getIslandInfo(event.getFrom());
        IslandInfo ii2 = plugin.getIslandInfo(event.getTo());
        if (!isBothTrusted(ii, ii2)) {
            Vehicle v = event.getVehicle();
            v.teleport(v.getVelocity().multiply(-1).toLocation(event.getVehicle().getWorld()));
            v.setVelocity(new Vector(0, 0, 0).subtract(v.getVelocity()));
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestPlace(BlockPlaceEvent event) { //防止跨岛连箱子
        if (!plugin.getWorldManager().isSkyAssociatedWorld(event.getBlock().getWorld())) {
            return;
        }
        Block b = event.getBlock();
        if (b.getType() != Material.TRAPPED_CHEST && b.getType() != Material.CHEST) {
            return;
        }
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
            Block rel = b.getRelative(face);
            if (rel.getType() == b.getType() && rel.getLocation() != b.getLocation()) {
                IslandInfo ii = plugin.getIslandInfo(rel.getLocation());
                IslandInfo ii2 = plugin.getIslandInfo(b.getLocation());
                if (!isBothTrusted(ii, ii2)) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage("cannot build");
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) { //防止火烧联营
        IgniteCause cause = event.getCause();
        if ((cause == IgniteCause.FLINT_AND_STEEL || cause == IgniteCause.FIREBALL) && event.getPlayer() != null) {
            // handle in InteractEvent
            return;
        }
        if (cause == IgniteCause.LIGHTNING || cause == IgniteCause.LAVA) {
            // 付钱防火？
            return;
        }
        Entity e = event.getIgnitingEntity();
        if (e == null) {
            return;
        }
        Block b = event.getIgnitingBlock();
        IslandInfo ii = plugin.getIslandInfo(e.getLocation());
        IslandInfo ii2;
        if (b == null) {
            ii2 = plugin.getIslandInfo(e.getLocation());
        } else {
            ii2 = plugin.getIslandInfo(b.getLocation());
        }
        event.setCancelled(!isBothTrusted(ii, ii2));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) { //防止方块传播 （蘑菇 火势）
        IslandInfo ii = plugin.getIslandInfo(event.getBlock().getLocation());
        IslandInfo ii2 = plugin.getIslandInfo(event.getSource().getLocation());
        event.setCancelled(!isBothTrusted(ii, ii2));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFertilize(BlockFertilizeEvent event) { //防骨粉种草
        IslandInfo ii = plugin.getIslandInfo(event.getBlock().getLocation());
        event.getBlocks().removeIf(block -> {
            IslandInfo ii2 = plugin.getIslandInfo(block.getLocation());
            return !isBothTrusted(ii, ii2);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) { //防火烧联营烧坏
        if (event.getIgnitingBlock() == null) {
            return;
        }
        IslandInfo ii = plugin.getIslandInfo(event.getBlock().getLocation());
        IslandInfo ii2 = plugin.getIslandInfo(event.getIgnitingBlock().getLocation());
        event.setCancelled(!isBothTrusted(ii, ii2));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWitherChangeBlock(EntityChangeBlockEvent event) { //凋零破坏
        if (event.getEntity() instanceof Wither && event.getTo() == Material.AIR) {
            if (!event.getEntity().getLocation().equals(event.getBlock().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public static boolean isBothTrusted(IslandInfo islandInfo, IslandInfo islandInfo1) {
        return islandInfo != null && islandInfo1 != null && !Objects.equals(islandInfo.getLeader(), "") && !Objects.equals(islandInfo1.getLeader(), "")
            && (islandInfo.getIslandLocation() == islandInfo1.getIslandLocation() ||
            (islandInfo.getTrusteeUUIDs().contains(islandInfo1.getLeaderUniqueId())) && (islandInfo1.getTrusteeUUIDs().contains(islandInfo.getLeaderUniqueId())));
    }

}
