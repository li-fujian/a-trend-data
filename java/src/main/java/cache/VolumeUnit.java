package cache;

/**
 * Tencent daily K-line volume is not a single unit across the whole A-share market.
 *
 * <p>Main board, ChiNext and B-shares are quoted in lots (手 = 100 shares).
 * STAR Market ({@code sh688}/{@code sh689}) and Beijing Stock Exchange use
 * 1-share order increments, so Tencent returns shares directly. Treating those
 * boards as lots and multiplying by 100 inflates volume by 100x.
 */
public final class VolumeUnit {

    public static final String SHARES = "shares";

    /** Cache files written after the STAR volume-unit fix. */
    public static final int SCHEMA_VERSION = 2;

    private VolumeUnit() {
    }

    public static boolean isIndex(String symbol) {
        if (symbol == null) {
            return false;
        }
        String lower = symbol.toLowerCase();
        return lower.startsWith("sh000") || lower.startsWith("sz399");
    }

    /**
     * Boards whose Tencent K-line volume is already in shares.
     * {@code sh000688} (STAR 50 index) is an index, not a STAR stock.
     */
    public static boolean isShareQuotedSymbol(String symbol) {
        if (symbol == null || isIndex(symbol)) {
            return false;
        }
        String lower = symbol.toLowerCase();
        return lower.startsWith("sh688")
                || lower.startsWith("sh689")
                || lower.startsWith("bj");
    }

    /**
     * Whether raw Tencent K-line volume should be multiplied by 100.
     * Indices are left unchanged; share-quoted boards are already in shares.
     */
    public static boolean shouldScaleLotsToShares(String symbol) {
        if (symbol == null || isIndex(symbol) || isShareQuotedSymbol(symbol)) {
            return false;
        }
        return true;
    }

    /**
     * Infer lot-vs-share scaling from a quote that carries both volume and amount.
     *
     * @return {@code Boolean.TRUE} if volume is in lots and should be *100;
     *         {@code Boolean.FALSE} if volume is already in shares;
     *         {@code null} if the quote is too ambiguous to decide
     */
    public static Boolean detectScaleFromQuote(Double price, Double volume, Double amountYuan) {
        if (price == null || volume == null || amountYuan == null) {
            return null;
        }
        if (!(price > 0 && volume > 0 && amountYuan > 0)) {
            return null;
        }

        double vwapShares = amountYuan / volume;
        double vwapLots = amountYuan / (volume * 100.0);
        double errShares = Math.abs(vwapShares - price) / price;
        double errLots = Math.abs(vwapLots - price) / price;

        if (errShares < 0.25 && errShares < errLots * 0.5) {
            return Boolean.FALSE;
        }
        if (errLots < 0.25 && errLots < errShares * 0.5) {
            return Boolean.TRUE;
        }
        return null;
    }
}
