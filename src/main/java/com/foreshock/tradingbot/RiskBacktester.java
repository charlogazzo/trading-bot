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

        final int n = series.getBarCount();
        final int start = Math.max(cfg.warmupBars, cfg.atrLength) + 1;

        // Account state
        double cash = cfg.startingEquity;

        // Position state (long-only for now)
        boolean inPosition = false;
        int     posShares = 0;
        double  posEntryPrice = 0.0;

        // Stop state
        double  posStop = Double.NaN;           // current working stop (moves up)
        double  posStopInitial = Double.NaN;    // initial stop at entry (fixed)
        double  entryCommissionTotal = 0.0;     // total commission paid at entry, to be pro-rated on partials
        int     entryIndex = -1;

        // Trade lifecycle helpers
        int     barsInTrade = 0;
        boolean partialTaken = false;           // whether first partial TP has been executed

        for (int i = start; i < n; i++) {
            final Bar prev = series.getBar(i - 1);
            final Bar curr = series.getBar(i);

            final double prevClose = close.getValue(i - 1).doubleValue();
            final double currOpen  = curr.getOpenPrice().doubleValue();
            final double currHigh  = curr.getHighPrice().doubleValue();
            final double currLow   = curr.getLowPrice().doubleValue();
            final double currClose = curr.getClosePrice().doubleValue();
            final double atrPrev   = atr.getValue(i - 1).doubleValue();

            // --------------------- EXIT PHASE ---------------------
            if (inPosition) {
                // --- Compute fixed 1R distance from initial stop ---
                final double riskPerShareInitial = posEntryPrice - posStopInitial; // 1R is fixed at entry

                // --- Break-even eligibility (conservative: based on previous close) ---
                final boolean canMoveToBE = cfg.useBreakEven
                        && (prevClose >= (posEntryPrice + cfg.breakEvenR * riskPerShareInitial));

                // --- ATR trailing (conservative: trail from previous close and ATR(i-1)) ---
                if (cfg.useAtrTrail && atrPrev > 0) {
                    final boolean trailActive = !cfg.trailOnlyAfterBE || canMoveToBE;
                    if (trailActive) {
                        // For a long position, trail = prevClose - atrPrev * atrTrailMult
                        double trailStop = prevClose - atrPrev * cfg.atrTrailMult;
                        // Never loosen the stop (only tighten upward)
                        posStop = Double.isNaN(posStop) ? trailStop : Math.max(posStop, trailStop);
                    }
                }

                // --- Move stop to breakeven if eligible (never lower it) ---
                if (cfg.useBreakEven && canMoveToBE) {
                    posStop = Math.max(posStop, posEntryPrice);
                }

                // --- Determine TP levels ---
                final boolean tp1Enabled = cfg.usePartialTP
                        && cfg.partialTpR > 0 && cfg.partialTpPct > 0 && !partialTaken;

                final boolean tpFinalEnabled = cfg.usePartialTP
                        ? (cfg.finalTpR > 0)
                        : (cfg.takeProfitR > 0);

                final double tp1Price = tp1Enabled
                        ? (posEntryPrice + cfg.partialTpR * riskPerShareInitial)
                        : Double.NaN;

                final double tpFinalR = cfg.usePartialTP ? cfg.finalTpR : cfg.takeProfitR;
                final double tpFinalPrice = tpFinalEnabled
                        ? (posEntryPrice + tpFinalR * riskPerShareInitial)
                        : Double.NaN;

                // --- Intrabar events for the current bar (priority will be enforced below) ---
                final boolean stopGap   = currOpen <= posStop;
                final boolean stopTouch = (currLow <= posStop) && (currOpen > posStop);
                final boolean stopHit   = stopGap || stopTouch;

                boolean tp1Gap = false, tp1Touch = false;
                if (tp1Enabled) {
                    tp1Gap   = currOpen >= tp1Price;
                    tp1Touch = (currHigh >= tp1Price) && (currOpen < tp1Price);
                }

                boolean tpFinalGap = false, tpFinalTouch = false;
                if (tpFinalEnabled) {
                    tpFinalGap   = currOpen >= tpFinalPrice;
                    tpFinalTouch = (currHigh >= tpFinalPrice) && (currOpen < tpFinalPrice);
                }

                final boolean exitSignal = strategy.shouldExit(i - 1);
                final boolean timeExit   = cfg.useMaxBarsInTrade && (barsInTrade >= cfg.maxBarsInTrade);

                // --- Execution priority: STOP -> TP1 -> TP FINAL -> SIGNAL/TIME ---
                if (stopHit || tp1Gap || tp1Touch || tpFinalGap || tpFinalTouch || exitSignal || timeExit) {

                    // Helper lambdas for fills

                    // 1) STOP has absolute priority (full exit)
                    if (stopHit) {
                        final boolean gap = stopGap;
                        final double base = gap ? currOpen : posStop;
                        final double exitFill = base - slipOn(base, cfg.slippageBps);

                        final int sharesToExit = posShares;
                        final double proceeds = sharesToExit * exitFill;
                        final double exitCommission = sharesToExit * cfg.commissionPerShare;

                        // All remaining entry commission is attributed to this final exit
                        final double allocatedEntryCommission = entryCommissionTotal;

                        final double pnl = proceeds - exitCommission
                                - (sharesToExit * posEntryPrice)
                                - allocatedEntryCommission;

                        cash += proceeds - exitCommission;

                        Trade tr = new Trade();
                        tr.entryIndex = entryIndex;
                        tr.exitIndex  = i;
                        tr.entryPrice = posEntryPrice;
                        tr.exitPrice  = exitFill;
                        tr.shares     = sharesToExit;
                        tr.exitByStop = true;
                        tr.exitByTP   = false;
                        tr.pnl        = pnl;
                        out.trades.add(tr);

                        // Reset position
                        inPosition = false;
                        posShares = 0;
                        posEntryPrice = 0.0;
                        posStop = Double.NaN;
                        posStopInitial = Double.NaN;
                        entryIndex = -1;
                        entryCommissionTotal = 0.0;
                        barsInTrade = 0;
                        partialTaken = false;

                    }
                    // 2) PARTIAL TAKE PROFIT (if enabled and not yet taken) — single action per bar
                    else if (tp1Enabled && (tp1Gap || tp1Touch)) {
                        final boolean gap = tp1Gap;
                        final double base = gap ? currOpen : tp1Price;
                        final double exitFill = base - slipOn(base, cfg.slippageBps);

                        int sharesToExit = (int) Math.floor(posShares * cfg.partialTpPct);
                        if (sharesToExit < 1) sharesToExit = 1;                  // at least 1 share
                        if (sharesToExit >= posShares) sharesToExit = posShares - 1; // keep some shares

                        final double proceeds = sharesToExit * exitFill;
                        final double exitCommission = sharesToExit * cfg.commissionPerShare;

                        // Pro-rate entry commission for the partial
                        final double fraction = (double) sharesToExit / (double) posShares;
                        final double allocatedEntryCommission = entryCommissionTotal * fraction;

                        final double pnl = proceeds - exitCommission
                                - (sharesToExit * posEntryPrice)
                                - allocatedEntryCommission;

                        cash += proceeds - exitCommission;

                        // Reduce position & entry commission pool
                        posShares -= sharesToExit;
                        entryCommissionTotal -= allocatedEntryCommission;
                        partialTaken = true; // mark partial done

                        // Record the partial as its own trade
                        Trade tr = new Trade();
                        tr.entryIndex = entryIndex;
                        tr.exitIndex  = i;
                        tr.entryPrice = posEntryPrice;
                        tr.exitPrice  = exitFill;
                        tr.shares     = sharesToExit;
                        tr.exitByStop = false;
                        tr.exitByTP   = true;
                        tr.pnl        = pnl;
                        out.trades.add(tr);

                        // Position remains open with updated posShares & (possibly tightened) posStop
                    }
                    // 3) FINAL TAKE PROFIT (full exit)
                    else if (tpFinalEnabled && (tpFinalGap || tpFinalTouch)) {
                        final boolean gap = tpFinalGap;
                        final double base = gap ? currOpen : tpFinalPrice;
                        final double exitFill = base - slipOn(base, cfg.slippageBps);

                        final int sharesToExit = posShares;
                        final double proceeds = sharesToExit * exitFill;
                        final double exitCommission = sharesToExit * cfg.commissionPerShare;

                        // All remaining entry commission goes here
                        final double allocatedEntryCommission = entryCommissionTotal;

                        final double pnl = proceeds - exitCommission
                                - (sharesToExit * posEntryPrice)
                                - allocatedEntryCommission;

                        cash += proceeds - exitCommission;

                        Trade tr = new Trade();
                        tr.entryIndex = entryIndex;
                        tr.exitIndex  = i;
                        tr.entryPrice = posEntryPrice;
                        tr.exitPrice  = exitFill;
                        tr.shares     = sharesToExit;
                        tr.exitByStop = false;
                        tr.exitByTP   = true;
                        tr.pnl        = pnl;
                        out.trades.add(tr);

                        // Reset position
                        inPosition = false;
                        posShares = 0;
                        posEntryPrice = 0.0;
                        posStop = Double.NaN;
                        posStopInitial = Double.NaN;
                        entryIndex = -1;
                        entryCommissionTotal = 0.0;
                        barsInTrade = 0;
                        partialTaken = false;

                    }
                    // 4) Exit by signal or time (full exit at open)
                    else if (exitSignal || timeExit) {
                        final double base = currOpen;
                        final double exitFill = base - slipOn(base, cfg.slippageBps);

                        final int sharesToExit = posShares;
                        final double proceeds = sharesToExit * exitFill;
                        final double exitCommission = sharesToExit * cfg.commissionPerShare;

                        // All remaining entry commission goes here
                        final double allocatedEntryCommission = entryCommissionTotal;

                        final double pnl = proceeds - exitCommission
                                - (sharesToExit * posEntryPrice)
                                - allocatedEntryCommission;

                        cash += proceeds - exitCommission;

                        Trade tr = new Trade();
                        tr.entryIndex = entryIndex;
                        tr.exitIndex  = i;
                        tr.entryPrice = posEntryPrice;
                        tr.exitPrice  = exitFill;
                        tr.shares     = sharesToExit;
                        tr.exitByStop = false;
                        tr.exitByTP   = false;
                        tr.pnl        = pnl;
                        out.trades.add(tr);

                        // Reset position
                        inPosition = false;
                        posShares = 0;
                        posEntryPrice = 0.0;
                        posStop = Double.NaN;
                        posStopInitial = Double.NaN;
                        entryIndex = -1;
                        entryCommissionTotal = 0.0;
                        barsInTrade = 0;
                        partialTaken = false;
                    }
                }

                // advance holding duration if still in position
                if (inPosition) {
                    barsInTrade++;
                }
            }

            // --------------------- ENTRY PHASE ---------------------
            if (!inPosition && strategy.shouldEnter(i - 1)) {
                if (!Double.isNaN(atrPrev) && atrPrev > 0) {
                    final double riskPerShare = atrPrev * cfg.atrMultiple;
                    final double riskBudget   = cash * cfg.riskFraction;
                    int shares = (int) Math.floor(riskBudget / riskPerShare);

                    if (shares >= 1) {
                        final double slippageBuy = currOpen * (cfg.slippageBps / 10_000.0);
                        final double entryFill   = currOpen + slippageBuy;

                        // Provisional commission for intended shares
                        double commission = shares * cfg.commissionPerShare;
                        double cost = shares * entryFill + commission;

                        if (!cfg.enforceCash || cost <= cash) {
                            // Enter with requested size
                            inPosition = true;
                            posShares = shares;
                            posEntryPrice = entryFill;
                            posStop = entryFill - riskPerShare;
                            posStopInitial = posStop;
                            entryCommissionTotal = commission;
                            entryIndex = i;
                            barsInTrade = 0;
                            partialTaken = false;
                            cash -= cost;
                        } else {
                            // Reduce to affordable size
                            int affordable = (int) Math.floor((cash) / (entryFill + cfg.commissionPerShare));
                            if (affordable >= 1) {
                                inPosition = true;
                                posShares = affordable;
                                posEntryPrice = entryFill;
                                posStop = entryFill - riskPerShare;
                                posStopInitial = posStop;
                                entryCommissionTotal = affordable * cfg.commissionPerShare;
                                entryIndex = i;
                                barsInTrade = 0;
                                partialTaken = false;
                                cash -= (affordable * entryFill + entryCommissionTotal);
                            }
                        }
                    }
                }
            }

            // --------------------- EQUITY CURVE ---------------------
            final double equityAtClose = cash + (inPosition ? posShares * currClose : 0.0);
            out.equityCurve.add(equityAtClose);
        }

        // Final metrics
        out.finalEquity = out.equityCurve.isEmpty()
                ? cfg.startingEquity
                : out.equityCurve.get(out.equityCurve.size() - 1);

        out.totalReturn = out.finalEquity / cfg.startingEquity - 1.0;

        int wins = 0;
        for (Trade t : out.trades) if (t.pnl > 0) wins++;
        out.winRate = out.trades.isEmpty() ? 0.0 : (wins * 1.0 / out.trades.size());

        // Max drawdown from stitched equity
        double peak = Double.NEGATIVE_INFINITY;
        double dd = 0.0;
        for (double e : out.equityCurve) {
            peak = Math.max(peak, e);
            if (peak > 0) dd = Math.max(dd, (peak - e) / peak);
        }
        out.maxDrawdown = dd;

        return out;
    }


    private static double slipOn(double price, double slipBps) {
        return price * (slipBps / 10_000.0);
    }


}
