package us.talabrek.ultimateskyblock.bootstrap;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.challenge.ChallengeRankingLogic;
import us.talabrek.ultimateskyblock.uSkyBlock;

@Singleton
public class SkyblockApp {

    private final Services services;
    private final Commands commands;
    private final Listeners listeners;
    private final ChallengeRankingLogic rankingLogic;

    @Inject
    public SkyblockApp(@NotNull Services services, @NotNull Commands commands, @NotNull Listeners listeners,
                       @NotNull ChallengeRankingLogic rankingLogic) {
        this.services = services;
        this.commands = commands;
        this.listeners = listeners;
        this.rankingLogic = rankingLogic;
    }


    public void startup(uSkyBlock plugin) {
        services.startup(plugin);
    }

    public void delayedEnable(uSkyBlock plugin) {
        services.delayedEnable(plugin);

        // do these really have to be delayed?
        commands.registerCommands(plugin);
        listeners.registerListeners(plugin);

        // Rebuild the in-memory challenge leaderboard from the on-disk completion files
        plugin.getScheduler().async(rankingLogic::rebuild);
    }

    public void shutdown(uSkyBlock plugin) {
        Bukkit.getScheduler().cancelTasks(plugin);
        listeners.unregisterListeners(plugin);
        services.shutdown(plugin);
    }
}
