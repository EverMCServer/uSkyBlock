package us.talabrek.ultimateskyblock.event;

import dk.lockfuglsang.minecraft.po.I18nUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.Squid;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Wither;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.MonsterEggs;
import org.bukkit.material.SpawnEgg;
import org.bukkit.metadata.FixedMetadataValue;
import us.talabrek.ultimateskyblock.api.IslandInfo;
import us.talabrek.ultimateskyblock.handler.WorldGuardHandler;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.util.LocationUtil;
import static us.talabrek.ultimateskyblock.util.LogUtil.log;

import java.util.*;
import java.util.logging.Level;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;
import static org.bukkit.block.Biome.THE_END;

/**
 * Responsible for controlling spawns on uSkyBlock islands.
 */
public class SpawnEvents implements Listener {
    private static final Set<Action> RIGHT_CLICKS = new HashSet<>(Arrays.asList(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK));
    private static final Set<CreatureSpawnEvent.SpawnReason> PLAYER_INITIATED = new HashSet<>(Arrays.asList(
            CreatureSpawnEvent.SpawnReason.BREEDING,
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM, CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN,
            CreatureSpawnEvent.SpawnReason.BUILD_WITHER
    ));
    private static final Set<CreatureSpawnEvent.SpawnReason> ADMIN_INITIATED = new HashSet<>(Arrays.asList(
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
    ));
    private HashMap<String, Boolean> newbieisland = new HashMap<>();
    private final uSkyBlock plugin;

    private boolean phantomsInOverworld;
    private boolean phantomsInNether;

    public SpawnEvents(uSkyBlock plugin) {
        this.plugin = plugin;
        phantomsInOverworld = plugin.getConfig().getBoolean("options.spawning.phantoms.overworld", true);
        phantomsInNether = plugin.getConfig().getBoolean("options.spawning.phantoms.nether", false);
    }

    @EventHandler
    public void onSpawnEggEvent(PlayerInteractEvent event) {
        Player player = event != null ? event.getPlayer() : null;
        if (player == null || event.isCancelled() || !plugin.getWorldManager().isSkyWorld(player.getWorld())) {
            return; // Bail out, we don't care
        }
        if (player.hasPermission("usb.mod.bypassprotection") || player.isOp()) {
            return;
        }
        ItemStack item = event.getItem();
        if (RIGHT_CLICKS.contains(event.getAction()) && item != null && isSpawnEgg(item)) {
            if (!plugin.playerIsOnIsland(player)) {
                event.setCancelled(true);
                plugin.notifyPlayer(player, tr("\u00a7eYou can only use spawn-eggs on your own island."));
                return;
            }
            SpawnEgg spawnEgg = (SpawnEgg) item.getData();
            checkLimits(event, spawnEgg.getSpawnedType(), player.getLocation());
            if (event.isCancelled()) {
                plugin.notifyPlayer(player, tr("\u00a7cYou have reached your spawn-limit for your island."));
                event.setUseItemInHand(Event.Result.DENY);
                event.setUseInteractedBlock(Event.Result.DENY);
            }
        }
    }

    private boolean isSpawnEgg(ItemStack item) {
        return item.getType().name().endsWith("_SPAWN_EGG") && item.getData() instanceof MonsterEggs;
    }
    private int fastpos(int pos){
        pos+=64;
        return (pos<0)?((pos+1)/128):(pos/128);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event == null || !plugin.getWorldManager().isSkyAssociatedWorld(event.getLocation().getWorld())) {
            return; // Bail out, we don't care
        }
        if (!event.isCancelled() && ADMIN_INITIATED.contains(event.getSpawnReason())) {
            return; // Allow it, the above method would have blocked it if it should be blocked.
        }
        
