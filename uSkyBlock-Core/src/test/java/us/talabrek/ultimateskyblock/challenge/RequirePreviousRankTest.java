package us.talabrek.ultimateskyblock.challenge;

import dk.lockfuglsang.minecraft.po.I18nUtil;
import dk.lockfuglsang.minecraft.util.BukkitServerMock;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import us.talabrek.ultimateskyblock.player.PlayerInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@code requirePreviousRank: false} in the server's runtime
 * challenges.yml is actually honored by {@link ChallengeDefaults} and
 * {@link Rank#getMissingRequirements(PlayerInfo)}.
 */
public class RequirePreviousRankTest {

    private static File findRuntimeChallenges() {
        for (String p : new String[]{
            "d:/code/evermc/uSkyBlock/uSkyBlock/challenges.yml",
            "../uSkyBlock/challenges.yml",
            "uSkyBlock/challenges.yml"}) {
            File f = new File(p);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private ItemFactory itemFactory;

    @Before
    public void beforeEach() throws Exception {
        BukkitServerMock.setupServerMock();
        // Paper 26.2: constructing a real ItemStack in unit tests requires RegistryAccess
        // (server-only). Stub displayItem creation like ChallengeFactoryTest does.
        itemFactory = Bukkit.getItemFactory();
        reset(itemFactory);
        doReturn(mock(ItemStack.class)).when(itemFactory).createItemStack(any());
        I18nUtil.initialize(new File("."), Locale.ENGLISH);
    }

    @After
    public void afterEach() {
        reset(itemFactory);
    }

    @Test
    public void runtimeChallenges_readsRequirePreviousRankFalse() throws Exception {
        File configFile = findRuntimeChallenges();
        Assume.assumeTrue("runtime challenges.yml not found (skip)", configFile != null);
        YamlConfiguration config = new YamlConfiguration();
        config.load(configFile);
        ChallengeDefaults defaults = ChallengeFactory.createDefaults(config.getRoot());
        assertThat("runtime challenges.yml should yield requirePreviousRank=false",
            defaults.requirePreviousRank, is(false));
    }

    @Test
    public void runtimeChallenges_rankWithRequires_unlocksWhenNamedGatesMet() throws Exception {
        File configFile = findRuntimeChallenges();
        Assume.assumeTrue("runtime challenges.yml not found (skip)", configFile != null);
        YamlConfiguration config = new YamlConfiguration();
        config.load(configFile);
        ChallengeDefaults defaults = ChallengeFactory.createDefaults(config.getRoot());
        Map<String, Rank> ranks = ChallengeFactory.createRankMap(config.getConfigurationSection("ranks"), defaults);

        // ClassTutorial (first rank, no requires) must be available out of the box.
        Rank tutorial = ranks.get("ClassTutorial");
        assertThat(tutorial.getMissingRequirements(freshPlayer(1)).isEmpty(), is(true));

        // ClassMain gates on 7 named tutorial challenges. With those completed the
        // rank must unlock even though the player completed NOTHING else of the
        // previous rank - proving no hidden leeway check leaks through.
        try (MockedStatic<uSkyBlock> mockedStatic = Mockito.mockStatic(uSkyBlock.class)) {
            uSkyBlock plugin = mock(uSkyBlock.class);
            ChallengeLogic challengeLogic = mock(ChallengeLogic.class);
            when(challengeLogic.getChallenge(anyString())).thenReturn(null);
            when(plugin.getChallengeLogic()).thenReturn(challengeLogic);
            mockedStatic.when(uSkyBlock::getInstance).thenReturn(plugin);

            Rank main = ranks.get("ClassMain");
            List<String> missing = main.getMissingRequirements(freshPlayer(1));
            assertThat("rank should unlock once the named gates are met: " + missing,
                missing.isEmpty(), is(true));

            // Nothing completed: named gates listed, but no leeway message.
            List<String> freshMissing = main.getMissingRequirements(freshPlayer(0));
            assertThat(freshMissing.isEmpty(), is(false));
            for (String line : freshMissing) {
                assertThat("leeway message leaked despite requirePreviousRank=false: " + freshMissing,
                    line.contains(" more "), is(false));
            }
        }
    }

    @Test
    public void syntheticRank_leewayGatedOnlyWhenFlagTrue() throws Exception {
        for (boolean flag : new boolean[]{true, false}) {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(
                "requirePreviousRank: " + flag + "\n" +
                "ranks:\n" +
                "  Rank1:\n" +
                "    challenges:\n" +
                "      a:\n" +
                "        type: onPlayer\n" +
                "        displayItem: stick\n" +
                "      b:\n" +
                "        type: onPlayer\n" +
                "        displayItem: stick\n" +
                "      c:\n" +
                "        type: onPlayer\n" +
                "        displayItem: stick\n" +
                "  Rank2:\n" +
                "    requires:\n" +
                "      rankLeeway: 0\n" +
                "    challenges: {}\n");
            ChallengeDefaults defaults = ChallengeFactory.createDefaults(config.getRoot());
            ConfigurationSection rankSection1 = config.getConfigurationSection("ranks.Rank1");
            ConfigurationSection rankSection2 = config.getConfigurationSection("ranks.Rank2");
            Rank rank1 = new Rank(rankSection1, null, defaults);
            Rank rank2 = new Rank(rankSection2, rank1, defaults);

            List<String> missing;
            try (MockedStatic<uSkyBlock> mockedStatic = Mockito.mockStatic(uSkyBlock.class)) {
                // Rank.getMissingRequirements evaluates uSkyBlock.getInstance() eagerly
                // even when the requires list is empty.
                uSkyBlock plugin = mock(uSkyBlock.class);
                when(plugin.getChallengeLogic()).thenReturn(mock(ChallengeLogic.class));
                mockedStatic.when(uSkyBlock::getInstance).thenReturn(plugin);
                missing = rank2.getMissingRequirements(freshPlayer(0));
            }
            if (flag) {
                assertThat(missing.isEmpty(), is(false));
                boolean hasLeeway = missing.stream().anyMatch(line -> line.contains(" more "));
                assertThat("expected leeway message when flag is true: " + missing, hasLeeway, is(true));
            } else {
                assertThat("expected no leeway message when flag is false: " + missing,
                    missing.isEmpty(), is(true));
            }
        }
    }

    private static PlayerInfo freshPlayer(int timesCompleted) {
        PlayerInfo pi = mock(PlayerInfo.class);
        when(pi.getChallenge(anyString())).thenReturn(new ChallengeCompletion("", null, timesCompleted, 0));
        return pi;
    }
}
