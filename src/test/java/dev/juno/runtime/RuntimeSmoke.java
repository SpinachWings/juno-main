package dev.juno.runtime;

import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.Settings;
import dev.juno.provider.AgentEvent;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.JunoHome;
import dev.juno.store.SettingsStore;
import java.nio.file.Path;

/** Manual check against the real CLI (not a unit test): java ... RuntimeSmoke <scratch home> <message> <answers>. */
public final class RuntimeSmoke {
    public static void main(String[] args) throws Exception {
        JunoHome home = new JunoHome(Path.of(args[0]));
        SettingsStore ss = new SettingsStore(home);
        Settings s = ss.load();
        SkillLibrary lib = new SkillLibrary(home);
        lib.refreshBundled(s);
        Profile t = s.profiles.stream().filter(p -> p.name.equals("searcher")).findFirst().orElseThrow().duplicate("tester");
        t.model = "haiku"; t.effort = "low"; t.allowedTools.clear(); t.allowedTools.add("Read");
        s.profiles.removeIf(p -> p.name.equals("tester"));
        s.profiles.add(t);
        ss.save(s);
        Settings[] holder = {s};
        AgentRuntime rt = new AgentRuntime(home, new ClaudeCodeProvider(home), () -> holder[0], () -> ss.save(holder[0]), lib, new SkillResolver(lib));
        String answers = args.length > 2 ? args[2] : "once";
        rt.addListener(new AgentRuntime.Listener() {
            int n;
            @Override public void event(Agent a, AgentEvent e) {
                System.out.println("EVENT " + e.toString().substring(0, Math.min(220, e.toString().length())));
                if (e instanceof AgentEvent.Permission p && p.answer() == null) {
                    String ans = answers.split(",")[Math.min(n++, answers.split(",").length - 1)];
                    new Thread(() -> {
                        AgentRuntime.Answer kind = switch (ans) { case "agent" -> AgentRuntime.Answer.ALLOW_AGENT; case "profile" -> AgentRuntime.Answer.ALLOW_PROFILE; case "deny" -> AgentRuntime.Answer.DENY; default -> AgentRuntime.Answer.ALLOW_ONCE; };
                        System.out.println("ANSWER " + kind + " -> " + rt.answer(a, p.requestId(), kind, null));
                    }).start();
                }
            }
            @Override public void changed(Agent a) { System.out.println("STATUS " + a.statusText() + " " + a.statusDetail); }
        });
        rt.start();
        Agent a = rt.agents().isEmpty() ? rt.create("bob", "tester", null, null) : rt.agents().get(0);
        if (args[1].equals("/resume")) System.out.println("RESUME " + rt.resume(a));
        else rt.send(a, args[1], "user");
        long until = System.currentTimeMillis() + 150_000;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(500);
            if (!a.turnOpen && a.status != Agent.Status.WORKING) break;
        }
        LiveAgent l = rt.live(a.id);
        System.out.println("FINAL " + a.statusText() + " ctx=" + l.contextTokens + "/" + l.contextWindow + " rules=" + a.rules.size() + " session=" + a.sessionId);
        rt.shutdown();
        System.exit(0);
    }
}
