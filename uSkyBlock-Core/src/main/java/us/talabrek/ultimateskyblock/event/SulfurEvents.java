package us.talabrek.ultimateskyblock.event;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.JukeboxSong;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static dk.lockfuglsang.minecraft.po.I18nUtil.tr;

/**
 * Minecraft 26.2 "Chaos Cubed" 内容机制:
 * <ul>
 *     <li>史莱姆转化: 手持硫磺方块右键史莱姆或岩浆怪, 消耗 1 硫磺将其转化为大型硫方怪
 *         (李芒果式生物转化; 之后依靠原版机制自我繁殖: 死亡分裂、黏液球喂养成长;
 *         硫方怪同为 Slime 子类, 转化时排除以免二次转化)</li>
 *     <li>间歇泉转化朱砂: 放入由烈性硫磺驱动的间歇泉水柱中的红石块,
 *         10-20 秒后被转化为朱砂 (替代合成配方, 结合新版本间歇泉玩法)</li>
 *     <li>唱片机喂食与音乐爱好者: 硫方怪生成时 5% 概率成为音乐爱好者 (持续音符粒子),
 *         可被喂入唱片机 (数据包将唱片机加入 #minecraft:sulfur_cube_swallowable,
 *         利用原版成年怪吞方块机制, 插件不消耗物品), 被击打时播放随机唱片
 *         (全部 12 张苦力怕唱片, 或低概率 bounce), 播放完成后掉落所播唱片。
 *         音乐爱好者必须先吞过唱片机 (SwallowItemEvent 标记) 才能触发播放;
 *         播放中若被击杀或失去唱片机 (被其它方块顶替/被剪刀剪下), 立即终止播放且不落唱片</li>
 * </ul>
 */
@Singleton
public class SulfurEvents implements Listener {
    private static final int SMALL_SIZE = 1;          // 仅两个尺寸: 小/大, 沿用 onSlimeConvert 的"2 = 大"预期
    private static final int TICK_PERIOD = 10;        // 音乐爱好者扫描任务周期
    private static final double MUSIC_LOVER_CHANCE = 0.05;
    private static final double BOUNCE_CHANCE = 0.02; // bounce 唱片概率较低, 取 2%
    private static final float MUSIC_VOLUME = 4.0f;   // 与原版唱片机播放音量一致
    private static final double NOTIFY_RADIUS = 16.0;
    /** 停声半径: 音量 4.0 的可闻距离上限 max(volume,1)*16=64, 且覆盖默认怪物追踪范围 48
     *  (实体声源包只发给追踪该实体的玩家, 顶替/剪毛时实体存活需主动停声) */
    private static final double SOUND_STOP_RADIUS = 64.0;

    private record Disc(JukeboxSong song, Material item) {}

    /** 苦力怕可掉落唱片池 (全部 12 张, 作为刷/卖唱片的全量替代途径) */
    private static final List<Disc> CREEPER_DISCS = List.of(
            new Disc(JukeboxSong.THIRTEEN, Material.MUSIC_DISC_13),
            new Disc(JukeboxSong.CAT, Material.MUSIC_DISC_CAT),
            new Disc(JukeboxSong.BLOCKS, Material.MUSIC_DISC_BLOCKS),
            new Disc(JukeboxSong.CHIRP, Material.MUSIC_DISC_CHIRP),
            new Disc(JukeboxSong.FAR, Material.MUSIC_DISC_FAR),
            new Disc(JukeboxSong.MALL, Material.MUSIC_DISC_MALL),
            new Disc(JukeboxSong.MELLOHI, Material.MUSIC_DISC_MELLOHI),
            new Disc(JukeboxSong.STAL, Material.MUSIC_DISC_STAL),
            new Disc(JukeboxSong.STRAD, Material.MUSIC_DISC_STRAD),
            new Disc(JukeboxSong.WARD, Material.MUSIC_DISC_WARD),
            new Disc(JukeboxSong.ELEVEN, Material.MUSIC_DISC_11),
            new Disc(JukeboxSong.WAIT, Material.MUSIC_DISC_WAIT));
    private static final Disc BOUNCE_DISC = new Disc(JukeboxSong.BOUNCE, Material.MUSIC_DISC_BOUNCE);

    private final uSkyBlock plugin;
    private final boolean geyserCinnabarEnabled;
    private final boolean musicSulfurCubeEnabled;
    private final NamespacedKey musicLoverKey;
    private final NamespacedKey ateJukeboxKey;
    private final NamespacedKey playingSongKey;
    private final NamespacedKey playTicksLeftKey;