        if (event.getEntity() instanceof Phantom) {
            String island = fastpos(event.getLocation().getBlockX()) + "," + fastpos(event.getLocation().getBlockZ());

            if(newbieisland.get(island) == null){
                IslandInfo is = plugin.getIslandInfo(event.getLocation());
                if(is != null){
                    PlayerInfo pi = plugin.getPlayerInfo(is.getLeader());
                    if (pi != null && pi.checkChallenge("builder5")==0){
                        // newbie protection
                        event.setCancelled(true);
                        pi = null; is = null;
                        newbieisland.put(island, true);
                        log(Level.INFO, "Add inf Phantom Protection: "+island+" : protect");
                        return;
                    }
                    pi = null; is = null;
                    newbieisland.put(island, false);
                    log(Level.INFO, "Add inf Phantom Protection: "+island+" : Not-protect");
                }
            }else{
                if (newbieisland.get(island) == true){
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (event.getEntity() instanceof WaterMob) {
            Random r = new Random();
            if (r.nextInt(20) != 0){
                event.setCancelled(true);
                return; 
            }
        }
        if (event.getEntity() instanceof ArmorStand){
            return;
        }
        checkLimits(event, event.getEntity().getType(), event.getLocation());
        if (event.getEntity() instanceof WaterMob) {
            Location loc = event.getLocation();
            if(doPrismarineRoof(loc))
                event.setCancelled(true);
        }
        if (!event.isCancelled() && event.getEntity() instanceof Enderman) {
            Location loc = event.getLocation();

            if(isPurpurFloor(loc)) {
                if (isEndBiome(loc)) {
                    Random r = new Random();
                    if (r.nextInt(10) == 0) {
                        loc.getWorld().spawnEntity(loc, EntityType.SHULKER);
                        event.setCancelled(true);
                    }
                } else {
                    loc.getWorld().spawnEntity(loc, EntityType.SHULKER);
                    event.setCancelled(true);
                }
            }
        }
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_WITHER && event.getEntity() instanceof Wither) {
            IslandInfo islandInfo = plugin.getIslandInfo(event.getLocation());
            if (islandInfo != null && islandInfo.getLeader() != null) {
                event.getEntity().setCustomName(I18nUtil.tr("{0}''s Wither", islandInfo.getLeader()));
                event.getEntity().setMetadata("fromIsland", new FixedMetadataValue(plugin, islandInfo.getName()));
            }
        }
    }

    private boolean isPurpurFloor(Location loc){
        List<Material> purpurBlocks = Arrays.asList(Material.PURPUR_BLOCK, Material.PURPUR_PILLAR, Material.PURPUR_SLAB);
        loc.setY(loc.getY()-1);
        boolean ret=purpurBlocks.contains(loc.getBlock().getType());
        loc.setY(loc.getY()+1);
        return ret;
    }
    private boolean isPrismarineRoof(Location loc) {
        List<Material> prismarineBlocks = Arrays.asList(Material.PRISMARINE, Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE);
        return prismarineBlocks.contains(LocationUtil.findRoofBlock(loc).getType());
    }
    private boolean doPrismarineRoof(Location loc) {
        List<Material> prismarineBlocks = Arrays.asList(Material.PRISMARINE, Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE);
        Location tloc = loc.clone();
        if(tloc.getBlockY()<47 || tloc.getBlockY()>64)
            return false;
        while(tloc.getBlockY()<=70){
            if (tloc.getBlock().getType() == Material.WATER){
                tloc.add(0,1,0);
                continue;
            }else{
                if(prismarineBlocks.contains(tloc.getBlock().getType())){
                    Random r = new Random();
                    if (r.nextInt(5) == 0){
                        if(r.nextInt(1000) == 0){
                            if(r.nextInt(1000)==0){
                                Drowned drowned= (Drowned) loc.getWorld().spawnEntity(loc, EntityType.DROWNED);
                                drowned.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
                                System.out.println(java.time.Clock.systemUTC().instant()+" "+plugin.getIslandInfo(loc).getLeader()+" RANDOM TRIDENT");
                            }
                            else{
                                Drowned drowned= (Drowned) loc.getWorld().spawnEntity(loc, EntityType.DROWNED);
                                if(drowned.getEquipment().getItemInMainHand().equals(new ItemStack(Material.TRIDENT))){
                                    System.out.println(java.time.Clock.systemUTC().instant()+" "+plugin.getIslandInfo(loc).getLeader()+" DROWNED TRIDENT");
                                }
                                else{
                                    System.out.println(java.time.Clock.systemUTC().instant()+" "+plugin.getIslandInfo(loc).getLeader()+" DROWNED NO TRIDENT");
                                }
                            }
                        }
                        else{
                            loc.getWorld().spawnEntity(loc, EntityType.GUARDIAN);
                        }
                    }
                    return true;
                }else if(tloc.getBlock().getType() == Material.SEA_LANTERN){
                    Random r = new Random();
                    if (r.nextInt(50) == 0)
                        loc.getWorld().spawnEntity(loc, EntityType.ELDER_GUARDIAN);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private boolean isDeepOceanBiome(Location loc) {
        List<Biome> deepOceans = Arrays.asList(Biome.DEEP_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.DEEP_FROZEN_OCEAN, Biome.DEEP_LUKEWARM_OCEAN, Biome.DEEP_WARM_OCEAN);
        return deepOceans.contains(loc.getWorld().getBiome(loc.getBlockX(), loc.getBlockZ()));
    }
    private boolean isEndBiome(Location loc) {
        return loc.getWorld().getBiome(loc.getBlockX(), loc.getBlockZ())==Biome.THE_END;
    }

    private void checkLimits(Cancellable event, EntityType entityType, Location location) {
        if (entityType == null) {
            return; // Only happens on "other-plugins", i.e. EchoPet
        }
        String islandName = WorldGuardHandler.getIslandNameAt(location);
        if (islandName == null) {
            event.setCancelled(true); // Only allow spawning on active islands...
            return;
        }
        if (entityType.getEntityClass().isAssignableFrom(Ghast.class) && location.getWorld().getEnvironment() != World.Environment.NETHER) {
            // Disallow ghasts for now...
            event.setCancelled(true);
            return;
        }
        us.talabrek.ultimateskyblock.api.IslandInfo islandInfo = plugin.getIslandInfo(islandName);
        if (islandInfo == null) {
            // Disallow spawns on inactive islands
            event.setCancelled(true);
            return;
        }
        if (!islandInfo.hasOnlineMembers()) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.getLimitLogic().canSpawn(entityType, islandInfo)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhantomSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Phantom) ||
                event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }

        World spawnWorld = event.getEntity().getWorld();
        if (!phantomsInOverworld && plugin.getWorldManager().isSkyWorld(spawnWorld)) {
            event.setCancelled(true);
        }

        if (!phantomsInNether && plugin.getWorldManager().isSkyNether(spawnWorld)) {
            event.setCancelled(true);
        }
    }

    /**
     * Changes the setting that allows Phantoms to spawn in the overworld. Used for testing purposes.
     * @param state True/enabled means spawning is allowed, false disallowed.
     */
    void setPhantomsInOverworld(boolean state) {
        this.phantomsInOverworld = state;
    }

    /**
     * Changes the setting that allows Phantoms to spawn in the nether. Used for testing purposes.
     * @param state True/enabled means spawning is allowed, false disallowed.
     */
    void setPhantomsInNether(boolean state) {
        this.phantomsInNether = state;
    }
}
