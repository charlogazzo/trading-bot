package com.foreshock.tradingbot.strategy;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
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

    /**
     * Opening Range Breakout strategy.
     * Entry: Close price crosses above the highest high of the opening range (first N bars)
     * Exit: Close price crosses below the lowest low of the opening range (first N bars)
     *
     * @param series the bar series
     * @param openingRangeBars number of bars that define the opening range (must be >= 1)
     * @return a Strategy implementing the opening range breakout
     */
    public static Strategy openingRangeBreakout(BarSeries series, int openingRangeBars) {
        if (openingRangeBars < 1) {
            throw new IllegalArgumentException("openingRangeBars must be >= 1");
        }

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        HighPriceIndicator high = new HighPriceIndicator(series);
        LowPriceIndicator low = new LowPriceIndicator(series);

        HighestValueIndicator openingHigh = new HighestValueIndicator(high, openingRangeBars);
        LowestValueIndicator openingLow = new LowestValueIndicator(low, openingRangeBars);

        return new BaseStrategy(
                new CrossedUpIndicatorRule(close, openingHigh),
                new CrossedDownIndicatorRule(close, openingLow)
        );
    }
}
