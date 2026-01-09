package com.foreshock.tradingbot;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.ArrayList;
import java.util.List;

public final class RiskBacktester {

    /* ======================= Config =================== */

    public static final class Config {
        public double startingEquity = 100_000.0;
        public double riskFraction = 0.01;
        public int atrLength = 14;
        public double atrMultiple = 2.0;
        public double takeProfitR = 2.0;
        public double commissionPerShare = 0.001;
        public double slippageBps = 1.0;
        public boolean enforceCash = true;
        public int warmupBars = 200;
    }

    /* ======================= Result Objects ================== */

    public static final class Trade {
        public int entryIndex;
        public int exitIndex;
        public double entryPrice;
        public double exitPrice;
        public int shares;
        public boolean exitByStop;
        public boolean exitByTP;
        public double pnl;

        public String toString() {

            return String.format(
                    "Trade{entryIdx=%d @ %.4f, exitIdx=%d @ %.4f, shares=%d, pnl=%.2f, stop=%s, tp=%s}",
                    entryIndex, entryPrice, exitIndex, exitPrice, shares, pnl, exitByStop, exitByTP
            );
        }
    }

    public static final class Result {
        public List<Double> equityCurve = new ArrayList<>(); // per bar
        public List<Trade> trades = new ArrayList<>();
        public double finalEquity;
        public double totalReturn;  // (finalEquity / startingEquity - 1)
        public double maxDrawdown;  // in decimal (0.12 = -12%)
        public double winRate;
    }

    /* ============ Core simulation ============= */

    public static Result simulate(BarSeries series, Strategy strategy, Config cfg) {
        Result out = new Result();

        final ClosePriceIndicator close = new ClosePriceIndicator(series);
        final ATRIndicator atr = new ATRIndicator(series, cfg.atrLength);

        // Iterate bars and evaluate signals at i-1; execute at i open.
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

            // Mark-to-market equity at previous close
            double equity = cash + (inPosition ? posShares * prevClose : 0.0);
            peakEquity = Math.max(peakEquity, equity);
            if (peakEquity > 0) {
                maxDD = Math.max(maxDD, (peakEquity - equity) / peakEquity);
            }

            /* -------- EXIT PHASE (fills at current bar) -------- */
            if (inPosition) {
                boolean stopHit = currLow <= posStop; // intrabar stop check
                boolean exitSignal = strategy.shouldExit(i - 1);
                boolean tpHit = false;

                if (cfg.takeProfitR > 0) {
                    double riskPerShare = posEntryPrice - posStop; // distance at entry
                    double tpPrice = posEntryPrice + cfg.takeProfitR * riskPerShare;
                    tpHit = currHigh >= tpPrice;
                }

                if (stopHit || exitSignal || tpHit) {
                    // Slippage: for sells, subtract slippage
                    double slippageSell = currOpen * (cfg.slippageBps / 10_000.0);

                    double exitFill;
                    if (stopHit) {
                        // Fill at stop price (conservative: minus slippage)
                        exitFill = posStop - (posStop * (cfg.slippageBps / 10_000.0));
                    } else if (tpHit) {
                        // Fill at TP (approximate); conservative: use open minus slippage
                        // You can choose to use tpPrice directly, but open-based is more reproducible.
                        exitFill = currOpen - slippageSell;
                    } else {
                        // Strategy exit at open
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

            /* -------- ENTRY PHASE (using signal at i-1, fill at i open) -------- */
            if (!inPosition && strategy.shouldEnter(i - 1)) {
                if (!Double.isNaN(atrPrev) && atrPrev > 0) {
                    double riskPerShare = atrPrev * cfg.atrMultiple;
                    double riskBudget = (cash) * cfg.riskFraction;
                    int shares = (int) Math.floor(riskBudget / riskPerShare);

                    if (shares >= 1) {
                        double slippageBuy = currOpen * (cfg.slippageBps / 10_000.0);
                        double entryFill = currOpen + slippageBuy;
                        double commission = shares * cfg.commissionPerShare;
                        double cost = shares * entryFill + commission;

                        if (!cfg.enforceCash || cost <= cash) {
                            // Open position
                            inPosition = true;
                            posShares = shares;
                            posEntryPrice = entryFill;
                            posStop = entryFill - riskPerShare;
                            entryCommission = commission;
                            entryIndex = i;

                            cash -= cost; // pay from cash
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

        // Max drawdown recomputed from equityCurve (robust)
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
