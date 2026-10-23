package dev.juno.main.ui;

import dev.juno.main.model.Ticket;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Shows the agent's question and collects the human's answer. */
final class DecisionDialog extends Dialog<DecisionDialog.Decision> {

    /** Either an answer to resume with, or a request to send the ticket back to Pending. */
    record Decision(boolean backToPending, String answer) {
    }

    DecisionDialog(Window owner, Ticket ticket) {
        initOwner(owner);
        Fx.inheritStyles(this, owner);
        setTitle("Decision needed: " + ticket.getId());
        setHeaderText(ticket.getId() + ": " + ticket.getTitle());
        setResizable(true);

        ButtonType resume = new ButtonType("Resume agent", ButtonBar.ButtonData.OK_DONE);
        ButtonType pending = new ButtonType("Move back to Pending", ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(resume, pending, ButtonType.CANCEL);

        TextArea question = new TextArea(ticket.getPendingQuestion() == null ? "" : ticket.getPendingQuestion());
        question.setEditable(false);
        question.setWrapText(true);
        question.setPrefRowCount(8);
        question.getStyleClass().add("question-text");

        TextArea answer = new TextArea();
        answer.setWrapText(true);
        answer.setPrefRowCount(6);
        answer.setPromptText("Your decision or guidance; it is sent to the agent, which continues its session.");

        VBox content = new VBox(8, new Label("The agent asks:"), question, new Label("Your response:"), answer);
        content.setPrefWidth(640);
        getDialogPane().setContent(content);

        Node resumeButton = getDialogPane().lookupButton(resume);
        resumeButton.disableProperty().bind(answer.textProperty().isEmpty());

        setResultConverter(button -> {
            if (button == resume) {
                return new Decision(false, answer.getText());
            }
            if (button == pending) {
                return new Decision(true, null);
            }
            return null;
        });
        setOnShown(e -> answer.requestFocus());
    }
}
