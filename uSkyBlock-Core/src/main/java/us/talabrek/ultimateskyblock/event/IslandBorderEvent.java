package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dispenser;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.*;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;
import static us.talabrek.ultimateskyblock.event.ItemDropEvents.clearDropInfo;
import static us.talabrek.ultimateskyblock.event.ItemDropEvents.wasDroppedBy;

@Singleton
public class IslandBorderEvent implements Listener {
    private final uSkyBlock plugin;
    private Map<UUID, Location> origin;

    @Inject
    public IslandBorderEvent(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        this.origin = new HashMap<>();

        // prevent mobs leave the island
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<Entity> entities = plugin.getWorldManager().getWorld().getEntities();
            entities.addAll(plugin.getWorldManager().getNetherWorld().getEntities());
            for (Entity e : entities) {
                if (e instanceof Vehicle) {
                    continue;
                }
                if (e instanceof Mob || e instanceof TNTPrimed || (e instanceof Firework fw && fw.getShooter() == null)) {
                    Location cur = e.getLocation();
                    Location ori = origin.get(e.getUniqueId());
                    if (cur.equals(ori)) {
                        continue;
                    }
                    IslandInfo ii = plugin.getIslandInfo(cur);
                    IslandInfo ii2 = plugin.getIslandInfo(ori);
                    if (isBothTrusted(ii, ii2)) {
                        continue;
                    }
                    e.teleport(getNearestPointOn(Point.at(origin.get(e.getUniqueId())), e.getLocation()));
                    e.setVelocity(new Vector(0, 0, 0).subtract(e.getVelocity()));
                }
            }
        }, 10, 1);
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        origin.put(entity.getUniqueId(), entity.getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerThrowEgg(PlayerEggThrowEvent event) {
        if (!plugin.getWorldManager().isSkyAssociatedWorld(event.getEgg().getWorld())) {
            return;
        }
        IslandInfo target = plugin.getIslandInfo(event.getEgg().getLocation());
        IslandInfo source = plugin.getIslandInfo(origin.get(event.getEgg().getUniqueId()));
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
            Location ori = origin.get(event.getTargetEntity().getUniqueId());
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
                ii2 = plugin.getIslandInfo(origin.get(entity.getUniqueId()));
                event.setCancelled(!isBothTrusted(ii, ii2));
            } else if (event.getSource().getHolder() instanceof Entity entity) {
                ii = plugin.getIslandInfo(origin.get(entity.getUniqueId()));
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
            IslandInfo ii2 = plugin.getIslandInfo(origin.get(entity.getUniqueId()));
            if (!isBothTrusted(ii, ii2)) {
                event.setCancelled(true);
                return;
            }
        }
        IslandInfo ii = plugin.getIslandInfo(inv.getLocation());
        IslandInfo ii2 = plugin.getIslandInfo(origin.get(event.getItem().getUniqueId()));
        if (isBothTrusted(ii, ii2)) {
            clearDropInfo(event.getItem());
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
            v.teleport(getNearestPointOn(Point.at(origin.get(v.getUniqueId())), event.getTo()));
            v.setVelocity(new Vector(0, 0, 0).subtract(v.getVelocity()));
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestPlace(BlockPlaceEvent event) { //防止跨岛连箱子
        if (!plugin.getWorldManager().isSkyAssociatedWorld(event.getBlock().getWorld())) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType() != Material.TRAPPED_CHEST && block.getType() != Material.CHEST) {
            return;
        }
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
            Block relative = block.getRelative(face);
            if (relative.getType() == block.getType() && Point.at(relative.getLocation()) != Point.at(block.getLocation())) {
                IslandInfo ii = plugin.getIslandInfo(relative.getLocation());
                IslandInfo ii2 = plugin.getIslandInfo(block.getLocation());
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
        IslandInfo ii = plugin.getIslandInfo(origin.get(e.getUniqueId()));
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
            Point a = Point.at(origin.get(event.getEntity().getUniqueId()));
            Point b = Point.at(event.getBlock().getLocation());
            if (!a.equals(b)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPickupEvent(EntityPickupItemEvent event) {
        if (!plugin.getWorldManager().isSkyAssociatedWorld((event.getEntity().getWorld()))) {
            return;
        }
        if (event.getEntity() instanceof Player player) { //对玩家
            if (wasDroppedBy(player, event) ||
                player.hasPermission("usb.mod.bypassprotection") ||
                IslandBorderEvent.isBothTrusted(plugin.getIslandInfo(player), plugin.getIslandInfo(origin.get(event.getItem().getUniqueId())))
            ) {
                clearDropInfo(event.getItem());
            } else {
                event.setCancelled(true);
                plugin.notifyPlayer(player, tr("You cannot pick up other players' loot when you are a visitor!"));
            }
        } else { //对实体
            IslandInfo ii = plugin.getIslandInfo(origin.get(event.getEntity().getUniqueId()));
            IslandInfo ii2 = plugin.getIslandInfo(origin.get(event.getItem().getUniqueId()));
            if(!IslandBorderEvent.isBothTrusted(ii, ii2)) {
                event.setCancelled(true);
            } else {
                clearDropInfo(event.getItem());
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerPickupArrow(PlayerPickupArrowEvent event) {
        if (!plugin.getWorldManager().isSkyAssociatedWorld(event.getPlayer().getLocation().getWorld())) {
            return;
        }
        if (isBothTrusted(plugin.getIslandInfo(event.getPlayer()), plugin.getIslandInfo(origin.get(event.getArrow().getUniqueId())))) {
            event.setCancelled(false);
        }
    }

    public static boolean isBothTrusted(IslandInfo islandInfo, IslandInfo islandInfo1) {
        return islandInfo != null && islandInfo1 != null && !Objects.equals(islandInfo.getLeader(), "") && !Objects.equals(islandInfo1.getLeader(), "")
            && (islandInfo.getIslandLocation() == islandInfo1.getIslandLocation() ||
            (islandInfo.getTrusteeUUIDs().contains(islandInfo1.getLeaderUniqueId())) && (islandInfo1.getTrusteeUUIDs().contains(islandInfo.getLeaderUniqueId())));
    }

    public Location getNearestPointOn(Point island, Location target) {
        if (Point.at(target).equals(island)) {
            return target;
        }
        Location ret = target.clone();
        double x = island.x * 160;
        double z = island.z * 160;
        x += (x >= 0)? -80: 80;
        z += (z >= 0)? -80: 80;
        double vx = target.getX() - x;
        double vz = target.getZ() - z;
        if (Math.abs(vx) >= Math.abs(vz)) {
            vz *= 79.9f / Math.abs(vx);
            vx = Math.copySign(79.9f, vx);
        } else {
            vx *= 79.9f / Math.abs(vz);
            vz = Math.copySign(79.9f, vz);
        }
        ret.setX(x + vx);
        ret.setZ(z + vz);
        if (!Point.at(ret).equals(island)) {
            plugin.getLogger().severe("getNearestPointOn: wrong answer! island=" + island + ", target=" + target + ", ret=" + ret);
        }
        if (ret.distance(target) > 20.0) {
            plugin.getLogger().severe("getNearestPointOn: too far! island=" + island + ", target=" + target + ", ret=" + ret);
        }
        return ret;
    }

    private static class Point { // tool class to calculate the island position
        public int x;
        public int z;
        public Point(int x, int z){
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object _p) {
            if (!(_p instanceof Point)) {
                return false;
            }
            Point p = (Point)_p;
            return this.x == p.x && this.z == p.z;
        }

        @Override
        public String toString() {
            return "[" + this.x + "," + this.z + "]";
        }

        @Override
        public int hashCode() {
            return this.x << 16 | this.z;
        }

        public static Point at(Location loc) {

            int x = loc.getBlockX();
            int z = loc.getBlockZ();

            return at(x, z);
        }

        /**
         * 0,0 -> 1,1
         * 127,127 -> 1,1
         * -1,-1 -> -1,-1
         * -128,-128 -> -1,-1
         */
        public static Point at(int x, int z) {

            x += (x >= 0)? 160 : -159;
            z += (z >= 0)? 160 : -159;

            x /= 160;
            z /= 160;

            return new Point(x,z);
        }
    }
}
