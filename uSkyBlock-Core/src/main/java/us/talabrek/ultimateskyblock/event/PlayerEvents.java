package us.talabrek.ultimateskyblock.event;

import com.google.common.util.concurrent.RateLimiter;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dk.lockfuglsang.minecraft.util.ItemStackUtil;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.api.async.Callback;
import us.talabrek.ultimateskyblock.api.event.IslandInfoEvent;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.island.BlockLimitLogic;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PatienceTester;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.LocationUtil;
import us.talabrek.ultimateskyblock.util.TranslationUtil;
import us.talabrek.ultimateskyblock.world.AcidBiomeProvider;
import us.talabrek.ultimateskyblock.world.WorldManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

@Singleton
public class PlayerEvents implements Listener {
    private static final Set<EntityDamageEvent.DamageCause> FIRE_TRAP = new HashSet<>(Arrays.asList(
        EntityDamageEvent.DamageCause.LAVA,
        EntityDamageEvent.DamageCause.FIRE,
        EntityDamageEvent.DamageCause.FIRE_TICK,
        EntityDamageEvent.DamageCause.HOT_FLOOR));
    private static final Random RANDOM = new Random();
    private static final Duration OBSIDIAN_SPAM = Duration.ofSeconds(10);

    private final uSkyBlock plugin;
    private final boolean visitorFallProtected;
    private final boolean visitorFireProtected;
    private final boolean visitorMonsterProtected;
    private final boolean protectLava;
    private final Map<UUID, Instant> obsidianClick = new HashMap<>();
    private final boolean blockLimitsEnabled;
    private final Map<Material, Material> leafSaplings = Map.of(
        Material.OAK_LEAVES, Material.OAK_SAPLING,
        Material.SPRUCE_LEAVES, Material.SPRUCE_SAPLING,
        Material.BIRCH_LEAVES, Material.BIRCH_SAPLING,
        Material.ACACIA_LEAVES, Material.ACACIA_SAPLING,
        Material.JUNGLE_LEAVES, Material.JUNGLE_SAPLING,
        Material.DARK_OAK_LEAVES, Material.DARK_OAK_SAPLING);

