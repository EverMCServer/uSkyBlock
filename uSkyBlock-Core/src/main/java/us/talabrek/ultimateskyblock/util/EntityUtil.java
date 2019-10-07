package us.talabrek.ultimateskyblock.util;

import dk.lockfuglsang.minecraft.util.FormatUtil;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.NPC;

import java.util.ArrayList;
import java.util.List;

import com.meowj.langutils.lang.LanguageHelper;

/**
 * Handles various entity operations.
 */
public enum EntityUtil {;
    public static List<Animals> getAnimals(List<? extends Entity> creatures) {
        return getEntity(creatures, Animals.class);
    }

    public static List<Monster> getMonsters(List<? extends Entity> creatures) {
        return getEntity(creatures, Monster.class);
    }

    public static List<NPC> getNPCs(List<? extends Entity> creatures) {
        return getEntity(creatures, NPC.class);
    }

    public static <T> List<T> getEntity(List<? extends Entity> creatures, Class<T> typeClass) {
        List<T> list = new ArrayList<>();
        for (Entity e : creatures) {
            if (typeClass.isInstance(e)) {
                //noinspection unchecked
                list.add((T) e);
            }
        }
        return list;
    }

    public static String getEntityDisplayName(EntityType entityType) {
        return LanguageHelper.getEntityName(entityType, "zh_cn");
    }
}
