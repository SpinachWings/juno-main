package dev.juno.main.integration;

import dev.juno.main.mcp.McpException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Builds an MCP server jar on first use, so the servers need no manual setup: if the configured jar is missing
 * but sits in its project's {@code target} folder ({@code juno-github-mcp/target/juno-github-mcp.jar}), the
 * project is packaged with Maven.
 */
final class McpJars {

    private static final Object LOCK = new Object();

    private McpJars() {
    }

    static void build(Path jar, String artifact) throws McpException {
        synchronized (LOCK) {
            if (Files.isRegularFile(jar)) {
                return; // built by a concurrent caller
            }
            Path target = jar.toAbsolutePath().getParent();
            Path project = target == null ? null : target.getParent();
            if (project == null || !Files.isRegularFile(project.resolve("pom.xml"))) {
                throw new McpException("The " + artifact + " server wasn't found at " + jar + ". Put the "
                        + artifact + " project next to juno-main (it is built automatically), or set the jar's "
                        + "path in Settings → General.");
            }
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            List<String> command = List.of(windows ? "mvn.cmd" : "mvn", "-q", "-B", "-DskipTests", "package");
            System.err.println("[juno] building " + artifact + " in " + project + " ...");
            Path output;
            try {
                output = Files.createTempFile("juno-mcp-build", ".log");
            } catch (IOException e) {
                throw new McpException("Could not build " + artifact + ": " + e.getMessage(), e);
            }
            try {
                Process p = new ProcessBuilder(command).directory(project.toFile())
                        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if (!p.waitFor(10, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    throw new McpException("Building " + artifact + " took more than 10 minutes and was stopped");
                }
                if (p.exitValue() != 0 || !Files.isRegularFile(jar)) {
                    String log = tail(Files.readString(output, StandardCharsets.UTF_8));
                    throw new McpException("Could not build " + artifact + " (mvn package in " + project + " "
                            + (p.exitValue() != 0 ? "failed" : "did not produce " + jar.getFileName()) + ")"
                            + (log.isBlank() ? "" : ":\n" + log));
                }
            } catch (IOException e) {
                throw new McpException("Could not build " + artifact + ": Maven ('" + command.getFirst()
                        + "') was not found. Install Maven, or run 'mvn package' in " + project + " once.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException("Interrupted while building " + artifact, e);
            } finally {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // temp file
                }
            }
        }
    }

    private static String tail(String text) {
        List<String> lines = text.strip().lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 15), lines.size()));
    }
}
