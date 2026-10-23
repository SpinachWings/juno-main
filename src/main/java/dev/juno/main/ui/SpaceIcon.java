package dev.juno.main.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Polygon;
import javafx.scene.transform.Scale;

/** A small ringed planet with a couple of stars, drawn with shapes (no image assets). */
public final class SpaceIcon {

    static final Color LIGHT_BLUE = Color.web("#6FD6FF");
    static final Color MAGENTA = Color.web("#FF4FD8");

    private SpaceIcon() {
    }

    /** The icon at its natural size (about 30×22 px). */
    public static Node create() {
        Group g = new Group();

        // Back half of the ring (drawn behind the planet).
        Arc ringBack = ring(true);

        Circle planet = new Circle(15, 11, 7);
        planet.setFill(new RadialGradient(0, 0, 0.35, 0.3, 0.9, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#C9F1FF")), new Stop(0.55, LIGHT_BLUE), new Stop(1, Color.web("#1E6E91"))));

        // Front half of the ring (drawn over the planet).
        Arc ringFront = ring(false);

        g.getChildren().addAll(ringBack, planet, ringFront, star(3, 3, 1.6, MAGENTA), star(27, 19, 1.3, LIGHT_BLUE),
                dot(26, 4, 0.9, Color.web("#E8E8F0")));
        return g;
    }

    /** The icon rendered to an image, e.g. for the window icon. */
    public static Image image(double scale) {
        Node icon = create();
        icon.getTransforms().add(new Scale(scale, scale));
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return new Group(icon).snapshot(params, null);
    }

    private static Arc ring(boolean back) {
        Arc arc = new Arc(15, 11, 13, 3.6, back ? 0 : 180, 180);
        arc.setType(ArcType.OPEN);
        arc.setFill(null);
        arc.setStroke(MAGENTA);
        arc.setStrokeWidth(1.6);
        arc.setRotate(-18);
        return arc;
    }

    /** A four-pointed sparkle. */
    private static Polygon star(double x, double y, double r, Color color) {
        double s = r * 0.35;
        Polygon p = new Polygon(
                x, y - r * 2, x + s, y - s, x + r * 2, y, x + s, y + s,
                x, y + r * 2, x - s, y + s, x - r * 2, y, x - s, y - s);
        p.setFill(color);
        return p;
    }

    private static Ellipse dot(double x, double y, double r, Color color) {
        Ellipse e = new Ellipse(x, y, r, r);
        e.setFill(color);
        return e;
    }
}
