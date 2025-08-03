package us.talabrek.ultimateskyblock.challenge;

import dk.lockfuglsang.minecraft.util.ItemRequirement;

/**
 * Represents a progress requirement for challenges.
 * This requirement checks if the player's island has achieved a certain progress value for a specific key.
 */
public record ProgressRequirement(String key, double amount, ItemRequirement.Operator operator, double increment) {

    /**
     * Calculates the required amount for the given number of repetitions.
     * @param repetitions The number of challenge repetitions
     * @return The required progress amount
     */
    public double amountForRepetitions(int repetitions) {
        return operator().apply(amount(), increment(), repetitions);
    }

    /**
     * Creates a simple progress requirement with no operator (fixed amount).
     * @param key The progress key to check
     * @param amount The required progress amount
     * @return A new ProgressRequirement
     */
    public static ProgressRequirement of(String key, double amount) {
        return new ProgressRequirement(key, amount, ItemRequirement.Operator.NONE, 0.0);
    }

    /**
     * Creates a progress requirement with an operator and increment for repeatable challenges.
     * @param key The progress key to check
     * @param amount The base required progress amount
     * @param operator The operator to apply for repetitions
     * @param increment The increment value for each repetition
     * @return A new ProgressRequirement
     */
    public static ProgressRequirement of(String key, double amount, ItemRequirement.Operator operator, double increment) {
        return new ProgressRequirement(key, amount, operator, increment);
    }
}
