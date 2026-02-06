
package com.foreshock.tradingbot;

import com.foreshock.tradingbot.alpaca.AlpacaHourlyLoader;
import com.foreshock.tradingbot.strategy.Strategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.*;
import org.ta4j.core.analysis.criteria.MaximumDrawdownCriterion;
import org.ta4j.core.analysis.criteria.NumberOfPositionsCriterion;
import org.ta4j.core.analysis.criteria.pnl.GrossProfitCriterion;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DoubleNum;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Backtest runner that can load data from CSV (resources) or Alpaca API,
 * run baseline TA4J backtest, and run risk-aware backtest (1% risk per trade).
 */
public class BacktestHourly {

    /* ============================ Toggle data source ============================ */
    enum Source {CSV, ALPACA}

    private static final Logger log = LoggerFactory.getLogger(BacktestHourly.class);

    // Update these if you want defaults
    private static final Source DEFAULT_SOURCE = Source.ALPACA; // or Source.CSV
    private static final String DEFAULT_SYMBOL = "AAPL";
    private static final String DEFAULT_CSV_RESOURCE = "hourly_stock_data.csv";
    private static final String DEFAULT_TIMEFRAME = "1Hour";
    private static final ZonedDateTime DEFAULT_START = ZonedDateTime.of(LocalDateTime.of(2024, 1, 2, 0, 0), ZoneId.of("UTC"));
    private static final ZonedDateTime DEFAULT_END = ZonedDateTime.of(LocalDateTime.of(2024, 6, 30, 0, 0), ZoneId.of("UTC"));

