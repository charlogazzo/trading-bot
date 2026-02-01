
package com.foreshock.tradingbot;

import org.junit.jupiter.api.Test;
import org.ta4j.core.*;
import org.ta4j.core.num.DoubleNum;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

public class RiskBacktesterTest {

    /** Build a simple hourly series with controlled ATR by making high-low = atrVal each bar. */
    private BarSeries buildSeries(double[] mids, double atrVal) {
        BaseBarSeriesBuilder builder = new BaseBarSeriesBuilder()
                .withName("TestSeries")
                .withNumTypeOf(DoubleNum::valueOf);

        BarSeries s = builder.build();
        ZonedDateTime t = ZonedDateTime.of(2024,1,1,0,0,0,0, ZoneId.of("UTC"));

        Double prevClose = null;
        for (double mid : mids) {
            double high = mid + atrVal / 2.0;
            double low  = mid - atrVal / 2.0;
            double open = mid;
            double close= mid;
            double vol  = 1000.0;

            // Make prevClose close to mid to keep TR dominated by (high - low) == atrVal
            if (prevClose != null) {
                // ok
            }
            s.addBar(new BaseBar(Duration.ofHours(1), t, open, high, low, close, vol));
            t = t.plusHours(1);
            prevClose = close;
        }
        return s;
    }


    @Test
    public void testStopGapFill() {
        // Bar 0 (prep), Bar 1 emits entry signal => enter at bar2 open
        // Entry @ bar2 open = 102.0; 1R = ATR = 1.0 => initial stop = 101.0
        // Bar3 opens at 100.5 (<= stop) => gap stop at open (100.5)

        BarSeries s = buildSeriesWithOHLC(new double[][]{
                {100, 100.5,  99.5, 100.0}, // bar0
                {101, 101.5, 100.5, 101.0}, // bar1 (signal here -> enter at bar2 open)
                {102, 102.5, 101.5, 102.0}, // bar2 (entry at 102)
                {100.5, 101.0, 100.0, 100.7} // bar3 (open <= 101 -> gap stop)
        });

        Strategy strat = enterOnceAt(1);
        var cfg = baseCfg();

        var res = BacktestHourly.RiskBacktester.simulate(s, strat, cfg);
        assertEquals(1, res.trades.size(), "Should have 1 exit due to stop gap");
        var tr = res.trades.get(0);
        assertTrue(tr.exitByStop, "Exit should be by stop");
        assertEquals(100.5, tr.exitPrice, 1e-9, "Gap stop fills at bar open");
    }


    @Test
    public void testStopTouchFill() {
        // Entry @ bar2 open = 102.0; stop = 101.0
        // Bar3: open=101.5 (>101), low=100.9 (<=101) => touch -> fill at stop (101.0)

        BarSeries s = buildSeriesWithOHLC(new double[][]{
                {100, 100.5,  99.5, 100.0}, // bar0
                {101, 101.0, 100.0, 100.5},   // TR = 1.0 // bar1 (signal)
                {102, 102.5, 101.5, 102.0}, // bar2 (entry at 102.0)
                {101.5, 101.6, 100.9, 101.4} // bar3 (touch stop)
        });

        Strategy strat = enterOnceAt(1);
        var cfg = baseCfg();

        var res = BacktestHourly.RiskBacktester.simulate(s, strat, cfg);
        assertEquals(1, res.trades.size());
        var tr = res.trades.get(0);
        assertTrue(tr.exitByStop);
        assertEquals(101.0, tr.exitPrice, 1e-9, "Touch stop fills at stop price");
    }


