package us.talabrek.ultimateskyblock.event;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
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
import java.util.concurrent.TimeUnit;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;
import static us.talabrek.ultimateskyblock.event.ItemDropEvents.clearDropInfo;
import static us.talabrek.ultimateskyblock.event.ItemDropEvents.wasDroppedBy;

@Singleton
public class IslandBorderEvent implements Listener {
    private static uSkyBlock plugin = null;
    private Cache<UUID, Location> origin;

    @Inject
    public IslandBorderEvent(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        this.origin = CacheBuilder.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

        // prevent mobs leave the island
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<Entity> entities = plugin.getWorldManager().getWorld().getEntities();
            entities.addAll(plugin.getWorldManager().getNetherWorld().getEntities());
            for (Entity e : entities) {
                if (e instanceof Vehicle) {
                    continue;
                }
                if (e instanceof Mob || e instanceof TNTPrimed || (e instanceof Firework fw && fw.getShooter() == null)) {
                    if (origin.getIfPresent(e.getUniqueId()) == null) {
                        origin.put(e.getUniqueId(), e.getLocation());
                        continue;
                    }
                    IslandInfo cur = plugin.getIslandInfo(e.getLocation());
                    IslandInfo ori = plugin.getIslandInfo(origin.getIfPresent(e.getUniqueId()));
                    if (ori == null || cur == null) {
                        continue;
                    }
                    if (Objects.equals(cur.getName(), ori.getName())) {
                        continue;
                    }
                    if (isBothTrusted(cur, ori)) {
                        continue;
                    }
                    e.teleport(getNearestlocationOn(plugin.getIslandInfo(origin.getIfPresent(e.getUniqueId())), e.getLocation()));
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
        IslandInfo source = plugin.getIslandInfo(origin.getIfPresent(event.getEgg().getUniqueId()));
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
        if (!(block.getState() instanceof Dispenser dispenser)) {//发射器
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
        } else { //对面部诗人
            Location ori = origin.getIfPresent(event.getTargetEntity().getUniqueId());
            IslandInfo islandInfo1 = plugin.getIslandInfo(ori);
            if (!isBothTrusted(islandInfo, islandInfo1)) {
                event.setCancelled(true);
            }
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
                ii2 = plugin.getIslandInfo(origin.getIfPresent(entity.getUniqueId()));
                event.setCancelled(!isBothTrusted(ii, ii2));
            } else if (event.getSource().getHolder() instanceof Entity entity) {
                ii = plugin.getIslandInfo(origin.getIfPresent(entity.getUniqueId()));
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
            IslandInfo ii2 = plugin.getIslandInfo(origin.getIfPresent(entity.getUniqueId()));
            if (!isBothTrusted(ii, ii2)) {
                event.setCancelled(true);
                return;
            }
        }
        IslandInfo ii = plugin.getIslandInfo(inv.getLocation());
        IslandInfo ii2 = plugin.getIslandInfo(origin.getIfPresent(event.getItem().getUniqueId()));
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
        Vehicle v = event.getVehicle();
        if (origin.getIfPresent(v.getUniqueId()) == null) {
            origin.put(v.getUniqueId(), v.getLocation());
            return;
        }
        IslandInfo ii = plugin.getIslandInfo(event.getFrom());
        IslandInfo ii2 = plugin.getIslandInfo(event.getTo());
        if (!isBothTrusted(ii, ii2)) {
            v.teleport(getNearestlocationOn(plugin.getIslandInfo(origin.getIfPresent(v.getUniqueId())), event.getTo()));
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
            IslandInfo ii = plugin.getIslandInfo(relative.getLocation());
            IslandInfo ii2 = plugin.getIslandInfo(block.getLocation());
            if (relative.getType() == block.getType()
                && !Objects.equals(
                ii == null ? null : ii.getName(),
                ii2 == null ? null : ii2.getName())
            ) {
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
        IslandInfo ii = plugin.getIslandInfo(origin.getIfPresent(e.getUniqueId()));
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
            IslandInfo a = plugin.getIslandInfo(origin.getIfPresent(event.getEntity().getUniqueId()));
            IslandInfo b = plugin.getIslandInfo(event.getBlock().getLocation());
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
                IslandBorderEvent.isBothTrusted(plugin.getIslandInfo(player), plugin.getIslandInfo(origin.getIfPresent(event.getItem().getUniqueId())))
            ) {
                clearDropInfo(event.getItem());
            } else {
                event.setCancelled(true);
                plugin.notifyPlayer(player, tr("You cannot pick up other players' loot when you are a visitor!"));
            }
        } else { //对实体
            IslandInfo ii = plugin.getIslandInfo(origin.getIfPresent(event.getEntity().getUniqueId()));
            IslandInfo ii2 = plugin.getIslandInfo(origin.getIfPresent(event.getItem().getUniqueId()));
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
        if (isBothTrusted(plugin.getIslandInfo(event.getPlayer()), plugin.getIslandInfo(origin.getIfPresent(event.getArrow().getUniqueId())))) {
            event.setCancelled(false);
        }
    }

    public static boolean isBothTrusted(IslandInfo islandInfo, IslandInfo islandInfo1) {
        plugin.getLogger().info("" + (islandInfo != null));
        plugin.getLogger().info("" + (islandInfo1 != null));
        plugin.getLogger().info("" + (Objects.equals(islandInfo.getName(), islandInfo1.getName())));
        plugin.getLogger().info("" + (islandInfo.getTrusteeUUIDs().contains(islandInfo1.getLeaderUniqueId())));
        plugin.getLogger().info("" + (islandInfo1.getTrusteeUUIDs().contains(islandInfo.getLeaderUniqueId())));
        return islandInfo != null && islandInfo1 != null && (
            Objects.equals(islandInfo.getName(), islandInfo1.getName()) || //同一个岛
            (islandInfo.getTrusteeUUIDs().contains(islandInfo1.getLeaderUniqueId())) && (islandInfo1.getTrusteeUUIDs().contains(islandInfo.getLeaderUniqueId())) //完全互信
        );
    }

    public Location getNearestlocationOn(IslandInfo island, Location target) {
        if (plugin.getIslandInfo(target).equals(island)) {
            return target;
        }
        Location location = target.clone(); //所在位置
        IslandInfo island2 = plugin.getIslandInfo(target); //对面岛
        Location islandlocation = island.getIslandLocation(); //这边岛位置
        Location island2location = island2.getIslandLocation(); //对面岛位置

        // 只用 X 和 Z，忽略 Y
        double ax = islandlocation.getX(), az = islandlocation.getZ();
        double bx = island2location.getX(), bz = island2location.getZ();

        // 中点 M
        double mx = (ax + bx) / 2.0;
        double mz = (az + bz) / 2.0;

        if (islandlocation.getX() == island2location.getX()) {
            return new Location(location.getWorld(), location.getX(), location.getBlockY(), mz);
        } else if (islandlocation.getZ() == island2location.getZ()) {
            return new Location(location.getWorld(), mx, location.getBlockY(), location.getZ());
        } else return location;//???
    }
}
