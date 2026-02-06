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
    // Allow short selling
    public boolean allowShorts   = false;   // disabled by default

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
        public boolean isShort;
        public double pnl;

        public String toString() {
            return String.format(
                    "Trade{side=%s, entryIdx=%d @ %.4f, exitIdx=%d @ %.4f, shares=%d, pnl=%.2f, stop=%s, tp=%s}",
                    (isShort ? "SHORT" : "LONG"), entryIndex, entryPrice, exitIndex, exitPrice, shares, pnl, exitByStop, exitByTP
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

    // Position state (supports long and short)
    boolean inPosition = false;
    int     posShares = 0; // positive number of shares
    double  posEntryPrice = 0.0;
    boolean posIsShort = false;

        // Stop state
    double  posStop = Double.NaN;           // current working stop (moves up for longs, down for shorts)
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
                final double riskPerShareInitial = posIsShort ? (posStopInitial - posEntryPrice) : (posEntryPrice - posStopInitial); // positive 1R

                // --- Break-even eligibility (conservative: based on previous close) ---
                final boolean canMoveToBE = cfg.useBreakEven
                        && (prevClose >= (posEntryPrice + cfg.breakEvenR * riskPerShareInitial));

                // --- ATR trailing (conservative: trail from previous close and ATR(i-1)) ---
                if (cfg.useAtrTrail && atrPrev > 0) {
                    final boolean trailActive = !cfg.trailOnlyAfterBE || canMoveToBE;
                    if (trailActive) {
                        if (!posIsShort) {
                            // For a long position, trail = prevClose - atrPrev * atrTrailMult
                            double trailStop = prevClose - atrPrev * cfg.atrTrailMult;
                            // Never loosen the stop (only tighten upward)
                            posStop = Double.isNaN(posStop) ? trailStop : Math.max(posStop, trailStop);
                        } else {
                            // For a short position, trail = prevClose + atrPrev * atrTrailMult
                            double trailStop = prevClose + atrPrev * cfg.atrTrailMult;
                            // Never loosen the stop (only tighten downward)
                            posStop = Double.isNaN(posStop) ? trailStop : Math.min(posStop, trailStop);
                        }
                    }
                }

                // --- Move stop to breakeven if eligible (never lower it) ---
                if (cfg.useBreakEven && canMoveToBE) {
                    if (!posIsShort) posStop = Math.max(posStop, posEntryPrice);
                    else posStop = Double.isNaN(posStop) ? posEntryPrice : Math.min(posStop, posEntryPrice);
                }

                // --- Determine TP levels ---
                final boolean tp1Enabled = cfg.usePartialTP
                        && cfg.partialTpR > 0 && cfg.partialTpPct > 0 && !partialTaken;

                final boolean tpFinalEnabled = cfg.usePartialTP
                        ? (cfg.finalTpR > 0)
                        : (cfg.takeProfitR > 0);

        final double tp1Price = tp1Enabled
            ? (!posIsShort ? (posEntryPrice + cfg.partialTpR * riskPerShareInitial) : (posEntryPrice - cfg.partialTpR * riskPerShareInitial))
            : Double.NaN;

                final double tpFinalR = cfg.usePartialTP ? cfg.finalTpR : cfg.takeProfitR;
        final double tpFinalPrice = tpFinalEnabled
            ? (!posIsShort ? (posEntryPrice + tpFinalR * riskPerShareInitial) : (posEntryPrice - tpFinalR * riskPerShareInitial))
            : Double.NaN;

                // --- Intrabar events for the current bar (priority will be enforced below) ---
                final boolean stopGap;
                final boolean stopTouch;
                if (!posIsShort) {
                    stopGap   = currOpen <= posStop;
                    stopTouch = (currLow <= posStop) && (currOpen > posStop);
                } else {
                    stopGap   = currOpen >= posStop;
                    stopTouch = (currHigh >= posStop) && (currOpen < posStop);
                }
                final boolean stopHit = stopGap || stopTouch;

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
                        final double exitFill = posIsShort ? base + slipOn(base, cfg.slippageBps) : base - slipOn(base, cfg.slippageBps);

                        final int sharesToExit = posShares;
                        final double proceeds = sharesToExit * exitFill;
                        final double exitCommission = sharesToExit * cfg.commissionPerShare;

                        // All remaining entry commission is attributed to this final exit
                        final double allocatedEntryCommission = entryCommissionTotal;

                        final double pnl;
                        if (!posIsShort) {
                            pnl = proceeds - exitCommission - (sharesToExit * posEntryPrice) - allocatedEntryCommission;
                            cash += proceeds - exitCommission;
                        } else {
                            // For shorts: buy-to-cover at exitFill (cash decreases), pnl = entryPrice - exitFill
                            pnl = (posEntryPrice - exitFill) * sharesToExit - exitCommission - allocatedEntryCommission;
                            cash -= (sharesToExit * exitFill) + exitCommission;
                        }

                        Trade tr = new Trade();
                        tr.entryIndex = entryIndex;
                        tr.exitIndex  = i;
                        tr.entryPrice = posEntryPrice;
                        tr.exitPrice  = exitFill;
                        tr.shares     = sharesToExit;
                        tr.exitByStop = true;
                        tr.exitByTP   = false;
                        tr.isShort    = posIsShort;
                        tr.pnl        = pnl;
                        out.trades.add(tr);

                        // Reset position
                        inPosition = false;
                        posShares = 0;
                        posEntryPrice = 0.0;
                        posIsShort = false;
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
                        final double exitFill = posIsShort ? base + slipOn(base, cfg.slippageBps) : base - slipOn(base, cfg.slippageBps);

                        int sharesToExit = (int) Math.floor(posShares * cfg.partialTpPct);
                        if (sharesToExit < 1) sharesToExit = 1;                  // at least 1 share
                        if (sharesToExit >= posShares) sharesToExit = posShares - 1; // keep some shares

                        final double proceeds = sharesToExit * exitFill;
                        final double exitCommission = sharesToExit * cfg.commissionPerShare;

                        // Pro-rate entry commission for the partial
                        final double fraction = (double) sharesToExit / (double) posShares;
                        final double allocatedEntryCommission = entryCommissionTotal * fraction;

                        final double pnl;
                        if (!posIsShort) {
                            pnl = proceeds - exitCommission - (sharesToExit * posEntryPrice) - allocatedEntryCommission;
                            cash += proceeds - exitCommission;
                        } else {
                            pnl = (posEntryPrice - exitFill) * sharesToExit - exitCommission - allocatedEntryCommission;
                            cash -= (sharesToExit * exitFill) + exitCommission;
                        }

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
                        final double exitFill = posIsShort ? base + slipOn(base, cfg.slippageBps) : base - slipOn(base, cfg.slippageBps);

                        final int sharesToExit = posShares;
                        final double proceeds = sharesToExit * exitFill;
                        final double exitCommission = sharesToExit * cfg.commissionPerShare;

                        // All remaining entry commission goes here
                        final double allocatedEntryCommission = entryCommissionTotal;

                        final double pnl;
                        if (!posIsShort) {
                            pnl = proceeds - exitCommission - (sharesToExit * posEntryPrice) - allocatedEntryCommission;
                            cash += proceeds - exitCommission;
                        } else {
                            pnl = (posEntryPrice - exitFill) * sharesToExit - exitCommission - allocatedEntryCommission;
                            cash -= (sharesToExit * exitFill) + exitCommission;
                        }

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
                        final double exitFill = posIsShort ? base + slipOn(base, cfg.slippageBps) : base - slipOn(base, cfg.slippageBps);

                        final int sharesToExit = posShares;
                        final double proceeds = sharesToExit * exitFill;
                        final double exitCommission = sharesToExit * cfg.commissionPerShare;

                        // All remaining entry commission goes here
                        final double allocatedEntryCommission = entryCommissionTotal;

                        final double pnl;
                        if (!posIsShort) {
                            pnl = proceeds - exitCommission - (sharesToExit * posEntryPrice) - allocatedEntryCommission;
                            cash += proceeds - exitCommission;
                        } else {
                            pnl = (posEntryPrice - exitFill) * sharesToExit - exitCommission - allocatedEntryCommission;
                            cash -= (sharesToExit * exitFill) + exitCommission;
                        }

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
                        posIsShort = false;
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
                        // Determine side: long by default; allow shorts when configured and heuristic indicates
                        boolean wantShort = false;
                        if (cfg.allowShorts) {
                            // TODO: Implement a more sophisticated heuristic for shorting
                            // Simple heuristic: if previous close is below open, treat as downward momentum and allow short
                            wantShort = prevClose > currOpen; // user can modify this condition later
                        }

                        final double slippage = currOpen * (cfg.slippageBps / 10_000.0);
                        final double entryFill = wantShort ? (currOpen - slippage) : (currOpen + slippage);

                        double commission = shares * cfg.commissionPerShare;

                        if (!wantShort) {
                            double cost = shares * entryFill + commission;
                            if (!cfg.enforceCash || cost <= cash) {
                                inPosition = true;
                                posShares = shares;
                                posEntryPrice = entryFill;
                                posStop = entryFill - riskPerShare;
                                posStopInitial = posStop;
                                entryCommissionTotal = commission;
                                entryIndex = i;
                                barsInTrade = 0;
                                partialTaken = false;
                                posIsShort = false;
                                cash -= cost;
                            } else {
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
                                    posIsShort = false;
                                    cash -= (affordable * entryFill + entryCommissionTotal);
                                }
                            }
                        } else {
                            // Short entry: credit proceeds, subtract commission
                            inPosition = true;
                            posShares = shares;
                            posEntryPrice = entryFill;
                            posStop = entryFill + riskPerShare;
                            posStopInitial = posStop;
                            entryCommissionTotal = commission;
                            entryIndex = i;
                            barsInTrade = 0;
                            partialTaken = false;
                            posIsShort = true;
                            cash += (shares * entryFill) - commission;
                        }
                    }
                }
            }

            // --------------------- EQUITY CURVE ---------------------
            final double equityAtClose;
            if (!inPosition) equityAtClose = cash;
            else if (!posIsShort) equityAtClose = cash + posShares * currClose;
            else {
                // For shorts, unrealized P&L = (entryPrice - currClose) * shares; cash already includes initial proceeds
                equityAtClose = cash + (posEntryPrice - currClose) * posShares;
            }
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
