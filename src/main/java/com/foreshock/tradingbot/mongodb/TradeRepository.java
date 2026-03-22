package com.foreshock.tradingbot.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Trade documents in MongoDB
 */
@Repository
public interface TradeRepository extends MongoRepository<Trade, String> {

    /**
     * Find all trades for a specific symbol
     */
    List<Trade> findBySymbol(String symbol);

    /**
     * Find all open trades for a specific symbol
     */
    List<Trade> findBySymbolAndStatus(String symbol, String status);

    /**
     * Find all trades within a date range
     */
    List<Trade> findByEntryTimeBetween(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Find all trades by strategy
     */
    List<Trade> findByStrategy(String strategy);

    /**
     * Find all trades by order ID
     */
    Optional<Trade> findByOrderId(String orderId);

    /**
     * Find all trades by client order ID
     */
    Optional<Trade> findByClientOrderId(String clientOrderId);

    /**
     * Find all trades with status
     */
    List<Trade> findByStatus(String status);

}
