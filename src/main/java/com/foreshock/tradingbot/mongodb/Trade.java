package com.foreshock.tradingbot.mongodb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MongoDB document representing a single trade transaction
 */
@Document(collection = "trades")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trade {

    @Id
    private String id;

    private String symbol;

    private String side; // BUY or SELL

    private BigDecimal quantity;

    private BigDecimal entryPrice;

    private BigDecimal exitPrice;

    private BigDecimal profitLoss;

    private BigDecimal profitLossPercentage;

    private String orderId;

    private String clientOrderId;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;

    private String status; // OPEN, CLOSED, CANCELLED

    private String strategy; // e.g., "SMA_RSI"

    private BigDecimal commission;

    private String notes;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