    @Inject
    public PlayerEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        visitorFallProtected = config.getBoolean("options.protection.visitors.fall", true);
        visitorFireProtected = config.getBoolean("options.protection.visitors.fire-damage", true);
        visitorMonsterProtected = config.getBoolean("options.protection.visitors.monster-damage", false);
        protectLava = config.getBoolean("options.protection.protect-lava", true);
        blockLimitsEnabled = config.getBoolean("options.island.block-limits.enabled", false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerFoodChange(final FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (player.getFoodLevel() > event.getFoodLevel() && plugin.playerIsOnIsland(player)) {
                if (RANDOM.nextFloat() <= plugin.getPerkLogic().getPerk(player).getHungerReduction()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClickOnObsidian(final PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (plugin.playerIsOnIsland(player)
            && Settings.extras_obsidianToLava
            && event.hasBlock()
            && event.hasItem()
            && event.getAction() == Action.RIGHT_CLICK_BLOCK
            && event.getMaterial() == Material.BUCKET
            && block != null
            && block.getType() == Material.OBSIDIAN
            && !testForObsidian(block)) {
            Instant now = Instant.now();
            Instant lastClick = obsidianClick.get(player.getUniqueId());
            if (lastClick != null && lastClick.plus(OBSIDIAN_SPAM).isAfter(now)) {
                plugin.notifyPlayer(player, tr("\u00a74You can only convert obsidian once every 10 seconds"));
                return;
            }
            PlayerInventory inventory = player.getInventory();
            if (inventory.firstEmpty() != -1) {
                HashMap<Integer, ItemStack> leftover = inventory.removeItem(new ItemStack(Material.BUCKET));
                if (leftover.isEmpty()) {
                    obsidianClick.put(player.getUniqueId(), now);
                    player.sendMessage(tr("\u00a7eChanging your obsidian back into lava. Be careful!"));
                    leftover = inventory.addItem(new ItemStack(Material.LAVA_BUCKET));
                    // Just in case, drop the item if their inventory somehow filled before we could add it
                    if (!leftover.isEmpty()) {
                        player.getWorld().dropItem(block.getLocation(), new ItemStack(Material.LAVA_BUCKET));
                    }
                    block.setType(Material.AIR);
                    event.setCancelled(true);
                }
            } else {
                player.sendMessage(tr("\u00a7eYour inventory must have another empty space!"));
            }
        }
    }

    /**
     * Tests for more than one obsidian close by.
     */
    public boolean testForObsidian(final Block block) {
        for (int x = -3; x <= 3; ++x) {
            for (int y = -3; y <= 3; ++y) {
                for (int z = -3; z <= 3; ++z) {
                    final Block testBlock = block.getWorld().getBlockAt(block.getX() + x, block.getY() + y, block.getZ() + z);
                    if ((x != 0 || y != 0 || z != 0) && testBlock.getType() == Material.OBSIDIAN) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Prevent re-placing lava that was picked up in the last tick
    @EventHandler(ignoreCancelled = true)
    public void onLavaPlace(final PlayerBucketEmptyEvent event) {
        if (Settings.extras_obsidianToLava && event.getBucket() == Material.LAVA_BUCKET) {
            Instant now = Instant.now();
            Instant lastClick = obsidianClick.get(event.getPlayer().getUniqueId());
            if (lastClick != null && lastClick.plus(Duration.ofMillis(50)).isAfter(now)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLavaReplace(BlockPlaceEvent event) {
        if (!protectLava || !plugin.getWorldManager().isSkyWorld(event.getPlayer().getWorld())) {
            return;
        }
        if (isLavaSource(event.getBlockReplacedState().getBlockData())) {
            plugin.notifyPlayer(event.getPlayer(), tr("\u00a74It''s a bad idea to replace your lava!"));
            event.setCancelled(true);
        }
    }

    private boolean isLavaSource(BlockData blockData) {
        return (blockData.getMaterial() == Material.LAVA
            && blockData instanceof Levelled level
            && level.getLevel() == 0);
    }

    // If an entity, such as an Enderman, attempts to replace a lava source block then cancel it and drop the item instead
    @EventHandler
    public void onLavaAbsorption(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (!protectLava || !plugin.getWorldManager().isSkyWorld(block.getWorld())) {
            return;
        }
        if (isLavaSource(block.getBlockData())) {
            if (event.getTo() != Material.LAVA) {
                event.setCancelled(true);
                // Drop the item diagonally above to reduce the risk of the item falling into the lava
                block.getWorld().dropItemNaturally(block.getLocation().add(1, 1, 1), new ItemStack(event.getTo()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVisitorDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
            && plugin.getWorldManager().isSkyAssociatedWorld(player.getWorld())
            && !plugin.playerIsOnIsland(player)) {
            if ((visitorFireProtected && FIRE_TRAP.contains(event.getCause()))
                || (visitorFallProtected && (event.getCause() == EntityDamageEvent.DamageCause.FALL))) {
                event.setDamage(-event.getDamage());
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVisitorDamageByEntity(final EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player player
            && plugin.getWorldManager().isSkyAssociatedWorld(player.getWorld())
            && !plugin.playerIsOnIsland(player)
            && !(event.getDamager() instanceof Player && Settings.island_allowPvP)) {
            if (visitorMonsterProtected &&
                (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    || event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE
                    || event.getCause() == EntityDamageEvent.DamageCause.MAGIC
                    || event.getCause() == EntityDamageEvent.DamageCause.POISON)) {
                event.setDamage(-event.getDamage());
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && plugin.playerIsInSpawn(player) && event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            event.setDamage(-event.getDamage());
            event.setCancelled(true);
            player.setFallDistance(0);
            plugin.getTeleportLogic().spawnTeleport(player, true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMemberDamage(final EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && plugin.getWorldManager().isSkyAssociatedWorld(victim.getWorld())) {
            if (event.getDamager() instanceof Player attacker) {
                cancelMemberDamage(attacker, victim, event);
            } else if (event.getDamager() instanceof Projectile && !(event.getDamager() instanceof EnderPearl)) {
                ProjectileSource shooter = ((Projectile) event.getDamager()).getShooter();
                if (shooter instanceof Player attacker) {
                    cancelMemberDamage(attacker, victim, event);
                }
            }
        }
    }

    private void cancelMemberDamage(Player attacker, Player victim, EntityDamageByEntityEvent event) {
        IslandInfo is1 = plugin.getIslandInfo(attacker);
        IslandInfo is2 = plugin.getIslandInfo(victim);
        if (is1 != null && is2 != null && is1.getName().equals(is2.getName())) {
            plugin.notifyPlayer(attacker, tr("\u00a7eYou cannot hurt island-members."));
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        WorldManager wm = plugin.getWorldManager();
        World pWorld = event.getPlayer().getWorld();
        if (!wm.isSkyAssociatedWorld(pWorld)) {
            return;
        }

        if (Settings.extras_respawnAtIsland) {
            PlayerInfo playerInfo = plugin.getPlayerInfo(event.getPlayer());
            if (playerInfo.getHasIsland()) {
                Location homeLocation = LocationUtil.findNearestSafeLocation(playerInfo.getHomeLocation(), null);
                if (homeLocation == null) {
                    homeLocation = LocationUtil.findNearestSafeLocation(playerInfo.getIslandLocation(), null);
                }
                // If homeLocation is somehow still null, we intentionally fallthrough
                if (homeLocation != null) {
                    event.setRespawnLocation(homeLocation);
                    return;
                }
            }
        }
        if (!Settings.extras_sendToSpawn && wm.isSkyWorld(pWorld)) {
            event.setRespawnLocation(plugin.getWorldManager().getWorld().getSpawnLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || !plugin.getWorldManager().isSkyWorld(event.getTo().getWorld())) {
            return;
        }
        final Player player = event.getPlayer();
        boolean isAdmin = player.isOp() || player.hasPermission("usb.mod.bypassprotection");
        IslandInfo islandInfo = uSkyBlock.getInstance().getIslandInfo(WorldGuardHandler.getIslandNameAt(event.getTo()));
        if (!isAdmin && islandInfo != null && islandInfo.isBanned(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(tr("\u00a74That player has forbidden you from teleporting to their island."));
        }
        if (!isAdmin && islandInfo != null && islandInfo.isLocked() && !islandInfo.getMembers().contains(player.getName()) && !islandInfo.isTrusted(player)) {
            event.setCancelled(true);
            player.sendMessage(tr("\u00a74That island is \u00a7clocked.\u00a7e No teleporting to the island."));
        }
        if (!event.isCancelled()) {
            final PlayerInfo playerInfo = plugin.getPlayerInfo(player);
            playerInfo.onTeleport(player);
        }
    }

    private RateLimiter rateLimiter = RateLimiter.create(1);

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(EntityPortalEvent event) {
        if (event.getEntityType() == EntityType.PLAYER) {
            Player player = (Player) event.getEntity();
            PlayerInfo playerInfo = plugin.getPlayerInfo(player);
            boolean isFirstCompletion = playerInfo.checkChallenge("beginner") == 0;
            if (player.isOp()) return;
            if (isFirstCompletion){
                event.setCancelled(true);
                if (rateLimiter.tryAcquire()) {
                    player.sendMessage("\u00a7c地狱门已被禁用");
                }
            }
        }
        else {
            IslandInfo islandInfo = plugin.getIslandInfo(event.getFrom());
            if (islandInfo != null) {
                PlayerInfo playerInfo = plugin.getPlayerInfo(islandInfo.getLeader());
                boolean isFirstCompletion = playerInfo.checkChallenge("beginner") == 0;
                if (isFirstCompletion){
                    event.setCancelled(true);
                }
            }
        }
    }

    /**
     * This EventHandler handles {@link BlockBreakEvent} to detect if a player broke leaves in the skyworld,
     * and will drop a sapling if so. This will prevent cases where the default generated tree on a new
     * island drops no saplings.
     *
     * @param event BlockBreakEvent to handle.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeafBreak(BlockBreakEvent event) {
        if (plugin.playerIsOnIsland(event.getPlayer()) && leafSaplings.containsKey(event.getBlock().getType())) {
            IslandInfo islandInfo = plugin.getIslandInfo(event.getBlock().getLocation());
            if (islandInfo != null && islandInfo.getLeafBreaks() == 0) {
                event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), new ItemStack(leafSaplings.get(event.getBlock().getType())));
                islandInfo.setLeafBreaks(1);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlaceEvent(BlockPlaceEvent event) {
        final Player player = event.getPlayer();
        if (!blockLimitsEnabled || !plugin.getWorldManager().isSkyAssociatedWorld(player.getWorld())) {
            return; // Skip
        }

        IslandInfo islandInfo = plugin.getIslandInfo(event.getBlock().getLocation());
        if (islandInfo == null) {
            return;
        }
        Material type = event.getBlock().getType();
        BlockLimitLogic.CanPlace canPlace = plugin.getBlockLimitLogic().canPlace(type, islandInfo);
        if (canPlace == BlockLimitLogic.CanPlace.UNCERTAIN) {
            event.setCancelled(true);
            final String key = "usb.block-limits";
            if (!PatienceTester.isRunning(player, key)) {
                PatienceTester.startRunning(player, key);
                player.sendMessage(tr("\u00a74{0} is limited. \u00a7eScanning your island to see if you are allowed to place more, please be patient", TranslationUtil.INSTANCE.getItemLocalizedName(new ItemStack(type))));
                plugin.fireAsyncEvent(new IslandInfoEvent(player, islandInfo.getIslandLocation(), new Callback<>() {
                    @Override
                    public void run() {
                        player.sendMessage(tr("\u00a7e... Scanning complete, you can try again"));
                        PatienceTester.stopRunning(player, key);
                    }
                }));
            }
            return;
        }
        if (canPlace == BlockLimitLogic.CanPlace.NO) {
            event.setCancelled(true);
            if (type == Material.HOPPER)
                player.sendMessage(tr("\u00a74You''ve hit the {0} limit!\u00a7e You can''t have more of that type on your island!\u00a79 Max: {1,number}", TranslationUtil.INSTANCE.getItemLocalizedName(new ItemStack(type)), (plugin.getBlockLimitLogic().getLimit(type)+islandInfo.getHopperLimit())));
            else
                player.sendMessage(tr("\u00a74You''ve hit the {0} limit!\u00a7e You can''t have more of that type on your island!\u00a79 Max: {1,number}", TranslationUtil.INSTANCE.getItemLocalizedName(new ItemStack(type)), plugin.getBlockLimitLogic().getLimit(type)));
            return;
        }
        plugin.getBlockLimitLogic().incBlockCount(islandInfo.getIslandLocation(), type);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!blockLimitsEnabled || !plugin.getWorldManager().isSkyAssociatedWorld(event.getBlock().getWorld())) {
            return; // Skip
        }
        IslandInfo islandInfo = plugin.getIslandInfo(event.getBlock().getLocation());
        if (islandInfo == null) {
            return;
        }
        plugin.getBlockLimitLogic().decBlockCount(islandInfo.getIslandLocation(), event.getBlock().getType());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(EntityExplodeEvent event) {
        if (blockLimitsEnabled && plugin.getWorldManager().isSkyAssociatedWorld(event.getLocation().getWorld())) {
            IslandInfo islandInfo = plugin.getIslandInfo(event.getLocation());
            if (islandInfo != null) {
                for (Block block : event.blockList()) {
                    plugin.getBlockLimitLogic().decBlockCount(islandInfo.getIslandLocation(), block.getType());
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplodeUnknown(BlockExplodeEvent event) {
        if (blockLimitsEnabled && plugin.getWorldManager().isSkyAssociatedWorld(event.getBlock().getWorld())) {
            IslandInfo islandInfo = plugin.getIslandInfo(event.getBlock().getLocation());
            if (islandInfo != null) {
                for (Block block : event.blockList()) {
                    plugin.getBlockLimitLogic().decBlockCount(islandInfo.getIslandLocation(), block.getType());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrostIce(EntityBlockFormEvent event){
        if (!plugin.getWorldManager().isSkyWorld(event.getBlock().getWorld())) {
            return;
        }
        Location loc = event.getBlock().getLocation();
        loc.setX(loc.getBlockX() & ~3);
        loc.setY(62);
        loc.setZ(loc.getBlockZ() & ~3);
        if (frostIceRecord.containsKey(loc)) {
            frostIceRecord.get(loc).cancel();
        }
        FrostIceRecord record = new FrostIceRecord(loc);
        record.runTaskTimer(uSkyBlock.getInstance(), 24000, 24000);
        frostIceRecord.put(loc, record);
    }

    HashMap<Location, FrostIceRecord> frostIceRecord = new HashMap<>();

    public static class FrostIceRecord extends BukkitRunnable {

        private final Location loc;
        public FrostIceRecord(Location loc) {
            this.loc = loc;
        }

        @Override
        public void run() {
            Biome biome = loc.getWorld().getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            int i;
            for (i = 0; i < 5; i++) {
                if (biome.equals(AcidBiomeProvider.tempToBiome[i])) {
                    break;
                }
            }
            i--;
            if (i <= 0) {
                this.cancel();
                i = 0;
            }
            for (int y = 0; y < 256; y += 4) {
                loc.getWorld().setBiome(loc.getBlockX(), y, loc.getBlockZ(), AcidBiomeProvider.tempToBiome[i]);
            }
        }
    }

    @EventHandler (priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceCoral(BlockPlaceEvent event){
        if (plugin.getWorldManager().isSkyWorld(event.getPlayer().getWorld())) {
            Material material = event.getBlock().getType();
            Location location = event.getBlock().getLocation();
            if (Tag.CORAL_BLOCKS.getValues().contains(material)) {
                checkCoral(location, event.getPlayer());
            }
        }
    }

    @EventHandler (priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakCoral(BlockBreakEvent event){
        if (plugin.getWorldManager().isSkyWorld(event.getPlayer().getWorld())) {
            Material material = event.getBlock().getType();
            Location location = event.getBlock().getLocation();
            if (Tag.CORAL_BLOCKS.getValues().contains(material)) {
                checkCoral(location, event.getPlayer());
            }
        }
    }

    private static void checkCoral(Location loc, Player p) {
        int ox = loc.getBlockX() >> 2;
        int oz = loc.getBlockZ() >> 2;
        HashSet<Location> record = new HashSet<>();
        record.add(loc);
        int temperature = AcidBiomeProvider.getTemperature(ox, oz);
        int need;
        if (temperature == 1) need = 32;
        else if (temperature == 2) need = 16;
        else if (temperature == 3) need = 8;
        else return;

        doCheckCoral(loc, ox, oz, record, need);
        int ret = record.size();
        int gain = 0;
        if (ret >= 32) gain = 3;
        else if (ret >= 16) gain = 2;
        else if (ret >= 8) gain = 1;
        temperature += gain;
        if (temperature >= 4) temperature = 4;
        if (loc.getWorld().getBiome(ox << 2, 62, oz << 2) != AcidBiomeProvider.tempToBiome[temperature]) {
            for (int y = 0; y < 256; y += 4) {
                loc.getWorld().setBiome(ox << 2, y, oz << 2, AcidBiomeProvider.tempToBiome[temperature]);
            }
            p.sendMessage("Temperature changed!");
        }
    }

    private static void doCheckCoral(Location loc, int ox, int oz, HashSet<Location> record, int stopvalue) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        for (int i = x - 1; i <= x + 1; i ++) {
            for (int j = y - 1; j <= y + 1; j ++) {
                for (int k = z - 1; k <= z + 1; k ++) {
                    if (j < 0 || j > 255) continue;
                    if (i >> 2 != ox || k >> 2 != oz) continue;
                    Location temp = loc.clone();
                    temp.setX(i);
                    temp.setY(j);
                    temp.setZ(k);
                    if (record.contains(temp)) continue;
                    if (!Tag.CORAL_BLOCKS.getValues().contains(temp.getBlock().getType())) continue;
                    record.add(temp);
                    if (record.size() >= stopvalue) return;
                    doCheckCoral(temp, ox, oz, record, stopvalue);
                    if (record.size() >= stopvalue) return;
                }
            }
        }
    }

    @EventHandler (priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceInNether(BlockPlaceEvent event){
        if (plugin.getWorldManager().isSkyNether(event.getPlayer().getWorld())) {
            Material material = event.getBlock().getType();
            // 地狱放置4*2*4方块 改变群系 （绯红森林/诡异森林 上层菌岩 下层地狱岩）
            // 玄武岩/黑石->玄武岩三角洲   绯红菌岩->绯红森林   诡异菌岩->诡异森林   灵魂土->灵魂沙峡谷   地狱岩->下界荒地
            if (material != Material.BASALT && material != Material.CRIMSON_NYLIUM && material != Material.WARPED_NYLIUM && material != Material.SOUL_SOIL && material != Material.NETHERRACK && material != Material.BLACKSTONE) {
                return;
            }
            Location location = event.getBlock().getLocation();
            int x = location.getBlockX() & ~3;
            int y = location.getBlockY();
            if (y <= 2) {
                return;
            }
            int z = location.getBlockZ() & ~3;
            World world = event.getBlock().getWorld();

            // 检查4x4内暴露在空气中的方块
            Material record = null;
            for (int i = x; i < x + 4; i ++) {
                for (int j = z; j < z + 4; j ++) {
                    int k = y;
                    if (world.getBlockAt(i, k, j).getType() == Material.AIR) {
                        do k--;
                        while(world.getBlockAt(i, k, j).getType() == Material.AIR);
                    } else {
                        do k++;
                        while(world.getBlockAt(i, k, j).getType() != Material.AIR);
                        k--;
                    }
                    if (k <= 2) {
                        return;
                    }
                    // 检查最上层
                    if (record == null) {
                        record = world.getBlockAt(i, k, j).getType();
                        if (record == Material.BLACKSTONE) {
                            record = Material.BASALT;
                        }
                    } else {
                        Material current = world.getBlockAt(i, k, j).getType();
                        if (current != record && (current != Material.BLACKSTONE || record != Material.BASALT)) {
                            return;
                        }
                    }
                    // 检查下一层
                    Material current = world.getBlockAt(i, k - 1, j).getType();
                    if (
                        ((current == Material.BLACKSTONE || current == Material.BASALT) && record == Material.BASALT) ||
                            (current == Material.NETHERRACK && record == Material.CRIMSON_NYLIUM) ||
                            (current == Material.NETHERRACK && record == Material.WARPED_NYLIUM) ||
                            (current == Material.NETHERRACK && record == Material.NETHERRACK) ||
                            (current == Material.SOUL_SOIL && record == Material.SOUL_SOIL)
                    ) continue;
                    else {
                        return;
                    }
                }
            }
            //检查完
            Biome biome;
            if (record == Material.BASALT) biome = Biome.BASALT_DELTAS;
            else if (record == Material.CRIMSON_NYLIUM) biome = Biome.CRIMSON_FOREST;
            else if (record == Material.WARPED_NYLIUM) biome = Biome.WARPED_FOREST;
            else if (record == Material.SOUL_SOIL) biome = Biome.SOUL_SAND_VALLEY;
            else biome = Biome.NETHER_WASTES;
            for (int k = y - 4; k < y + 12; k += 4) {
                if (k < 0 || k > 255) continue;
                world.setBiome(x, k, z, biome);
            }
            Player player = event.getPlayer();
            player.sendMessage("changed biome to" + biome.name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!plugin.getWorldManager().isSkyWorld(event.getBlock().getWorld())) {
            return;
        }
        Block block = event.getBlock();
        Material toType = event.getTo();
        Material fromType = block.getType();
        Random random = new Random();
        if (fromType == Material.AIR){
            if (toType == Material.ANVIL || toType == Material.CHIPPED_ANVIL || toType == Material.DAMAGED_ANVIL){
                // 铁砧下落砸方块更改
                Block change = block.getRelative(BlockFace.DOWN);
                Material changeType = change.getType();
                BlockData changed = change.getBlockData();
                if (changed instanceof Leaves){
                    change.setType(Material.AIR);
                    return;
                }
                if (changeType == Material.STONE_BRICKS){
                    change.setType(Material.CRACKED_STONE_BRICKS);
                    return;
                }
                if (changeType == Material.COBBLESTONE){
                    if (random.nextInt(50) == 0) {
                        change.setType(Material.GRAVEL);
                    }
                    return;
                }
                if (changeType == Material.STONE){
                    if (random.nextInt(10) == 0) {
                        change.setType(Material.DEEPSLATE);
                    }
                    return;
                }
                if (changeType == Material.MAGMA_BLOCK){
                    if (random.nextInt(10) == 0) {
                        change.setType(Material.AIR);
                        if (random.nextInt(2) == 0) {
                            change.getWorld().dropItemNaturally(change.getLocation(), new ItemStack(Material.BLAZE_POWDER));
                        }
                    }
                    return;
                }
            }
        }
    }
}
