package dev.juno.main.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResolutionTest {

    @Test
    void ticketOverridesProjectOverridesOrganisation() {
        JunoSettings s = new JunoSettings();
        AgentProfile orgAgent = new AgentProfile("org agent");
        AgentProfile projectAgent = new AgentProfile("project agent");
        AgentProfile ticketAgent = new AgentProfile("ticket agent");
        s.getAgentProfiles().add(orgAgent);
        s.getAgentProfiles().add(projectAgent);
        s.getAgentProfiles().add(ticketAgent);

        Organisation org = new Organisation("Acme");
        s.getOrganisations().add(org);
        Project project = new Project();
        project.setName("Web");
        project.setOrgId(org.getId());
        s.getProjects().add(project);
        Ticket ticket = new Ticket("JUNO-1", "t", "");
        ticket.setProjectId(project.getId());

        // Nothing assigned anywhere.
        assertTrue(s.resolveAgent(ticket).isEmpty());

        org.setAgentProfileId(orgAgent.getId());
        AgentAssignment a = s.resolveAgent(ticket).orElseThrow();
        assertEquals(orgAgent, a.profile());
        assertEquals(AgentAssignment.Level.ORGANISATION, a.level());
        assertEquals("Acme", a.owner());

        project.setAgentProfileId(projectAgent.getId());
        assertEquals(projectAgent, s.resolveAgent(ticket).orElseThrow().profile());
        assertEquals(AgentAssignment.Level.PROJECT, s.resolveAgent(ticket).orElseThrow().level());

        ticket.setAgentProfileId(ticketAgent.getId());
        assertEquals(ticketAgent, s.resolveAgent(ticket).orElseThrow().profile());
        assertEquals(AgentAssignment.Level.TICKET, s.resolveAgent(ticket).orElseThrow().level());

        // A removed profile falls through to the next level.
        s.getAgentProfiles().remove(ticketAgent);
        assertEquals(projectAgent, s.resolveAgent(ticket).orElseThrow().profile());
    }

    @Test
    void ticketWithoutProjectHasOnlyItsOwnAgent() {
        JunoSettings s = JunoSettings.createDefault();
        Ticket ticket = new Ticket("JUNO-2", "t", "");
        assertTrue(s.resolveAgent(ticket).isEmpty());
        ticket.setAgentProfileId(s.getAgentProfiles().getFirst().getId());
        assertEquals(AgentAssignment.Level.TICKET, s.resolveAgent(ticket).orElseThrow().level());
    }

    @Test
    void envTokenReferencesResolve() {
        TrackerSettings t = new TrackerSettings(true, "https://gitlab.com");
        t.setToken("literal");
        assertEquals("literal", t.resolvedToken());
        t.setToken("env:JUNO_TEST_SURELY_UNSET_VARIABLE");
        assertEquals(null, t.resolvedToken());
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            t.setToken("env:PATH");
            assertEquals(path.trim(), t.resolvedToken());
        }
    }
}