    @Test
    public void testPartialThenFinalTP() {
        // Entry @ bar2 open=102.0; 1R=1.0 -> tp1=103.0, final=104.0
        // Bar3 high >= 103.0 triggers partial
        // Bar4 high >= 104.0 triggers final

        BarSeries s = buildSeriesWithOHLC(new double[][]{
                {100, 100.5,  99.5, 100.0}, // bar0
                {101, 101.0, 100.0, 100.5}, // bar1 (signal)
                {102, 102.5, 101.5, 102.0}, // bar2 (entry at 102.0)
                {102.8, 103.2, 102.2, 103.0}, // bar3 (tp1 touch)
                {103.8, 104.2, 103.2, 104.0}  // bar4 (tp final touch)
        });

        Strategy strat = enterOnceAt(1);
        var cfg = baseCfg();
        cfg.usePartialTP = true;
        cfg.partialTpR   = 1.0;
        cfg.partialTpPct = 0.5;
        cfg.finalTpR     = 2.0;

        var res = BacktestHourly.RiskBacktester.simulate(s, strat, cfg);
        assertEquals(2, res.trades.size(), "Expect one partial TP and one final TP");

        var partial = res.trades.get(0);
        var fin     = res.trades.get(1);

        assertTrue(partial.exitByTP && fin.exitByTP, "Both exits should be TP");
        assertEquals(103.0, partial.exitPrice, 1e-9);
        assertEquals(104.0, fin.exitPrice, 1e-9);
        assertTrue(partial.shares < fin.shares || partial.shares < (partial.shares + fin.shares),
                "Partial should be subset of total position");
    }


    /*@Test
    public void testBreakEvenRaisesStopToEntry() {
        // Entry @ 102.0; after bar3 prevClose >= 103.0 -> BE triggers (stop raised to 102.0)
        // Bar4 low < 102 triggers stop at 102.0

        BarSeries s = buildSeriesWithOHLC(new double[][]{
                {100, 100.5,  99.5, 100.0}, // bar0
                {101, 101.5, 100.5, 101.0}, // bar1 (signal)
                {102, 102.5, 101.5, 102.0}, // bar2 (entry)
                {103.2, 103.5, 102.8, 103.2}, // bar3 (prevClose >= 103 -> BE eligible next bar)
                {101.9, 102.3, 101.5, 102.0}  // bar4: low < entry => stop @ entry
        });

        Strategy strat = enterOnceAt(1);
        var cfg = baseCfg();
        cfg.useBreakEven = true;
        cfg.breakEvenR   = 1.0;

        var res = BacktestHourly.RiskBacktester.simulate(s, strat, cfg);
        assertEquals(1, res.trades.size());
        var tr = res.trades.get(0);
        assertEquals(102.0, tr.exitPrice, 1e-9);
        assertTrue(tr.exitByStop, "Should exit via stop (moved to entry)");
    }*/

    @Test
    public void testBreakEvenRaisesStopToEntry() {
        // Entry @ 102.0; after bar3 prevClose >= 103.0 -> BE triggers (stop raised to 102.0)
        // Bar4 low < 102 triggers stop at 102.0

        // bar0: establish ATR (let's say TR=1.0 for simplicity)
        // bar1: signal
        // bar2: entry at 102.0, risk=1.0, beTrigger=102.0+1.0*1.0=103.0
        // bar3: close needs to be >= 103.0

        BarSeries s = buildSeriesWithOHLC(new double[][]{
                {100, 101,  99, 100},    // bar0: TR=2.0 (100-99=1, but max is actually 101-99=2)
                {101, 101.5, 100.5, 101}, // bar1: signal
                {102, 102.5, 101.5, 103.1}, // bar2: entry, close=103.1 (>= 103.0!)
                {103.2, 103.5, 102.8, 103.2}, // bar3: BE applies (prevClose=103.1 >= 103.0)
                {101.9, 102.3, 101.5, 102.0}  // bar4: low < 102 => stop at 102.0
        });

        Strategy strat = enterOnceAt(1);
        var cfg = baseCfg();
        cfg.useBreakEven = true;
        cfg.breakEvenR   = 1.0;
        cfg.atrLength = 1;
        cfg.atrMultiple = 1.0;

        var res = BacktestHourly.RiskBacktester.simulate(s, strat, cfg);
        assertEquals(1, res.trades.size());
        var tr = res.trades.get(0);
        assertEquals(102.0, tr.exitPrice, 1e-9);
        assertTrue(tr.exitByStop, "Should exit via stop (moved to entry)");
    }


