package us.talabrek.ultimateskyblock.challenge;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ChallengeEffectiveRepetitionsTest {

    private Challenge createChallenge(Duration resetDuration) {
        return new Challenge("test", "Test", "", Challenge.Type.PLAYER,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), 0, Collections.emptyList(), null, resetDuration,
            null, null, null, 0, true, 10, null, null, 0);
    }

    @Test
    public void onCooldown_returnsInCooldownCount() {
        Challenge challenge = createChallenge(Duration.ofHours(24));
        ChallengeCompletion completion = new ChallengeCompletion("test", Instant.now().plusSeconds(3600), 10, 3);
        assertThat(challenge.getEffectiveRepetitions(completion), is(3));
    }

    @Test
    public void offCooldown_withResetDuration_returnsZero() {
        Challenge challenge = createChallenge(Duration.ofHours(24));
        ChallengeCompletion completion = new ChallengeCompletion("test", null, 10, 3);
        assertThat(challenge.getEffectiveRepetitions(completion), is(0));
    }

    @Test
    public void expiredCooldown_withResetDuration_returnsZero() {
        Challenge challenge = createChallenge(Duration.ofHours(24));
        ChallengeCompletion completion = new ChallengeCompletion("test", Instant.now().minusSeconds(60), 10, 3);
        assertThat(challenge.getEffectiveRepetitions(completion), is(0));
    }

    @Test
    public void noResetWindow_returnsLifetimeTotal() {
        Challenge challenge = createChallenge(Duration.ZERO);
        ChallengeCompletion completion = new ChallengeCompletion("test", null, 4, 0);
        assertThat(challenge.getEffectiveRepetitions(completion), is(4));
    }

    @Test
    public void neverCompleted_returnsZero() {
        Challenge challenge = createChallenge(Duration.ofHours(24));
        ChallengeCompletion completion = new ChallengeCompletion("test", null, 0, 0);
        assertThat(challenge.getEffectiveRepetitions(completion), is(0));
    }
}
