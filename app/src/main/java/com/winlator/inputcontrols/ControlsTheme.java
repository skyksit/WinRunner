package com.winlator.inputcontrols;

import com.winlator.R;

/**
 * DGPlayer: colour presets applied to every element of a profile at once from the editor.
 * They only set the per-element keys (borderColor/backgroundColor/textColor/opacity), so a themed
 * profile is an ordinary .icp and each element can still be tweaked afterwards.
 */
public final class ControlsTheme {
    public final int nameResId;
    public final Integer border;
    public final Integer background;
    public final Integer text;
    /** Coloured elements skip the overlay opacity, so a theme brings its own or the pad would hide the game. */
    public final float opacity;

    private ControlsTheme(int nameResId, Integer border, Integer background, Integer text, float opacity) {
        this.nameResId = nameResId;
        this.border = border;
        this.background = background;
        this.text = text;
        this.opacity = opacity;
    }

    public static final ControlsTheme[] ALL = {
        // Clears the colours: back to the stock white outline × overlay opacity.
        new ControlsTheme(R.string.theme_default, null, null, null, 1.0f),
        new ControlsTheme(R.string.theme_dark, 0xffffff, 0x000000, 0xffffff, 0.6f),
        new ControlsTheme(R.string.theme_neon_green, 0x39ff14, 0x0a0a0a, 0x39ff14, 0.7f),
        new ControlsTheme(R.string.theme_arcade, 0xd32f2f, 0x000000, 0xfdd835, 0.7f),
        new ControlsTheme(R.string.theme_ocean, 0x00bcd4, 0x0d47a1, 0xffffff, 0.6f),
        new ControlsTheme(R.string.theme_sunset, 0xff8f00, 0x3e2723, 0xffe0b2, 0.65f),
        new ControlsTheme(R.string.theme_purple, 0xb388ff, 0x311b92, 0xffffff, 0.6f),
        new ControlsTheme(R.string.theme_light, 0x424242, 0xffffff, 0x212121, 0.6f),
        new ControlsTheme(R.string.theme_gameboy, 0x0f380f, 0x9bbc0f, 0x0f380f, 0.7f),
        new ControlsTheme(R.string.theme_pink, 0xf48fb1, 0x880e4f, 0xffffff, 0.6f)
    };

    public boolean isDefault() {
        return border == null && background == null && text == null;
    }

    public void applyTo(ControlsProfile profile) {
        for (ControlElement element : profile.getElements()) {
            element.setBorderColor(border);
            element.setBackgroundColor(background);
            element.setTextColor(text);
            element.setOpacity(opacity);
        }
    }
}
