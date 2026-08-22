package cache;

import java.util.List;

/**
 * Guards against silent 100x volume bugs by checking implied turnover
 * {@code close * volume / marketCap}. A 100x STAR-board error shows up as
 * multi-day turnover well above 100%; real A-share sessions almost never do.
 */
public final class VolumeSanity {

    /** Warn when a single session turns over more than the entire market cap. */
    public static final double WARN_TURNOVER = 1.0;

    /** Refuse to write when a session implies more than 200% of market cap traded. */
    public static final double REJECT_SINGLE_DAY = 2.0;

    /** Refuse when at least this many of the last N bars exceed 100% turnover. */
    public static final int REJECT_STREAK_BARS = 10;

    public static final int TAIL_BARS = 20;

    private VolumeSanity() {
    }

    public static Double impliedTurnover(DailyBar bar, Long marketCapYuan) {
        if (bar == null || marketCapYuan == null || marketCapYuan <= 0) {
            return null;
        }
        if (bar.getClose() == null || bar.getVolume() == null) {
            return null;
        }
        if (!(bar.getClose() > 0 && bar.getVolume() > 0)) {
            return null;
        }
        return bar.getClose() * bar.getVolume() / marketCapYuan.doubleValue();
    }

    public static boolean isPlausible(String symbol, List<DailyBar> bars, Long marketCapYuan) {
        return explainReject(symbol, bars, marketCapYuan) == null;
    }

    /**
     * @return rejection reason, or {@code null} if the bars look plausible
     *         (or the check is skipped because market cap is missing)
     */
    public static String explainReject(String symbol, List<DailyBar> bars, Long marketCapYuan) {
        if (marketCapYuan == null || marketCapYuan <= 0 || bars == null || bars.isEmpty()) {
            return null;
        }

        DailyBar last = lastDatedBar(bars);
        Double lastTurnover = impliedTurnover(last, marketCapYuan);
        if (lastTurnover != null && lastTurnover > REJECT_SINGLE_DAY) {
            return String.format(
                    "%s implied turnover %.2f on %s (close*volume/marketCap); refusing to write",
                    symbol, lastTurnover, last.getDate());
        }
        if (lastTurnover != null && lastTurnover > WARN_TURNOVER) {
            System.err.printf(
                    "WARN %s implied turnover %.2f on %s exceeds 100%%%n",
                    symbol, lastTurnover, last.getDate());
        }

        int from = Math.max(0, bars.size() - TAIL_BARS);
        int over = 0;
        int counted = 0;
        for (int i = from; i < bars.size(); i++) {
            Double turnover = impliedTurnover(bars.get(i), marketCapYuan);
            if (turnover == null) {
                continue;
            }
            counted++;
            if (turnover > WARN_TURNOVER) {
                over++;
            }
        }
        if (counted >= REJECT_STREAK_BARS && over >= REJECT_STREAK_BARS) {
            return String.format(
                    "%s implied turnover >100%% on %d of last %d bars; refusing to write",
                    symbol, over, counted);
        }
        return null;
    }

    static DailyBar lastDatedBar(List<DailyBar> bars) {
        DailyBar last = null;
        for (DailyBar bar : bars) {
            if (bar != null && bar.getDate() != null && !bar.getDate().isEmpty()) {
                last = bar;
            }
        }
        return last;
    }
}
