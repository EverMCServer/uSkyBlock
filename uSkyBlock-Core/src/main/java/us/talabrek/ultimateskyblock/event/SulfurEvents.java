package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.concurrent.ThreadLocalRandom;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * Minecraft 26.2 "Chaos Cubed" 内容机制:
 * <ul>
 *     <li>史莱姆转化: 手持硫磺方块右键史莱姆, 消耗 1 硫磺将其转化为大型硫方怪
 *         (李芒果式生物转化; 之后依靠原版机制自我繁殖: 死亡分裂、黏液球喂养成长)</li>
 *     <li>间歇泉转化朱砂: 放入由烈性硫磺驱动的间歇泉水柱中的红石块,
 *         10-20 秒后被转化为朱砂 (替代合成配方, 结合新版本间歇泉玩法)</li>
 * </ul>
 */
@Singleton
public class SulfurEvents implements Listener {
    private final uSkyBlock plugin;
    private final boolean geyserCinnabarEnabled;

    @Inject
    public SulfurEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        this.geyserCinnabarEnabled = plugin.getConfig().getBoolean("options.extras.geyserCinnabar", true);
    }

    private static boolean isWaterish(Material material) {
        return material == Material.WATER || material == Material.BUBBLE_COLUMN;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSlimeConvert(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(player.getWorld())) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // 仅主手
        }
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Slime slime)) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.SULFUR) {
            return;
        }
        // 与试炼刷怪笼刷怪蛋相同的岛屿归属限制 (SpawnEvents.onSpawnEggEvent)
        if (!player.hasPermission("usb.mod.bypassprotection") && !player.isOp()) {
            if (!plugin.playerIsOnIsland(player)) {
                plugin.notifyPlayer(player, tr("§eYou can only convert slimes on your own island."));
                return;
            }
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
            if (item.getAmount() <= 0) {
                player.getInventory().remove(item);
            }
        }
        Location location = slime.getLocation();
        slime.remove();
        Entity spawned = location.getWorld().spawnEntity(location, EntityType.SULFUR_CUBE);
        if (spawned instanceof SulfurCube cube) {
            // 硫方怪仅两个尺寸: 小/大, 预期 2 = 大 (8HP)。需在游戏内实测确认语义
            cube.setSize(2);
            cube.setPersistent(true);
        }
        location.getWorld().playSound(location, Sound.ENTITY_SLIME_SQUISH, 1.0f, 1.0f);
        plugin.notifyPlayer(player, tr("§eYou turned the slime into a sulfur cube!"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstoneInGeyser(BlockPlaceEvent event) {
        if (!geyserCinnabarEnabled
                || !plugin.getWorldManager().isSkyAssociatedWorld(event.getBlock().getWorld())) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType() != Material.REDSTONE_BLOCK) {
            return;
        }
        if (!isWaterish(event.getBlockReplacedState().getType())) {
            return; // 必须实际放置在水柱内
        }
        if (findPotentSulfurBelow(block) == null) {
            return; // 不是有效的间歇泉 (烈性硫磺 + 下方岩浆块/熔岩源)
        }
        Location loc = block.getLocation();
        World world = block.getWorld();
        world.spawnParticle(Particle.BUBBLE_COLUMN_UP, loc.toCenterLocation(), 10, 0.4, 0.4, 0.4, 0.05);
        plugin.notifyPlayer(event.getPlayer(), tr("§7The water bubbles strangely around the redstone block..."));
        // 10-20 秒随机延迟, 模拟喷发节奏
        Bukkit.getScheduler().runTaskLater(plugin, () -> convertToCinnabar(loc),
                200L + ThreadLocalRandom.current().nextLong(200L));
    }

    /**
     * 从红石块向下扫描至多 4 格水/气泡柱, 找到站在岩浆块或熔岩源上的烈性硫磺
     * (原版间歇泉的构成条件: 烈性硫磺上方 1-4 格水源)。不是有效间歇泉水柱时返回 null。
     */
    private static Block findPotentSulfurBelow(Block block) {
        Block below = block.getRelative(BlockFace.DOWN);
        if (!isWaterish(below.getType())) {
            return null; // 无水柱 (紧贴烈性硫磺放置会破坏间歇泉)
        }
        for (int i = 0; i < 4; i++) {
            if (below.getType() == Material.POTENT_SULFUR) {
                Material under = below.getRelative(BlockFace.DOWN).getType();
                if (under != Material.MAGMA_BLOCK && under != Material.LAVA) {
                    return null;
                }
                return below;
            }
            if (!isWaterish(below.getType())) {
                return null;
            }
            below = below.getRelative(BlockFace.DOWN);
        }
        return null;
    }

    private void convertToCinnabar(Location loc) {
        if (!loc.isWorldLoaded()) {
            return;
        }
        World world = loc.getWorld();
        if (world == null || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return; // 区块已卸载: 中止, 红石块保持原样
        }
        Block block = loc.getBlock();
        if (block.getType() != Material.REDSTONE_BLOCK) {
            return; // 方块已被替换/拆除
        }
        if (findPotentSulfurBelow(block) == null) {
            return; // 等待期间间歇泉被拆除
        }
        block.setType(Material.CINNABAR);
        world.spawnParticle(Particle.BUBBLE_POP, loc.toCenterLocation(), 30, 0.5, 0.5, 0.5, 0.3);
        world.playSound(loc, Sound.BLOCK_LAVA_EXTINGUISH, 0.7f, 1.2f);
    }
}
