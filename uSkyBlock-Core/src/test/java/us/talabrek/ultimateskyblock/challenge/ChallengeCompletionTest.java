package us.talabrek.ultimateskyblock.challenge;

import org.junit.Test;

import java.time.Instant;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ChallengeCompletionTest {

    @Test
    public void inCooldownCount_isZero_whenOffCooldown() {
        ChallengeCompletion completion = new ChallengeCompletion("test", null, 5, 5);
        assertThat(completion.isOnCooldown(), is(false));
        assertThat(completion.getTimesCompletedInCooldown(), is(0));
    }

    @Test
    public void inCooldownCount_isZero_whenCooldownExpired() {
        ChallengeCompletion completion = new ChallengeCompletion("test", Instant.now().minusSeconds(60), 5, 5);
        assertThat(completion.isOnCooldown(), is(false));
        assertThat(completion.getTimesCompletedInCooldown(), is(0));
    }

    @Test
    public void inCooldownCount_returnsRawCount_whenOnCooldown() {
        ChallengeCompletion completion = new ChallengeCompletion("test", Instant.now().plusSeconds(3600), 5, 5);
        assertThat(completion.isOnCooldown(), is(true));
        assertThat(completion.getTimesCompletedInCooldown(), is(5));
    }

    @Test
    public void setCooldownUntil_resetsInCooldownCount() {
        ChallengeCompletion completion = new ChallengeCompletion("test", Instant.now().plusSeconds(3600), 5, 5);
        completion.setCooldownUntil(Instant.now().plusSeconds(3600));
        assertThat(completion.getTimesCompletedInCooldown(), is(0));
    }

    @Test
    public void addTimesCompleted_incrementsBothCounters() {
        ChallengeCompletion completion = new ChallengeCompletion("test", Instant.now().plusSeconds(3600), 1, 1);
        completion.addTimesCompleted();
        assertThat(completion.getTimesCompleted(), is(2));
        assertThat(completion.getTimesCompletedInCooldown(), is(2));
    }

    @Test
    public void setCooldownUntil_null_clearsCooldown() {
        ChallengeCompletion completion = new ChallengeCompletion("test", Instant.now().plusSeconds(3600), 5, 5);
        completion.setCooldownUntil(null);
        assertThat(completion.isOnCooldown(), is(false));
        assertThat(completion.getTimesCompletedInCooldown(), is(0));
    }
}
