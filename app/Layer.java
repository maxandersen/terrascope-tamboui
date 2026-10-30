import dev.tamboui.widgets.canvas.Context;

/*
 * A toggleable overlay drawn onto the shared braille Canvas. Mirrors
 * Terrascope's core/layer.py: a key toggles it, it paints itself given the
 * current viewport. Vector layers (outlines, provinces, cities, flights,
 * quakes, day/night) implement this; the raster land base is separate because
 * it draws under the Canvas via tamboui-image.
 */
interface Toggleable {
    char key();
    String label();        // short footer text, e.g. "cities"
    boolean enabled();
    void toggle();
    default void start() { }   // optional background refresh
}

/** A vector overlay drawn onto the shared Canvas Context (foreground glyphs). */
interface Layer extends Toggleable {
    void draw(Context ctx, View view);
}

/**
 * A raster layer painted as per-cell backgrounds, under the glyph overlays.
 * Applied after the Canvas so its bg patches survive (setStyle keeps the glyph).
 * Land coverage-shading and radar tile-mosaic are both BgLayers.
 */
interface BgLayer extends Toggleable {
    void renderBg(dev.tamboui.buffer.Buffer buffer, dev.tamboui.layout.Rect area, View view);
}

/** Base for the common enabled/toggle boilerplate. */
abstract class AbstractLayer implements Layer {
    private boolean enabled;
    AbstractLayer(boolean enabled) { this.enabled = enabled; }
    public boolean enabled() { return enabled; }
    public void toggle() { enabled = !enabled; }
}
