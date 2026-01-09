
package com.foreshock.tradingbot;

import org.ta4j.core.*;
import org.ta4j.core.analysis.criteria.MaximumDrawdownCriterion;
import org.ta4j.core.analysis.criteria.NumberOfPositionsCriterion;
import org.ta4j.core.analysis.criteria.pnl.GrossProfitCriterion;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.rules.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-file backtest runner with:
 *  - Baseline TA4J backtest (unit positions)
 *  - Risk-aware backtester (1% risk per trade via ATR-based stop)
 */
public class BacktestHourly {

    /* ============================ Data Loading ============================ */
    static BarSeries loadSeriesFromCsv(String resourceName) throws Exception {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName("HourlyData")
                .withNumTypeOf(DoubleNum::valueOf)    // If your TA4J uses withNumTypeOf, rename accordingly
                .build();

        // CSV timestamps look like: 2024-01-02T10:30
        DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

        InputStream inputStream = BacktestHourly.class.getClassLoader().getResourceAsStream(resourceName);
        if (inputStream == null) {
            throw new IllegalArgumentException("Resource not found: " + resourceName);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String header = br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                LocalDateTime ldt = LocalDateTime.parse(p[0], tsFmt);
                ZonedDateTime endTime = ldt.atZone(ZoneId.systemDefault());

                double open = Double.parseDouble(p[1]);
                double high = Double.parseDouble(p[2]);
                double low  = Double.parseDouble(p[3]);
                double close= Double.parseDouble(p[4]);
                double volume = Double.parseDouble(p[5]);

                // Hourly bars: duration required by BaseBar
                Bar bar = new BaseBar(Duration.ofHours(1), endTime, open, high, low, close, volume);
                series.addBar(bar);
            }
        }
        return series;
    }

    /* ============================ Strategy ============================ */
    /*
        Basic Trading strategy
     */
    static Strategy buildStrategy(BarSeries series) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator smaFast  = new SMAIndicator(close, 20);
        SMAIndicator smaSlow = new SMAIndicator(close, 60);
        RSIIndicator rsi14  = new RSIIndicator(close, 14);

        // Relaxed RSI so you get trades on synthetic data (e.g., 80)
        Rule entryRule = new CrossedUpIndicatorRule(smaFast, smaSlow)
                .and(new UnderIndicatorRule(rsi14, series.numOf(80)));

        // Exit when trend turns down (you can add StopLoss/StopGain in risk engine instead)
        Rule exitRule = new CrossedDownIndicatorRule(smaFast, smaSlow);

        return new BaseStrategy(entryRule, exitRule);
    }

    /* ====================== Risk-Aware Backtester (Nested) ====================== */
    public static final class RiskBacktester {

        public static final class Config {
            public double startingEquity = 100_000.0;
            public double riskFraction = 0.01;   // 1% per trade
            public int atrLength = 14;
            public double atrMultiple = 2.0;     // stop distance = ATR * multiple
            public double takeProfitR = 2.0;     // take profit at 2R; set <=0 to disable
            public double commissionPerShare = 0.001; // $0.001/share
            public double slippageBps = 1.0;     // 1 bp = 0.01% (applied to fills)
            public boolean enforceCash = true;   // do not exceed available cash
            public int warmupBars = 200;         // ensure indicators (SMA200, ATR) are ready
        }

        public static final class Trade {
            public int entryIndex;
            public int exitIndex;
            public double entryPrice;
            public double exitPrice;
            public int shares;
            public boolean exitByStop;
            public boolean exitByTP;
            public double pnl; // realized PnL after commissions
            @Override public String toString() {
                return String.format(
                        "Trade{entryIdx=%d @ %.4f, exitIdx=%d @ %.4f, shares=%d, pnl=%.2f, stop=%s, tp=%s}",
                        entryIndex, entryPrice, exitIndex, exitPrice, shares, pnl, exitByStop, exitByTP
                );
            }
        }

        public static final class Result {
            public List<Double> equityCurve = new ArrayList<>(); // per bar equity at close
            public List<Trade> trades = new ArrayList<>();
            public double finalEquity;
            public double totalReturn;  // finalEquity / startingEquity - 1
            public double maxDrawdown;  // in decimal (0.12 = -12%)
            public double winRate;
        }

        public static Result simulate(BarSeries series, Strategy strategy, Config cfg) {
            Result out = new Result();

            final ClosePriceIndicator close = new ClosePriceIndicator(series);
            final ATRIndicator atr = new ATRIndicator(series, cfg.atrLength);

            int n = series.getBarCount();
            int start = Math.max(cfg.warmupBars, cfg.atrLength) + 1;

            double cash = cfg.startingEquity;

            boolean inPosition = false;
            int posShares = 0;
            double posEntryPrice = 0.0;
            double posStop = Double.NaN;
            int entryIndex = -1;
            double entryCommission = 0.0;

            double peakEquity = cfg.startingEquity;
            double maxDD = 0.0;

            for (int i = start; i < n; i++) {
                Bar prev = series.getBar(i - 1);
                Bar curr = series.getBar(i);

                double prevClose = close.getValue(i - 1).doubleValue();
                double currOpen  = curr.getOpenPrice().doubleValue();
                double currHigh  = curr.getHighPrice().doubleValue();
                double currLow   = curr.getLowPrice().doubleValue();
                double currClose = curr.getClosePrice().doubleValue();
                double atrPrev   = atr.getValue(i - 1).doubleValue();

                // Equity at previous close (mark-to-market)
                double equity = cash + (inPosition ? posShares * prevClose : 0.0);
                peakEquity = Math.max(peakEquity, equity);
                if (peakEquity > 0) {
                    maxDD = Math.max(maxDD, (peakEquity - equity) / peakEquity);
                }

                /* -------- EXIT PHASE (evaluated on prev bar, filled on current bar) -------- */
                if (inPosition) {
                    boolean stopHit = currLow <= posStop; // intrabar breach of stop
                    boolean exitSignal = strategy.shouldExit(i - 1);

                    boolean tpHit = false;
                    if (cfg.takeProfitR > 0) {
                        double riskPerShareAtEntry = posEntryPrice - posStop;
                        double tpPrice = posEntryPrice + cfg.takeProfitR * riskPerShareAtEntry;
                        tpHit = currHigh >= tpPrice;
                    }

                    if (stopHit || exitSignal || tpHit) {
                        // Slippage for sells: subtract
                        double slippageSell = currOpen * (cfg.slippageBps / 10_000.0);

                        double exitFill;
                        if (stopHit) {
                            // Fill at stop (conservative: minus slippage)
                            exitFill = posStop - (posStop * (cfg.slippageBps / 10_000.0));
                        } else {
                            // Strategy/TP exits at open minus slippage
                            exitFill = currOpen - slippageSell;
                        }

                        double proceeds = posShares * exitFill;
                        double exitCommission = posShares * cfg.commissionPerShare;

                        double pnl = proceeds - exitCommission - (posShares * posEntryPrice) - entryCommission;
                        cash += proceeds - exitCommission;

                        Trade tr = new Trade();
                        tr.entryIndex = entryIndex;
                        tr.exitIndex = i;
                        tr.entryPrice = posEntryPrice;
                        tr.exitPrice = exitFill;
                        tr.shares = posShares;
                        tr.exitByStop = stopHit;
                        tr.exitByTP = tpHit;
                        tr.pnl = pnl;
                        out.trades.add(tr);

                        // Reset position
                        inPosition = false;
                        posShares = 0;
                        posEntryPrice = 0.0;
                        posStop = Double.NaN;
                        entryIndex = -1;
                        entryCommission = 0.0;
                    }
                }

                /* -------- ENTRY PHASE (signal on i-1, fill at i open) -------- */
                if (!inPosition && strategy.shouldEnter(i - 1)) {
                    if (!Double.isNaN(atrPrev) && atrPrev > 0) {
                        double riskPerShare = atrPrev * cfg.atrMultiple;
                        double riskBudget = cash * cfg.riskFraction;
                        int shares = (int) Math.floor(riskBudget / riskPerShare);

                        if (shares >= 1) {
                            double slippageBuy = currOpen * (cfg.slippageBps / 10_000.0);
                            double entryFill = currOpen + slippageBuy;
                            double commission = shares * cfg.commissionPerShare;
                            double cost = shares * entryFill + commission;

                            if (!cfg.enforceCash || cost <= cash) {
                                inPosition = true;
                                posShares = shares;
                                posEntryPrice = entryFill;
                                posStop = entryFill - riskPerShare;
                                entryCommission = commission;
                                entryIndex = i;
                                cash -= cost; // pay from cash
                            } else {
                                // Not enough cash -> try scaled position
                                int affordable = (int) Math.floor((cash - commission) / entryFill);
                                if (affordable >= 1) {
                                    inPosition = true;
                                    posShares = affordable;
                                    posEntryPrice = entryFill;
                                    posStop = entryFill - riskPerShare;
                                    entryCommission = affordable * cfg.commissionPerShare;
                                    entryIndex = i;
                                    cash -= (affordable * entryFill + entryCommission);
                                }
                            }
                        }
                    }
                }

                // Record equity at current close
                double equityAtClose = cash + (inPosition ? posShares * currClose : 0.0);
                out.equityCurve.add(equityAtClose);
            }

            out.finalEquity = out.equityCurve.isEmpty() ? cfg.startingEquity : out.equityCurve.get(out.equityCurve.size() - 1);
            out.totalReturn = out.finalEquity / cfg.startingEquity - 1.0;

            // Win rate
            int wins = 0;
            for (Trade t : out.trades) if (t.pnl > 0) wins++;
            out.winRate = out.trades.isEmpty() ? 0.0 : (wins * 1.0 / out.trades.size());

            // Max drawdown recomputed from equity curve
            double peak = Double.NEGATIVE_INFINITY;
            double mdd = 0.0;
            for (double e : out.equityCurve) {
                peak = Math.max(peak, e);
                if (peak > 0) mdd = Math.max(mdd, (peak - e) / peak);
            }
            out.maxDrawdown = mdd;

            return out;
        }
    }

    /*
        Used to debug the trading signals to show even the signals that occur before the
        During the warmup phase
     */
    /*static void debugSignals(BarSeries series, Strategy strategy) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        int crosses = 0;
        for (int i = 0; i < series.getBarCount(); i++) {
            boolean enter = strategy.shouldEnter(i);
            boolean exit  = strategy.shouldExit(i);
            if (enter || exit) {
                System.out.printf("Bar %d  time=%s  enter=%s  exit=%s  close=%.4f%n",
                        i, series.getBar(i).getEndTime(), enter, exit, close.getValue(i).doubleValue());
            }
            if (enter) crosses++;
        }
        System.out.println("Total ENTER signals found: " + crosses);
    }*/


    /* ============================ Main Runner ============================ */
    public static void main(String[] args) throws Exception {
        // 1) Load data from resources
        BarSeries series = loadSeriesFromCsv("hourly_stock_data.csv");

        // 2) Build strategy (SMA(50/200) + RSI filter)
        Strategy strategy = buildStrategy(series);

        // debugging the signals
        // debugSignals(series, strategy);

        // 3) Baseline TA4J backtest (unit positions)
        BarSeriesManager manager = new BarSeriesManager(series);
        TradingRecord record = manager.run(strategy);

        AnalysisCriterion grossProfit = new GrossProfitCriterion();
        AnalysisCriterion maxDDCriterion = new MaximumDrawdownCriterion();
        AnalysisCriterion tradesCriterion = new NumberOfPositionsCriterion();

        System.out.println("=== TA4J Baseline (unit positions) ===");
        System.out.println("Bars: " + series.getBarCount());
        System.out.println("Trades: " + tradesCriterion.calculate(series, record));
        System.out.println("Gross Profit (multiple): " + grossProfit.calculate(series, record));
        System.out.println("Max Drawdown (criterion): " + maxDDCriterion.calculate(series, record));
        System.out.println();

        // 4) Risk-aware backtest (1% risk/trade)
        RiskBacktester.Config cfg = new RiskBacktester.Config();
        cfg.startingEquity = 100_000.0;
        cfg.riskFraction = 0.01;     // 1%
        cfg.atrLength = 14;
        cfg.atrMultiple = 2.0;       // stop = entry - 2*ATR
        cfg.takeProfitR = 2.0;       // TP at 2R (set <= 0 to disable)
        cfg.commissionPerShare = 0.001;
        cfg.slippageBps = 1.0;
        cfg.enforceCash = true;
        cfg.warmupBars = 200;

        RiskBacktester.Result res = RiskBacktester.simulate(series, strategy, cfg);

        System.out.println("=== Risk-Aware Backtest (1% risk/trade) ===");
        System.out.printf("Trades: %d%n", res.trades.size());
        System.out.printf("Final Equity: %.2f%n", res.finalEquity);
        System.out.printf("Total Return: %.2f%%%n", res.totalReturn * 100);
        System.out.printf("Win Rate: %.2f%%%n", res.winRate * 100);
        System.out.printf("Max Drawdown: %.2f%%%n", res.maxDrawdown * 100);

        // Optional: print trade log detail
        for (RiskBacktester.Trade t : res.trades) {
            System.out.println(t);
        }
    }
}