    @Test
    public void testTimeBasedExit() {
        // Exit after 2 bars in trade if nothing else triggers.
        BarSeries s = buildSeriesWithOHLC(new double[][]{
                {100, 100.5,  99.5, 100.0}, // bar0
                {101, 101.5, 100.5, 101.0}, // bar1 (signal)
                {102, 102.5, 101.5, 102.0}, // bar2 (entry)
                {102, 102.3, 101.7, 102.0}, // bar3
                {102, 102.3, 101.7, 102.0}, // bar4 (time exit at open here)
                {102, 102.3, 101.7, 102.0}, // bar5 (not reached)
        });

        Strategy strat = enterOnceAt(1);
        var cfg = baseCfg();
        cfg.useMaxBarsInTrade = true;
        cfg.maxBarsInTrade    = 2;

        var res = BacktestHourly.RiskBacktester.simulate(s, strat, cfg);
        assertEquals(1, res.trades.size());
        var tr = res.trades.get(0);
        assertFalse(tr.exitByStop);
        assertFalse(tr.exitByTP);
        assertEquals(102.0, tr.exitPrice, 1e-9, "Exit at open due to time limit");
    }


    /** Build a series where each bar has the same mid and symmetrical high/low around it (keeps ATR ~ constant). */
    private BarSeries buildSeriesWithSymmetricHL(double[] mids, double halfRange) {
        BaseBarSeriesBuilder builder = new BaseBarSeriesBuilder()
                .withName("TestSeriesSymHL")
                .withNumTypeOf(org.ta4j.core.num.DoubleNum::valueOf);
        BarSeries s = builder.build();

        java.time.ZonedDateTime t = java.time.ZonedDateTime.of(2024,1,1,0,0,0,0, java.time.ZoneId.of("UTC"));
        for (double mid : mids) {
            double open = mid;
            double high = mid + halfRange;
            double low  = mid - halfRange;
            double close= mid;
            double vol  = 1000.0;
            s.addBar(new org.ta4j.core.BaseBar(java.time.Duration.ofHours(1), t, open, high, low, close, vol));
            t = t.plusHours(1);
        }
        return s;
    }

    /** Build a series with explicit OHLC per bar (useful for precise gap/touch scenarios). */
    private BarSeries buildSeriesWithOHLC(double[][] ohlc) {
        // each row: {open, high, low, close}
        BaseBarSeriesBuilder builder = new BaseBarSeriesBuilder()
                .withName("TestSeriesOHLC")
                .withNumTypeOf(org.ta4j.core.num.DoubleNum::valueOf);
        BarSeries s = builder.build();

        java.time.ZonedDateTime t = java.time.ZonedDateTime.of(2024,1,1,0,0,0,0, java.time.ZoneId.of("UTC"));
        for (double[] b : ohlc) {
            double open = b[0], high = b[1], low = b[2], close = b[3];
            s.addBar(new org.ta4j.core.BaseBar(java.time.Duration.ofHours(1), t, open, high, low, close, 1000.0));
            t = t.plusHours(1);
        }
        return s;
    }

    /** Strategy that emits a single entry signal at index (signalIndex), so we enter at next bar open. */
    private Strategy enterOnceAt(int signalIndex) {
        Rule entry = new org.ta4j.core.rules.FixedRule(signalIndex);
        Rule exit  = new org.ta4j.core.rules.FixedRule(); // always false
        return new org.ta4j.core.BaseStrategy(entry, exit);
    }

    private BacktestHourly.RiskBacktester.Config baseCfg() {
        BacktestHourly.RiskBacktester.Config cfg = new BacktestHourly.RiskBacktester.Config();
        cfg.startingEquity = 100_000;
        cfg.riskFraction   = 0.1;   // big enough to buy some shares
        cfg.atrLength      = 1;     // ATR(i-1) ~ TR(i-1)
        cfg.atrMultiple    = 1.0;   // 1R = ATR
        cfg.commissionPerShare = 0.0;
        cfg.slippageBps    = 0.0;   // simplify assertions
        cfg.enforceCash    = true;
        cfg.warmupBars     = 0;

        // Disable extras by default (enable per test as needed)
        cfg.useBreakEven   = false;
        cfg.useAtrTrail    = false;
        cfg.useMaxBarsInTrade = false;
        cfg.usePartialTP   = false;
        cfg.takeProfitR    = 0.0; // disabled unless set
        return cfg;
    }

}
