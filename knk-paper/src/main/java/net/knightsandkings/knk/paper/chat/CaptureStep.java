package net.knightsandkings.knk.paper.chat;

/**
 * Enum representing different capture steps within a flow.
 * Each step represents a specific input being requested from the player.
 */
public enum CaptureStep {
    /**
     * Capturing email address input
     */
    EMAIL,
    
    /**
     * Capturing password input
     */
    PASSWORD,
    
    /**
     * Capturing password confirmation input
     */
    PASSWORD_CONFIRM,
    
    /**
     * Capturing account choice (A or B) for merge scenario
     */
    ACCOUNT_CHOICE,

    /**
     * Capturing one free-text line for {@link CaptureFlow#TEXT_INPUT}.
     */
    TEXT_INPUT
}
