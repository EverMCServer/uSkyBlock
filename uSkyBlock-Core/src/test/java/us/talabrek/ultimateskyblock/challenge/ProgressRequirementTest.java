package us.talabrek.ultimateskyblock.challenge;

import dk.lockfuglsang.minecraft.util.ItemRequirement;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ProgressRequirementTest {

    @Test
    public void noOperator_isConstant() {
        ProgressRequirement req = ProgressRequirement.of("visit", 100);
        assertThat(req.amountForRepetitions(0), is(100.0));
        assertThat(req.amountForRepetitions(1), is(100.0));
        assertThat(req.amountForRepetitions(2), is(100.0));
    }

    @Test
    public void addOperator_addsIncrementPerRepetition() {
        ProgressRequirement req = ProgressRequirement.of("visit", 100, ItemRequirement.Operator.ADD, 25);
        assertThat(req.amountForRepetitions(0), is(100.0));
        assertThat(req.amountForRepetitions(1), is(125.0));
        assertThat(req.amountForRepetitions(2), is(150.0));
    }

    @Test
    public void subtractOperator_subtractsIncrementPerRepetition() {
        ProgressRequirement req = ProgressRequirement.of("visit", 100, ItemRequirement.Operator.SUBTRACT, 10);
        assertThat(req.amountForRepetitions(0), is(100.0));
        assertThat(req.amountForRepetitions(1), is(90.0));
        assertThat(req.amountForRepetitions(2), is(80.0));
    }

    @Test
    public void multiplyOperator_multipliesPerRepetition() {
        ProgressRequirement req = ProgressRequirement.of("visit", 100, ItemRequirement.Operator.MULTIPLY, 2);
        assertThat(req.amountForRepetitions(0), is(100.0));
        assertThat(req.amountForRepetitions(1), is(200.0));
        assertThat(req.amountForRepetitions(2), is(400.0));
    }
}
