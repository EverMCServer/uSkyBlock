package us.talabrek.ultimateskyblock.event;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enderman;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.Before;
import org.junit.Test;
import us.talabrek.ultimateskyblock.island.IslandInfo;
import us.talabrek.ultimateskyblock.uSkyBlock;
import us.talabrek.ultimateskyblock.world.WorldManager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the end-game "endermen pick up a copy of the block" mechanic.
 */
public class EndermanCopyEventsTest {

    private uSkyBlock plugin;
    private WorldManager worldManager;
    private World endWorld;
    private Block block;
    private BlockData sandData;
    private Enderman enderman;

    @Before
    public void setUp() {
        plugin = mock(uSkyBlock.class);
        worldManager = mock(WorldManager.class);
        endWorld = mock(World.class);
        block = mock(Block.class);
        sandData = mock(BlockData.class);
        enderman = mock(Enderman.class);

        when(plugin.getWorldManager()).thenReturn(worldManager);
        when(worldManager.isSkyEnd(endWorld)).thenReturn(true);
        when(block.getWorld()).thenReturn(endWorld);
        when(block.getType()).thenReturn(Material.SAND);
        when(block.getBlockData()).thenReturn(sandData);
        when(block.getLocation()).thenReturn(new Location(endWorld, 0, 64, 0));
        when(plugin.getIslandInfo(any(Location.class))).thenReturn(mock(IslandInfo.class));
    }

    @Test
    public void pickupInEndLeavesTheBlockAndGivesACopyToTheEnderman() {
        EntityChangeBlockEvent event = pickupEvent(Material.AIR);

        listener(true, true).onEndermanPickUpBlock(event);

        assertTrue("the original block must stay", event.isCancelled());
        verify(enderman).setCarriedBlock(sandData);
    }

    @Test
    public void pickupDoesNotReplaceAnAlreadyCarriedBlock() {
        BlockData alreadyCarried = mock(BlockData.class);
        when(enderman.getCarriedBlock()).thenReturn(alreadyCarried);
        EntityChangeBlockEvent event = pickupEvent(Material.AIR);

        listener(true, true).onEndermanPickUpBlock(event);

        assertTrue(event.isCancelled());
        verify(enderman, never()).setCarriedBlock(any(BlockData.class));
    }

    @Test
    public void placementIsNotTouched() {
        EntityChangeBlockEvent event = pickupEvent(Material.SAND);

        listener(true, true).onEndermanPickUpBlock(event);

        assertFalse("placing a block must stay vanilla", event.isCancelled());
        verify(enderman, never()).setCarriedBlock(any(BlockData.class));
    }

    @Test
    public void overworldAndNetherStayVanilla() {
        when(worldManager.isSkyEnd(endWorld)).thenReturn(false);
        EntityChangeBlockEvent event = pickupEvent(Material.AIR);

        listener(true, true).onEndermanPickUpBlock(event);

        assertFalse(event.isCancelled());
        verify(enderman, never()).setCarriedBlock(any(BlockData.class));
    }

    @Test
    public void mainCityIsNotCopiedByDefault() {
        when(plugin.getIslandInfo(any(Location.class))).thenReturn(null);
        EntityChangeBlockEvent event = pickupEvent(Material.AIR);

        listener(true, true).onEndermanPickUpBlock(event);

        assertFalse(event.isCancelled());
        verify(enderman, never()).setCarriedBlock(any(BlockData.class));
    }

    @Test
    public void mainCityIsCopiedWhenIslandsAreNotRequired() {
        when(plugin.getIslandInfo(any(Location.class))).thenReturn(null);
        EntityChangeBlockEvent event = pickupEvent(Material.AIR);

        listener(true, false).onEndermanPickUpBlock(event);

        assertTrue(event.isCancelled());
        verify(enderman).setCarriedBlock(sandData);
    }

    @Test
    public void disabledConfigurationKeepsVanillaBehaviour() {
        EntityChangeBlockEvent event = pickupEvent(Material.AIR);

        listener(false, true).onEndermanPickUpBlock(event);

        assertFalse(event.isCancelled());
        verify(enderman, never()).setCarriedBlock(any(BlockData.class));
    }

    private EndermanCopyEvents listener(boolean enabled, boolean onlyOnIslands) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("end.enderman-copy.enabled", enabled);
        config.set("end.enderman-copy.only-on-islands", onlyOnIslands);
        when(plugin.getConfig()).thenReturn(config);
        return new EndermanCopyEvents(plugin);
    }

    private EntityChangeBlockEvent pickupEvent(Material to) {
        BlockData toData = mock(BlockData.class);
        when(toData.getMaterial()).thenReturn(to);
        return new EntityChangeBlockEvent(enderman, block, toData);
    }
}
