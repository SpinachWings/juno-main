package dev.juno.main.model;

/**
 * The agent profile that applies to a ticket and the level it came from.
 *
 * @param profile the effective profile
 * @param level   where it was assigned
 * @param owner   name of the ticket, project or organisation it was assigned on
 */
public record AgentAssignment(AgentProfile profile, Level level, String owner) {

    public enum Level {
        TICKET("ticket"),
        PROJECT("project"),
        ORGANISATION("organisation");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** e.g. {@code Opus careful (from project Web)}. */
    public String describe() {
        return profile.getName() + " (from " + level.label() + (level == Level.TICKET ? "" : " " + owner) + ")";
    }
}
