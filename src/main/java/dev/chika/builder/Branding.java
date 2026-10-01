package dev.chika.builder;

/**
 * Branding constants for Chika Builder.
 *
 * <p>Product name is "Chika Builder"; the studio behind it is "D Web Studio".
 * Kept in one place so every user-facing surface stays consistent.
 */
public final class Branding {

    /** The product name shown to players. */
    public static final String PRODUCT_NAME = "Chika Builder";

    /** The brand / company name used in the watermark and about screen. */
    public static final String STUDIO_NAME = "D Web Studio";

    /**
     * Headline form used on the settings/about screen.
     *
     * <p>Requirement: present as
     * <pre>
     * CHIKA BUILDER
     * by D Web Studio
     * </pre>
     */
    public static final String PRODUCT_DISPLAY = "CHIKA BUILDER";

    /** The "by ..." byline shown directly under the product name. */
    public static final String PRODUCT_BYLINE = "by " + STUDIO_NAME;

    /** The watermark text drawn in the HUD corner. */
    public static final String WATERMARK_TEXT = STUDIO_NAME;

    /** Translation keys (see assets/chika-builder/lang/en_us.json). */
    public static final String LANG_ROOT = "chika_builder";

    private Branding() {
    }
}