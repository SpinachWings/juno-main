package dev.juno.main.git;

/** A git operation failed; the message is suitable for showing to the user. */
public class GitException extends Exception {

    public GitException(String message) {
        super(message);
    }
}