    /* ============================ Data Loading ============================ */
    static BarSeries loadSeriesFromCsv(String resourceName, String alpacaTimeframe) throws Exception {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName("CSV-HourlyData")
                .withNumTypeOf(DoubleNum::valueOf) // change to withNumTypeOf if your TA4J version requires it
                .build();

    DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    Duration barDuration = timeframeToDuration(alpacaTimeframe);
        InputStream inputStream = BacktestHourly.class.getClassLoader().getResourceAsStream(resourceName);
        if (inputStream == null) {
            throw new IllegalArgumentException("Resource not found: " + resourceName);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                ZonedDateTime endTime = ZonedDateTime.parse(p[0], tsFmt);

                double open = Double.parseDouble(p[1]);
                double high = Double.parseDouble(p[2]);
                double low = Double.parseDouble(p[3]);
                double close = Double.parseDouble(p[4]);
                double volume = Double.parseDouble(p[5]);

                Bar bar = new BaseBar(barDuration, endTime, open, high, low, close, volume);
                series.addBar(bar);
            }
        }
        return series;
    }

    // Convenience main that delegates to the nested RiskBacktester main.
    // The original CLI entrypoint lives in BacktestHourly.RiskBacktester.main(...)
    // but many users expect to run the outer class directly. Delegate so both
    // forms work: `com.foreshock.tradingbot.BacktestHourly` and
    // `com.foreshock.tradingbot.BacktestHourly$RiskBacktester`.
    public static void main(String[] args) throws Exception {
        RiskBacktester.main(args);
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
        // default timeframe 1Hour retained here for backwards compat - prefer calling overload below
        return AlpacaHourlyLoader.loadBars(symbol, "1Hour", start, end, apiKey, apiSecret);
    }

    static BarSeries loadSeriesFromAlpaca(String symbol,
                                          ZonedDateTime start,
                                          ZonedDateTime end,
                                          String alpacaTimeframe) throws Exception {
        String apiKey = System.getenv("ALPACA_API_KEY");
        String apiSecret = System.getenv("ALPACA_API_SECRET");
        if (apiKey == null || apiSecret == null) {
            throw new IllegalStateException("Missing env vars ALPACA_API_KEY / ALPACA_API_SECRET");
        }
        return AlpacaHourlyLoader.loadBars(symbol, alpacaTimeframe, start, end, apiKey, apiSecret);
    }

    private static Duration timeframeToDuration(String tf) {
        // reuse the loader's parser where possible
        return AlpacaHourlyLoader.parseAlpacaTimeFrameToDuration(tf);
    }

    static BarSeries loadSeries(Source source,
                                String symbol,
                                String csvResource,
                                ZonedDateTime start,
                                ZonedDateTime end,
                                String alpacaTimeframe) throws Exception {
        switch (source) {
            case CSV:
                return loadSeriesFromCsv(csvResource, alpacaTimeframe);
            case ALPACA:
                return loadSeriesFromAlpaca(symbol, start, end, alpacaTimeframe);
            default:
                throw new IllegalArgumentException("Unknown source: " + source);
        }
    }

    /* ============================ Strategy ============================ */
    static Strategy buildStrategy(BarSeries series) {
        // Use the shared strategy factory for SMA+RSI variants

        return Strategies.smaRsi(series, 50, 100, 14, 50);
    }

    /* ============================ Debug (optional) ============================ */
    static void debugSignals(BarSeries series, Strategy strategy) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        int enters = 0, exits = 0;
        for (int i = 0; i < series.getBarCount(); i++) {
            boolean enter = strategy.shouldEnter(i);
            boolean exit = strategy.shouldExit(i);
            if (enter || exit) {
                log.info("Signal @ {}  {}  enter={} exit={}  close={}",
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
            public double riskFraction = 0.01;
            public int atrLength = 14;
            public double atrMultiple = 2.0;     // initial stop distance = ATR * atrMultiple
            public double takeProfitR = 2.0;     // used if you DON'T use partial TP
            public double commissionPerShare = 0.001;
            public double slippageBps = 1.0;     // 1 bp = 0.01%
            public boolean enforceCash = true;
            public int warmupBars = 100;

            // --- NEW: Break-even stop ---
            public boolean useBreakEven = true;    // move stop to entry after X R move
            public double breakEvenR = 1.0;     // when prev close >= entry + breakEvenR*R

            // --- NEW: ATR trailing stop ---
            public boolean useAtrTrail = true;    // enable ATR trailing
            public double atrTrailMult = 2.0;     // trail = prevClose - ATR(i-1)*atrTrailMult
            public boolean trailOnlyAfterBE = true; // activate trail only after BE is reached

            // --- NEW: Time-based exit ---
            public boolean useMaxBarsInTrade = true;
            public int maxBarsInTrade = 80;  // ~two weeks on hourly

            // --- NEW: Partial take-profits ---
            public boolean usePartialTP = true;    // take a partial at partialTpR, then final at finalTpR
            public double partialTpR = 1.0;     // 1R first TP
            public double partialTpPct = 0.5;     // sell 50% at first TP
            public double finalTpR = 2.0;     // final TP in R; if <=0, disabled

            // Notes:
            // If usePartialTP=false, we fall back to single takeProfitR above.
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
            boolean exitByTime;

            public double getProfit() {
                return (exitPrice - entryPrice) * shares;
            }

            @Override
            public String toString() {
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

        public static Result simulate(
                BarSeries series,
                Strategy strategy,
                Config cfg
        ) {
            Result res = new Result();

            ClosePriceIndicator close = new ClosePriceIndicator(series);
            ATRIndicator atr = new ATRIndicator(series, cfg.atrLength);

            int n = series.getBarCount();
            double cash = cfg.startingEquity;

            boolean inPosition = false;
            boolean partialTaken = false;
            boolean breakEvenApplied = false;

            int entryIndex = -1;
            int shares = 0;
            int barsInTrade = 0;  // NEW: Track bars in trade

            double entryPrice = 0.0;
            double stopPrice = 0.0;
            double initialRiskPerShare = 0.0;

            for (int i = 1; i < n; i++) {
                Bar bar = series.getBar(i);

                double open  = bar.getOpenPrice().doubleValue();
                double high  = bar.getHighPrice().doubleValue();
                double low   = bar.getLowPrice().doubleValue();

                double prevClose = close.getValue(i - 1).doubleValue();
                double atrPrev   = atr.getValue(i - 1).doubleValue();

                /* ========= ENTRY ========= */
                if (!inPosition && strategy.shouldEnter(i - 1)) {
                    double risk = atrPrev * cfg.atrMultiple;
                    if (risk > 0) {
                        int qty = (int) Math.floor(
                                (cash * cfg.riskFraction) / risk
                        );

                        if (qty > 0) {
                            entryPrice = open;
                            stopPrice = entryPrice - risk;
                            initialRiskPerShare = risk;

                            shares = qty;
                            entryIndex = i;
                            barsInTrade = 0;  // NEW: Reset counter on entry

                            inPosition = true;
                            partialTaken = false;
                            breakEvenApplied = false;

                            cash -= shares * entryPrice;
                        }
                    }
                }

                if (!inPosition) {
                    res.equityCurve.add(cash);
                    continue;
                }

                // NEW: Increment bars in trade counter
                barsInTrade++;

                /* ===== TIME-BASED EXIT ===== */
                if (cfg.useMaxBarsInTrade && barsInTrade >= cfg.maxBarsInTrade) {
                    // Exit at open of current bar
                    double exitPrice = open;
                    cash += shares * exitPrice;

                    Trade tr = new Trade();
                    tr.entryIndex = entryIndex;
                    tr.exitIndex = i;
                    tr.entryPrice = entryPrice;
                    tr.exitPrice = exitPrice;
                    tr.shares = shares;
                    tr.exitByStop = false;
                    tr.exitByTP = false;
                    tr.exitByTime = true;  // NEW: Add this field to Trade class

                    res.trades.add(tr);
                    inPosition = false;
                    continue;
                }

                /* ===== BREAK EVEN ===== */
                if (cfg.useBreakEven && !breakEvenApplied) {
                    double beTrigger =
                            entryPrice + cfg.breakEvenR * initialRiskPerShare;

                    if (prevClose >= beTrigger) {
                        stopPrice = entryPrice;
                        breakEvenApplied = true;
                    }
                }

                /* ========= STOP ========= */
//                boolean stopGap   = open <= stopPrice;
//                boolean stopTouch = low <= stopPrice && open > stopPrice;

                boolean stopGap = open <= stopPrice;
                boolean stopTouch = !stopGap && low <= stopPrice;


                if (stopGap || stopTouch) {
                    // Always use stop price for exit (assumes stop order fills at limit)
                    double exitPrice = stopPrice;

                    cash += shares * exitPrice;

                    Trade tr = new Trade();
                    tr.entryIndex = entryIndex;
                    tr.exitIndex = i;
                    tr.entryPrice = entryPrice;
                    tr.exitPrice = exitPrice;
                    tr.shares = shares;
                    tr.exitByStop = true;
                    tr.exitByTP = false;
                    tr.exitByTime = false;

                    res.trades.add(tr);
                    inPosition = false;
                    continue;
                }

                /* ===== PARTIAL TP ===== */
                if (cfg.usePartialTP && !partialTaken) {
                    double tp1 =
                            entryPrice + cfg.partialTpR * initialRiskPerShare;

                    if (high >= tp1) {
                        int qty = (int) Math.floor(
                                shares * cfg.partialTpPct
                        );

                        if (qty > 0) {
                            cash += qty * tp1;

                            Trade tr = new Trade();
                            tr.entryIndex = entryIndex;
                            tr.exitIndex = i;
                            tr.entryPrice = entryPrice;
                            tr.exitPrice = tp1;
                            tr.shares = qty;
                            tr.exitByTP = true;
                            tr.exitByStop = false;
                            tr.exitByTime = false;  // NEW

                            res.trades.add(tr);

                            shares -= qty;
                            partialTaken = true;
                        }
                    }
                }

                /* ===== FINAL TP ===== */
                double finalTp =
                        entryPrice + cfg.finalTpR * initialRiskPerShare;

                if (cfg.finalTpR > 0 && high >= finalTp) {
                    cash += shares * finalTp;

                    Trade tr = new Trade();
                    tr.entryIndex = entryIndex;
                    tr.exitIndex = i;
                    tr.entryPrice = entryPrice;
                    tr.exitPrice = finalTp;
                    tr.shares = shares;
                    tr.exitByTP = true;
                    tr.exitByStop = false;
                    tr.exitByTime = false;  // NEW

                    res.trades.add(tr);
                    inPosition = false;
                    continue;
                }

                res.equityCurve.add(
                        cash + shares * close.getValue(i).doubleValue()
                );
            }

            // Handle open position at end of series
            if (inPosition) {
                double exitPrice = close.getValue(n-1).doubleValue();
                cash += shares * exitPrice;

                Trade tr = new Trade();
                tr.entryIndex = entryIndex;
                tr.exitIndex = n-1;
                tr.entryPrice = entryPrice;
                tr.exitPrice = exitPrice;
                tr.shares = shares;
                tr.exitByStop = false;
                tr.exitByTP = false;
                tr.exitByTime = false;  // NEW: End of data exit

                res.trades.add(tr);
            }

            res.finalEquity = cash;
            return res;
        }


        private static Map<String, String> parseArgs(String[] args) {
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--")) {
                    String key = a.substring(2);
                    String val = "true";
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        val = args[++i];
                    }
                    map.put(key.toLowerCase(), val);
                }
            }
            return map;
        }

        public static void main(String[] args) throws Exception {
            // Defaults
            Source source = DEFAULT_SOURCE;
            String symbol = DEFAULT_SYMBOL;
            ZonedDateTime start = DEFAULT_START;
            ZonedDateTime end = DEFAULT_END;

            // Parse flags
            var flags = parseArgs(args);

            // Source
            if (flags.containsKey("source")) {
                source = Source.valueOf(flags.get("source").toUpperCase());
            }
            if (flags.containsKey("symbol")) {
                symbol = flags.get("symbol");
            }
            if (flags.containsKey("start")) {
                start = ZonedDateTime.of(java.time.LocalDate.parse(flags.get("start")).atStartOfDay(), ZoneId.of("UTC"));
            }
            if (flags.containsKey("end")) {
                end = ZonedDateTime.of(java.time.LocalDate.parse(flags.get("end")).atStartOfDay(), ZoneId.of("UTC"));
            }

            // Mode
            String mode = flags.getOrDefault("mode", "WFT").toUpperCase(); // BASELINE|RISK|WFT

            // Timeframe
            String timeframe = flags.getOrDefault("timeframe", DEFAULT_TIMEFRAME);

        // Load data
        BarSeries series = (source == Source.CSV)
            ? loadSeries(source, symbol, DEFAULT_CSV_RESOURCE, start, end, timeframe)
            : loadSeries(source, symbol, null, start, end, timeframe);

            System.out.printf("Mode=%s  Source=%s  Symbol=%s  Bars=%d%n", mode, source, symbol, series.getBarCount());

            // Strategy (you can later drive these params from CLI too)
            Strategy strategy = buildStrategy(series);

            switch (mode) {
                case "BASELINE": {
                    BarSeriesManager manager = new BarSeriesManager(series);
                    TradingRecord record = manager.run(strategy);
                    var grossProfit = new GrossProfitCriterion();
                    var maxDD = new MaximumDrawdownCriterion();
                    var trades = new NumberOfPositionsCriterion();

                    System.out.println("\n=== TA4J Baseline (unit positions) ===");
                    System.out.println("Trades: " + trades.calculate(series, record));
                    System.out.println("Gross Profit (multiple): " + grossProfit.calculate(series, record));
                    System.out.println("Max Drawdown (criterion): " + maxDD.calculate(series, record));
                    break;
                }

                case "RISK": {
                    RiskBacktester.Config cfg = new RiskBacktester.Config();
                    cfg.startingEquity = Double.parseDouble(flags.getOrDefault("equity", "100000"));
                    cfg.riskFraction = Double.parseDouble(flags.getOrDefault("risk", "0.01"));
                    cfg.atrLength = Integer.parseInt(flags.getOrDefault("atr", "14"));
                    cfg.atrMultiple = Double.parseDouble(flags.getOrDefault("atrmult", "2.0"));

                    // Single take profit (used when partial TP is disabled)
                    cfg.takeProfitR = Double.parseDouble(flags.getOrDefault("tp", "2.0"));

                    cfg.commissionPerShare = Double.parseDouble(flags.getOrDefault("commission", "0.001"));
                    cfg.slippageBps = Double.parseDouble(flags.getOrDefault("slipbps", "1.0"));
                    cfg.enforceCash = Boolean.parseBoolean(flags.getOrDefault("enforcecash", "true"));
                    cfg.warmupBars = Integer.parseInt(flags.getOrDefault("warmup", "100"));

                    // --- NEW FLAGS ---
                    // Break-even
                    cfg.useBreakEven = Boolean.parseBoolean(flags.getOrDefault("usebreakeven", "true"));
                    cfg.breakEvenR = Double.parseDouble(flags.getOrDefault("breakevenr", "1.0"));

                    // ATR trailing
                    cfg.useAtrTrail = Boolean.parseBoolean(flags.getOrDefault("useatrtrail", "true"));
                    cfg.atrTrailMult = Double.parseDouble(flags.getOrDefault("atrtrailmult", "2.0"));
                    cfg.trailOnlyAfterBE = Boolean.parseBoolean(flags.getOrDefault("trailonlyafterbe", "true"));

                    // Time-based exit
                    cfg.useMaxBarsInTrade = Boolean.parseBoolean(flags.getOrDefault("usemaxbarsintrade", "true"));
                    cfg.maxBarsInTrade = Integer.parseInt(flags.getOrDefault("maxbarsintrade", "80"));

                    // Partial TPs (when enabled, they override `takeProfitR` with partial/final)
                    cfg.usePartialTP = Boolean.parseBoolean(flags.getOrDefault("usepartialtp", "true"));
                    cfg.partialTpR = Double.parseDouble(flags.getOrDefault("partialtpr", "1.0"));
                    cfg.partialTpPct = Double.parseDouble(flags.getOrDefault("partialtppct", "0.5"));
                    cfg.finalTpR = Double.parseDouble(flags.getOrDefault("finaltpr", "2.0"));

                    var res = RiskBacktester.simulate(series, strategy, cfg);
                    System.out.println("\n=== Risk-Aware Backtest (enhanced risk engine) ===");
                    System.out.printf("Trades: %d%n", res.trades.size());
                    System.out.printf("Final Equity: %.2f%n", res.finalEquity);
                    System.out.printf("Total Return: %.2f%%%n", res.totalReturn * 100);
                    System.out.printf("Win Rate: %.2f%%%n", res.winRate * 100);
                    System.out.printf("Max Drawdown: %.2f%%%n", res.maxDrawdown * 100);
                    res.trades.forEach(System.out::println);
                    break;
                }


                case "WFT": {
                    var wfCfg = new WalkForwardTester.Config();
                    wfCfg.trainPeriod = java.time.Period.ofMonths(Integer.parseInt(flags.getOrDefault("trainmonths", "4")));
                    wfCfg.testPeriod = java.time.Period.ofMonths(Integer.parseInt(flags.getOrDefault("testmonths", "1")));

                    // Risk settings reused per fold
                    wfCfg.risk.startingEquity = Double.parseDouble(flags.getOrDefault("equity", "100000"));
                    wfCfg.risk.riskFraction = Double.parseDouble(flags.getOrDefault("risk", "0.01"));
                    wfCfg.risk.atrLength = Integer.parseInt(flags.getOrDefault("atr", "14"));
                    wfCfg.risk.atrMultiple = Double.parseDouble(flags.getOrDefault("atrmult", "2.0"));

                    // Single TP when partials disabled
                    wfCfg.risk.takeProfitR = Double.parseDouble(flags.getOrDefault("tp", "2.0"));

                    wfCfg.risk.commissionPerShare = Double.parseDouble(flags.getOrDefault("commission", "0.001"));
                    wfCfg.risk.slippageBps = Double.parseDouble(flags.getOrDefault("slipbps", "1.0"));
                    wfCfg.risk.enforceCash = Boolean.parseBoolean(flags.getOrDefault("enforcecash", "true"));
                    wfCfg.risk.warmupBars = Integer.parseInt(flags.getOrDefault("warmup", "100"));

                    // --- NEW FLAGS mirrored into walk-forward risk config ---
                    wfCfg.risk.useBreakEven = Boolean.parseBoolean(flags.getOrDefault("usebreakeven", "true"));
                    wfCfg.risk.breakEvenR = Double.parseDouble(flags.getOrDefault("breakevenr", "1.0"));

                    wfCfg.risk.useAtrTrail = Boolean.parseBoolean(flags.getOrDefault("useatrtrail", "true"));
                    wfCfg.risk.atrTrailMult = Double.parseDouble(flags.getOrDefault("atrtrailmult", "2.0"));
                    wfCfg.risk.trailOnlyAfterBE = Boolean.parseBoolean(flags.getOrDefault("trailonlyafterbe", "true"));

                    wfCfg.risk.useMaxBarsInTrade = Boolean.parseBoolean(flags.getOrDefault("usemaxbarsintrade", "true"));
                    wfCfg.risk.maxBarsInTrade = Integer.parseInt(flags.getOrDefault("maxbarsintrade", "80"));

                    wfCfg.risk.usePartialTP = Boolean.parseBoolean(flags.getOrDefault("usepartialtp", "true"));
                    wfCfg.risk.partialTpR = Double.parseDouble(flags.getOrDefault("partialtpr", "1.0"));
                    wfCfg.risk.partialTpPct = Double.parseDouble(flags.getOrDefault("partialtppct", "0.5"));
                    wfCfg.risk.finalTpR = Double.parseDouble(flags.getOrDefault("finaltpr", "2.0"));

                    // Param grids
                    if (flags.containsKey("fast")) wfCfg.fastSmaGrid = parseIntArray(flags.get("fast"));
                    if (flags.containsKey("slow")) wfCfg.slowSmaGrid = parseIntArray(flags.get("slow"));
                    if (flags.containsKey("rsi")) wfCfg.rsiThreshGrid = parseIntArray(flags.get("rsi"));

                    wfCfg.scoring = WalkForwardTester.Scoring.valueOf(
                            flags.getOrDefault("score", "RETURN_OVER_DRAWDOWN").toUpperCase()
                    );

                    WalkForwardTester.Summary summary =
                            WalkForwardTester.runWalkForward(series, wfCfg, WalkForwardTester::smaRsiFactory);

                    System.out.println();
                    summary.folds.forEach(System.out::println);
                    System.out.println(summary);
                    System.out.println("Params chosen per fold: " + summary.paramsFrequency);
                    break;
                }
                default: {
                    printUsage();
                }
            }
        }

        private static int[] parseIntArray(String csv) {
            return Arrays.stream(csv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .mapToInt(Integer::parseInt).toArray();
        }


        private static void printUsage() {
            System.out.println("""
                    Usage:
                      java -cp <cp> com.foreshock.tradingbot.BacktestHourly --mode BASELINE|RISK|WFT [options]
                    
                    Common options:
                      --source CSV|ALPACA      Data source (default: ALPACA)
                      --symbol AAPL            Ticker (default: AAPL)
                      --start yyyy-MM-dd       Start date (UTC midnight) (default: 2024-01-02)
                      --end   yyyy-MM-dd       End date (UTC midnight)   (default: 2024-03-31)
                    
                    Risk-mode options (also applied to each WFT fold):
                      --equity 100000          Starting equity
                      --risk 0.01              Risk fraction per trade (e.g., 0.01 = 1%)
                      --atr 14                 ATR length
                      --atrMult 2.0            Initial stop distance = ATR * atrMult
                      --commission 0.001       Commission per share
                      --slipBps 1.0            Slippage in basis points (1 bp = 0.01%)
                      --enforceCash true       Enforce cash check (true/false)
                      --warmup 100             Warmup bars per slice
                    
                      # Take-profit controls:
                      --tp 2.0                 Single TP in R (used only if partial TP is disabled)
                      --usePartialTP true      Enable partial + final TP (overrides --tp when true)
                      --partialTpR 1.0         Partial TP at R
                      --partialTpPct 0.5       Fraction to sell at partial TP (0..1)
                      --finalTpR 2.0           Final TP at R (close remaining position)
                    
                      # Break-even & trailing:
                      --useBreakEven true      Move stop to entry after breakEvenR * R move
                      --breakEvenR 1.0         R multiple to trigger break-even stop
                      --useAtrTrail true       Enable ATR trailing stop
                      --atrTrailMult 2.0       Trail distance = ATR * atrTrailMult
                      --trailOnlyAfterBE true  Start trailing only after breakeven is reached
                    
                      # Time-based exit:
                      --useMaxBarsInTrade true Exit after N bars in trade
                      --maxBarsInTrade 80      Max bars to hold a position
                    
                    Walk-forward options (in addition to risk options above):
                      --trainMonths 2          Train window size in months
                      --testMonths 1           Test window size in months
                      --fast 10,20,30          Fast SMA grid (comma-separated)
                      --slow 40,60,80          Slow SMA grid (comma-separated)
                      --rsi 50,60,80           RSI threshold grid
                      --score RETURN_OVER_DRAWDOWN|PROFIT_FACTOR|TOTAL_RETURN
                    
                    Examples:
                      # Risk-aware backtest with partial TP + breakeven + trailing:
                      java -cp target/classes com.foreshock.tradingbot.BacktestHourly --mode RISK --symbol AAPL \\
                           --risk 0.01 --atr 14 --atrMult 2.0 --usePartialTP true --partialTpR 1.0 --partialTpPct 0.5 --finalTpR 2.0 \\
                           --useBreakEven true --breakEvenR 1.0 --useAtrTrail true --atrTrailMult 2.0 --trailOnlyAfterBE true
                    
                      # Walk-forward with same risk settings:
                      java -cp target/classes com.foreshock.tradingbot.BacktestHourly --mode WFT --symbol AAPL \\
                           --trainMonths 2 --testMonths 1 --fast 10,20,30 --slow 40,60,80 --rsi 50,80 --score PROFIT_FACTOR \\
                           --usePartialTP true --useBreakEven true --useAtrTrail true
                    """);
        }


    }
}
