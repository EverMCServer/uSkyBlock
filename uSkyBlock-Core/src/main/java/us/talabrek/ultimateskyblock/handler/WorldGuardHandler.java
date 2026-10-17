package us.talabrek.ultimateskyblock.handler;

import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dk.lockfuglsang.minecraft.po.I18nUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.Settings;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.LogUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

public class WorldGuardHandler {
    private static final String CN = WorldGuardHandler.class.getName();
    private static final Logger log = Logger.getLogger(CN);
    /**
     * Version of the island region layout. It is stored per island as {@code general.regionVersion} and compared in
     * {@link #noOrOldRegion} to decide whether an existing island needs its regions rebuilt.
     *
     * <p>Bump this whenever the set of island regions or their geometry/flags changes, so that existing islands get
     * rebuilt through {@code protectIsland()} (a member logging in) or {@code /usb protectall} (all islands).
     * {@code updateRegion()} always (re)creates all regions for the enabled dimensions at once.</p>
     *
     * <p>15: adds the per island end region ({@code <islandName>end}) next to island/nether.</p>
     */
    private static final String VERSION = "15";

    public static WorldGuardPlatform getWorldGuard() {
        final Plugin plugin = uSkyBlock.getInstance().getServer().getPluginManager().getPlugin("WorldGuard");
        if (!(plugin instanceof WorldGuardPlugin)) {
            return null;
        }
        return WorldGuard.getInstance().getPlatform();
    }

    public static boolean protectIsland(final CommandSender sender, final PlayerInfo pi) {
        log.entering(CN, "protectIsland", new Object[]{sender, pi});
        try {
            uSkyBlock plugin = uSkyBlock.getInstance();
            IslandInfo islandConfig = plugin.getIslandInfo(pi);
            if (islandConfig == null) {
                return false;
            }
            if (islandConfig.getLeader().isEmpty()) {
                islandConfig.setupPartyLeader(pi.getPlayerName());
                updateRegion(islandConfig);
                return true;
            } else {
                return protectIsland(plugin, sender, islandConfig);
            }
        } finally {
            log.exiting(CN, "protectIsland");
        }
    }

    public static boolean protectIsland(uSkyBlock plugin, CommandSender sender, IslandInfo islandConfig) {
        try {
            RegionManager regionManager = getRegionManager(plugin.getWorldManager().getWorld());
            String regionName = islandConfig.getName() + "island";
            if (noOrOldRegion(regionManager, regionName, islandConfig)) {
                updateRegion(islandConfig);
                islandConfig.setRegionVersion(getVersion());
                return true;
            }
        } catch (Exception ex) {
            String name = islandConfig != null ? islandConfig.getLeader() : "Unknown";
            LogUtil.log(Level.SEVERE, "ERROR: Failed to protect " + name + "'s Island (" + sender.getName() + ")", ex);
        }
        return false;
    }

    private static String getVersion() {
        return VERSION + " " + I18nUtil.getLocale();
    }

    /**
     * The suffixes of island regions in the plugin managed extra dimensions (nether and end). Regions in these
     * dimensions span the full world height.
     */
    private static final List<String> EXTRA_WORLD_SUFFIXES = List.of("nether", "end");

    /**
     * The suffixes of all island regions. {@code island} is the overworld region, the others are extra dimensions.
     */
    private static final List<String> ISLAND_REGION_SUFFIXES = List.of("island", "nether", "end");

    private static boolean isExtraWorldRegion(@Nullable String regionName) {
        return regionName != null && EXTRA_WORLD_SUFFIXES.stream().anyMatch(regionName::endsWith);
    }

    /**
     * Extracts the island name from a region id, or null if the region is not an island region.
     *
     * @param regionId Region id to inspect (case insensitive).
     * @return Island name, or null when the id is not an island region.
     */
    @Nullable
    public static String getIslandNameFromRegion(@Nullable String regionId) {
        if (regionId == null) {
            return null;
        }
        String id = regionId.toLowerCase();
        if (id.equalsIgnoreCase("__global__")) {
            return null;
        }
        for (String suffix : ISLAND_REGION_SUFFIXES) {
            if (id.length() > suffix.length() && id.endsWith(suffix)) {
                return id.substring(0, id.length() - suffix.length());
            }
        }
        return null;
    }

