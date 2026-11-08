package us.talabrek.ultimateskyblock.enddragon;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.util.UUID;

/**
 * Stores exactly the four active-dragon fields in the end world's persistent data container.
 *
 * <p>No file is created. The world PDC is part of the world data and is saved with the map.</p>
 */
@Singleton
public class DragonFightPersistence {
    private final NamespacedKey dragonUuidKey;
    private final NamespacedKey initiatorKey;
    private final NamespacedKey difficultyKey;
    private final NamespacedKey lastDamageTickKey;

    @Inject
    public DragonFightPersistence(@NotNull uSkyBlock plugin) {
        this.dragonUuidKey = new NamespacedKey(plugin, "_dragon_uuid");
        this.initiatorKey = new NamespacedKey(plugin, "_dragon_initiator");
        this.difficultyKey = new NamespacedKey(plugin, "_dragon_difficulty");
        this.lastDamageTickKey = new NamespacedKey(plugin, "_dragon_last_damage_tick");
    }

    @Nullable
    public DragonFightData loadActive(@Nullable World world) {
        if (world == null) {
            return null;
        }
        PersistentDataContainer container = world.getPersistentDataContainer();
        UUID initiator = parseUuid(container.get(initiatorKey, PersistentDataType.STRING));
        if (initiator == null) {
            return null;
        }
        Integer difficulty = container.get(difficultyKey, PersistentDataType.INTEGER);
        Long lastDamageTick = container.get(lastDamageTickKey, PersistentDataType.LONG);
        UUID dragonUuid = parseUuid(container.get(dragonUuidKey, PersistentDataType.STRING));
        return new DragonFightData(
            initiator,
            difficulty != null ? Math.max(1, Math.min(8, difficulty)) : 1,
            dragonUuid,
            lastDamageTick != null ? lastDamageTick : world.getGameTime()
        );
    }

    public void saveActive(@Nullable World world, @Nullable DragonFightData data) {
        if (world == null) {
            return;
        }
        PersistentDataContainer container = world.getPersistentDataContainer();
        if (data == null) {
            container.remove(dragonUuidKey);
            container.remove(initiatorKey);
            container.remove(difficultyKey);
            container.remove(lastDamageTickKey);
            return;
        }
        container.remove(initiatorKey);
        container.set(initiatorKey, PersistentDataType.STRING, data.initiator().toString());
        container.set(difficultyKey, PersistentDataType.INTEGER, data.difficulty());
        container.set(lastDamageTickKey, PersistentDataType.LONG, data.lastDamageTick());
        container.remove(dragonUuidKey);
        if (data.dragonUuid() != null) {
            container.set(dragonUuidKey, PersistentDataType.STRING, data.dragonUuid().toString());
        }
    }

    @Nullable
    private static UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
