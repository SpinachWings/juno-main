package dev.juno.main.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Juno's own PR comments are never treated as review feedback. */
class ReviewCommentTest {

    @Test
    void recognisesJunosOwnComments() throws Exception {
        assertTrue(ReviewComment.isJunoText("### AI review 2\n\nFixed things.\n\n<!-- juno -->"));
        assertTrue(ReviewComment.isJunoText("### Merge conflicts resolved\n\nMerged main.")); // marker edited out
        assertTrue(ReviewComment.isJunoText("Anything\n" + ReviewComment.JUNO_MARKER));
        assertFalse(ReviewComment.isJunoText("Please rename foo to bar"));
        assertFalse(ReviewComment.isJunoText("> ### AI review 2\n> Fixed things.\n\nThis broke the build"));

        var mapper = new ObjectMapper();
        assertTrue(ReviewComment.fromJson(mapper.readTree(
                "{\"kind\":\"conversation\",\"body\":\"hi\",\"postedByJuno\":true}")).postedByJuno());
        assertFalse(ReviewComment.fromJson(mapper.readTree(
                "{\"kind\":\"inline\",\"body\":\"Use a constant here\"}")).postedByJuno());
    }
}
