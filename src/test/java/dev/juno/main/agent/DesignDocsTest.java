package dev.juno.main.agent;

import dev.juno.main.model.Ticket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignDocsTest {

    @TempDir
    Path tmp;

    @Test
    void extractsTheLastDocBlock() {
        assertNull(DesignDocs.extract("no doc here"));
        assertEquals("v2", DesignDocs.extract("<design-doc>v1</design-doc> then <design-doc>\n v2 \n</design-doc>"));
    }

    @Test
    void recognisesApproval() {
        assertTrue(DesignDocs.isApproval("/approve"));
        assertTrue(DesignDocs.isApproval(" Approve! "));
        assertFalse(DesignDocs.isApproval("approve but change the API"));
    }

    @Test
    void exportsTxt() throws Exception {
        Ticket t = new Ticket("JUNO-7", "Add OAuth login!", "");
        t.setDesignDoc("# Summary\nUse OAuth.");
        t.setDesignDocSource("agent");
        Path file = DesignDocs.export(t, "Web", tmp.resolve("docs"));
        assertEquals("JUNO-7-add-oauth-login-design.txt", file.getFileName().toString());
        String text = Files.readString(file);
        assertTrue(text.startsWith("Design doc: JUNO-7 - Add OAuth login!"));
        assertTrue(text.contains("Status: draft (written with the agent)"));
        assertTrue(text.contains("Use OAuth."));
    }
}