    public static void updateRegion(IslandInfo islandInfo) {
        try {
            ProtectedCuboidRegion region = setRegionFlags(islandInfo);
            RegionManager regionManager = getRegionManager(uSkyBlock.getInstance().getWorldManager().getWorld());
            regionManager.removeRegion(islandInfo.getName() + "island");
            regionManager.removeRegion(islandInfo.getLeader() + "island");
            regionManager.addRegion(region);
            String netherName = islandInfo.getName() + "nether";
            region = setRegionFlags(islandInfo, netherName);
            World netherWorld = uSkyBlock.getInstance().getWorldManager().getNetherWorld();
            if (netherWorld != null) {
                regionManager = getRegionManager(netherWorld);
                regionManager.removeRegion(netherName);
                regionManager.addRegion(region);
            }
            String endName = islandInfo.getName() + "end";
            region = setRegionFlags(islandInfo, endName);
            World endWorld = uSkyBlock.getInstance().getWorldManager().getEndWorld();
            if (endWorld != null) {
                regionManager = getRegionManager(endWorld);
                regionManager.removeRegion(endName);
                regionManager.addRegion(region);
            }
            islandInfo.setRegionVersion(getVersion());
        } catch (Exception e) {
            LogUtil.log(Level.SEVERE, "ERROR: Failed to update region for " + islandInfo.getName(), e);
        }
    }

    private static RegionManager getRegionManager(World world) {
        return getWorldGuard().getRegionContainer().get(new BukkitWorld(world));
    }

    private static ProtectedCuboidRegion setRegionFlags(IslandInfo islandConfig) {
        String regionName = islandConfig.getName() + "island";
        return setRegionFlags(islandConfig, regionName);
    }

    @SuppressWarnings("deprecation") // WorldGuard flags are deprecated to warn developers without replacement option
    private static ProtectedCuboidRegion setRegionFlags(IslandInfo islandConfig, String regionName) {
        Location islandLocation = islandConfig.getIslandLocation();
        BlockVector3 minPoint = getProtectionVectorRight(islandLocation);
        BlockVector3 maxPoint = getProtectionVectorLeft(islandLocation);
        if (isExtraWorldRegion(regionName)) {
            minPoint = minPoint.withY(0);
            maxPoint = maxPoint.withY(255);
        }
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(regionName, minPoint, maxPoint);
        final DefaultDomain owners = new DefaultDomain();
        DefaultDomain members = new DefaultDomain();
        for (UUID member : islandConfig.getMemberUUIDs()) {
            owners.addPlayer(member);
        }
        for (UUID trust : islandConfig.getTrusteeUUIDs()) {
            members.addPlayer(trust);
        }
        region.setOwners(owners);
        region.setMembers(members);
        region.setPriority(100);
        if (uSkyBlock.getInstance().getConfig().getBoolean("worldguard.entry-message", true)) {
            if (owners.size() == 0) {
                region.setFlag(Flags.GREET_MESSAGE, tr("\u00a74** You are entering a protected - but abandoned - island area."));
            } else {
                region.setFlag(Flags.GREET_MESSAGE, tr("\u00a7d** You are entering \u00a7b{0}''s \u00a7disland.", islandConfig.getLeader()));
            }
        } else {
            region.setFlag(Flags.GREET_MESSAGE, null);
        }
        if (uSkyBlock.getInstance().getConfig().getBoolean("worldguard.exit-message", true)) {
            if (owners.size() == 0) {
                region.setFlag(Flags.FAREWELL_MESSAGE, tr("\u00a74** You are leaving an abandoned island."));
            } else {
                region.setFlag(Flags.FAREWELL_MESSAGE, tr("\u00a7d** You are leaving \u00a7b{0}''s \u00a7disland.", islandConfig.getLeader()));
            }
        } else {
            region.setFlag(Flags.FAREWELL_MESSAGE, null);
        }
        region.setFlag(Flags.PVP, null);
        boolean isLocked = islandConfig.isLocked();
        updateLockStatus(region, isLocked);
        return region;
    }

    private static void updateLockStatus(ProtectedRegion region, boolean isLocked) {
        if (isLocked) {
            region.setFlag(Flags.ENTRY, StateFlag.State.DENY);
        } else {
            region.setFlag(Flags.ENTRY, null);
        }
    }

    private static boolean noOrOldRegion(RegionManager regionManager, String regionId, IslandInfo island) {
        if (!regionManager.hasRegion(regionId)) {
            return true;
        }
        if (regionManager.getRegion(regionId).getOwners().size() == 0) {
            return true;
        }
        return !island.getRegionVersion().equals(getVersion());
    }

