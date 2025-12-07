package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Cocoa;
import org.bukkit.damage.DamageSource;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.hook.economy.EconomyHook;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.*;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

@Singleton
public class AltarEvents implements Listener {
    private final uSkyBlock plugin;
    private static final Random RANDOM = new Random();
    private static final HashMap<UUID, Long> deliciousFruitConsumers = new HashMap<>();
    private static final HashMap<UUID, Long> specialBlendConfirm = new HashMap<>();
    public enum AltarType {
        NONE,
        HARVEST,
        WAR,
        WEALTH,
        SOUL,
        EXPLORATION
    }

    public enum AltarBuffType {
        /*
            THRIVE:
            每级增加3%动物成长速度和鸡下蛋量
            每级增加1%动物繁殖速度
            每5级增加2个动物上限
         */
        THRIVE,
    }

    public enum HarvestFoodType {
        MEAT,
        SEA_FOOD,
        SOUP,
        DESSERT,
        STAPLE_FOOD,
        SPECIAL,
        EGA,
        NOT_ACCEPTED
    }

    @Inject
    public AltarEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(
            plugin,
            () -> {
                for (World world : Bukkit.getWorlds()) {
                    for (Player p : world.getPlayers()) {
                        int remainingTicks = getSpecialBlendTick(p);
                        if (remainingTicks > 0) {
                            p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.0, 0), 5, 0.5, 0.5, 0.5, 0.1);
                            setSpecialBlendTick(p, remainingTicks - 60);
                        }
                    }
                    for (Animals animal : world.getEntitiesByClass(Animals.class)) {
                        int remainingTicks = getSpecialBlendTick(animal);
                        if (remainingTicks > 0) {
                            if (animal.getAge() == 0) {
                                animal.setLoveModeTicks(remainingTicks);
                            } else {
                                // 产生较小的粒子效果以提示
                                Location loc = animal.getLocation().add(0, animal.getHeight() / 2.0, 0);
                                animal.getWorld().spawnParticle(Particle.HEART, loc, 1, 0.5, 0.5, 0.5, 0.1);
                            }
                            setSpecialBlendTick(animal, remainingTicks - 60);
                        }
                    }
                }
            },
            100L,
            60L
        );
    }

    static public ItemStack stoneOfPeace() {
        ItemStack item = new ItemStack(Material.LAPIS_LAZULI);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(tr("\u00a7l\u00a79和平之石"));
        meta.addEnchant(Enchantment.PROTECTION, 10, true);
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a79和平之石");
        lore.add("\u00a7l\u00a7e消耗品，为副手物品加一级\u00a7l\u00a79保护");
        lore.add("\u00a7l\u00a7e消耗数等于目标保护等级");
        lore.add("\u00a7l\u00a7e最多升级到保护10");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static public ItemStack stoneOfEternity() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(tr("\u00a7l\u00a7b永久之石"));
        meta.addEnchant(Enchantment.UNBREAKING, 10, true);
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a7b永久之石");
        lore.add("\u00a7l\u00a7e消耗品，为副手物品加一级\u00a7l\u00a7b耐久");
        lore.add("\u00a7l\u00a7e消耗数等于目标等级");
        lore.add("\u00a7l\u00a7e最多升级到耐久10");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    static public ItemStack stoneOfWealth() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(tr("\u00a7l\u00a76财富之石"));
        meta.addEnchant(Enchantment.FORTUNE, 1, true);
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a76财富之石");
        lore.add("\u00a7l\u00a7e消耗品，为副手物品加一级\u00a7l\u00a76时运");
        lore.add("\u00a7l\u00a7e消耗数等于目标等级的\u00a7l\u00a76平方");
        lore.add("\u00a7l\u00a7e最多升级到时运8");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static public ItemStack stoneOfLife() {
        // 红石 效率10
        ItemStack item = new ItemStack(Material.REDSTONE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(tr("\u00a7l\u00a74生命之石"));
        meta.addEnchant(Enchantment.EFFICIENCY, 10, true);
        // 对可种植方块使用，其上的作物在生长时立即成熟
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a74生命之石");
        lore.add("\u00a7l\u00a7e消耗品，对可种植方块使用");
        lore.add("\u00a7l\u00a7e其上的作物在生长时立即成熟");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static public ItemStack scytheOfHarvest(int fortuneLevel) {
        ItemStack item = new ItemStack(Material.GOLDEN_HOE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(tr("\u00a7l\u00a76收获之镰"));
        meta.addEnchant(Enchantment.FORTUNE, fortuneLevel, true);
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);

        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a76收获之镰");
        lore.add(String.format("\u00a7l\u00a76lv%d 0/%d", fortuneLevel, scytheOfHarvestExpRequired(fortuneLevel)));
        lore.add("\u00a7l\u00a7e右键使用以收获而不破坏作物");
        lore.add("\u00a7l\u00a7e使用以积累经验值并升级，上限为10");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static public ItemStack scytheOfHarvest() {
        return scytheOfHarvest(1);
    }

    static public ItemStack whipOfPastor(int LootingLevel) {
        // 烈焰棒 附加抢夺
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(tr("\u00a7l\u00a76牧者之鞭"));
        meta.addEnchant(Enchantment.LOOTING, LootingLevel, true);
        // 秒杀动物，无法对敌对生物造成伤害
        // 使用右键可以将堆叠的两个此物品合成为更高等级
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a76牧者之鞭");
        lore.add("\u00a7l\u00a7e一击杀死动物，但无法对其他生物造成伤害");
        lore.add("\u00a7l\u00a7e右键合成两个相同等级的牧者之鞭以提升等级");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static public ItemStack whipOfPastor() {
        return whipOfPastor(3);
    }

    static public ItemStack deliciousFruit() {
        // 苹果，食用后给与60分钟饱和效果
        ItemStack item = new ItemStack(Material.APPLE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(tr("\u00a7l\u00a75美味果实"));
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a7e一个看起来非常好吃的苹果...还是樱桃？");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static public ItemStack specialBlend() {
        // 秘制特调，给家畜使用使其24小时内进入love mode
        ItemStack item = new ItemStack(Material.HONEY_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(tr("\u00a7l\u00a7d秘制特调"));
        List<String> lore = new ArrayList<>();
        lore.add("\u00a7l\u00a7e动物们吃了以后...根本把持不住！");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public double getHarvestFoodValue(ItemStack itemStack) {
        // handle special cases first
        Material mat = itemStack.getType();
        int num = itemStack.getAmount();
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null || meta.hasLore()) {
            // 防止把特殊物品送出去
            return 0;
        }
        // value = (saturation + hunger) * num
        return switch (mat) {
            case ENCHANTED_GOLDEN_APPLE -> 10000 * num;
            case RABBIT_STEW -> 22 * num;
            case COOKED_PORKCHOP, COOKED_BEEF -> 20.8 * num;
            case GOLDEN_CARROT -> 20.4 * num;
            case CAKE -> 16.8 * num;
            case COOKED_MUTTON, COOKED_SALMON -> 15.6 * num;
            case GOLDEN_APPLE -> 13.6 * num;
            case BEETROOT_SOUP, MUSHROOM_STEW, COOKED_CHICKEN, SUSPICIOUS_STEW -> 13.2 * num;
            case PUMPKIN_PIE -> 12.8 * num;
            case BAKED_POTATO, BREAD, COOKED_COD, COOKED_RABBIT -> 11 * num;
            case HONEY_BOTTLE -> 7.2 * num;
            case APPLE -> 6.4 * num;
            case MELON_SLICE -> 3.2 * num;
            case COOKIE -> 2.4 * num;
            case DRIED_KELP -> 1.6 * num;
            default -> 0.0;
        };
    }

    static public String getHarvestFoodTypeName(HarvestFoodType type) {
        return switch (type) {
            case MEAT -> tr("肉类");
            case SEA_FOOD -> tr("海产");
            case SOUP -> tr("炖汤");
            case DESSERT -> tr("甜点");
            case STAPLE_FOOD -> tr("主食");
            case SPECIAL -> tr("\"特别的\"");
            case EGA -> tr("\"非常罕见的\"");
            case NOT_ACCEPTED -> tr("不被接受的食物");
        };
    }

    static public HarvestFoodType getHarvestFoodType(Material mat) {
        return switch (mat) {
            case COOKED_BEEF, COOKED_CHICKEN, COOKED_MUTTON, COOKED_PORKCHOP, COOKED_RABBIT -> HarvestFoodType.MEAT;
            case COOKED_COD, COOKED_SALMON, DRIED_KELP -> HarvestFoodType.SEA_FOOD;
            case MUSHROOM_STEW, RABBIT_STEW, BEETROOT_SOUP, SUSPICIOUS_STEW -> HarvestFoodType.SOUP;
            case CAKE, COOKIE, PUMPKIN_PIE, MELON_SLICE, APPLE, HONEY_BOTTLE -> HarvestFoodType.DESSERT;
            case BREAD, BAKED_POTATO -> HarvestFoodType.STAPLE_FOOD;
            case GOLDEN_APPLE, GOLDEN_CARROT -> HarvestFoodType.SPECIAL;
            case ENCHANTED_GOLDEN_APPLE -> HarvestFoodType.EGA;
            default -> HarvestFoodType.NOT_ACCEPTED;
        };
    }

    /*
        玩家向祭坛献祭物品。items中适合的物品会被移除。
        warning: 默认altar是一个合法的收获之祭坛, raw_values长度为7，对应每一类食物的总价值
     */
    public void offerToAltarOfHarvestEval(Player p, Block altar, double[] raw_values) {
        // 获得原值
        PersistentDataContainer pdc = altar.getChunk().getPersistentDataContainer();
        long[] old_values = pdc.getOrDefault(getKeyAltarHarvestCounters(altar), PersistentDataType.LONG_ARRAY, new long[7]);
        // 计算最小值
        long minimal = Long.MAX_VALUE;
        long maximal = Long.MIN_VALUE;
        HarvestFoodType min_type = HarvestFoodType.MEAT, max_type = HarvestFoodType.MEAT;
        for (int i = 0; i < 7; ++i) {
            long effective_value = old_values[i];
            if (i == HarvestFoodType.EGA.ordinal()) {
                effective_value *= 10; // 附魔金苹果在计算“吃腻”时按10倍价值计算
            }
            if (minimal > effective_value) {
                minimal = effective_value;
                min_type = HarvestFoodType.values()[i];
            }
            if (maximal < old_values[i]) {
                maximal = old_values[i];
                max_type = HarvestFoodType.values()[i];
            }
        }
        if (minimal < 10000) {
            minimal = 10000;
        }
        // 计算打折后的新值
        double max_ratio = maximal / (5.0 * minimal);
        for (int i = 0; i < 7; ++i) {
            double ratio = old_values[i] / (5.0 * minimal);
            raw_values[i] = raw_values[i] * (ratio < 1 ? 1.0 : (1.0 / ratio / ratio));
        }
        // 结算
        long sum = 0;
        for (int i = 0; i < 7; ++i) {
            old_values[i] += (long) Math.floor(raw_values[i]);
            sum += (long) Math.floor(raw_values[i]);
        }
        pdc.set(getKeyAltarHarvestCounters(altar), PersistentDataType.LONG_ARRAY, old_values);
        long old_counter = pdc.getOrDefault(getKeyAltarCounter(altar), PersistentDataType.LONG, 0L);
        pdc.set(getKeyAltarCounter(altar), PersistentDataType.LONG, old_counter + sum);
        // 向玩家展示结算结果
        if (sum == 0) {
            p.sendMessage(tr("\u00a7a祭坛似乎不太喜欢这些......"));
            return;
        }

        p.sendMessage(String.format(tr("\u00a7a你向收获之祭坛奉献了总价值 \u00a7l\u00a73%d \u00a7a的食物。"), sum));
        // 如果触发打折，则提示
        if (max_ratio > 1) {
            p.sendMessage(String.format(tr("\u00a7a祭坛之灵对\u00a74%s\u00a7a有些抗拒了，效率降低了%.2f%%。多试试\u00a74%s\u00a7a吧！"),
                getHarvestFoodTypeName(max_type), 100 * (1 - 1 / max_ratio / max_ratio), getHarvestFoodTypeName(min_type)));
        } else if (max_ratio > 0.8) {
            p.sendMessage(String.format(tr("\u00a7a祭坛之灵想换换口味，来点\u00a74%s\u00a7a如何？"),getHarvestFoodTypeName(min_type)));
        }
        long draws = (old_counter + sum) / 5000 - old_counter / 5000;
        harvestReward(p, draws);
    }
    public void offerToAltarOfHarvest(Player p, Block altar, Inventory inventory) {
        double[] raw_values = new double[7];
        for (int slot = 0; slot < inventory.getSize(); ++slot) {
            ItemStack itemstack = inventory.getItem(slot);
            if (itemstack == null) {
                continue;
            }
            HarvestFoodType type = getHarvestFoodType(itemstack.getType());
            if (type != HarvestFoodType.NOT_ACCEPTED) {
                double value = getHarvestFoodValue(itemstack);
                raw_values[type.ordinal()] += value;
                // 移除物品
                inventory.setItem(slot, null);
            }
        }
        offerToAltarOfHarvestEval(p, altar, raw_values);
    }

    public void offerToAltarOfHarvest(Player p, Block altar, ItemStack item) {
        // 统计每一类
        double[] raw_values = new double[7];
        HarvestFoodType type = getHarvestFoodType(item.getType());
        if (type != HarvestFoodType.NOT_ACCEPTED) {
            double value = getHarvestFoodValue(item);
            raw_values[type.ordinal()] += value;
            // 移除物品
            item.setAmount(0);
        }
        offerToAltarOfHarvestEval(p, altar, raw_values);
    }

    private NamespacedKey getKeyAltarType(Block b) {
        // Stored as int (decoded as AltarType.ordinal())
        return new NamespacedKey(plugin, String.format("altar_type_%d_%d_%d", b.getX(), b.getY(), b.getZ()));
    }

    private NamespacedKey getKeyAltarCounter(Block b) {
        // Stored as long
        return new NamespacedKey(plugin, String.format("altar_counter_%d_%d_%d", b.getX(), b.getY(), b.getZ()));
    }

    private NamespacedKey getKeyAltarHarvestCounters(Block b) {
        // Stored as long[7]
        return new NamespacedKey(plugin, String.format("altar_harvest_counters_%d_%d_%d", b.getX(), b.getY(), b.getZ()));
    }

    private NamespacedKey getKeyStoneOfLifeBlocks(Block b) {
        // Stored as 256-bit (long[4]) for a y-layer
        return new NamespacedKey(plugin, String.format("altar_SoL_y%d", b.getY()));
    }

    private NamespacedKey getKeySpecialBlendTick() {
        // Stored as int (remaining ticks)
        return new NamespacedKey(plugin, "special_blend");
    }

    private void buildAltar(IslandInfo ii, Block block, AltarType type) {
        ii.setAltarBuilt(type, ii.getAltarBuilt(type) + 1);
        block.setType(Material.REINFORCED_DEEPSLATE);
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        pdc.set(getKeyAltarType(block), PersistentDataType.INTEGER, type.ordinal());
        pdc.set(getKeyAltarCounter(block), PersistentDataType.LONG, 0L);
        switch (type) {
            case HARVEST -> pdc.set(getKeyAltarHarvestCounters(block), PersistentDataType.LONG_ARRAY, new long[7]);
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAltarDestroyed(final BlockBreakEvent event) {
        // 当祭坛被破坏时，移除其持久化数据
        if (!plugin.getWorldManager().isSkyWorld(event.getBlock().getWorld())) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType() != Material.REINFORCED_DEEPSLATE) {
            return;
        }
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        Integer type = pdc.get(getKeyAltarType(block), PersistentDataType.INTEGER);
        if (type != null) {
            IslandInfo islandInfo = plugin.getIslandInfo(block.getLocation());
            AltarType altarType = AltarType.values()[type];
            if (islandInfo != null) {
                int n = islandInfo.getAltarBuilt(altarType);
                if (n > 0) {
                    islandInfo.setAltarBuilt(altarType, n - 1);
                }
            }
            pdc.remove(getKeyAltarType(block));
            pdc.remove(getKeyAltarCounter(block));
            switch (altarType) {
                case HARVEST -> pdc.remove(getKeyAltarHarvestCounters(block));
                default -> {}
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWhipOfPastorUsed(final EntityDamageByEntityEvent event) {
        // 确认是玩家使用牧者之鞭攻击实体
        DamageSource ds = event.getDamageSource();
        Entity e = ds.getCausingEntity();
        if (!(e instanceof Player)) {
            return;
        }
        ItemStack hand = ((Player) e).getInventory().getItemInMainHand();
        if (hand.getType() != Material.BLAZE_ROD || !hand.hasItemMeta() || !hand.getItemMeta().hasLore()) {
            return;
        }
        if (!hand.getItemMeta().getLore().getFirst().contains("牧者之鞭")) {
            return;
        }
        // 如果实体是动物，则一击必杀
        Entity target = event.getEntity();
        if (target instanceof Animals) {
            event.setDamage(99);
        } else {
            // 不能对敌对生物造成伤害
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void thriveEffect(final EntitySpawnEvent event) {
        // 使幼体的成长时间减少为1/(1+0.03*thriveLevel)
        Entity e = event.getEntity();
        if (!(e instanceof Animals animal)) {
            return;
        }
        IslandInfo ii = plugin.getIslandInfo(e.getLocation());
        if (ii == null) {
            return;
        }
        int thriveLevel = ii.getAltarBuffLevel(AltarBuffType.THRIVE);
        if (thriveLevel <= 0) {
            return;
        }
        if (animal.getAge() < 0) {
            int newAge = (int) Math.ceil(animal.getAge() / (1.0 + 0.03 * thriveLevel));
            animal.setAge(newAge);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void thriveEffect(final EntityBreedEvent event) {
        // 使双亲的繁殖冷却时间减少为1/(1+0.01*thriveLevel)
        Entity f = event.getFather();
        IslandInfo ii = plugin.getIslandInfo(f.getLocation());
        if (ii == null) {
            return;
        }
        int thriveLevel = ii.getAltarBuffLevel(AltarBuffType.THRIVE);
        if (thriveLevel <= 0) {
            return;
        }
        if (f instanceof Animals father) {
            int newAge = (int) Math.ceil(6000 / (1.0 + 0.01 * thriveLevel));
            Bukkit.getScheduler().runTaskLater(plugin,
                () -> father.setAge(newAge),
                1L
            );
        }
        if (event.getMother() instanceof Animals mother) {
            int newAge = (int) Math.ceil(6000 / (1.0 + 0.01 * thriveLevel));
            Bukkit.getScheduler().runTaskLater(plugin,
                () -> mother.setAge(newAge),
                1L
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void thriveEffect(final EntityDropItemEvent event) {
        // 使鸡下蛋的产量增加0.03*thriveLevel
        Entity e = event.getEntity();
        if (!(e instanceof Chicken)) {
            return;
        }
        ItemStack eggs = event.getItemDrop().getItemStack();
        if (eggs.getType() != Material.EGG) {
            return;
        }
        IslandInfo ii = plugin.getIslandInfo(e.getLocation());
        if (ii == null) {
            return;
        }
        int thriveLevel = ii.getAltarBuffLevel(AltarBuffType.THRIVE);
        if (thriveLevel <= 0) {
            return;
        }
        double additionalEggs = 0.03 * thriveLevel;
        int extraEggs = (int) additionalEggs;
        if (RANDOM.nextDouble() < (additionalEggs - extraEggs)) {
            extraEggs += 1;
        }
        eggs.setAmount(extraEggs + 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeliciousFruitConsumed(final PlayerItemConsumeEvent event) {
        // 确认是美味果实
        ItemStack item = event.getItem();
        if (item.getType() != Material.APPLE || !item.hasItemMeta() || !item.getItemMeta().hasLore()) {
            return;
        }
        if (!item.getItemMeta().getItemName().contains("美味果实")) {
            return;
        }
        // 是美味果实，临时记录状态：在玩家收到来自怪物的伤害时，失去饱和效果
        Player player = event.getPlayer();
        player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 72000, 0));
        deliciousFruitConsumers.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeliciousFruitConsumerTakeDamage(final EntityDamageEvent event) {
        // 确认实体是玩家
        Entity e = event.getEntity();
        if (!(e instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        // 记录中没有该玩家，或者记录超过了一小时
        if (!deliciousFruitConsumers.containsKey(playerId) ||
            System.currentTimeMillis() - deliciousFruitConsumers.get(playerId) > 3600000L) {
            return;
        }
        // 确认伤害来源是怪物
        DamageSource ds = event.getDamageSource();
        Entity damager = ds.getCausingEntity();
        if (damager == null) {
            return;
        }
        if (!(damager instanceof Enemy)) {
            return;
        }
        // 移除饱和效果
        player.removePotionEffect(PotionEffectType.SATURATION);
        deliciousFruitConsumers.remove(playerId);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onSpecialItemsUsed(final PlayerInteractEvent event) {
        Player player = event.getPlayer();
        // 只处理主手的交互，防止重复响应
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.hasItemMeta() && itemInHand.getItemMeta().hasLore()) {
            List<String> lore = itemInHand.getItemMeta().getLore();
            if (lore == null || lore.isEmpty()) {
                return;
            }
            String firstLore = itemInHand.getItemMeta().getLore().getFirst();
            if (firstLore.contains("和平之石")) {
                tryUseStoneOfPeace(player, itemInHand, event);
            } else if (firstLore.contains("永久之石")) {
                tryUseStoneOfEternity(player, itemInHand, event);
            } else if (firstLore.contains("财富之石")) {
                tryUseStoneOfWealth(player, itemInHand, event);
            } else if (firstLore.contains("生命之石")) {
                tryUseStoneOfLife(player, itemInHand, event);
            } else if (firstLore.contains("收获之镰")) {
                tryUseScytheOfHarvest(player, itemInHand, event);
            } else if (firstLore.contains("牧者之鞭")) {
                tryUpgradeWhipOfPastor(player, itemInHand, event);
            }
        }
    }
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpecialBlendUsedOnAnimal(final PlayerInteractEntityEvent event) {
        // 确认实体是动物
        Entity e = event.getRightClicked();
        if (!(e instanceof Animals animal)) {
            return;
        }
        // 确认玩家手中的物品是秘制特调
        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.getType() != Material.HONEY_BOTTLE || !itemInHand.hasItemMeta() || !itemInHand.getItemMeta().hasLore()) {
            return;
        }
        ItemMeta meta = itemInHand.getItemMeta();
        if (meta == null || !meta.hasItemName()) {
            return;
        }
        if (!meta.getItemName().contains("秘制特调")) {
            return;
        }
        int tickNow = getSpecialBlendTick(e);
        if (tickNow > 0) {
            boolean needConfirm = true;
            if (specialBlendConfirm.containsKey(e.getUniqueId())) {
                Long confirmTime = specialBlendConfirm.get(e.getUniqueId());
                if (System.currentTimeMillis() - confirmTime < 10000L) {
                    needConfirm = false;
                }
            }
            if (needConfirm) {
                specialBlendConfirm.put(e.getUniqueId(), System.currentTimeMillis());
                player.sendMessage(tr(String.format("\u00a7e该动物已经处于秘制特调的影响下，仍有 %d 分钟的效果剩余。", tickNow / 1200)));
                player.sendMessage(tr("\u00a7e再次使用以确认。"));
                event.setCancelled(true);
                return;
            } else {
                specialBlendConfirm.remove(e.getUniqueId());
            }
        }
        // 使动物进入love mode，持续24小时
        if (e.getType() != EntityType.AXOLOTL) {
            animal.setLoveModeTicks(1728000);
            setSpecialBlendTick(e, 1728000);
        } else {
            // 平衡性调整，对美西螈只持续4小时
            animal.setLoveModeTicks(288000);
            setSpecialBlendTick(e, 288000);
            // 提示玩家
            player.sendMessage(tr("\u00a7e美西螈对秘制特调的反应似乎没有那么强烈..."));
        }
        // 播放粒子效果
        Location loc = animal.getLocation().add(0, animal.getHeight() / 2.0, 0);
        animal.getWorld().spawnParticle(Particle.HEART, loc, 10, 0.5, 0.5, 0.5, 0.1);
        // 消耗秘制特调
        itemInHand.setAmount(itemInHand.getAmount() - 1);
        event.setCancelled(false);
    }

    // 彩蛋，玩家食用秘制特调后持续冒爱心
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpecialBlendConsumed(final PlayerItemConsumeEvent event) {
        // 确认是秘制特调
        ItemStack item = event.getItem();
        if (item.getType() != Material.HONEY_BOTTLE || !item.hasItemMeta() || !item.getItemMeta().hasItemName()) {
            return;
        }
        if (!item.getItemMeta().getItemName().contains("秘制特调")) {
            return;
        }
        Player player = event.getPlayer();
        setSpecialBlendTick(player, 12000); // 10分钟
    }
    static private Material getCropDropType(Material mat) {
        return switch (mat) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case NETHER_WART -> Material.NETHER_WART;
            case SWEET_BERRY_BUSH -> Material.SWEET_BERRIES;
            case COCOA -> Material.COCOA_BEANS;
            default -> Material.AIR;
        };
    }

    public void tryUseScytheOfHarvest(Player player, ItemStack itemInHand, final PlayerInteractEvent event) {
        // 当使用收获之镰，右键使用以收获而不破坏作物
        // 确认交互的方块是成熟的作物
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        Material mat = block.getType();
        BlockData bd = block.getBlockData();
        switch (mat) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, SWEET_BERRY_BUSH, COCOA -> {
                if (bd instanceof Ageable ageable) {
                    if (ageable.getAge() != ageable.getMaximumAge()) {
                        return;
                    }
                } else {
                    // this should not happen!
                    plugin.getLogger().warning(String.format("Find block %s which is not Ageable in scytheOfHarvest!", mat.name()));
                    return;
                }
            }
            default -> {
                return;
            }
        }
        // 必须是自己岛上的方块
        IslandInfo ii = plugin.getIslandInfo(block.getLocation());
        if (ii == null || !ii.isMember(player)) {
            return;
        }
        // 获取掉落物
        var li = block.getDrops(itemInHand, player);  // TODO: check if FORTUNE works

        // 当收获小麦、甜菜、甜浆果、可可豆时，额外使其时运起作用
        if (mat == Material.WHEAT || mat == Material.BEETROOTS || mat == Material.SWEET_BERRY_BUSH || mat == Material.COCOA) {
            int fortuneLevel = itemInHand.getItemMeta().getEnchantLevel(Enchantment.FORTUNE);
            int num = RANDOM.nextInt(fortuneLevel + 1);
            if (num > 0) {
                li.forEach(is -> {
                        if (is.getType() == getCropDropType(mat)) {
                            is.setAmount(is.getAmount() + num);
                        }
                    }
                );
            }
        }
        // 移除作物，重置为未成熟状态
        ((Ageable)bd).setAge(0);
        block.setBlockData(bd);
        // 掉落物品
        li.forEach(is -> player.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), is));
        // 积累经验值
        scytheOfHarvestAddExp(player, itemInHand);
    }

    static public int scytheOfHarvestExpRequired(int level) {
        return switch (level) {
            case 1 -> 32;
            case 2 -> 128;
            case 3 -> 512;
            case 4 -> 2048;
            case 5 -> 8192;
            case 6 -> 32768;
            case 7 -> 131072;
            case 8 -> 524288;
            case 9 -> 2097152;
            default -> 9999999; // should not happen
        };
    }
    private void scytheOfHarvestAddExp(Player player, ItemStack scythe) {
        ItemMeta meta = scythe.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return;
        }
        List<String> lore = meta.getLore();
        if (lore == null || lore.size() < 2) {
            return;
        }
        String secondLine = lore.get(1); // e.g. "lv1 0/32"
        String[] parts = secondLine.split(" ");
        if (parts.length != 2) {
            return;
        }
        String[] levelPart = parts[0].split("lv"); // e.g. "lv1"
        String[] expParts = parts[1].split("/");
        if (expParts.length != 2) {
            return;
        }
        int currentLevel, currentExp, expRequired;
        try {
            currentLevel = Integer.parseInt(levelPart[levelPart.length-1]);
            currentExp = Integer.parseInt(expParts[0]);
            expRequired = Integer.parseInt(expParts[1]);
        } catch (NumberFormatException e) {
            return;
        }
        if (currentLevel < 1 || currentLevel >= 10) {
            return;
        }
        if (currentExp + 1 >= expRequired) {
            // 升级
            currentLevel += 1;
            currentExp = 0;
            expRequired = scytheOfHarvestExpRequired(currentLevel);
            // 提升时运附魔等级
            meta.addEnchant(Enchantment.FORTUNE, currentLevel, true);
            plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a使用\u00a76收获之镰 \u00a7a，将其时运等级提升到了 \u00a76%d\u00a7a！",
                player.getName(), currentLevel));
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        } else {
            currentExp += 1;
        }
        lore.set(1, String.format("\u00a7l\u00a76lv%d %d/%d", currentLevel, currentExp, expRequired));
        meta.setLore(lore);
        scythe.setItemMeta(meta);
    }
    private void tryUseStoneOfPeace(Player player, ItemStack itemInHand, PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        // 只能对副手物品使用
        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        if (offHandItem.getType() == Material.AIR || offHandItem.getAmount() != 1) {
            player.sendMessage(tr("\u00a7c你必须在副手持有一个物品。"));
            return;
        }
        ItemMeta offHandMeta = offHandItem.getItemMeta();
        if (offHandMeta == null) {
            // This should never happen
            return;
        }
        if (!offHandMeta.hasEnchant(Enchantment.PROTECTION)) {
            player.sendMessage(tr("\u00a7c你的副手物品没有保护附魔。"));
            return;
        }
        int currentProtectionLevel = offHandMeta.getEnchantLevel(Enchantment.PROTECTION);
        if (currentProtectionLevel >= 10) {
            player.sendMessage(tr("\u00a7c你的副手物品的等级已经达到最高。"));
            return;
        }
        // 消耗对应数量的和平之石
        int stonesNeeded = currentProtectionLevel + 1;
        if (itemInHand.getAmount() < stonesNeeded) {
            player.sendMessage(String.format("\u00a7c你需要 %d 个和平之石来提升副手物品的保护等级。", stonesNeeded));
            return;
        }
        // 提升保护等级
        offHandMeta.addEnchant(Enchantment.PROTECTION, currentProtectionLevel + 1, true);
        offHandItem.setItemMeta(offHandMeta);
        // 消耗和平之石
        itemInHand.setAmount(itemInHand.getAmount() - stonesNeeded);
        plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a使用\u00a79和平之石 \u00a7a，将 %s 的保护等级提升到了 \u00a79%d\u00a7a！",
            player.getName(), offHandMeta.hasDisplayName() ? offHandMeta.getDisplayName() : offHandItem.getType().toString(), currentProtectionLevel + 1));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    private void tryUseStoneOfEternity(Player player, ItemStack itemInHand, PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        // 只能对副手物品使用
        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        if (offHandItem.getType() == Material.AIR || offHandItem.getAmount() != 1) {
            player.sendMessage(tr("\u00a7c你必须在副手持有一个物品。"));
            return;
        }
        ItemMeta offHandMeta = offHandItem.getItemMeta();
        if (offHandMeta == null) {
            // This should never happen
            return;
        }
        if (!offHandMeta.hasEnchant(Enchantment.UNBREAKING)) {
            player.sendMessage(tr("\u00a7c你的副手物品没有耐久附魔。"));
            return;
        }
        int currentUnbreakingLevel = offHandMeta.getEnchantLevel(Enchantment.UNBREAKING);
        if (currentUnbreakingLevel >= 10) {
            player.sendMessage(tr("\u00a7c你的副手物品的耐久等级已经达到最高。"));
            return;
        }
        // 消耗对应数量的永久之石
        int stonesNeeded = currentUnbreakingLevel + 1;
        if (itemInHand.getAmount() < stonesNeeded) {
            player.sendMessage(String.format("\u00a7c你需要 %d 个永久之石来提升副手物品的耐久等级。", stonesNeeded));
            return;
        }
        // 提升耐久等级
        offHandMeta.addEnchant(Enchantment.UNBREAKING, currentUnbreakingLevel + 1, true);
        offHandItem.setItemMeta(offHandMeta);
        // 消耗永久之石
        itemInHand.setAmount(itemInHand.getAmount() - stonesNeeded);
        plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a使用\u00a7b永久之石 \u00a7a，将 %s 的耐久等级提升到了 \u00a7b%d\u00a7a！",
            player.getName(), offHandMeta.hasDisplayName() ? offHandMeta.getDisplayName() : offHandItem.getType().toString(), currentUnbreakingLevel + 1));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    public void tryUseStoneOfWealth(Player player, ItemStack itemInHand, PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        // 只能对副手物品使用
        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        if (offHandItem.getType() == Material.AIR || offHandItem.getAmount() != 1) {
            player.sendMessage(tr("\u00a7c你必须在副手持有一个物品。"));
            return;
        }
        ItemMeta offHandMeta = offHandItem.getItemMeta();
        if (offHandMeta == null) {
            // This should never happen
            return;
        }
        if (!offHandMeta.hasEnchant(Enchantment.FORTUNE)) {
            player.sendMessage(tr("\u00a7c你的副手物品没有时运附魔。"));
            return;
        }
        int currentFortuneLevel = offHandMeta.getEnchantLevel(Enchantment.FORTUNE);
        if (currentFortuneLevel >= 8) {
            player.sendMessage(tr("\u00a7c你的副手物品的时运等级已经达到最高。"));
            return;
        }
        // 消耗对应数量的财富之石
        int stonesNeeded = (currentFortuneLevel + 1) * (currentFortuneLevel + 1);
        if (itemInHand.getAmount() < stonesNeeded) {
            player.sendMessage(String.format("\u00a7c你需要 %d 个财富之石来提升副手物品的时运等级。", stonesNeeded));
            return;
        }
        // 提升时运等级
        offHandMeta.addEnchant(Enchantment.FORTUNE, currentFortuneLevel + 1, true);
        offHandItem.setItemMeta(offHandMeta);
        // 消耗财富之石
        itemInHand.setAmount(itemInHand.getAmount() - stonesNeeded);
        plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a使用\u00a76财富之石 \u00a7a，将 %s 的时运等级提升到了 \u00a76%d\u00a7a！",
            player.getName(), offHandMeta.hasDisplayName() ? offHandMeta.getDisplayName() : offHandItem.getType().toString(), currentFortuneLevel + 1));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }

    static boolean isPlantableBlock(Material mat) {
        // note: for simplicity, NOT ALL actually plantable blocks are included here
        return switch (mat) {
            case FARMLAND, DIRT, GRASS_BLOCK, MUD, MYCELIUM, PODZOL, MOSS_BLOCK -> true;
            case SAND, RED_SAND, SOUL_SAND -> true;
            case JUNGLE_LOG, JUNGLE_WOOD, STRIPPED_JUNGLE_LOG, STRIPPED_JUNGLE_WOOD -> true; // for cocoa beans
            default -> false;
        };
    }

    public boolean getStoneOfLifeFlag(Block block) {
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = getKeyStoneOfLifeBlocks(block);
        long[] yLayer = pdc.get(key, PersistentDataType.LONG_ARRAY);
        if (yLayer == null || yLayer.length != 4) {
            return false;
        }
        int id = (block.getX() & 0x0F) << 4 | (block.getZ() & 0x0F);  // 0-255
        int longIndex = id / 64;
        int bitIndex = id % 64;
        return (yLayer[longIndex] & (1L << bitIndex)) != 0;
    }
    public void setStoneOfLifeFlag(Block block, boolean flag) {
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = getKeyStoneOfLifeBlocks(block);
        long[] yLayer = pdc.get(key, PersistentDataType.LONG_ARRAY);
        if (yLayer == null || yLayer.length != 4) {
            if (!flag) {
                return;
            }
            yLayer = new long[4];
        }
        int id = (block.getX() & 0x0F) << 4 | (block.getZ() & 0x0F);  // 0-255
        int longIndex = id / 64;
        int bitIndex = id % 64;
        if (flag) {
            yLayer[longIndex] |= (1L << bitIndex);
        } else {
            yLayer[longIndex] &= ~(1L << bitIndex);
        }
        pdc.set(key, PersistentDataType.LONG_ARRAY, yLayer);
    }

    public int getSpecialBlendTick(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        NamespacedKey key = getKeySpecialBlendTick();
        return pdc.getOrDefault(key, PersistentDataType.INTEGER, 0);
    }

    public void setSpecialBlendTick(Entity e, int ticks) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        NamespacedKey key = getKeySpecialBlendTick();
        pdc.set(key, PersistentDataType.INTEGER, ticks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void updateStoneOfLifeOnBlockBreak(final BlockBreakEvent event) {
        // 当被破坏的方块有生命之石祝福时，移除祝福
        setStoneOfLifeFlag(event.getBlock(), false);
    }

    public void tryUseStoneOfLife(Player player, ItemStack itemInHand, PlayerInteractEvent event) {
        // 必须对方块使用
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        // 必须是自己岛上的方块
        Block block = event.getClickedBlock();
        IslandInfo ii = plugin.getIslandInfo(block.getLocation());
        if (ii == null || !ii.isMember(player)) {
            return;
        }
        Material mat = block.getType();
        // 确认交互的方块是可种植方块
        if (!isPlantableBlock(mat)) {
            player.sendMessage(tr("\u00a7c你只能对可种植方块使用生命之石。"));
            return;
        }
        // 在chunk pdc读取此方块
        if (getStoneOfLifeFlag(block)) {
            player.sendMessage(tr("\u00a7c这个方块已经被祝福过了。"));
            return;
        }
        // 祝福此方块
        setStoneOfLifeFlag(block, true);
        // 消耗生命之石
        itemInHand.setAmount(itemInHand.getAmount() - 1);
        player.sendMessage(tr("\u00a7a你用生命之石祝福了这个方块。"));
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
    }

    public void tryUpgradeWhipOfPastor(Player player, ItemStack itemInHand, PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // 确认主手有至少2个相同等级的牧者之鞭
        if (itemInHand.getAmount() < 2) {
            return;
        }
        event.setCancelled(true);
        ItemMeta meta = itemInHand.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return;
        }
        int level = meta.getEnchantLevel(Enchantment.LOOTING);
        int count = itemInHand.getAmount();
        if (level >= 10) {
            return;
        }
        // 获得一半数量、高一级的物品
        ItemStack newWhip = whipOfPastor(level + 1);
        newWhip.setAmount(count / 2);
        // 设置主手物品
        player.getInventory().setItemInMainHand(newWhip);
        // 如果数量是奇数，保留一个原物品
        if (count % 2 == 1) {
            ItemStack remainingWhip = whipOfPastor(level);
            remainingWhip.setAmount(1);
            if (player.getInventory().firstEmpty() == -1) {
                // 没有空位，掉落在地上
                player.getWorld().dropItemNaturally(player.getLocation(), remainingWhip);
            } else {
                player.getInventory().addItem(remainingWhip);
            }
        }
        // 如果合成的达到6级或以上，全服播报
        if (level + 1 >= 7) {
            plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a合成了 \u00a76lv%d\u00a7a 的牧者之鞭！",
                player.getName(), level + 1));
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        } else {
            player.sendMessage(String.format("\u00a7a你合成了 \u00a76lv%d\u00a7a 的牧者之鞭！", level + 1));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCropGrowth(final BlockGrowEvent event) {
        //plugin.getLogger().info(String.format("onCropGrowth called for block %s at %s", event.getBlock().getType().name(), event.getBlock().getLocation()));
        // 当作物生长时，检查其附着的方块是否被生命之石祝福过
        // 注意：甘蔗、竹子、仙人掌会触发BlockGrowEvent，形式为从AIR长出植物，因此需要特殊处理
        BlockState newState = event.getNewState();
        Block block = event.getBlock();
        Block toCheck, toGrow2 = null;
        switch (newState.getType()) {
            case COCOA -> {
                toCheck = block.getRelative(((Cocoa) newState.getBlockData()).getFacing());
            }
            case CACTUS, SUGAR_CANE -> {
                toGrow2 = block.getRelative(BlockFace.DOWN);
                toCheck = toGrow2.getRelative(BlockFace.DOWN);
                if (toCheck.getType() == newState.getType()) {
                    // 最多3格
                    toCheck = toCheck.getRelative(BlockFace.DOWN);
                }
            }
            case BAMBOO -> {
                // 无法处理，因为本就是一次概率生长
                return;
            }
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, SWEET_BERRY_BUSH -> {
                toCheck = block.getRelative(BlockFace.DOWN);
            }
            default -> {
                return;
            }
        }
        if (!isPlantableBlock(toCheck.getType()) || !getStoneOfLifeFlag(toCheck)) {
            return;
        }
        // 祝福生效，使作物立即成熟
        BlockData bd = newState.getBlockData();
        if (bd instanceof Ageable ageable) {
            ageable.setAge(ageable.getMaximumAge());
            event.getNewState().setBlockData(bd);
        }
        if (toGrow2 != null) {
            // 处理仙人掌、甘蔗第二格的生长
            Block finalToGrow = toGrow2;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                BlockData bd2 = finalToGrow.getBlockData();
                if (bd2 instanceof Ageable ageable2) {
                    ageable2.setAge(ageable2.getMaximumAge());
                    finalToGrow.setBlockData(bd2);
                }
            }, 1L
            );
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAltarInteract(final PlayerInteractEvent event) {
        // 当玩家右键点击祭坛时，提示其类型和计数器
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        // 只处理主手的交互，防止重复响应
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!plugin.getWorldManager().isSkyWorld(player.getWorld())) {
            return;
        }
        if (block != null &&
            block.getType() == Material.REINFORCED_DEEPSLATE &&
            event.getAction() == Action.RIGHT_CLICK_BLOCK) {

            PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
            Integer altarTypeOrdinal = pdc.get(getKeyAltarType(block), PersistentDataType.INTEGER);
            Long altarCounter = pdc.get(getKeyAltarCounter(block), PersistentDataType.LONG);
            if (altarTypeOrdinal != null && altarCounter != null) {
                event.setCancelled(true);
                // 如果此方块不是玩家的岛屿，则提示
                IslandInfo islandInfo = plugin.getIslandInfo(block.getLocation());
                if (islandInfo == null || !islandInfo.isMember(player)) {
                    player.sendMessage(tr("\u00a7c你只能在自己的岛屿上使用祭坛。"));
                    return;
                }
                AltarType altarType = AltarType.values()[altarTypeOrdinal];
                // 检查上面是信标
                Block up = block.getRelative(0, 1, 0);
                if (up.getType() != Material.BEACON) {
                    player.sendMessage(tr("\u00a7c祭坛上方必须有一个信标才能使用。"));
                    return;
                }

                switch (altarType) {
                    case HARVEST -> {
                        player.sendMessage(String.format("\u00a7a这是一个 \u00a7l\u00a72收获之祭坛\u00a7a，共奉献了价值 \u00a7l\u00a73%d \u00a7a的食物。", altarCounter));
                        // 玩家可以奉献食物,或者潜影盒装的食物
                        ItemStack itemInHand = player.getInventory().getItemInMainHand();
                        double foodValue = getHarvestFoodValue(itemInHand);
                        if (Tag.SHULKER_BOXES.isTagged(itemInHand.getType())) {
                            // 潜影盒，检查里面的物品
                            BlockStateMeta bsm = (BlockStateMeta) itemInHand.getItemMeta();
                            if (bsm == null) {
                                return;
                            }
                            BlockState bs = bsm.getBlockState();
                            if (!(bs instanceof ShulkerBox shulkerBox)) {
                                return;
                            }
                            offerToAltarOfHarvest(player, block, shulkerBox.getInventory());
                            bsm.setBlockState(bs);
                            itemInHand.setItemMeta(bsm);
                        } else if (foodValue > 0) {
                            offerToAltarOfHarvest(player, block, itemInHand);
                        } else {
                            // 提示目前供奉的各类食物的价值
                            long[] counters = pdc.getOrDefault(getKeyAltarHarvestCounters(block), PersistentDataType.LONG_ARRAY, new long[7]);
                            for (int i = 0; i < 7; i++) {
                                HarvestFoodType hft = HarvestFoodType.values()[i];
                                if (counters[i] > 0) {
                                    player.sendMessage(String.format("\u00a7a- %s: \u00a7l\u00a73%d",
                                        getHarvestFoodTypeName(hft), counters[i]));
                                }
                            }
                            // 提示当前的THRIVE等级
                            int thriveLevel = islandInfo.getAltarBuffLevel(AltarBuffType.THRIVE);
                            player.sendMessage(String.format("\u00a7a<茁壮> \u00a7l\u00a76lv. %d\u00a7a 提供家畜成长速度和鸡蛋产量+%d%%，繁殖恢复速度+%d%%，动物上限增加%d",
                                thriveLevel, thriveLevel * 3, thriveLevel, thriveLevel / 5 * 2));
                            // 提示玩家需要的物品
                            player.sendMessage(tr("\u00a7c奉献需要合适的食物或潜影盒。"));
                            return;
                        }
                    }
                    case WAR -> {
                        // player.sendMessage(tr("\u00a7a这是一个 \u00a7l\u00a74战争之祭坛\u00a7a，已被使用了 \u00a7l\u00a73%d \u00a7a次。", altarCounter));
                    }
                    case WEALTH -> {
                        // 向前兼容：如果islandInfo没有记录，则初始化
                        if (islandInfo.getAltarBuilt(AltarType.WEALTH) == 0) {
                            islandInfo.setAltarBuilt(AltarType.WEALTH, 1);
                        }
                        player.sendMessage(String.format("\u00a7a这是一个 \u00a7l\u00a76富饶之祭坛\u00a7a，共奉献了 \u00a7l\u00a73%d \u00a7a金币。", altarCounter));
                        // 玩家可奉献1个钻石+1000金币
                        ItemStack itemInHand = player.getInventory().getItemInMainHand();
                        if (itemInHand.getType() == Material.DIAMOND) {
                            EconomyHook hook = plugin.getHookManager().getEconomyHook().get();
                            double money = hook.getBalance(player);
                            if (money >= 1000) {
                                // 扣除物品和金币
                                itemInHand.setAmount(itemInHand.getAmount() - 1);
                                hook.withdrawPlayer(player, 1000);
                                // 增加祭坛计数器
                                altarCounter += 1000;
                                pdc.set(getKeyAltarCounter(block), PersistentDataType.LONG, altarCounter);
                                player.sendMessage(tr("\u00a7a你向富饶之祭坛奉献了 \u00a7l\u00a73一颗钻石 \u00a7a和 \u00a7l\u00a73 1000g\u00a7a。"));
                                player.getWorld().playSound(block.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.0f);
                                wealthReward(player);
                            } else {
                                player.sendMessage(tr("\u00a7c奉献需要一颗钻石和1000g。"));
                                return;
                            }
                        } else {
                            // 否则，提示玩家需要的物品
                            player.sendMessage(tr("\u00a7c奉献需要一颗钻石和1000g。"));
                        }
                    }
                    case SOUL -> {
                        // player.sendMessage(tr("\u00a7a这是一个 \u00a7l\u00a75灵魂之祭坛\u00a7a，已被使用了 \u00a7l\u00a73%d \u00a7a次。", altarCounter));
                    }
                    case EXPLORATION -> {
                        // player.sendMessage(tr("\u00a7a这是一个 \u00a7l\u00a79探索之祭坛\u00a7a，已被使用了 \u00a7l\u00a73%d \u00a7a次。", altarCounter));
                    }
                    default -> {
                        return;
                    }
                }
            } else {
                return;
            }
        }
    }

    private void harvestReward(Player player, long draws) {
        /*
            2% 牧者之鞭
            15% 美味果实
            35% 秘制特调
            3% 茁壮(如果没有满级)
            其余 生命之石
         */
        boolean hasRareItem = false;
        for (long i = 0; i < draws; i++) {
            double roll = RANDOM.nextDouble();
            if (roll < 0.02) {
                player.getWorld().dropItemNaturally(player.getLocation(), whipOfPastor());
                plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a从收获之祭坛获得了 \u00a76牧者之鞭\u00a7a！",
                    player.getName()));
                hasRareItem = true;
            } else if (roll < 0.17) {
                player.getWorld().dropItemNaturally(player.getLocation(), deliciousFruit());
            } else if (roll < 0.52) {
                player.getWorld().dropItemNaturally(player.getLocation(), specialBlend());
            } else if (roll < 0.55) {
                // 茁壮
                boolean canUpgrade = false;
                IslandInfo islandInfo = plugin.getIslandInfo(player);
                if (islandInfo != null) {
                    int currentLevel = islandInfo.getAltarBuffLevel(AltarBuffType.THRIVE);
                    if (currentLevel < 100) {
                        // 提升茁壮等级
                        islandInfo.setAltarBuffLevel(AltarBuffType.THRIVE, currentLevel + 1);
                        plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a通过收获之祭坛的奉献，将岛屿的 \u00a7l\u00a76<茁壮>\u00a7a 等级提升到了 \u00a7l\u00a76lv.%d\u00a7a！",
                            player.getName(), currentLevel + 1));
                        hasRareItem = true;
                        canUpgrade = true;
                    }
                }
                // 如果已经满级，改为生命之石
                if (!canUpgrade) {
                    player.getWorld().dropItemNaturally(player.getLocation(), stoneOfLife());
                }
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), stoneOfLife());
            }
        }
        if (hasRareItem) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        } else {
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.0f);
        }
    }

    private void wealthReward(Player player) {
        double roll = RANDOM.nextDouble();
        // 3% 和平之石，5% 永久之石，2% 财富之石
        // 钻石 蓝冰 绿宝石块 金块 各10%
        // 钻石块 远古残骸 下界合金 各1%
        // 下界合金块 0.1%
        // 青金石块 红石块 平分其余
        if (roll < 0.03) {
            plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a从富饶之祭坛获得了 \u00a79和平之石\u00a7a！",
                player.getName()));
            player.getWorld().dropItemNaturally(player.getLocation(), stoneOfPeace());
        } else if (roll < 0.08) {
            plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a从富饶之祭坛获得了 \u00a7b永久之石\u00a7a！",
                player.getName()));
            player.getWorld().dropItemNaturally(player.getLocation(), stoneOfEternity());
        } else if (roll < 0.10) {
            plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a从富饶之祭坛获得了 \u00a76财富之石\u00a7a！",
                player.getName()));
            player.getWorld().dropItemNaturally(player.getLocation(), stoneOfWealth());
        } else if (roll < 0.20) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.DIAMOND, 1));
        } else if (roll < 0.30) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.BLUE_ICE, 1));
        } else if (roll < 0.40) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.EMERALD_BLOCK, 1));
        } else if (roll < 0.50) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.GOLD_BLOCK, 1));
        } else if (roll < 0.51) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.DIAMOND_BLOCK, 1));
        } else if (roll < 0.52) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.ANCIENT_DEBRIS, 1));
        } else if (roll < 0.53) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.NETHERITE_INGOT, 1));
        } else if (roll < 0.531) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.NETHERITE_BLOCK, 1));
        } else if (roll < 0.7655) {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.LAPIS_BLOCK, 1));
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(Material.REDSTONE_BLOCK, 1));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBuildAltar(final PlayerInteractEvent event) {
        // 当右键点击一个下界合金块时，检查是否允许建造祭坛
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        // 只处理主手，防止重复响应
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!plugin.getWorldManager().isSkyWorld(player.getWorld())) {
            return;
        }
        if (block != null &&
            block.getType() == Material.NETHERITE_BLOCK &&
            event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            // 如果上方的方块不是信标，则忽略
            if (block.getRelative(0, 1, 0).getType() != Material.BEACON) {
                return;
            }
            // 如果此方块不是玩家的岛屿，则提示
            IslandInfo islandInfo = plugin.getIslandInfo(block.getLocation());
            if (islandInfo == null || !islandInfo.isMember(player)) {
                player.sendMessage(tr("\u00a7c你只能在自己的岛屿上建造祭坛。"));
                return;
            }
            PlayerInfo playerInfo = plugin.getPlayerInfo(player);
            // 如果玩家未完成进度，则提示
            if (plugin.getChallengeLogic().checkChallenge(playerInfo, "advanced") == 0) {
                player.sendMessage(tr("\u00a7c完成'海岛进阶'挑战后，才能建造祭坛。"));
                return;
            }
            // 检查玩家主手的物品
            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            // 如果是满足要求的物品，则成功建造祭坛
            if (itemInHand.getType() == Material.DIAMOND) {
                if (islandInfo.getAltarBuilt(AltarType.WEALTH) > 0) {
                    player.sendMessage(tr("\u00a7c你已经在你的岛屿上建造过 富饶之祭坛 了。"));
                    return;
                }
                // 尝试消耗5个钻石+50000金币
                if (itemInHand.getAmount() >= 5) {
                    plugin.getHookManager().getEconomyHook().ifPresent((hook) -> {
                        double money = hook.getBalance(player);
                        if (money >= 50000) {
                            // 扣除物品和金币
                            itemInHand.setAmount(itemInHand.getAmount() - 5);
                            hook.withdrawPlayer(player, 50000);
                            plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a在他们的岛屿上建造了 富饶之祭坛!", player.getName()));
                            buildAltar(islandInfo, block, AltarType.WEALTH);
                            // 产生音效
                            player.getWorld().playSound(block.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
                            // 在玩家脚下掉落三种石头
                            block.getWorld().dropItemNaturally(player.getLocation(), stoneOfPeace());
                            block.getWorld().dropItemNaturally(player.getLocation(), stoneOfEternity());
                            block.getWorld().dropItemNaturally(player.getLocation(), stoneOfWealth());
                            plugin.getChallengeLogic().completeChallengeIfNotDone(plugin.getPlayerInfo(player), "build_altar");
                        } else {
                            player.sendMessage(tr("\u00a7c需要5个钻石和50000g来建造 富饶之祭坛。"));
                            return;
                        }
                    });
                } else {
                    player.sendMessage(tr("\u00a7c需要5个钻石和50000g来建造 富饶之祭坛。"));
                    return;
                }
            } else if (itemInHand.getType() == Material.GOLDEN_APPLE) {
                if (islandInfo.getAltarBuilt(AltarType.HARVEST) > 0) {
                    player.sendMessage(tr("\u00a7c你已经在你的岛屿上建造过 收获之祭坛 了。"));
                    return;
                }
                // 尝试消耗64个金苹果
                if (itemInHand.getAmount() >= 64) {
                    // 扣除物品
                    itemInHand.setAmount(itemInHand.getAmount() - 64);
                    plugin.getServer().broadcastMessage(String.format("\u00a7e%s \u00a7a在他们的岛屿上建造了 收获之祭坛!", player.getName()));
                    buildAltar(islandInfo, block, AltarType.HARVEST);
                    // 产生音效
                    player.getWorld().playSound(block.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
                    player.getWorld().dropItemNaturally(player.getLocation(), scytheOfHarvest());
                    islandInfo.setAltarBuffLevel(AltarBuffType.THRIVE, 1);
                    plugin.getChallengeLogic().completeChallengeIfNotDone(plugin.getPlayerInfo(player), "build_altar");
                } else {
                    player.sendMessage(tr("\u00a7c需要64个金苹果来建造 收获之祭坛。"));
                    return;
                }
            } else {
                // 否则，提示玩家需要的物品
                player.sendMessage(tr("\u00a7c需要5个钻石和50000g来建造 富饶之祭坛。"));
                player.sendMessage(tr("\u00a7c需要64个金苹果来建造 收获之祭坛。"));
            }
        }
    }
}
