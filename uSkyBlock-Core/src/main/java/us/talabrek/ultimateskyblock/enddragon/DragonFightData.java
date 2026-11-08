package us.talabrek.ultimateskyblock.enddragon;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Minimal active challenge record. The four values are the only persisted dragon state.
 *
 * @param initiator     UUID of the player who started the challenge
 * @param difficulty    chosen difficulty (1..8)
 * @param dragonUuid    UUID of the active dragon, or null while the vanilla summoning sequence is still running
 * @param lastDamageTick world game-time tick of the last valid hit; set to the summon time at start
 */
public record DragonFightData(
    @NotNull UUID initiator,
    int difficulty,
    @Nullable UUID dragonUuid,
    long lastDamageTick
) {
    public DragonFightData withDragon(@Nullable UUID newDragonUuid) {
        return new DragonFightData(initiator, difficulty, newDragonUuid, lastDamageTick);
    }

    public DragonFightData withLastDamageTick(long tick) {
        return new DragonFightData(initiator, difficulty, dragonUuid, tick);
    }
}
