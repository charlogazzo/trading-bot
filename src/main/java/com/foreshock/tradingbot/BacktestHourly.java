
package com.foreshock.tradingbot;

import com.foreshock.tradingbot.alpaca.AlpacaHourlyLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(BacktestHourly.class);

    // Update these if you want defaults
    private static final Source DEFAULT_SOURCE = Source.ALPACA; // or Source.CSV
    private static final String DEFAULT_SYMBOL = "AAPL";
    private static final String DEFAULT_CSV_RESOURCE = "hourly_stock_data.csv";
    private static final ZonedDateTime DEFAULT_START = ZonedDateTime.of(LocalDateTime.of(2024, 1, 2, 0, 0), ZoneId.of("UTC"));
    private static final ZonedDateTime DEFAULT_END   = ZonedDateTime.of(LocalDateTime.of(2024, 6, 30, 0, 0), ZoneId.of("UTC"));

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
        SMAIndicator smaFast = new SMAIndicator(close, 50);
        SMAIndicator smaSlow = new SMAIndicator(close, 100);
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
            public double riskFraction   = 0.01;
            public int    atrLength      = 14;
            public double atrMultiple    = 2.0;     // initial stop distance = ATR * atrMultiple
            public double takeProfitR    = 2.0;     // used if you DON'T use partial TP
            public double commissionPerShare = 0.001;
            public double slippageBps    = 1.0;     // 1 bp = 0.01%
            public boolean enforceCash   = true;
            public int    warmupBars     = 100;

            // --- NEW: Break-even stop ---
            public boolean useBreakEven  = true;    // move stop to entry after X R move
            public double  breakEvenR    = 1.0;     // when prev close >= entry + breakEvenR*R

            // --- NEW: ATR trailing stop ---
            public boolean useAtrTrail   = true;    // enable ATR trailing
            public double  atrTrailMult  = 2.0;     // trail = prevClose - ATR(i-1)*atrTrailMult
            public boolean trailOnlyAfterBE = true; // activate trail only after BE is reached

            // --- NEW: Time-based exit ---
            public boolean useMaxBarsInTrade = true;
            public int     maxBarsInTrade    = 80;  // ~two weeks on hourly

            // --- NEW: Partial take-profits ---
            public boolean usePartialTP  = true;    // take a partial at partialTpR, then final at finalTpR
            public double  partialTpR    = 1.0;     // 1R first TP
            public double  partialTpPct  = 0.5;     // sell 50% at first TP
            public double  finalTpR      = 2.0;     // final TP in R; if <=0, disabled

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
                    // boolean stopHit = currLow <= posStop;

                    boolean stopGap = currOpen <= posStop;
                    boolean stopTouch = currLow <= posStop && currOpen > posStop;
                    boolean stopHit = stopGap || stopTouch;

                    boolean exitSignal = strategy.shouldExit(i - 1);

                    boolean tpHit = false;
                    double tpPrice = Double.NaN;
                    if (cfg.takeProfitR > 0) {
                        double riskPerShare = posEntryPrice - posStop;
                        tpPrice = posEntryPrice + cfg.takeProfitR * riskPerShare;
                        tpHit = currHigh >= tpPrice;
                    }

                    if (stopHit || exitSignal || tpHit) {
                        double exitFill;

                        if (stopHit) {
                            if (stopGap) {
                                double slip = currOpen * (cfg.slippageBps / 10_000.0);
                                exitFill = currOpen - slip;
                            } else {
                                double slip = posStop * (cfg.slippageBps / 10_000.0);
                                exitFill = posStop - slip;
                            }
                        } else {
                            double slip = currOpen * (cfg.slippageBps / 10_000.0);
                            exitFill = currOpen - slip;
                        }

                        double proceeds = posShares * exitFill;
                        double exitCommission = posShares * cfg.commissionPerShare;

                        double pnl = proceeds
                                - exitCommission
                                - (posShares * posEntryPrice)
                                - entryCommission;

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


    private static java.util.Map<String, String> parseArgs(String[] args) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
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
        ZonedDateTime end   = DEFAULT_END;

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

        // Load data
        BarSeries series = (source == Source.CSV)
                ? loadSeries(source, symbol, DEFAULT_CSV_RESOURCE, start, end)
                : loadSeries(source, symbol, null, start, end);

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
                cfg.riskFraction   = Double.parseDouble(flags.getOrDefault("risk", "0.01"));
                cfg.atrLength      = Integer.parseInt(flags.getOrDefault("atr", "14"));
                cfg.atrMultiple    = Double.parseDouble(flags.getOrDefault("atrmult", "2.0"));

                // Single take profit (used when partial TP is disabled)
                cfg.takeProfitR    = Double.parseDouble(flags.getOrDefault("tp", "2.0"));

                cfg.commissionPerShare = Double.parseDouble(flags.getOrDefault("commission", "0.001"));
                cfg.slippageBps    = Double.parseDouble(flags.getOrDefault("slipbps", "1.0"));
                cfg.enforceCash    = Boolean.parseBoolean(flags.getOrDefault("enforcecash", "true"));
                cfg.warmupBars     = Integer.parseInt(flags.getOrDefault("warmup", "100"));

                // --- NEW FLAGS ---
                // Break-even
                cfg.useBreakEven   = Boolean.parseBoolean(flags.getOrDefault("usebreakeven", "true"));
                cfg.breakEvenR     = Double.parseDouble(flags.getOrDefault("breakevenr", "1.0"));

                // ATR trailing
                cfg.useAtrTrail    = Boolean.parseBoolean(flags.getOrDefault("useatrtrail", "true"));
                cfg.atrTrailMult   = Double.parseDouble(flags.getOrDefault("atrtrailmult", "2.0"));
                cfg.trailOnlyAfterBE = Boolean.parseBoolean(flags.getOrDefault("trailonlyafterbe", "true"));

                // Time-based exit
                cfg.useMaxBarsInTrade = Boolean.parseBoolean(flags.getOrDefault("usemaxbarsintrade", "true"));
                cfg.maxBarsInTrade    = Integer.parseInt(flags.getOrDefault("maxbarsintrade", "80"));

                // Partial TPs (when enabled, they override `takeProfitR` with partial/final)
                cfg.usePartialTP   = Boolean.parseBoolean(flags.getOrDefault("usepartialtp", "true"));
                cfg.partialTpR     = Double.parseDouble(flags.getOrDefault("partialtpr", "1.0"));
                cfg.partialTpPct   = Double.parseDouble(flags.getOrDefault("partialtppct", "0.5"));
                cfg.finalTpR       = Double.parseDouble(flags.getOrDefault("finaltpr", "2.0"));

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
                wfCfg.testPeriod  = java.time.Period.ofMonths(Integer.parseInt(flags.getOrDefault("testmonths", "1")));

                // Risk settings reused per fold
                wfCfg.risk.startingEquity = Double.parseDouble(flags.getOrDefault("equity", "100000"));
                wfCfg.risk.riskFraction   = Double.parseDouble(flags.getOrDefault("risk", "0.01"));
                wfCfg.risk.atrLength      = Integer.parseInt(flags.getOrDefault("atr", "14"));
                wfCfg.risk.atrMultiple    = Double.parseDouble(flags.getOrDefault("atrmult", "2.0"));

                // Single TP when partials disabled
                wfCfg.risk.takeProfitR    = Double.parseDouble(flags.getOrDefault("tp", "2.0"));

                wfCfg.risk.commissionPerShare = Double.parseDouble(flags.getOrDefault("commission", "0.001"));
                wfCfg.risk.slippageBps    = Double.parseDouble(flags.getOrDefault("slipbps", "1.0"));
                wfCfg.risk.enforceCash    = Boolean.parseBoolean(flags.getOrDefault("enforcecash", "true"));
                wfCfg.risk.warmupBars     = Integer.parseInt(flags.getOrDefault("warmup", "100"));

                // --- NEW FLAGS mirrored into walk-forward risk config ---
                wfCfg.risk.useBreakEven   = Boolean.parseBoolean(flags.getOrDefault("usebreakeven", "true"));
                wfCfg.risk.breakEvenR     = Double.parseDouble(flags.getOrDefault("breakevenr", "1.0"));

                wfCfg.risk.useAtrTrail    = Boolean.parseBoolean(flags.getOrDefault("useatrtrail", "true"));
                wfCfg.risk.atrTrailMult   = Double.parseDouble(flags.getOrDefault("atrtrailmult", "2.0"));
                wfCfg.risk.trailOnlyAfterBE = Boolean.parseBoolean(flags.getOrDefault("trailonlyafterbe", "true"));

                wfCfg.risk.useMaxBarsInTrade = Boolean.parseBoolean(flags.getOrDefault("usemaxbarsintrade", "true"));
                wfCfg.risk.maxBarsInTrade    = Integer.parseInt(flags.getOrDefault("maxbarsintrade", "80"));

                wfCfg.risk.usePartialTP   = Boolean.parseBoolean(flags.getOrDefault("usepartialtp", "true"));
                wfCfg.risk.partialTpR     = Double.parseDouble(flags.getOrDefault("partialtpr", "1.0"));
                wfCfg.risk.partialTpPct   = Double.parseDouble(flags.getOrDefault("partialtppct", "0.5"));
                wfCfg.risk.finalTpR       = Double.parseDouble(flags.getOrDefault("finaltpr", "2.0"));

                // Param grids
                if (flags.containsKey("fast")) wfCfg.fastSmaGrid = parseIntArray(flags.get("fast"));
                if (flags.containsKey("slow")) wfCfg.slowSmaGrid = parseIntArray(flags.get("slow"));
                if (flags.containsKey("rsi"))  wfCfg.rsiThreshGrid = parseIntArray(flags.get("rsi"));

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
        return java.util.Arrays.stream(csv.split(","))
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
