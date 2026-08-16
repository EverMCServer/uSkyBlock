package us.talabrek.ultimateskyblock.challenge;

import dk.lockfuglsang.minecraft.util.BukkitServerMock;
import dk.lockfuglsang.minecraft.util.ItemRequirement;
import dk.lockfuglsang.minecraft.util.ItemStackUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

public class ChallengeFactoryTest {

    @Before
    public void beforeEach() throws NoSuchFieldException, IllegalAccessException {
        BukkitServerMock.setupServerMock();
        // Paper 26.2: constructing a real ItemStack in unit tests requires RegistryAccess
        // (server-only). These tests only verify progress parsing, so stub out displayItem creation.
        // Note: reset() first, otherwise re-stubbing would execute BukkitServerMock's answer
        // during recording (which constructs a real ItemStack and fails).
        ItemFactory itemFactory = Bukkit.getItemFactory();
        reset(itemFactory);
        doReturn(mock(ItemStack.class)).when(itemFactory).createItemStack(any());
    }

    @Test
    @Ignore
    public void createChallenge_IronGolem() {
        InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream("challengefactory/requiredEntities.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(resourceAsStream));
        ChallengeDefaults defaults = ChallengeFactory.createDefaults(config.getRoot());
        ConfigurationSection rankSection = config.getConfigurationSection("ranks.Tier1");
        Rank rank = new Rank(rankSection, null, defaults);
        Challenge challenge = ChallengeFactory.createChallenge(rank, rankSection.getConfigurationSection("challenges.villageguard"), defaults);

        assertThat(challenge, notNullValue());
        assertThat(challenge.getRequiredEntities().size(), is(2));
        assertThat(challenge.getRequiredEntities().get(0).getType(), is(EntityType.VILLAGER));
        assertThat(challenge.getRequiredEntities().get(1).getType(), is(EntityType.IRON_GOLEM));
    }

    @Test
    @Ignore
    public void createChallenge_ManyItems() {
        InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream("challengefactory/manyRequiredItems.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(resourceAsStream));
        ChallengeDefaults defaults = ChallengeFactory.createDefaults(config.getRoot());
        ConfigurationSection rankSection = config.getConfigurationSection("ranks.Tier1");
        Rank rank = new Rank(rankSection, null, defaults);
        Challenge challenge = ChallengeFactory.createChallenge(rank, rankSection.getConfigurationSection("challenges.villageguard"), defaults);

        assertThat(challenge, notNullValue());
        Map<ItemStack, Integer> requiredItems = challenge.getRequiredItems(0);
        assertThat(requiredItems.size(), is(1));
        assertThat(ItemStackUtil.asString(requiredItems.keySet().stream().findFirst().get()), is(ItemStackUtil.asString(new ItemStack(Material.COBBLESTONE, 257))));
    }

    private Challenge createProgressChallenge(String requiredProgressYml) throws org.bukkit.configuration.InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(
            "defaultResetInHours: 20\n" +
            "ranks:\n" +
            "  Tier1:\n" +
            "    challenges:\n" +
            "      myprogress:\n" +
            "        name: \"Demo\"\n" +
            "        type: progress\n" +
            requiredProgressYml +
            "        resetInHours: 0\n" +
            "        displayItem: compass\n");
        ChallengeDefaults defaults = ChallengeFactory.createDefaults(config.getRoot());
        Map<String, Rank> ranks = ChallengeFactory.createRankMap(config.getConfigurationSection("ranks"), defaults);
        return ranks.get("Tier1").getChallenges().get(0);
    }

    @Test
    public void createChallenge_ProgressType_withOperatorAndIncrement() throws Exception {
        Challenge challenge = createProgressChallenge("        requiredProgress:\n          - \"visit:5:+:2\"\n");
        assertThat(challenge, notNullValue());
        assertThat(challenge.getType(), is(Challenge.Type.PROGRESS));
        assertThat(challenge.getRequiredProgress().size(), is(1));
        ProgressRequirement req = challenge.getRequiredProgress().get(0);
        assertThat(req.key(), is("visit"));
        assertThat(req.amount(), is(5.0));
        assertThat(req.operator(), is(ItemRequirement.Operator.ADD));
        assertThat(req.increment(), is(2.0));
        assertThat(challenge.getResetDuration(), is(Duration.ZERO));
    }

    @Test
    public void createChallenge_ProgressType_simpleFormat() throws Exception {
        Challenge challenge = createProgressChallenge("        requiredProgress:\n          - \"visit:5\"\n");
        assertThat(challenge.getType(), is(Challenge.Type.PROGRESS));
        ProgressRequirement req = challenge.getRequiredProgress().get(0);
        assertThat(req.key(), is("visit"));
        assertThat(req.amount(), is(5.0));
        assertThat(req.operator(), is(ItemRequirement.Operator.NONE));
        assertThat(req.increment(), is(0.0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void createChallenge_ProgressType_malformedProgress_throws() throws Exception {
        createProgressChallenge("        requiredProgress:\n          - \"nonsense\"\n");
    }
}
