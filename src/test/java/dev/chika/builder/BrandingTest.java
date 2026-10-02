package dev.chika.builder;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the D Web Studio branding constants.
 *
 * <p>The studio name must stay exactly "D Web Studio" for documentation,
 * credits and the settings screen. {@code Branding.WATERMARK_TEXT} is retained
 * as a documentation constant only: the gameplay HUD watermark it once fed was
 * removed outright, and {@code NoGameplayWatermarkTest} proves no overlay draws
 * it.
 */
class BrandingTest {

    @Test
    void productNameIsChikaBuilder() {
        assertEquals("Chika Builder", Branding.PRODUCT_NAME);
    }

    @Test
    void aboutScreenShowsChikaBuilderByDWebStudio() {
        // Required presentation:
        //   CHIKA BUILDER
        //   by D Web Studio
        assertEquals("CHIKA BUILDER", Branding.PRODUCT_DISPLAY);
        assertEquals("by D Web Studio", Branding.PRODUCT_BYLINE);
    }

    @Test
    void noUserFacingBrandingMentionsTheEngine() {
        for (String value : new String[]{
                Branding.PRODUCT_NAME, Branding.PRODUCT_DISPLAY,
                Branding.STUDIO_NAME, Branding.PRODUCT_BYLINE,
                Branding.WATERMARK_TEXT}) {
            assertFalse(value.toLowerCase(Locale.ROOT).contains("baritone"),
                    "user-facing branding must not mention the engine: " + value);
        }
    }

    @Test
    void studioNameIsDWebStudio() {
        assertEquals("D Web Studio", Branding.STUDIO_NAME);
    }

    @Test
    void watermarkTextMatchesStudioName() {
        assertEquals(Branding.STUDIO_NAME, Branding.WATERMARK_TEXT);
        assertEquals("D Web Studio", Branding.WATERMARK_TEXT);
    }

    @Test
    void brandingContainsNoUrlsOrLinks() {
        // The watermark must stay pure text: no external links or advertising.
        String all = Branding.PRODUCT_NAME + Branding.STUDIO_NAME + Branding.WATERMARK_TEXT;
        assertFalse(all.contains("http"), "branding must not contain URLs");
        assertFalse(all.contains("www."), "branding must not contain URLs");
        assertFalse(all.contains(".com"), "branding must not contain domains");
    }
}