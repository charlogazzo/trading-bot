package com.foreshock.tradingbot;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Walk-forward tester: time-based folds with parameter search on train, OOS test on the next window
 * Uses BacktestHourly.RiskBacktester for realistic sizing and PnL
 */
public class WalkForwardTester {

    public static final Logger log = LoggerFactory.getLogger(WalkForwardTester.class);

    /* ----------------------------- Config ----------------------------- */
    public static final class Config {
        public Period trainPeriod = Period.ofMonths(2);
        public Period testPeriod = Period.ofMonths(1);

        // Parameter grid (coarse & robust; ensure fast < slow)
        public int[] fastSmaGrid = new int[]{20, 30, 50};
        public int[] slowSmaGrid = new int[]{50, 100, 200};
        public int[] rsiThreshGrid = new int[]{50, 60, 80};

        // Risk config (copied into each fold; you can adjust per fold if needed)
        public BacktestHourly.RiskBacktester.Config risk = new BacktestHourly.RiskBacktester.Config();

        // Scoring function for parameter selection on train
        public Scoring scoring = Scoring.RETURN_OVER_DRAWDOWN;

        // Minimum bars required in a slice (safety)
        public int minBarsTrain = 250;  // ~ needed for SMA60 and warmup
        public int minBarsTest = 50;   // ensure some trades possible
    }

    public enum Scoring {
        RETURN_OVER_DRAWDOWN,  // totalReturn / (1 + maxDD)
        PROFIT_FACTOR,         // sum(positive pnl) / abs(sum(negative pnl))
        TOTAL_RETURN           // plain total return
    }

    public static final class Params {
        public final int fast;
        public final int slow;
        public final int rsi;

        public Params(int fast, int slow, int rsi) {
            this.fast = fast;
            this.slow = slow;
            this.rsi = rsi;
        }

        public String toString() {
            return "fast=" + fast + ", slow=" + slow + ", rsi<" + rsi;
        }
    }

    /**
     * Captures results after running tests on one set of Params
     */
    public static final class FoldResult {
        public ZonedDateTime trainStart, trainEnd, testStart, testEnd;
        public Params trainedParams;
        public BacktestHourly.RiskBacktester.Result trainRes;
        public BacktestHourly.RiskBacktester.Result testRes;

        @Override
        public String toString() {
            return String.format(
                    "Fold [train=%s→%s, test=%s→%s]  params={%s}  OOS: return=%.2f%%, mdd=%.2f%%, trades=%d, winRate=%.1f%%",
                    trainStart, trainEnd, testStart, testEnd,
                    trainedParams, 100 * testRes.totalReturn, 100 * testRes.maxDrawdown, testRes.trades.size(), 100 * testRes.winRate
            );
        }
    }


    public static final class Summary {
        public List<FoldResult> folds = new ArrayList<>();
        public double oosReturnCumulative;   // compound: product(1+r)-1
        public double oosMaxDrawdown;        // computed from stitched equity curve
        public double oosWinRate;            // weighted by trades
        public int oosTrades;
        public Map<Params, Integer> paramsFrequency = new LinkedHashMap<>();

        @Override
        public String toString() {
            return String.format("WFT Summary: folds=%d, OOS trades=%d, OOS return=%.2f%%, OOS maxDD=%.2f%%, OOS winRate=%.2f%%",
                    folds.size(), oosTrades, 100 * oosReturnCumulative, 100 * oosMaxDrawdown, 100 * oosWinRate);
        }
    }

    /* -------------------------- Public API --------------------------- */

    public static Summary runWalkForward(BarSeries fullSeries, Config cfg,
                                         Function<Params, StrategyBuilder> strategyFactory) {

        // Build fold boundaries by time (train window followed by test window)
        List<int[]> foldIndices = buildTimeFolds(fullSeries, cfg.trainPeriod, cfg.testPeriod,
                cfg.minBarsTrain, cfg.minBarsTest);

        Summary summary = new Summary();


        for (int[] idx : foldIndices) {
            int iTrainStart = idx[0], iTrainEnd = idx[1]; // inclusive..exclusive
            int iTestStart = idx[2], iTestEnd = idx[3];

            BarSeries train = fullSeries.getSubSeries(iTrainStart, iTrainEnd);
            BarSeries test = fullSeries.getSubSeries(iTestStart, iTestEnd);

            // 1) Parameter search on train
            Params best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            BacktestHourly.RiskBacktester.Result bestTrainRes = null;

            for (int fast : cfg.fastSmaGrid) {
                for (int slow : cfg.slowSmaGrid) {
                    if (fast >= slow) continue; // constraint
                    for (int rsi : cfg.rsiThreshGrid) {
                        Params p = new Params(fast, slow, rsi);
                        StrategyBuilder sb = strategyFactory.apply(p);
                        Strategy trainStrat = sb.build(train);
                        BacktestHourly.RiskBacktester.Result tr = BacktestHourly.RiskBacktester.simulate(train, trainStrat, cfg.risk);
                        double score = scoreFold(tr, cfg.scoring);
                        if (score > bestScore) {
                            bestScore = score;
                            best = p;
                            bestTrainRes = tr;
                        }
                    }
                }
            }
            if (best == null) continue; // skip if no feasible params


            // 2) OOS test with best params
            StrategyBuilder sbBest = strategyFactory.apply(best);
            Strategy testStrat = sbBest.build(test);
            BacktestHourly.RiskBacktester.Result te = BacktestHourly.RiskBacktester.simulate(test, testStrat, cfg.risk);

            FoldResult fr = new FoldResult();
            fr.trainStart = train.getFirstBar().getEndTime();
            fr.trainEnd = train.getLastBar().getEndTime();
            fr.testStart = test.getFirstBar().getEndTime();
            fr.testEnd = test.getLastBar().getEndTime();
            fr.trainedParams = best;
            fr.trainRes = bestTrainRes;
            fr.testRes = te;

            summary.folds.add(fr);
            summary.paramsFrequency.merge(best, 1, Integer::sum);
        }

        // Aggregate OOS metrics
        stitchAndSummarize(fullSeries, summary);

        return summary;

    }


