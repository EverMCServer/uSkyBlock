package us.talabrek.ultimateskyblock.enddragon;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dk.lockfuglsang.minecraft.util.ItemStackUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.PluginConfig;
import us.talabrek.ultimateskyblock.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Typed access to the {@code end.dragon} configuration section.
 */
@Singleton
public class EndDragonConfig {
    private static final Logger log = Logger.getLogger(EndDragonConfig.class.getName());
    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 8;

    private final PluginConfig pluginConfig;
    private final Map<Integer, DragonDifficulty> difficulties = new LinkedHashMap<>();

    private boolean enabled;
    private String altarRegion = "dragon_altar";
    private String worldName = "";
    private int timeoutSeconds = 3600;
    private int summoningTimeoutSeconds = 120;
    private int failCooldownSeconds = 30;
    private boolean keepArenaLoaded = true;
    private boolean protectArena = true;
    private boolean keepVanillaXp;
    private boolean keepVanillaDrops;

    @Inject
    public EndDragonConfig(@NotNull PluginConfig pluginConfig) {
        this.pluginConfig = pluginConfig;
        reload();
    }

    public void reload() {
        difficulties.clear();
        FileConfiguration config = pluginConfig.getYamlConfig();
        ConfigurationSection section = config.getConfigurationSection("end.dragon");
        enabled = Settings.end_enabled && section != null && section.getBoolean("enabled", false);
        if (section == null) {
            return;
        }

        altarRegion = section.getString("altar-region", "dragon_altar");
        if (altarRegion == null || altarRegion.isBlank()) {
            altarRegion = "dragon_altar";
        }
        worldName = section.getString("world", "");
        if (worldName == null || worldName.isBlank()) {
            worldName = Settings.general_worldName + "_the_end";
        }
        timeoutSeconds = Math.max(1, section.getInt("timeout-seconds", 3600));
        summoningTimeoutSeconds = Math.max(1, section.getInt("summoning-timeout-seconds", 120));
        failCooldownSeconds = Math.max(0, section.getInt("fail-cooldown-seconds", 30));
        keepArenaLoaded = section.getBoolean("keep-arena-loaded", true);
        protectArena = section.getBoolean("protect-arena", true);
        keepVanillaXp = section.getBoolean("keep-vanilla-xp", false);
        keepVanillaDrops = section.getBoolean("keep-vanilla-drops", false);

        ConfigurationSection difficultySection = section.getConfigurationSection("difficulties");
        for (int level = MIN_LEVEL; level <= MAX_LEVEL; level++) {
            ConfigurationSection levelSection = difficultySection != null
                ? difficultySection.getConfigurationSection(String.valueOf(level))
                : null;
            difficulties.put(level, readDifficulty(level, levelSection));
        }
    }

    private DragonDifficulty readDifficulty(int level, @Nullable ConfigurationSection section) {
        int defaultCost = 4 << (level - 1);
        int cost = section != null ? section.getInt("cost", defaultCost) : defaultCost;
        cost = Math.max(1, cost);
        String displayName = section != null ? section.getString("display-name", "难度 " + level) : "难度 " + level;
        double maxHealth = section != null ? section.getDouble("max-health", 200.0 + (level - 1) * 100.0) : 200.0 + (level - 1) * 100.0;
        maxHealth = Math.max(1.0, maxHealth);
        double damageMultiplier = section != null
            ? section.getDouble("damage-multiplier", 1.0 + (level - 1) * 0.1)
            : 1.0 + (level - 1) * 0.1;
        damageMultiplier = Math.max(0.0, damageMultiplier);
        int levelTimeout = section != null ? section.getInt("timeout-seconds", timeoutSeconds) : timeoutSeconds;

        List<ItemStack> itemRewards = List.of();
        int currency = 0;
        int xp = 0;
        List<String> commands = List.of();
        if (section != null) {
            ConfigurationSection reward = section.getConfigurationSection("reward");
            if (reward != null) {
                List<String> itemSpecs = new ArrayList<>(reward.getStringList("items"));
                if (itemSpecs.isEmpty()) {
                    String single = reward.getString("items", null);
                    if (single != null && !single.isBlank()) {
                        itemSpecs.add(single);
                    }
                }
                if (!itemSpecs.isEmpty()) {
                    try {
                        itemRewards = ItemStackUtil.createItemList(itemSpecs);
                    } catch (IllegalArgumentException e) {
                        log.log(Level.WARNING, "Invalid end dragon reward items for level " + level + ": " + itemSpecs, e);
                    }
                }
                currency = Math.max(0, reward.getInt("currency", 0));
                xp = Math.max(0, reward.getInt("xp", 0));
                commands = List.copyOf(reward.getStringList("commands"));
            }
        }
        return new DragonDifficulty(level, cost, displayName, maxHealth, damageMultiplier,
            Math.max(1, levelTimeout), itemRewards, currency, xp, commands);
    }

    public boolean enabled() {
        return enabled;
    }

    @NotNull
    public String altarRegion() {
        return altarRegion;
    }

    @NotNull
    public String worldName() {
        return worldName;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public int summoningTimeoutSeconds() {
        return summoningTimeoutSeconds;
    }

    public int failCooldownSeconds() {
        return failCooldownSeconds;
    }

    public boolean keepArenaLoaded() {
        return keepArenaLoaded;
    }

    public boolean protectArena() {
        return protectArena;
    }

    public boolean keepVanillaXp() {
        return keepVanillaXp;
    }

    public boolean keepVanillaDrops() {
        return keepVanillaDrops;
    }

    @Nullable
    public DragonDifficulty difficulty(int level) {
        return difficulties.get(level);
    }

    @NotNull
    public Map<Integer, DragonDifficulty> difficulties() {
        return Map.copyOf(difficulties);
    }
}
