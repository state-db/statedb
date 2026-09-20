package app.statedb.persistence.report;

/**
 * A {@link ChangeReport} rendered for one surface.
 *
 * @param title a single line naming the run; a comment's heading, a check run's name
 * @param summaryLine the counts in one line, short enough for a commit status description
 * @param body the full rendered report
 * @param truncated whether the report or the rendering dropped content for length
 */
public record RenderedReport(String title, String summaryLine, String body, boolean truncated) {}
