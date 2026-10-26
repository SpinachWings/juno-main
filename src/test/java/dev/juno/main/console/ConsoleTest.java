package dev.juno.main.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTest {

    @TempDir
    Path tmp;

    @Test
    void writesWindowsScriptWithQuotedArguments() throws Exception {
        var launcher = new TerminalLauncher(tmp, l -> { }, TerminalLauncher.Os.WINDOWS);
        Path script = launcher.writeScript("JUNO-1", "Juno JUNO-1",
                List.of("C:\\Program Files\\Java\\bin\\java.exe", "--module-path", "C:\\a b;C:\\100%", "x\"y"));
        String content = Files.readString(script);
        assertTrue(content.startsWith("@echo off\r\ntitle Juno JUNO-1\r\n"));
        assertTrue(content.contains("\"C:\\Program Files\\Java\\bin\\java.exe\" \"--module-path\" \"C:\\a b;C:\\100%%\" \"x\"\"y\""));
        assertTrue(content.contains("pause >nul"));
    }

    @Test
    void writesPosixScript() throws Exception {
        var launcher = new TerminalLauncher(tmp, l -> { }, TerminalLauncher.Os.LINUX);
        Path script = launcher.writeScript("JUNO-2", "Juno JUNO-2", List.of("/usr/bin/java", "it's"));
        String content = Files.readString(script);
        assertTrue(content.contains("'/usr/bin/java' 'it'\\''s'"));
        assertTrue(Files.isExecutable(script));
    }

    @Test
    void consoleOfDeletedTicketClosesItsWindow() throws Exception {
        AgentConsoles.Backend backend = new AgentConsoles.Backend() {
            @Override
            public Optional<AgentConsoles.TicketState> state(String ticketId) {
                return Optional.empty(); // the ticket was deleted
            }

            @Override
            public Path logFile(String ticketId) {
                return tmp.resolve("gone.log");
            }

            @Override
            public void answer(String ticketId, String answer) {
            }

            @Override
            public String exportDesignDoc(String ticketId) {
                return "x.txt";
            }
        };
        try (var consoles = new AgentConsoles(backend, tmp, l -> { })) {
            var launcher = new TerminalLauncher(tmp.resolve("consoles"), l -> { }, TerminalLauncher.Os.LINUX);
            Files.createDirectories(tmp.resolve("consoles"));
            Path script = launcher.writeScript("GONE", "Juno - gone", consoles.consoleCommand("GONE", "GONE"));
            Process p = new ProcessBuilder("sh", script.toString()).redirectErrorStream(true).start();
            assertTrue(p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "console should exit by itself");
            String out = new String(p.getInputStream().readAllBytes());
            assertTrue(out.contains("This ticket was deleted"), out);
            assertFalse(out.contains("Console closed"), out); // window closes without waiting for a key
            assertEquals(0, p.exitValue());
        }
    }

    @Test
    void apiRequiresToken() throws Exception {
        AgentConsoles.Backend backend = new AgentConsoles.Backend() {
            @Override
            public Optional<AgentConsoles.TicketState> state(String ticketId) {
                return Optional.of(new AgentConsoles.TicketState("WORKING", "Working", "t", null, true, false, null, "b", false, null));
            }

            @Override
            public Path logFile(String ticketId) {
                return tmp.resolve("log.txt");
            }

            @Override
            public void answer(String ticketId, String answer) {
            }

            @Override
            public String exportDesignDoc(String ticketId) {
                return "x.txt";
            }
        };
        Files.writeString(tmp.resolve("log.txt"), "line one\nline two\npartial");
        try (var consoles = new AgentConsoles(backend, tmp, l -> { })) {
            HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
            String url = "http://127.0.0.1:" + consoles.port() + "/api/stream?ticket=JUNO-1&from=0&wait=0&pid="
                    + ProcessHandle.current().pid();
            var denied = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode());

            String token = Files.readString(tmp.resolve("console.token")).strip();
            var ok = http.send(HttpRequest.newBuilder(URI.create(url)).header("X-Juno-Token", token).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("line one\\nline two\\n"));
            assertFalse(ok.body().contains("partial")); // incomplete last line is held back
            assertTrue(consoles.isOpen("JUNO-1"));
            assertFalse(consoles.isOpen("JUNO-9"));

            // A console whose process has ended (terminal closed or force-closed) is closed at once.
            Process gone = new ProcessBuilder("java", "-version").start();
            gone.waitFor();
            String deadUrl = "http://127.0.0.1:" + consoles.port() + "/api/stream?ticket=JUNO-1&console=JUNO-1-review"
                    + "&from=0&wait=0&pid=" + gone.pid();
            http.send(HttpRequest.newBuilder(URI.create(deadUrl)).header("X-Juno-Token", token).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertFalse(consoles.isOpen("JUNO-1-review"));
        }
    }
}