    public static void islandLock(final CommandSender sender, final String islandName) {
        try {
            RegionManager regionManager = getRegionManager(uSkyBlock.getInstance().getWorldManager().getWorld());
            if (regionManager.hasRegion(islandName + "island")) {
                ProtectedRegion region = regionManager.getRegion(islandName + "island");
                updateLockStatus(region, true);
                updateExtraWorldLockStatus(islandName, true);
                sender.sendMessage(tr("\u00a7eYour island is now locked. Only your party members may enter."));
            } else {
                sender.sendMessage(tr("\u00a74You must be the party leader to lock your island!"));
            }
        } catch (Exception ex) {
            LogUtil.log(Level.SEVERE, "ERROR: Failed to lock " + islandName + "'s Island (" + sender.getName() + ")", ex);
        }
    }

    public static void islandUnlock(final CommandSender sender, final String islandName) {
        try {
            RegionManager regionManager = getRegionManager(uSkyBlock.getInstance().getWorldManager().getWorld());
            if (regionManager.hasRegion(islandName + "island")) {
                ProtectedRegion region = regionManager.getRegion(islandName + "island");
                updateLockStatus(region, false);
                updateExtraWorldLockStatus(islandName, false);
                sender.sendMessage(tr("\u00a7eYour island is unlocked and anyone may enter, however only you and your party members may build or remove blocks."));
            } else {
                sender.sendMessage(tr("\u00a74You must be the party leader to unlock your island!"));
            }
        } catch (Exception ex) {
            LogUtil.log(Level.SEVERE, "ERROR: Failed to unlock " + islandName + "'s Island (" + sender.getName() + ")", ex);
        }
    }

    /**
     * Applies the island lock state to the nether and end regions of an island, so that locking an island also
     * locks its extra dimensions.
     *
     * @param islandName Name of the island.
     * @param locked     Whether the island is locked.
     */
    private static void updateExtraWorldLockStatus(String islandName, boolean locked) {
        World netherWorld = uSkyBlock.getInstance().getWorldManager().getNetherWorld();
        if (netherWorld != null) {
            RegionManager netherRegions = getRegionManager(netherWorld);
            ProtectedRegion nether = netherRegions.getRegion(islandName + "nether");
            if (nether != null) {
                updateLockStatus(nether, locked);
            }
        }
        World endWorld = uSkyBlock.getInstance().getWorldManager().getEndWorld();
        if (endWorld != null) {
            RegionManager endRegions = getRegionManager(endWorld);
            ProtectedRegion end = endRegions.getRegion(islandName + "end");
            if (end != null) {
                updateLockStatus(end, locked);
            }
        }
    }

    public static BlockVector3 getProtectionVectorLeft(final Location island) {
        World world = island.getWorld();
        return BlockVector3.at(island.getX() + Settings.island_radius - 1, world.getMaxHeight() - 1, island.getZ() + Settings.island_radius - 1);
    }

    public static BlockVector3 getProtectionVectorRight(final Location island) {
        World world = island.getWorld();
        return BlockVector3.at(island.getX() - Settings.island_radius, world.getMinHeight(), island.getZ() - Settings.island_radius);
    }

