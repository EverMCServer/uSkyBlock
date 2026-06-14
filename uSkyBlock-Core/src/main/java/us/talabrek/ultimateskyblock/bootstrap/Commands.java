package us.talabrek.ultimateskyblock.bootstrap;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import us.talabrek.ultimateskyblock.chat.IslandTalkCommand;
import us.talabrek.ultimateskyblock.chat.PartyTalkCommand;
import us.talabrek.ultimateskyblock.command.AdminCommand;
import us.talabrek.ultimateskyblock.command.ChallengeCommand;
import us.talabrek.ultimateskyblock.command.IslandCommand;
import us.talabrek.ultimateskyblock.command.SlimeCommand;

import static java.util.Objects.requireNonNull;

@Singleton
public class Commands {

    private final IslandCommand islandCommand;
    private final ChallengeCommand challengeCommand;
    private final AdminCommand adminCommand;
    private final IslandTalkCommand islandTalkCommand;
    private final PartyTalkCommand partyTalkCommand;
    private final SlimeCommand slimeCommand;

    @Inject
    public Commands(
        @NotNull IslandCommand islandCommand,
        @NotNull ChallengeCommand challengeCommand,
        @NotNull AdminCommand adminCommand,
        @NotNull IslandTalkCommand islandTalkCommand,
        @NotNull PartyTalkCommand partyTalkCommand,
        @NotNull SlimeCommand slimeCommand
    ) {
        this.islandCommand = islandCommand;
        this.challengeCommand = challengeCommand;
        this.adminCommand = adminCommand;
        this.islandTalkCommand = islandTalkCommand;
        this.partyTalkCommand = partyTalkCommand;
        this.slimeCommand = slimeCommand;
    }

    public void registerCommands(JavaPlugin plugin) {
        requireNonNull(plugin.getCommand("island")).setExecutor(islandCommand);
        requireNonNull(plugin.getCommand("challenges")).setExecutor(challengeCommand);
        requireNonNull(plugin.getCommand("usb")).setExecutor(adminCommand);
        requireNonNull(plugin.getCommand("islandtalk")).setExecutor(islandTalkCommand);
        requireNonNull(plugin.getCommand("partytalk")).setExecutor(partyTalkCommand);
        requireNonNull(plugin.getCommand("slime")).setExecutor(slimeCommand);
    }
}
