package dev.juno.main.ui;

import dev.juno.main.model.Ticket;
import dev.juno.main.service.BoardService;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Shows the agent's question and collects the human's answer. For a design doc awaiting review it shows the
 * draft and offers Approve (start implementation), Request changes, and Export as .txt.
 */
final class DecisionDialog extends Dialog<DecisionDialog.Decision> {

    /** An answer to resume with, a design approval, or a request to send the ticket back. */
    record Decision(boolean backToPending, boolean approveDesign, String answer) {
    }

    DecisionDialog(Window owner, Ticket ticket, BoardService service) {
        initOwner(owner);
        Fx.inheritStyles(this, owner);
        boolean design = ticket.isDesignAwaitingApproval();
        setTitle((design ? "Design review: " : "Decision needed: ") + ticket.getTitle());
        setHeaderText(ticket.getTitle());
        setResizable(true);

        ButtonType approve = new ButtonType("Approve design & start work", ButtonBar.ButtonData.OK_DONE);
        ButtonType resume = new ButtonType(design ? "Request changes" : "Resume agent",
                design ? ButtonBar.ButtonData.OTHER : ButtonBar.ButtonData.OK_DONE);
        ButtonType export = new ButtonType("Export .txt", ButtonBar.ButtonData.HELP);
        ButtonType pending = new ButtonType("Move back to " + ticket.getRunKind().homeStatus().label(),
                ButtonBar.ButtonData.LEFT);
        if (design) {
            getDialogPane().getButtonTypes().addAll(approve, resume, export, pending, ButtonType.CANCEL);
        } else {
            getDialogPane().getButtonTypes().addAll(resume, pending, ButtonType.CANCEL);
        }

        VBox content = new VBox(8);
        if (design) {
            TextArea doc = new TextArea(ticket.getDesignDoc());
            doc.setEditable(false);
            doc.setWrapText(true);
            doc.setPrefRowCount(18);
            doc.getStyleClass().add("log-text");
            content.getChildren().addAll(new Label("Design doc draft:"), doc);
        }
        TextArea question = new TextArea(ticket.getPendingQuestion() == null ? "" : ticket.getPendingQuestion());
        question.setEditable(false);
        question.setWrapText(true);
        question.setPrefRowCount(design ? 3 : 8);
        question.getStyleClass().add("question-text");

        TextArea answer = new TextArea();
        answer.setWrapText(true);
        answer.setPrefRowCount(5);
        answer.setPromptText(design
                ? "The changes you want; the agent revises the doc and you review it again."
                : "Your decision or guidance; it is sent to the agent, which continues its session.");

        content.getChildren().addAll(new Label(design ? "Agent:" : "The agent asks:"), question,
                new Label(design ? "Your feedback:" : "Your response:"), answer);
        content.setPrefWidth(design ? 820 : 640);
        getDialogPane().setContent(content);

        Node resumeButton = getDialogPane().lookupButton(resume);
        resumeButton.disableProperty().bind(answer.textProperty().isEmpty());

        if (design) {
            Button exportButton = (Button) getDialogPane().lookupButton(export);
            exportButton.addEventFilter(ActionEvent.ACTION, e -> {
                e.consume(); // keep the dialog open
                exportButton.setDisable(true);
                Fx.async(() -> service.exportDesignDoc(ticket.getId()), path -> {
                    exportButton.setDisable(false);
                    Fx.info(getDialogPane().getScene().getWindow(), "Design doc exported", path.toString());
                }, err -> {
                    exportButton.setDisable(false);
                    Fx.error(getDialogPane().getScene().getWindow(), "Could not export the design doc", err);
                });
            });
        }

        setResultConverter(button -> {
            if (button == approve) {
                return new Decision(false, true, null);
            }
            if (button == resume) {
                return new Decision(false, false, answer.getText());
            }
            if (button == pending) {
                return new Decision(true, false, null);
            }
            return null;
        });
        setOnShown(e -> answer.requestFocus());
    }
}
