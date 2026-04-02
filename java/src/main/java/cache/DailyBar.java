package cache;

import java.util.Objects;

/**
 * OHLCV bar representing one trading day
 * Used by KLineCache (persistence) and TrendFollowingEngine (computation)
 * Fields match Sina JSON format and cache file format
 *
 * Note: Double fields may be null when data is unavailable or parsing fails.
 * Callers must check for null before performing arithmetic operations.
 */
public class DailyBar {
    private String date;
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Double volume;

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Double getOpen() {
        return open;
    }

    public void setOpen(Double open) {
        this.open = open;
    }

    public Double getHigh() {
        return high;
    }

    public void setHigh(Double high) {
        this.high = high;
    }

    public Double getLow() {
        return low;
    }

    public void setLow(Double low) {
        this.low = low;
    }

    public Double getClose() {
        return close;
    }

    public void setClose(Double close) {
        this.close = close;
    }

    public Double getVolume() {
        return volume;
    }

    public void setVolume(Double volume) {
        this.volume = volume;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DailyBar dailyBar = (DailyBar) o;
        return Objects.equals(date, dailyBar.date) &&
               Objects.equals(open, dailyBar.open) &&
               Objects.equals(high, dailyBar.high) &&
               Objects.equals(low, dailyBar.low) &&
               Objects.equals(close, dailyBar.close) &&
               Objects.equals(volume, dailyBar.volume);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, open, high, low, close, volume);
    }

    @Override
    public String toString() {
        return "DailyBar{" +
               "date='" + date + '\'' +
               ", open=" + open +
               ", high=" + high +
               ", low=" + low +
               ", close=" + close +
               ", volume=" + volume +
               '}';
    }
}
