package cache;

import org.junit.Test;

import static org.junit.Assert.*;

public class VolumeUnitTest {

    @Test
    public void testShareQuotedBoards() {
        assertTrue(VolumeUnit.isShareQuotedSymbol("sh688256"));
        assertTrue(VolumeUnit.isShareQuotedSymbol("SH688981"));
        assertTrue(VolumeUnit.isShareQuotedSymbol("sh689009"));
        assertTrue(VolumeUnit.isShareQuotedSymbol("bj430047"));
        assertFalse(VolumeUnit.isShareQuotedSymbol("sh600000"));
        assertFalse(VolumeUnit.isShareQuotedSymbol("sh601688"));
        assertFalse(VolumeUnit.isShareQuotedSymbol("sz300059"));
        assertFalse(VolumeUnit.isShareQuotedSymbol("sh000688"));
        assertFalse(VolumeUnit.isShareQuotedSymbol("sz399006"));
    }

    @Test
    public void testShouldScaleLotsToShares() {
        assertTrue(VolumeUnit.shouldScaleLotsToShares("sh600000"));
        assertTrue(VolumeUnit.shouldScaleLotsToShares("sz300059"));
        assertTrue(VolumeUnit.shouldScaleLotsToShares("sh900901"));
        assertFalse(VolumeUnit.shouldScaleLotsToShares("sh688256"));
        assertFalse(VolumeUnit.shouldScaleLotsToShares("sh689009"));
        assertFalse(VolumeUnit.shouldScaleLotsToShares("sh000001"));
        assertFalse(VolumeUnit.shouldScaleLotsToShares("bj430047"));
    }

    @Test
    public void testDetectCambriconQuoteIsShares() {
        // 2026-08-21: amount 12,446,848,997 / volume 12,033,293 ≈ 1034 vs price 1035
        Boolean scale = VolumeUnit.detectScaleFromQuote(1035.00, 12_033_293.0, 12_446_848_997.0);
        assertEquals(Boolean.FALSE, scale);
    }

    @Test
    public void testDetectPudongQuoteIsLots() {
        // 2026-08-21: amount 465,159,863 / (volume 512,703 * 100) ≈ 9.07 vs price 9.05
        Boolean scale = VolumeUnit.detectScaleFromQuote(9.05, 512_703.0, 465_159_863.0);
        assertEquals(Boolean.TRUE, scale);
    }

    @Test
    public void testDetectReturnsNullWhenAmbiguous() {
        assertNull(VolumeUnit.detectScaleFromQuote(10.0, 100.0, 50.0));
        assertNull(VolumeUnit.detectScaleFromQuote(null, 1.0, 1.0));
    }
}
