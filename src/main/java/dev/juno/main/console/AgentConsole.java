package dev.juno.main.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The agent console shown in a terminal window for a ticket. It streams the agent's activity from Juno and,
 * when the agent asks for a decision, lets you answer right here (the same as answering on the board).
 *
 * <p>Started by Juno: {@code AgentConsole --port <port> --token-file <file> --ticket <id>}.
 */
public final class AgentConsole {

    private static final String RULE = "-".repeat(72);

    private final HttpClient http = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrintStream out = System.out;
    private final String base;
    private final String token;
    private final String ticket;
    private final String consoleKey;

    private final Object inputLock = new Object();
    /** The question currently awaiting an answer, or null. */
    private volatile String awaiting;
    private final StringBuilder answer = new StringBuilder();

    private AgentConsole(int port, String token, String ticket, String consoleKey) {
        this.base = "http://127.0.0.1:" + port + "/api/";
        this.token = token;
        this.ticket = ticket;
        this.consoleKey = consoleKey == null ? ticket : consoleKey;
    }

    public static void main(String[] args) throws Exception {
        int port = 0;
        String tokenFile = null;
        String ticket = null;
        String consoleKey = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--token-file" -> tokenFile = args[i + 1];
                case "--ticket" -> ticket = args[i + 1];
                case "--console" -> consoleKey = args[i + 1];
                default -> {
                    // ignore unknown options
                }
            }
        }
        if (port == 0 || tokenFile == null || ticket == null) {
            System.err.println("Usage: AgentConsole --port <port> --token-file <file> --ticket <id>");
            System.exit(2);
        }
        String token = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip();
        new AgentConsole(port, token, ticket, consoleKey).run();
    }

    private void run() {
        out.println(RULE);
        out.println(" Juno agent console - " + ticket + (consoleKey.endsWith("-review") ? " (AI review)" : ""));
        out.println(" Live agent activity. When the agent asks for a decision you can answer here or on the board.");
        out.println(RULE);

        Thread reader = new Thread(this::readInput, "console-input");
        reader.setDaemon(true);
        reader.start();

        long offset = 0;
        String signature = "";
        String lastStatus = null;
        int failures = 0;
        while (true) {
            JsonNode response;
            try {
                response = get("stream?ticket=" + enc(ticket) + "&console=" + enc(consoleKey) + "&from=" + offset
                        + "&state=" + enc(signature) + "&wait=20");
                if (failures > 0) {
                    out.println("[juno] reconnected");
                }
                failures = 0;
            } catch (IOException | InterruptedException e) {
                failures++;
                if (failures == 1) {
                    out.println("[juno] lost connection to Juno; retrying…");
                }
                if (failures >= 40) {
                    out.println("[juno] Juno appears to have closed. You can close this window.");
                    return;
                }
                sleep(2000);
                continue;
            }
            if (response.has("error")) {
                out.println("[juno] " + response.get("error").asText());
                sleep(5000);
                continue;
            }

            String text = response.path("text").asText("");
            if (!text.isEmpty()) {
                out.print(text);
                out.flush();
            }
            offset = response.path("next").asLong(offset);

            JsonNode state = response.path("state");
            String newSignature = state.path("sig").asText("");
            if (!newSignature.equals(signature)) {
                signature = newSignature;
                lastStatus = onStateChange(state, lastStatus);
            }
        }
    }

    /** Prints status changes and opens / closes the decision prompt. Returns the new status. */
    private String onStateChange(JsonNode state, String lastStatus) {
        String status = state.path("status").asText("");
        String label = state.path("statusLabel").asText(status);
        String question = state.hasNonNull("question") ? state.get("question").asText() : null;
        boolean waiting = "AWAITING_DECISION".equals(status) && question != null;

        boolean announced = false;
        synchronized (inputLock) {
            if (waiting && !question.equals(awaiting)) {
                awaiting = question;
                answer.setLength(0);
                out.println();
                out.println(RULE);
                out.println(" DECISION NEEDED - " + ticket);
                out.println(RULE);
                question.lines().forEach(l -> out.println(" " + l));
                out.println(RULE);
                out.println(" Type your answer and press Enter on an empty line to send it,");
                out.println(" or answer on Juno's board (Awaiting decision > Respond).");
                out.print("> ");
                out.flush();
            } else if (!waiting && awaiting != null) {
                awaiting = null;
                answer.setLength(0);
                out.println();
                out.println("[juno] Decision answered. Ticket is now: " + label);
                announced = true;
            }
        }
        if (!announced && !status.equals(lastStatus) && lastStatus != null && !waiting) {
            out.println("[juno] Ticket is now: " + label);
        }
        if ("REVIEW".equals(status) && !state.path("running").asBoolean(false)
                && !state.path("delivering").asBoolean(false) && state.hasNonNull("pr")
                && !status.equals(lastStatus)) {
            out.println("[juno] Pull request: " + state.get("pr").asText());
        }
        return status;
    }

    private void readInput() {
        Console console = System.console();
        Charset charset = console != null ? console.charset() : Charset.defaultCharset();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, charset))) {
            String line;
            while ((line = in.readLine()) != null) {
                onInputLine(line);
            }
        } catch (IOException e) {
            // stdin closed
        }
    }

    private void onInputLine(String line) {
        String toSend = null;
        synchronized (inputLock) {
            if (awaiting == null) {
                if (!line.isBlank()) {
                    out.println("[juno] The agent isn't waiting for an answer right now.");
                }
                return;
            }
            if (!line.isBlank()) {
                answer.append(line).append('\n');
                out.print("> ");
                out.flush();
                return;
            }
            if (answer.isEmpty()) {
                out.print("> ");
                out.flush();
                return;
            }
            toSend = answer.toString().strip();
            answer.setLength(0);
        }
        try {
            JsonNode result = post("answer?ticket=" + enc(ticket), toSend);
            if (result.has("error")) {
                out.println("[juno] Could not send the answer: " + result.get("error").asText());
                out.print("> ");
            } else {
                out.println("[juno] Sent. Resuming the agent…");
            }
        } catch (IOException | InterruptedException e) {
            out.println("[juno] Could not reach Juno: " + e.getMessage());
            out.print("> ");
        }
        out.flush();
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Juno-Token", token)
                .timeout(Duration.ofSeconds(40))
                .GET().build();
        return mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
    }

    private JsonNode post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Juno-Token", token)
                .header("Content-Type", "text/plain; charset=utf-8")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
