
package com.foreshock.tradingbot;

import com.foreshock.tradingbot.alpaca.AlpacaHourlyLoader;
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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Backtest runner that can load data from CSV (resources) or Alpaca API,
 * run baseline TA4J backtest, and run risk-aware backtest (1% risk per trade).
 */
public class BacktestHourly {

    /* ============================ Toggle data source ============================ */
    enum Source { CSV, ALPACA }

    // Update these if you want defaults
    private static final Source DEFAULT_SOURCE = Source.ALPACA; // or Source.CSV
    private static final String DEFAULT_SYMBOL = "AAPL";
    private static final String DEFAULT_CSV_RESOURCE = "hourly_stock_data.csv";
    private static final ZonedDateTime DEFAULT_START = ZonedDateTime.of(LocalDateTime.of(2024, 1, 2, 0, 0), ZoneId.of("UTC"));
    private static final ZonedDateTime DEFAULT_END   = ZonedDateTime.of(LocalDateTime.of(2024, 3, 31, 0, 0), ZoneId.of("UTC"));

    /* ============================ Data Loading ============================ */
    static BarSeries loadSeriesFromCsv(String resourceName) throws Exception {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName("CSV-HourlyData")
                .withNumTypeOf(DoubleNum::valueOf) // change to withNumTypeOf if your TA4J version requires it
                .build();

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
                ZonedDateTime endTime = ZonedDateTime.parse(p[0], tsFmt);

                double open  = Double.parseDouble(p[1]);
                double high  = Double.parseDouble(p[2]);
                double low   = Double.parseDouble(p[3]);
                double close = Double.parseDouble(p[4]);
                double volume= Double.parseDouble(p[5]);

                Bar bar = new BaseBar(Duration.ofHours(1), endTime, open, high, low, close, volume);
                series.addBar(bar);
            }
        }
        return series;
    }

    static BarSeries loadSeriesFromAlpaca(String symbol,
                                          ZonedDateTime start,
                                          ZonedDateTime end) throws Exception {
        // Delegate to your working AlpacaHourlyLoader
        // Your loader reads API keys from env or parameters; here we pass env
        String apiKey = System.getenv("ALPACA_API_KEY");
        String apiSecret = System.getenv("ALPACA_API_SECRET");
        if (apiKey == null || apiSecret == null) {
            throw new IllegalStateException("Missing env vars ALPACA_API_KEY / ALPACA_API_SECRET");
        }
        return AlpacaHourlyLoader.loadHourlyBars(symbol, start, end, apiKey, apiSecret);
    }

    static BarSeries loadSeries(Source source,
                                String symbol,
                                String csvResource,
                                ZonedDateTime start,
                                ZonedDateTime end) throws Exception {
        switch (source) {
            case CSV:    return loadSeriesFromCsv(csvResource);
            case ALPACA: return loadSeriesFromAlpaca(symbol, start, end);
            default: throw new IllegalArgumentException("Unknown source: " + source);
        }
    }

    /* ============================ Strategy ============================ */
    static Strategy buildStrategy(BarSeries series) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        // 20/60 SMAs to get reasonable frequency on hourly data
        SMAIndicator smaFast = new SMAIndicator(close, 20);
        SMAIndicator smaSlow = new SMAIndicator(close, 60);
        RSIIndicator rsi14   = new RSIIndicator(close, 14);

        Rule entryRule = new CrossedUpIndicatorRule(smaFast, smaSlow)
                .and(new UnderIndicatorRule(rsi14, series.numOf(50))); // relax to 80 if desired
        Rule exitRule  = new CrossedDownIndicatorRule(smaFast, smaSlow);

        return new BaseStrategy(entryRule, exitRule);
    }

    /* ============================ Debug (optional) ============================ */
    static void debugSignals(BarSeries series, Strategy strategy) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        int enters = 0, exits = 0;
        for (int i = 0; i < series.getBarCount(); i++) {
            boolean enter = strategy.shouldEnter(i);
            boolean exit  = strategy.shouldExit(i);
            if (enter || exit) {
                System.out.printf("Signal @ %4d  %s  enter=%s exit=%s  close=%.4f%n",
                        i, series.getBar(i).getEndTime(), enter, exit, close.getValue(i).doubleValue());
            }
            if (enter) enters++;
            if (exit) exits++;
        }
        System.out.println("Total ENTER signals: " + enters + " | EXIT signals: " + exits);
    }

    /* ====================== Risk-Aware Backtester (nested) ====================== */
    public static final class RiskBacktester {

        public static final class Config {
            public double startingEquity = 100_000.0;
            public double riskFraction   = 0.01;    // 1% risk per trade
            public int    atrLength      = 14;
            public double atrMultiple    = 2.0;     // stop = entry - ATR*multiple
            public double takeProfitR    = 2.0;     // TP at 2R (<=0 disables)
            public double commissionPerShare = 0.001;
            public double slippageBps    = 1.0;     // 1 bp = 0.01%
            public boolean enforceCash   = true;
            public int    warmupBars     = 100;     // allow earlier entries; ensure indicators valid
        }

        public static final class Trade {
            public int entryIndex;
            public int exitIndex;
            public double entryPrice;
            public double exitPrice;
            public int shares;
            public boolean exitByStop;
            public boolean exitByTP;
            public double pnl;
            @Override public String toString() {
                return String.format(
                        "Trade{entryIdx=%d @ %.4f, exitIdx=%d @ %.4f, shares=%d, pnl=%.2f, stop=%s, tp=%s}",
                        entryIndex, entryPrice, exitIndex, exitPrice, shares, pnl, exitByStop, exitByTP
                );
            }
        }

        public static final class Result {
            public List<Double> equityCurve = new ArrayList<>();
            public List<Trade> trades = new ArrayList<>();
            public double finalEquity;
            public double totalReturn;
            public double maxDrawdown;
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

            double peak = cfg.startingEquity;
            double mdd = 0.0;

            for (int i = start; i < n; i++) {
                Bar prev = series.getBar(i - 1);
                Bar curr = series.getBar(i);

                double prevClose = close.getValue(i - 1).doubleValue();
                double currOpen  = curr.getOpenPrice().doubleValue();
                double currHigh  = curr.getHighPrice().doubleValue();
                double currLow   = curr.getLowPrice().doubleValue();
                double currClose = curr.getClosePrice().doubleValue();
                double atrPrev   = atr.getValue(i - 1).doubleValue();

                // equity at previous close
                double equity = cash + (inPosition ? posShares * prevClose : 0.0);
                peak = Math.max(peak, equity);
                if (peak > 0) mdd = Math.max(mdd, (peak - equity) / peak);

                // EXIT phase (signal at i-1, fill at bar i)
                if (inPosition) {
                    boolean stopHit = currLow <= posStop;
                    boolean exitSignal = strategy.shouldExit(i - 1);

                    boolean tpHit = false;
                    if (cfg.takeProfitR > 0) {
                        double riskPerShare = posEntryPrice - posStop;
                        double tpPrice = posEntryPrice + cfg.takeProfitR * riskPerShare;
                        tpHit = currHigh >= tpPrice;
                    }

                    if (stopHit || exitSignal || tpHit) {
                        double slippageSell = currOpen * (cfg.slippageBps / 10_000.0);
                        double exitFill = stopHit ? (posStop - posStop * (cfg.slippageBps / 10_000.0))
                                : (currOpen - slippageSell);

                        double proceeds = posShares * exitFill;
                        double exitCommission = posShares * cfg.commissionPerShare;

                        double pnl = proceeds - exitCommission - (posShares * posEntryPrice) - entryCommission;
                        cash += proceeds - exitCommission;

                        Trade tr = new Trade();
                        tr.entryIndex = entryIndex;
                        tr.exitIndex  = i;
                        tr.entryPrice = posEntryPrice;
                        tr.exitPrice  = exitFill;
                        tr.shares     = posShares;
                        tr.exitByStop = stopHit;
                        tr.exitByTP   = tpHit;
                        tr.pnl        = pnl;
                        out.trades.add(tr);

                        inPosition = false;
                        posShares = 0;
                        posEntryPrice = 0.0;
                        posStop = Double.NaN;
                        entryIndex = -1;
                        entryCommission = 0.0;
                    }
                }

                // ENTRY phase (signal at i-1, fill at bar i open)
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
                                cash -= cost;
                            } else {
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

                // record equity at current close
                double equityAtClose = cash + (inPosition ? posShares * currClose : 0.0);
                out.equityCurve.add(equityAtClose);
            }

            out.finalEquity = out.equityCurve.isEmpty() ? cfg.startingEquity : out.equityCurve.get(out.equityCurve.size() - 1);
            out.totalReturn = out.finalEquity / cfg.startingEquity - 1.0;

            int wins = 0;
            for (Trade t : out.trades) if (t.pnl > 0) wins++;
            out.winRate = out.trades.isEmpty() ? 0.0 : (wins * 1.0 / out.trades.size());

            // recompute max DD from equity curve
            double peak2 = Double.NEGATIVE_INFINITY;
            double dd = 0.0;
            for (double e : out.equityCurve) {
                peak2 = Math.max(peak2, e);
                if (peak2 > 0) dd = Math.max(dd, (peak2 - e) / peak2);
            }
            out.maxDrawdown = dd;

            return out;
        }
    }

    /* ============================ Main ============================ */
    public static void main(String[] args) throws Exception {
        // CLI: BacktestHourly [CSV|ALPACA] [SYMBOL] [START_yyyy-MM-dd] [END_yyyy-MM-dd]
        Source source = (args.length >= 1) ? Source.valueOf(args[0].toUpperCase()) : DEFAULT_SOURCE;
        String symbol  = (args.length >= 2) ? args[1] : DEFAULT_SYMBOL;

        ZonedDateTime start = (args.length >= 3)
                ? ZonedDateTime.of(LocalDate.parse(args[2]).atStartOfDay(), ZoneId.of("UTC"))
                : DEFAULT_START;
        ZonedDateTime end   = (args.length >= 4)
                ? ZonedDateTime.of(LocalDate.parse(args[3]).atStartOfDay(), ZoneId.of("UTC"))
                : DEFAULT_END;

        System.out.printf("Loading %s data for %s from %s to %s ...%n",
                source, symbol, start, end);

        BarSeries series = (source == Source.CSV)
                ? loadSeries(source, symbol, DEFAULT_CSV_RESOURCE, start, end)
                : loadSeries(source, symbol, null, start, end);

        System.out.println("Loaded bars: " + series.getBarCount());

        Strategy strategy = buildStrategy(series);

        // Optional: watch signals
        // debugSignals(series, strategy);

        // Baseline TA4J unit-position backtest
        TradingRecord record = new BarSeriesManager(series).run(strategy);
        AnalysisCriterion grossProfit = new GrossProfitCriterion();
        AnalysisCriterion maxDDCriterion = new MaximumDrawdownCriterion();
        AnalysisCriterion tradesCriterion = new NumberOfPositionsCriterion();

        System.out.println("\n=== TA4J Baseline (unit positions) ===");
        System.out.println("Bars: " + series.getBarCount());
        System.out.println("Trades: " + tradesCriterion.calculate(series, record));
        System.out.println("Gross Profit (multiple): " + grossProfit.calculate(series, record));
        System.out.println("Max Drawdown (criterion): " + maxDDCriterion.calculate(series, record));

        // Risk-aware backtest (1% risk/trade)
        RiskBacktester.Config cfg = new RiskBacktester.Config();
        cfg.startingEquity = 100_000.0;
        cfg.riskFraction   = 0.01;
        cfg.atrLength      = 14;
        cfg.atrMultiple    = 2.0;
        cfg.takeProfitR    = 2.0;
        cfg.commissionPerShare = 0.001;
        cfg.slippageBps    = 1.0;
        cfg.enforceCash    = true;
        cfg.warmupBars     = 100;

        RiskBacktester.Result res = RiskBacktester.simulate(series, strategy, cfg);

        System.out.println("\n=== Risk-Aware Backtest (1% risk/trade) ===");
        System.out.printf("Trades: %d%n", res.trades.size());
        System.out.printf("Final Equity: %.2f%n", res.finalEquity);
        System.out.printf("Total Return: %.2f%%%n", res.totalReturn * 100);
        System.out.printf("Win Rate: %.2f%%%n", res.winRate * 100);
        System.out.printf("Max Drawdown: %.2f%%%n", res.maxDrawdown * 100);

        // Optional: print trade log
        for (RiskBacktester.Trade t : res.trades) {
            System.out.println(t);
        }
    }
}
