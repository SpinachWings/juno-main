package dev.juno.main.integration;

import dev.juno.main.mcp.McpException;
import dev.juno.main.model.JunoSettings;
import dev.juno.main.model.McpLaunch;
import dev.juno.main.model.TrackerKind;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes sure each tracker's MCP server is listening on its localhost port. With {@link McpLaunch#JAR} Juno
 * starts the jar on the port (building it first if needed) unless the server is already running there; with
 * {@link McpLaunch#PORT} the server was started separately and Juno only connects. Either way Juno checks that
 * what answers on the port really is that server ({@code GET /health}).
 */
public final class McpServers implements AutoCloseable {

    private record Launched(Process process, int port, String jar) {
    }

    private enum Probe { OURS, OTHER, NOTHING }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private final Consumer<String> log;
    private final Map<TrackerKind, Launched> launched = new EnumMap<>(TrackerKind.class);

    public McpServers(Consumer<String> log) {
        this.log = log;
    }

    public static String artifact(TrackerKind kind) {
        return kind == TrackerKind.GITLAB ? "juno-gitlab-mcp" : "juno-github-mcp";
    }

    public static URI endpoint(JunoSettings s, TrackerKind kind) {
        return URI.create("http://127.0.0.1:" + s.mcpPort(kind) + "/mcp");
    }

    /** Returns the server's MCP endpoint once it is listening, starting the jar first if Juno is to run it. */
    public synchronized URI ensure(JunoSettings s, TrackerKind kind) throws McpException {
        int port = s.mcpPort(kind);
        String artifact = artifact(kind);
        McpLaunch launch = s.mcpLaunch(kind);
        Launched own = launched.get(kind);
        if (own != null && (!own.process().isAlive() || own.port() != port || launch != McpLaunch.JAR
                || !own.jar().equals(s.resolvedMcpJar(kind)))) {
            stop(kind);
        }
        switch (probe(port, artifact)) {
            case OURS -> {
                return endpoint(s, kind);
            }
            case OTHER -> throw new McpException("Port " + port + " is used by another program, not " + artifact
                    + ". Choose a different " + kind.displayName() + " MCP port in Settings → General"
                    + (launch == McpLaunch.PORT ? " and start " + artifact + " with --port <that port>." : "."));
            case NOTHING -> {
                if (launch == McpLaunch.PORT) {
                    throw new McpException("Nothing is listening on port " + port + ". Start " + artifact
                            + " (run.sh / run.cmd in its folder, or 'java -jar " + artifact + ".jar --port " + port
                            + "'), or let Juno start the jar: Settings → General.");
                }
            }
        }
        start(s, kind, port, artifact);
        return endpoint(s, kind);
    }

    private void start(JunoSettings s, TrackerKind kind, int port, String artifact) throws McpException {
        String jar = s.resolvedMcpJar(kind);
        Path jarPath = Path.of(jar.trim());
        if (!Files.isRegularFile(jarPath)) {
            McpJars.build(jarPath, artifact);
        }
        List<String> command = List.of(IssueTrackerService.javaExecutable(s), "-jar", jarPath.toString(),
                "--port", Integer.toString(port));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new McpException("Could not start " + artifact + ": " + e.getMessage(), e);
        }
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        Thread.ofPlatform().daemon().name(artifact + "-output").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                    if (output.size() > 20) {
                        output.removeFirst();
                    }
                    log.accept("[" + artifact + "] " + line);
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
        launched.put(kind, new Launched(process, port, jar));
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                launched.remove(kind);
                sleep(200); // let the last output arrive
                throw new McpException(artifact + " stopped straight after starting on port " + port
                        + (output.isEmpty() ? "." : ":\n" + String.join("\n", output)));
            }
            if (probe(port, artifact) == Probe.OURS) {
                log.accept("[juno] started " + artifact + " on port " + port);
                return;
            }
            sleep(250);
        }
        stop(kind);
        throw new McpException(artifact + " did not start listening on port " + port + " within 30 seconds");
    }

    /** Stops servers Juno started that no longer match the settings (port, jar or launch mode changed). */
    public synchronized void reconcile(JunoSettings s) {
        for (TrackerKind kind : TrackerKind.values()) {
            Launched own = launched.get(kind);
            if (own != null && (own.port() != s.mcpPort(kind) || s.mcpLaunch(kind) != McpLaunch.JAR
                    || !own.jar().equals(s.resolvedMcpJar(kind)))) {
                stop(kind);
            }
        }
    }

    private void stop(TrackerKind kind) {
        Launched own = launched.remove(kind);
        if (own != null && own.process().isAlive()) {
            own.process().destroy();
            try {
                own.process().waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (own.process().isAlive()) {
                own.process().destroyForcibly();
            }
        }
    }

    /** Whether the expected server answers on the port, another program does, or nothing does. */
    private static Probe probe(int port, String artifact) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            Matcher m = NAME.matcher(response.body() == null ? "" : response.body());
            return response.statusCode() == 200 && m.find() && m.group(1).equals(artifact) ? Probe.OURS : Probe.OTHER;
        } catch (ConnectException e) {
            return Probe.NOTHING;
        } catch (IOException e) {
            return Probe.OTHER; // something is there but doesn't speak HTTP like our server
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Probe.NOTHING;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        for (TrackerKind kind : TrackerKind.values()) {
            stop(kind);
        }
    }
}
