package com.foreshock.tradingbot.strategy;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

/**
 * Central place for reusable strategy factories.
 */
public final class Strategies {

    private Strategies() {}

    /**
     * Generic SMA + RSI strategy builder.
     * Entry: fast SMA crosses up slow SMA AND RSI < rsiThreshold
     * Exit: fast SMA crosses down slow SMA
     */
    public static Strategy smaRsi(BarSeries series, int fastSma, int slowSma, int rsiLength, int rsiThreshold) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator smaFast = new SMAIndicator(close, fastSma);
        SMAIndicator smaSlow = new SMAIndicator(close, slowSma);
        RSIIndicator rsi = new RSIIndicator(close, rsiLength);

        return new BaseStrategy(
                new CrossedUpIndicatorRule(smaFast, smaSlow)
                        .and(new UnderIndicatorRule(rsi, series.numOf(rsiThreshold))),
                new CrossedDownIndicatorRule(smaFast, smaSlow)
        );
    }

    // Convenience presets matching the originals
    public static Strategy sma50_100_rsi14(BarSeries series) {
        return smaRsi(series, 50, 100, 14, 50);
    }

    public static Strategy sma20_60_rsi14(BarSeries series) {
        return smaRsi(series, 20, 60, 14, 50);
    }
}
