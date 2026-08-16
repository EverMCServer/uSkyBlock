package us.talabrek.ultimateskyblock.progress;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.UUID;
import java.util.logging.Logger;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class PlayerProgressTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private PlayerProgress createProgress() throws Exception {
        UUID uuid = UUID.randomUUID();
        return new PlayerProgress(uuid, new File(tmp.getRoot(), uuid + ".yml"), Logger.getLogger("test"));
    }

    @Test
    public void getProgress_defaultsToZero() throws Exception {
        PlayerProgress progress = createProgress();
        assertThat(progress.getProgress("unknown"), is(0.0));
        assertThat(progress.getTotalProgress("unknown"), is(0.0));
    }

    @Test
    public void addToProgress_accumulatesCurrentAndTotal() throws Exception {
        PlayerProgress progress = createProgress();
        assertThat(progress.addToProgress("visit", 3.0), is(3.0));
        progress.addToProgress("visit", 2.0);
        assertThat(progress.getProgress("visit"), is(5.0));
        assertThat(progress.getTotalProgress("visit"), is(5.0));
    }

    @Test
    public void setProgress_doesNotTouchTotal() throws Exception {
        PlayerProgress progress = createProgress();
        progress.addToProgress("visit", 5.0);
        progress.setProgress("visit", 0.5); // consume
        assertThat(progress.getProgress("visit"), is(0.5));
        assertThat(progress.getTotalProgress("visit"), is(5.0));
    }

    @Test
    public void flush_persistsBothValues() throws Exception {
        UUID uuid = UUID.randomUUID();
        File file = new File(tmp.getRoot(), uuid + ".yml");
        PlayerProgress progress = new PlayerProgress(uuid, file, Logger.getLogger("test"));
        progress.addToProgress("visit", 4.0);
        progress.addToProgress("other", 1.0);
        progress.setProgress("other", 0.0);
        progress.flush();

        // A fresh instance reads the values back from disk
        PlayerProgress reloaded = new PlayerProgress(uuid, file, Logger.getLogger("test"));
        assertThat(reloaded.getProgress("visit"), is(4.0));
        assertThat(reloaded.getTotalProgress("visit"), is(4.0));
        assertThat(reloaded.getProgress("other"), is(0.0));
        assertThat(reloaded.getTotalProgress("other"), is(1.0));
    }

    @Test
    public void reset_clearsBothValues() throws Exception {
        PlayerProgress progress = createProgress();
        progress.addToProgress("visit", 5.0);
        progress.reset();
        progress.flush();
        assertThat(progress.getProgress("visit"), is(0.0));
        assertThat(progress.getTotalProgress("visit"), is(0.0));
    }

    @Test
    public void mergeFrom_overwritesCurrentAndAddsTotal() throws Exception {
        PlayerProgress leader = createProgress();
        leader.addToProgress("visit", 1.0);

        PlayerProgress oldLeader = createProgress();
        oldLeader.addToProgress("visit", 5.0);
        oldLeader.setProgress("visit", 2.0); // current 2, total 5

        leader.mergeFrom(oldLeader);
        assertThat(leader.getProgress("visit"), is(2.0));
        assertThat(leader.getTotalProgress("visit"), is(6.0));
    }

    @Test
    public void flush_isNoop_whenNotDirty() throws Exception {
        UUID uuid = UUID.randomUUID();
        File file = new File(tmp.getRoot(), uuid + ".yml");
        PlayerProgress progress = new PlayerProgress(uuid, file, Logger.getLogger("test"));
        progress.flush(); // not dirty — must not create a file
        assertThat(file.exists(), is(false));
    }
}
