package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.*;

@Singleton
public class ToxicEvents implements Listener {
    private final uSkyBlock plugin;
    private final NamespacedKey key;
    private final long[] toxicData;

    private static final Set<Location> toSpread = new HashSet<>();
    //  uuid -> (#ticks to next damage, in toxic now?)
    private final Map<UUID, Pair<Integer, Boolean>> dmgTick = new HashMap<>();
    //  uuid -> #damage blocked since last coal taken
    private final Map<UUID, Integer> coalCount = new HashMap<>();
    @Inject
    public ToxicEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "is_toxic");
        this.toxicData = new long[4 * 384];
        // toxicData: every 4 longs represent a layer of 16x16 blocks, starting from y=-64 to y=319
        for (int i = 4; i < 4 + 4 * 126; i++) {
            // toxic sea from y=-63 to y=62
            this.toxicData[i] = ~0L;
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::doToxicSpreadTick, 20L, 1L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> doToxicDamageTick(plugin.getServer()), 20L, 1L);
    }

    public static boolean isWater(Block b) {
        return b.getType() == Material.WATER || b.getType() == Material.BUBBLE_COLUMN ||
               (b.getBlockData() instanceof Waterlogged wld && wld.isWaterlogged());
    }

    public boolean isToxic(Block b) {
        if (!plugin.getWorldManager().isSkyWorld(b.getWorld())) {
            return false;
        }
        if (!isWater(b)) {
            return false;
        }
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        long[] data = pdc.get(key, PersistentDataType.LONG_ARRAY);
        if (data == null || data.length != toxicData.length) {
            toxicizeChunk(b.getChunk());
            data = pdc.get(key, PersistentDataType.LONG_ARRAY);
        }
        int cx = b.getX() & 0x0F;
        int cz = b.getZ() & 0x0F;
        int cy = b.getY() + 64;
        int index = cy * 4 + (cx >> 2);
        long bit = 1L << (((cx & 0x03) << 4) | cz);
        return (data[index] & bit) != 0;
    }

    private List<Pair<ItemStack, EquipmentSlot>> getAntiToxicArmors(Player player) {
        List<Pair<ItemStack, EquipmentSlot>> ret = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            ItemStack armor = player.getInventory().getItem(slot);
            if (armor != null && armor.getType() != Material.AIR) {
                if (!armor.getEnchantments().isEmpty()) {
                    // TODO: implement pseudo-enchantment for anti-toxic
                    if (true /*armor.getEnchantments().containsKey(plugin.getCustomEnchants().ANTI_TOXIC)*/) {
                        ret.add(Pair.of(armor, slot));
                    }
                }
            }
        }
        return ret;
    }
    public void triggerToxicDamageTick(Player player) {
        if (player == null) return;
        dmgTick.compute(player.getUniqueId(), (k, entry) -> Pair.of(entry == null ? 10 : entry.getLeft(), true));
    }
    private boolean isRainyBiome(Biome b) {
        // only consider uSkyBlock biomes:
        return switch (b) {
            case OCEAN, LUKEWARM_OCEAN, COLD_OCEAN, WARM_OCEAN -> true;
            default -> false;
        };
    }

    private void checkToxicRain(Server server) {
        server.getOnlinePlayers().forEach((player) -> {
            Location loc = player.getLocation();
            if (!player.getWorld().isClearWeather() && isRainyBiome(loc.getBlock().getBiome())) {
                if (loc.getY() >= player.getWorld().getHighestBlockYAt(loc) - 1) {
                    // in toxic rain
                    triggerToxicDamageTick(player);
                    //plugin.getLogger().info("Player " + player.getName() + " is in toxic rain at " + loc.toString());
                }
            }
        });
    }

    private void checkToxicWater(Server server) {
        server.getOnlinePlayers().forEach((player) -> {
           if (player.isInWater()) {
                Location loc = player.getLocation();
                Block b = loc.getBlock();
                if (isToxic(b)) {
                    triggerToxicDamageTick(player);
                    //plugin.getLogger().info("Player " + player.getName() + " is in toxic water at " + loc.toString());
                }
           }
        });
    }

    private void doToxicDamageTick(Server server) {
        checkToxicRain(server);
        checkToxicWater(server);

        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, Pair<Integer, Boolean>> entry : dmgTick.entrySet()) {
            UUID uuid = entry.getKey();
            Pair<Integer, Boolean> pair = entry.getValue();
            int ticks = pair.getLeft();
            boolean inToxic = pair.getRight();

            Player player = server.getPlayer(uuid);
            if (player == null) {
                toRemove.add(uuid);
                continue;
            }

            if (inToxic) {
                if (ticks <= 1) {
                    List<Pair<ItemStack, EquipmentSlot>> antiToxicArmors = getAntiToxicArmors(player);
                    if (antiToxicArmors.isEmpty()) {
                        // 没有防酸装备，检查玩家背包是否有煤炭
                        ItemStack coalStack = null;
                        for (ItemStack item : player.getInventory().getContents()) {
                            if (item != null && item.getType() == Material.COAL) {
                                coalStack = item;
                                break;
                            }
                        }
                        if (coalStack == null) {
                            player.damage(1.0);
                        } else {
                            // 有煤炭，每60次消耗1个
                            Integer count = coalCount.get(uuid);
                            if (count == null) count = 1;
                            else count = count + 1;
                            coalCount.put(uuid, count);
                            if (count >= 60) {
                                coalCount.put(uuid, 0);
                                coalStack.setAmount(coalStack.getAmount() - 1);
                                if (coalStack.getAmount() <= 0) {
                                    player.getInventory().removeItem(coalStack);
                                }
                            }
                        }
                    } else {
                        int n = antiToxicArmors.size();
                        java.util.Random random = new java.util.Random();
                        if (random.nextInt(n * 2) == 0) {
                            // 随机损坏一件防酸装备
                            Pair<ItemStack, EquipmentSlot> armorPair = antiToxicArmors.get(random.nextInt(n));
                            ItemStack armor = armorPair.getLeft();
                            EquipmentSlot slot = armorPair.getRight();
                            int unbreaking = armor.getEnchantmentLevel(Enchantment.UNBREAKING);
                            if (random.nextInt(unbreaking + 1) == 0) {
                                Damageable meta = (Damageable) armor.getItemMeta();
                                if (meta != null) {
                                    meta.setDamage(meta.getDamage() + 1);
                                    // 如果装备损坏完毕，移除
                                    if (meta.getDamage() >= armor.getType().getMaxDurability()) {
                                        player.getInventory().setItem(slot, null);
                                    }
                                }
                            }
                        }
                    }
                    dmgTick.put(uuid, Pair.of(10, false)); // false: to check if still in toxic next tick
                } else {
                    dmgTick.put(uuid, Pair.of(ticks - 1, false));
                }
            } else {
                toRemove.add(uuid);
            }
        }
        for (java.util.UUID uuid : toRemove) {
            dmgTick.remove(uuid);
            coalCount.remove(uuid);
        }
    }

    public void setToxic(Block b, Boolean toxic) {
        if (!plugin.getWorldManager().isSkyWorld(b.getWorld())) {
            return;
        }
        if (b.getY() < -63 || b.getY() > 62) {
            return;
        }
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        long[] data = pdc.get(key, PersistentDataType.LONG_ARRAY);
        if (data == null || data.length != toxicData.length) {
            toxicizeChunk(b.getChunk());
        }
        int cx = b.getX() & 0x0F;
        int cz = b.getZ() & 0x0F;
        int cy = b.getY() + 64;
        int index = cy * 4 + (cx >> 2);
        long bit = 1L << (((cx & 0x03) << 4) | cz);
        data[index] = toxic ? (data[index] | bit) : (data[index] & ~bit);
    }

    @EventHandler (priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerBucketToxicWater(PlayerBucketFillEvent event) {
        Block b = event.getBlock();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(b.getWorld())) {
            return;
        }

        if (event.getItemStack().getType() == Material.WATER_BUCKET) {
            if (isToxic(event.getBlock())){
                setBucketToxic(event.getItemStack());
                setToxic(b, false);
            }
        }
    }

    /*
        This only trigger pollution check when player releases water,
        as the event cannot tell whether the water bucket used is toxic or not.
     */
    @EventHandler (priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerReleaseWater(PlayerBucketEmptyEvent event) {
        Block b = event.getBlock();

        if (!plugin.getWorldManager().isSkyAssociatedWorld(b.getWorld())) {
            return;
        }

        if (event.getItemStack() != null && event.getItemStack().getType() == Material.WATER_BUCKET) {
            setToxic(b, false);
            checkPollute(b);
        }
    }

    @EventHandler (priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(from.getWorld()) || !isWater(from)) {
            return;
        }

        if (to.getY() < -64) {
            return;
        }
        if (event.getToBlock().getType() == Material.WATER && event.getBlock().getType() == Material.LAVA) {
            // 防止毒水生成石头
            if (isToxic(to)) {
                event.setCancelled(true);
            }
            return;
        }
        boolean toxic_from = isToxic(from);
        boolean toxic_to = isToxic(to);

        if (toxic_from) {
            spread(from);
        }
        if (toxic_to) {
            spread(to);
        }
    }

    /*
        Make drained/replaced water non-toxic
     */
    @EventHandler (priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhysicsCheck(BlockPhysicsEvent e) {
        // TODO: make this more efficient, as BlockPhysicsEvent is very frequent
        Block b = e.getSourceBlock();
        if (!isWater(b)) {
            setToxic(b, false);
        }
    }

    public void checkPollute(Block b) {
        for (BlockFace face : new BlockFace[] {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN }) {
            Block nb = b.getRelative(face);
            if (isToxic(nb)) {
                setToxic(b, true);
                spread(b);
                return;
            }
        }
    }

    public void spread(Block b) {
        toSpread.add(b.getLocation());
    }

    public void doToxicSpreadTick() {
        Set<Location> newToSpread = new HashSet<>();
        for (Location loc : toSpread) {
            Block b = loc.getBlock();
            if (!isToxic(b)) {
                continue;
            }
            for (BlockFace face : new BlockFace[] {
                    BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
                    BlockFace.UP, BlockFace.DOWN }) {
                Block nb = b.getRelative(face);
                if (isWater(nb) && !isToxic(nb) && !newToSpread.contains(nb.getLocation())) {
                    plugin.getLogger().info("Water at " + nb.getLocation() + " became toxic.");
                    setToxic(nb, true);
                    nb.getWorld().playEffect(nb.getLocation(), Effect.LAVA_INTERACT, 0);
                    newToSpread.add(nb.getLocation());
                }
            }
        }
        toSpread.clear();
        toSpread.addAll(newToSpread);
    }

    public static void setBucketToxic(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("有毒的水桶");
            meta.setLore(List.of("从污染水源里面舀到的水", "剧毒"));
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            item.setItemMeta(meta);
        }
    }

    public static boolean isBucketToxic(ItemStack item) {
        if (item == null || item.getType() != Material.WATER_BUCKET) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasEnchant(Enchantment.UNBREAKING);
    }

    /*
        Set toxic data of chunk as if the chunk is newly generated/reset, i.e., all blocks from y=-63 to y=62 are toxic.
     */
    public void toxicizeChunk(Chunk chunk) {
        // only work in overworld
        if (!plugin.getWorldManager().isSkyAssociatedWorld(chunk.getWorld())) {
            return;
        }
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        pdc.set(key, PersistentDataType.LONG_ARRAY, toxicData);
    }


    @EventHandler
    public void toxicizeChunk(ChunkPopulateEvent event) {
        if (!plugin.getWorldManager().isSkyWorld(event.getWorld())) {
            return;
        }
        toxicizeChunk(event.getChunk());
    }
}