    public static String getIslandNameAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        RegionManager regionManager = getRegionManager(location.getWorld());
        if (regionManager == null) {
            return null;
        }
        Iterable<ProtectedRegion> applicableRegions = regionManager.getApplicableRegions(toVector(location));
        for (ProtectedRegion region : applicableRegions) {
            String islandName = getIslandNameFromRegion(region.getId());
            if (islandName != null) {
                return islandName;
            }
        }
        return null;
    }

    public static ProtectedRegion getIslandRegionAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        RegionManager regionManager = getRegionManager(location.getWorld());
        if (regionManager == null) {
            return null;
        }
        Iterable<ProtectedRegion> applicableRegions = regionManager.getApplicableRegions(toVector(location));
        for (ProtectedRegion region : applicableRegions) {
            if (getIslandNameFromRegion(region.getId()) != null) {
                return region;
            }
        }
        return null;
    }

    private static BlockVector3 toVector(Location location) {
        return BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static ProtectedRegion getNetherRegionAt(Location location) {
        return getRegionAt(location, "nether", Settings.nether_enabled);
    }

    /**
     * Gets the end region covering the given {@link Location}, or null if there is none.
     *
     * @param location Location to look up.
     * @return The end region at the location, or null.
     */
    @Nullable
    public static ProtectedRegion getEndRegionAt(Location location) {
        return getRegionAt(location, "end", Settings.end_enabled);
    }

    private static ProtectedRegion getRegionAt(Location location, String suffix, boolean enabled) {
        if (!enabled || location == null || location.getWorld() == null) {
            return null;
        }
        RegionManager regionManager = getRegionManager(location.getWorld());
        if (regionManager == null) {
            return null;
        }
        Iterable<ProtectedRegion> applicableRegions = regionManager.getApplicableRegions(toVector(location));
        for (ProtectedRegion region : applicableRegions) {
            String id = region.getId().toLowerCase();
            if (!id.equalsIgnoreCase("__global__") && id.endsWith(suffix)) {
                return region;
            }
        }
        return null;
    }

    public static void removeIslandRegion(String islandName) {
        RegionManager regionManager = getRegionManager(uSkyBlock.getInstance().getWorldManager().getWorld());
        regionManager.removeRegion(islandName + "island");
        regionManager.removeRegion(islandName + "nether");
        World endWorld = uSkyBlock.getInstance().getWorldManager().getEndWorld();
        if (endWorld != null) {
            getRegionManager(endWorld).removeRegion(islandName + "end");
        }
    }

    public static void setupGlobal(World world) {
        RegionManager regionManager = getRegionManager(world);
        if (regionManager != null) {
            ProtectedRegion global = regionManager.getRegion("__global__");
            if (global == null) {
                global = new GlobalProtectedRegion("__global__");
            }
            global.setFlag(Flags.BUILD, StateFlag.State.DENY);
            if (Settings.island_allowPvP) {
                global.setFlag(Flags.PVP, StateFlag.State.ALLOW);
            } else {
                global.setFlag(Flags.PVP, StateFlag.State.DENY);
            }
            regionManager.addRegion(global);
        }
    }

    private static Set<ProtectedRegion> getRegions(ApplicableRegionSet set) {
        Set<ProtectedRegion> regions = new HashSet<>();
        for (ProtectedRegion region : set) {
            regions.add(region);
        }
        return regions;
    }

    public static Set<ProtectedRegion> getIntersectingRegions(Location islandLocation) {
        log.entering(CN, "getIntersectingRegions", islandLocation);
        RegionManager regionManager = getRegionManager(islandLocation.getWorld());
        ApplicableRegionSet applicableRegions = regionManager.getApplicableRegions(getIslandRegion(islandLocation));
        Set<ProtectedRegion> regions = getRegions(applicableRegions);
        regions.removeIf(protectedRegion -> protectedRegion instanceof GlobalProtectedRegion);
        log.exiting(CN, "getIntersectingRegions");
        return regions;
    }

    public static boolean isIslandIntersectingSpawn(Location islandLocation) {
        log.entering(CN, "isIslandIntersectingSpawn", islandLocation);
        try {
            int r = Settings.general_spawnSize;
            if (r == 0) {
                return false;
            }
            World world = islandLocation.getWorld();
            ProtectedRegion spawn = new ProtectedCuboidRegion("spawn", BlockVector3.at(-r, world.getMinHeight(), -r), BlockVector3.at(r, world.getMaxHeight() - 1, r));
            ProtectedCuboidRegion islandRegion = getIslandRegion(islandLocation);
            return !islandRegion.getIntersectingRegions(Collections.singletonList(spawn)).isEmpty();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Unable to locate intersecting regions", e);
            return false;
        } finally {
            log.exiting(CN, "isIslandIntersectingSpawn");
        }
    }

    public static ProtectedCuboidRegion getIslandRegion(Location islandLocation) {
        BlockVector3 islandCenter = BlockVector3.at(islandLocation.getBlockX(), 0, islandLocation.getBlockZ());
        return new ProtectedCuboidRegion(
                String.format("%d,%disland", islandCenter.getBlockX(), islandLocation.getBlockZ()),
                getProtectionVectorLeft(islandLocation),
                getProtectionVectorRight(islandLocation));
    }

    public static List<Player> getPlayersInRegion(World world, ProtectedRegion region) {
        // Note: This might be heavy - for large servers...
        List<Player> players = new ArrayList<>();
        if (region == null) {
            return players;
        }
        for (Player player : world.getPlayers()) {
            if (player != null && player.isOnline()) {
                Location p = player.getLocation();
                if (region.contains(p.getBlockX(), p.getBlockY(), p.getBlockZ())) {
                    players.add(player);
                }
            }
        }
        return players;
    }

    public static List<LivingEntity> getCreaturesInRegion(World world, ProtectedRegion region) {
        List<LivingEntity> livingEntities = world.getLivingEntities();
        List<LivingEntity> creatures = new ArrayList<>();
        for (LivingEntity e : livingEntities) {
            if (region.contains(asVector(e.getLocation()))) {
                creatures.add(e);
            }
        }
        return creatures;
    }

    public static BlockVector3 asVector(Location location) {
        if (location == null) {
            return BlockVector3.at(0, 0, 0);
        }
        return toVector(location);
    }

    public static boolean isInRegion(ProtectedCuboidRegion islandRegion, Location loc) {
        return islandRegion.contains(asVector(loc));
    }
}