    @Inject
    public SulfurEvents(@NotNull uSkyBlock plugin) {
        this.plugin = plugin;
        this.geyserCinnabarEnabled = plugin.getConfig().getBoolean("options.extras.geyserCinnabar", true);
        this.musicSulfurCubeEnabled = plugin.getConfig().getBoolean("options.extras.musicSulfurCube", true);
        this.musicLoverKey = new NamespacedKey(plugin, "music_lover");
        this.ateJukeboxKey = new NamespacedKey(plugin, "ate_jukebox");
        this.playingSongKey = new NamespacedKey(plugin, "playing_song");
        this.playTicksLeftKey = new NamespacedKey(plugin, "play_ticks_left");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickMusicCubes, 20L, TICK_PERIOD);
    }

    /**
     * 音乐爱好者掷骰: 硫方怪生成时 5% 概率打上标记 (覆盖转化/分裂/自然生成全部路径)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSulfurCubeSpawn(EntitySpawnEvent event) {
        if (!musicSulfurCubeEnabled
                || !(event.getEntity() instanceof SulfurCube cube)
                || !plugin.getWorldManager().isSkyAssociatedWorld(cube.getWorld())) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < MUSIC_LOVER_CHANCE) {
            cube.getPersistentDataContainer().set(musicLoverKey, PersistentDataType.BOOLEAN, true);
            cube.setPersistent(true); // 防自然消失; 分裂后代不继承该标记, 各自重新掷骰
        }
    }

    /**
     * 唱片机喂食提示: 物品消耗由原版成年怪吞方块机制完成
     * (数据包已把唱片机加入 #minecraft:sulfur_cube_swallowable), 插件只发提示。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFeedJukebox(PlayerInteractEntityEvent event) {
        if (!musicSulfurCubeEnabled) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(player.getWorld())) {
            return;
        }
        if (!(event.getRightClicked() instanceof SulfurCube cube)) {
            return;
        }
        ItemStack item = player.getInventory().getItem(event.getHand());
        if (item == null || item.getType() != Material.JUKEBOX) {
            return;
        }
        if (cube.getSize() == SMALL_SIZE) {
            plugin.notifyPlayer(player, tr("§7This sulfur cube is too small to swallow a jukebox."));
            return;
        }
        if (Boolean.TRUE.equals(cube.getPersistentDataContainer().get(musicLoverKey, PersistentDataType.BOOLEAN))) {
            plugin.notifyPlayer(player, tr("§dThe sulfur cube joyfully swallows the jukebox — it loves music! Try hitting it."));
            Location loc = cube.getLocation().add(0, cube.getHeight() / 2.0, 0);
            player.getWorld().spawnParticle(Particle.NOTE, loc, 12, 0.5, 0.6, 0.5, 0, null);
        } else {
            plugin.notifyPlayer(player, tr("§7The sulfur cube swallows the jukebox, but doesn't seem to like music at all. Some rare sulfur cubes love it, though!"));
        }
    }

    /**
     * 击打触发播放: 用 Paper 的 PrePlayerAttackEntityEvent (任何伤害逻辑之前触发) 而非伤害事件,
     * 因为吞了方块的硫方怪免疫近战/弹射物伤害 (#sulfur_cube_with_block_immune_to), 伤害事件
     * 不一定能到达; 该攻击事件对免疫实体同样触发。与史莱姆转化相同的岛屿归属限制,
     * 访客击打静默忽略; 播放期间不可重复触发, 播放完成后由 tickMusicCubes 掉落唱片。
     * 音乐爱好者必须先吞过唱片机 (ate_jukebox 标记) 才能触发播放。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMusicCubeAttack(PrePlayerAttackEntityEvent event) {
        if (!musicSulfurCubeEnabled
                || !(event.getAttacked() instanceof SulfurCube cube)
                || cube.isDead()) { // 击打时可能已被打死, 避免把状态写到死实体上
            return;
        }
        Player attacker = event.getPlayer();
        if (!plugin.getWorldManager().isSkyAssociatedWorld(cube.getWorld())) {
            return;
        }
        if (!attacker.hasPermission("usb.mod.bypassprotection") && !attacker.isOp()
                && !plugin.playerIsOnIsland(attacker)) {
            return; // 访客击打不触发 (静默, 与 GriefEvents 的访客伤害保护语义一致)
        }
        PersistentDataContainer pdc = cube.getPersistentDataContainer();
        if (!Boolean.TRUE.equals(pdc.get(musicLoverKey, PersistentDataType.BOOLEAN))) {
            return;
        }
        if (!Boolean.TRUE.equals(pdc.get(ateJukeboxKey, PersistentDataType.BOOLEAN))) {
            plugin.notifyPlayer(attacker, tr("§7This sulfur cube loves music, but it hasn't swallowed a jukebox yet."));
            return;
        }
        if (isPlaying(pdc)) {
            plugin.notifyPlayer(attacker, tr("§7The sulfur cube is already listening to music..."));
            return;
        }
        Disc disc = rollDisc();
        pdc.set(playingSongKey, PersistentDataType.STRING, disc.item().name());
        pdc.set(playTicksLeftKey, PersistentDataType.INTEGER, Math.round(disc.song().getLengthInSeconds() * 20));
        // 实体绑定播放: 声源跟随硫方怪, 被击杀时声音随实体消失
        cube.getWorld().playSound(cube, disc.song().getSound(), SoundCategory.RECORDS, MUSIC_VOLUME, 1f);
        plugin.notifyPlayer(attacker, tr("§dThe sulfur cube starts grooving to the music!"));
    }

    /**
     * 吞唱片机标记: 原版成年怪吞方块机制触发 Paper 的 SwallowItemEvent。
     * 吞下唱片机即打上 ate_jukebox 标记 (播放/掉落交互的前提); 若播放中吞下
     * 非唱片机方块顶掉旧唱片机, 则清除标记并立即终止播放 (不落唱片)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSulfurCubeSwallow(SulfurCubeSwallowItemEvent event) {
        if (!musicSulfurCubeEnabled
                || !plugin.getWorldManager().isSkyAssociatedWorld(event.getEntity().getWorld())) {
            return;
        }
        SulfurCube cube = event.getEntity();
        PersistentDataContainer pdc = cube.getPersistentDataContainer();
        ItemStack newItem = event.getNewItem();
        if (newItem != null && newItem.getType() == Material.JUKEBOX) {
            pdc.set(ateJukeboxKey, PersistentDataType.BOOLEAN, true);
            return;
        }
        ItemStack oldItem = event.getOldItem();
        if (oldItem != null && oldItem.getType() == Material.JUKEBOX
                && Boolean.TRUE.equals(pdc.get(ateJukeboxKey, PersistentDataType.BOOLEAN))) {
            loseJukebox(cube, pdc);
        }
    }

    /**
     * 剪毛取出吞下的方块: 硫方怪为 Shearable (26.2 原版机制), 玩家用剪刀剪下时
     * 原版会将其吞下的方块作为剪毛掉落取出 — 该路径不经过 SwallowItemEvent,
     * 必须在此同步清除 ate_jukebox 标记; 播放中被剪则立即终止且不落唱片。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSulfurCubeShear(PlayerShearEntityEvent event) {
        if (!musicSulfurCubeEnabled
                || !(event.getEntity() instanceof SulfurCube cube)
                || !plugin.getWorldManager().isSkyAssociatedWorld(cube.getWorld())) {
            return;
        }
        PersistentDataContainer pdc = cube.getPersistentDataContainer();
        if (Boolean.TRUE.equals(pdc.get(ateJukeboxKey, PersistentDataType.BOOLEAN))) {
            loseJukebox(cube, pdc);
        }
    }

    /**
     * 唱片机被取出 (被其它方块顶替/被剪刀剪下): 清除 ate_jukebox 标记;
     * 若正在播放则立即终止 (停声+提示), 不经过 finishPlay 故不落唱片。
     */
    private void loseJukebox(SulfurCube cube, PersistentDataContainer pdc) {
        pdc.remove(ateJukeboxKey);
        if (!isPlaying(pdc)) {
            return;
        }
        stopPlay(cube, pdc);
        for (Entity nearby : cube.getWorld().getNearbyEntities(cube.getLocation(),
                NOTIFY_RADIUS, NOTIFY_RADIUS, NOTIFY_RADIUS)) {
            if (nearby instanceof Player player) {
                plugin.notifyPlayer(player, tr("§7The sulfur cube lost its jukebox — the music stops abruptly."));
            }
        }
    }

    /**
     * 播放中被击杀: 立即终止播放 (停声、清除状态), 不再掉落唱片。
     * 实体绑定声源会随实体消失, 这里再对附近玩家显式停声作为双保险。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSulfurCubeDeath(EntityDeathEvent event) {
        if (!musicSulfurCubeEnabled
                || !(event.getEntity() instanceof SulfurCube cube)
                || !plugin.getWorldManager().isSkyAssociatedWorld(cube.getWorld())) {
            return;
        }
        PersistentDataContainer pdc = cube.getPersistentDataContainer();
        if (isPlaying(pdc)) {
            stopPlay(cube, pdc);
        }
    }

    /**
     * 全局扫描: 音乐爱好者持续产生音符粒子; 播放中的唱片倒计时, 播放完成掉落唱片。
     * 仿 AltarEvents 构造器中的全量扫描形态, 只扫描已加载区块内的实体 (区块卸载时
     * 播放倒计时自然暂停, 重新加载后继续, PDC 状态保留)。
     */
    private void tickMusicCubes() {
        if (!musicSulfurCubeEnabled) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            if (!plugin.getWorldManager().isSkyAssociatedWorld(world)) {
                continue;
            }
            for (SulfurCube cube : world.getEntitiesByClass(SulfurCube.class)) {
                PersistentDataContainer pdc = cube.getPersistentDataContainer();
                if (!Boolean.TRUE.equals(pdc.get(musicLoverKey, PersistentDataType.BOOLEAN))) {
                    continue;
                }
                boolean playing = isPlaying(pdc);
                Integer ticksLeft = pdc.get(playTicksLeftKey, PersistentDataType.INTEGER);
                Location loc = cube.getLocation().add(0, cube.getHeight() / 2.0, 0);
                // 26.2 的 NOTE 粒子不携带数据, data 必须传 null (传 Note 会抛 IllegalArgumentException)
                world.spawnParticle(Particle.NOTE, loc, playing ? 5 : 2, 0.3, 0.4, 0.3, 0, null);
                if (!playing || ticksLeft == null) {
                    continue;
                }
                int remaining = ticksLeft - TICK_PERIOD;
                if (remaining > 0) {
                    pdc.set(playTicksLeftKey, PersistentDataType.INTEGER, remaining);
                } else {
                    finishPlay(world, cube, pdc);
                }
            }
        }
    }

    private void finishPlay(World world, SulfurCube cube, PersistentDataContainer pdc) {
        String songName = pdc.get(playingSongKey, PersistentDataType.STRING);
        pdc.remove(playingSongKey);
        pdc.remove(playTicksLeftKey);
        Disc played = songName == null ? null : findDisc(Material.matchMaterial(songName));
        if (played == null) {
            return;
        }
        world.dropItemNaturally(cube.getLocation(), new ItemStack(played.item()));
        for (Entity nearby : world.getNearbyEntities(cube.getLocation(), NOTIFY_RADIUS, NOTIFY_RADIUS, NOTIFY_RADIUS)) {
            if (nearby instanceof Player player) {
                plugin.notifyPlayer(player, tr("§dThe sulfur cube drops a music disc!"));
            }
        }
    }

    /**
     * 终止播放: 清除播放状态并对附近玩家停掉唱片声。
     * 被击杀或失去唱片机时调用, 不经过 finishPlay, 因此不会掉落唱片。
     * 停声半径取 SOUND_STOP_RADIUS: 实体声源包发给所有追踪该实体的玩家,
     * 顶替/剪毛时实体存活, 客户端不会自动停声, 必须覆盖完整可闻范围。
     */
    private void stopPlay(SulfurCube cube, PersistentDataContainer pdc) {
        String songName = pdc.get(playingSongKey, PersistentDataType.STRING);
        pdc.remove(playingSongKey);
        pdc.remove(playTicksLeftKey);
        Disc played = songName == null ? null : findDisc(Material.matchMaterial(songName));
        if (played == null) {
            return;
        }
        for (Entity nearby : cube.getWorld().getNearbyEntities(cube.getLocation(),
                SOUND_STOP_RADIUS, SOUND_STOP_RADIUS, SOUND_STOP_RADIUS)) {
            if (nearby instanceof Player player) {
                player.stopSound(played.song().getSound(), SoundCategory.RECORDS);
            }
        }
    }

    private boolean isPlaying(PersistentDataContainer pdc) {
        Integer ticksLeft = pdc.get(playTicksLeftKey, PersistentDataType.INTEGER);
        return ticksLeft != null && ticksLeft > 0;
    }

    private static Disc findDisc(Material item) {
        if (item == null) {
            return null;
        }
        if (item == BOUNCE_DISC.item()) {
            return BOUNCE_DISC;
        }
        for (Disc disc : CREEPER_DISCS) {
            if (disc.item() == item) {
                return disc;
            }
        }
        return null;
    }

    private static Disc rollDisc() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < BOUNCE_CHANCE) {
            return BOUNCE_DISC;
        }
        return CREEPER_DISCS.get(random.nextInt(CREEPER_DISCS.size()));
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
        // Slime 家族 (史莱姆/岩浆怪) 均可转化, 但硫方怪同为 Slime 子类, 需排除以免二次转化
        if (!(clicked instanceof Slime slime) || clicked instanceof SulfurCube) {
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