    /* ----------------------------- Helpers ----------------------------- */

    /**
     * Strategy builder so we can recreate strategies on train/test series with given params.
     */
    public interface StrategyBuilder {
        Strategy build(BarSeries s);
    }

    private static double scoreFold(BacktestHourly.RiskBacktester.Result r, Scoring scoring) {
        switch (scoring) {
            case PROFIT_FACTOR:
                double pos = 0.0, neg = 0.0;
                for (BacktestHourly.RiskBacktester.Trade t : r.trades) {
                    if (t.pnl >= 0) pos += t.pnl;
                    else neg += t.pnl;
                }
                if (pos == 0.0 && neg == 0.0) return 0.0;
                return pos / Math.max(1e-9, Math.abs(neg));
            case TOTAL_RETURN:
                return r.totalReturn;
            case RETURN_OVER_DRAWDOWN:
            default:
                return r.totalReturn / (1.0 + r.maxDrawdown); // conservative
        }
    }


    /**
     * Build time-based folds: [trainStart, trainEnd) followed by [testStart, testEnd)
     * Returns list of index quadruples: {iTrainStart, iTrainEnd, iTestStart, iTestEnd}
     */
    private static List<int[]> buildTimeFolds(BarSeries series, java.time.Period trainP, java.time.Period testP,
                                              int minTrainBars, int minTestBars) {
        List<int[]> out = new ArrayList<>();
        if (series.isEmpty()) return out;

        ZonedDateTime first = series.getFirstBar().getEndTime();
        ZonedDateTime last = series.getLastBar().getEndTime();

        ZonedDateTime cursor = first;
        while (true) {
            ZonedDateTime trainStartT = cursor;
            ZonedDateTime trainEndT = trainStartT.plus(trainP);
            ZonedDateTime testStartT = trainEndT;
            ZonedDateTime testEndT = testStartT.plus(testP);

            if (!testEndT.isBefore(last) && !testEndT.isEqual(last)) break; // we need full test window

            int iTrainStart = indexOfTime(series, trainStartT);
            int iTrainEnd = indexOfTime(series, trainEndT);
            int iTestStart = indexOfTime(series, testStartT);
            int iTestEnd = indexOfTime(series, testEndT);

            if (iTrainStart < 0 || iTrainEnd < 0 || iTestStart < 0 || iTestEnd < 0) break;

            int trainBars = iTrainEnd - iTrainStart;
            int testBars = iTestEnd - iTestStart;
            if (trainBars >= minTrainBars && testBars >= minTestBars) {
                out.add(new int[]{iTrainStart, iTrainEnd, iTestStart, iTestEnd});
            }

            cursor = testStartT; // slide by one test window; use overlapping train windows (standard WFT)
        }
        return out;
    }


    /**
     * Find the first bar whose end time >= target.
     */
    private static int indexOfTime(BarSeries s, ZonedDateTime target) {
        for (int i = 0; i < s.getBarCount(); i++) {
            if (!s.getBar(i).getEndTime().isBefore(target)) return i;
        }
        return -1;
    }


    /**
     * Build OOS summary: cumulative return, stitched max DD, winRate weighted by trades.
     */
    private static void stitchAndSummarize(BarSeries full, Summary sum) {
        double equity = 1.0; // normalize
        double peak = 1.0;
        double maxDD = 0.0;

        int trades = 0, wins = 0;

        for (FoldResult fr : sum.folds) {
            double r = fr.testRes.totalReturn; // fold return
            equity *= (1.0 + r);
            peak = Math.max(peak, equity);
            if (peak > 0) {
                maxDD = Math.max(maxDD, (peak - equity) / peak);
            }
            trades += fr.testRes.trades.size();
            for (BacktestHourly.RiskBacktester.Trade t : fr.testRes.trades) if (t.pnl > 0) wins++;
        }

        sum.oosReturnCumulative = equity - 1.0;
        sum.oosMaxDrawdown = maxDD;
        sum.oosTrades = trades;
        sum.oosWinRate = (trades == 0) ? 0.0 : (wins * 1.0 / trades);
    }


    /* ----------------------------- Strategy factory ----------------------------- */

    /**
     * A convenient factory for your SMA/RSI strategy with variable params.
     */
    public static StrategyBuilder smaRsiFactory(Params p) {
        return (BarSeries s) -> {
            ClosePriceIndicator close = new ClosePriceIndicator(s);
            SMAIndicator smaFast = new SMAIndicator(close, p.fast);
            SMAIndicator smaSlow = new SMAIndicator(close, p.slow);
            RSIIndicator rsi14 = new RSIIndicator(close, 14);

            Rule entry = new CrossedUpIndicatorRule(smaFast, smaSlow)
                    .and(new UnderIndicatorRule(rsi14, s.numOf(p.rsi)));
            Rule exit = new CrossedDownIndicatorRule(smaFast, smaSlow);

            return new BaseStrategy(entry, exit);
        };
    }
}
