package com.foreshock.tradingbot.mongodb;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for trade statistics
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeStatistics {

    private String symbol;

    private int totalTrades;

    private int winningTrades;

    private int losingTrades;

    private double winRate;

    private BigDecimal totalProfitLoss;

    private BigDecimal averageProfitLoss;

}
