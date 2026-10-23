module dev.juno.main {
    requires javafx.controls;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.annotation;

    // Jackson maps model/store classes by field reflection.
    opens dev.juno.main.model to com.fasterxml.jackson.databind;
    opens dev.juno.main.store to com.fasterxml.jackson.databind;

    exports dev.juno.main;
}
