package com.foreshock.tradingbot.mongodb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service class for managing trades in MongoDB
 */
@Slf4j
@Service
public class TradeService {

    private final TradeRepository tradeRepository;

    @Autowired
    public TradeService(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    /**
     * Save a new trade to MongoDB
     */
    public Trade saveTrade(Trade trade) {
        if (trade.getCreatedAt() == null) {
            trade.setCreatedAt(LocalDateTime.now());
        }
        trade.setUpdatedAt(LocalDateTime.now());
        Trade savedTrade = tradeRepository.save(trade);
        log.info("Trade saved: {}", savedTrade.getId());
        return savedTrade;
    }

    /**
     * Update an existing trade
     */
    public Trade updateTrade(Trade trade) {
        trade.setUpdatedAt(LocalDateTime.now());
        Trade updatedTrade = tradeRepository.save(trade);
        log.info("Trade updated: {}", updatedTrade.getId());
        return updatedTrade;
    }

    /**
     * Get a trade by ID
     */
    public Optional<Trade> getTradeById(String id) {
        return tradeRepository.findById(id);
    }

    /**
     * Get all trades for a specific symbol
     */
    public List<Trade> getTradesBySymbol(String symbol) {
        return tradeRepository.findBySymbol(symbol);
    }

    /**
     * Get all open trades for a specific symbol
     */
    public List<Trade> getOpenTradesBySymbol(String symbol) {
        return tradeRepository.findBySymbolAndStatus(symbol, "OPEN");
    }

    /**
     * Get all closed trades for a specific symbol
     */
    public List<Trade> getClosedTradesBySymbol(String symbol) {
        return tradeRepository.findBySymbolAndStatus(symbol, "CLOSED");
    }

    /**
     * Get all trades within a date range
     */
    public List<Trade> getTradesByDateRange(LocalDateTime startTime, LocalDateTime endTime) {
        return tradeRepository.findByEntryTimeBetween(startTime, endTime);
    }

    /**
     * Get all trades by strategy
     */
    public List<Trade> getTradesByStrategy(String strategy) {
        return tradeRepository.findByStrategy(strategy);
    }

    /**
     * Get a trade by order ID
     */
    public Optional<Trade> getTradeByOrderId(String orderId) {
        return tradeRepository.findByOrderId(orderId);
    }

    /**
     * Get a trade by client order ID
     */
    public Optional<Trade> getTradeByClientOrderId(String clientOrderId) {
        return tradeRepository.findByClientOrderId(clientOrderId);
    }

    /**
     * Get all open trades
     */
    public List<Trade> getOpenTrades() {
        return tradeRepository.findByStatus("OPEN");
    }

    /**
     * Get all closed trades
     */
    public List<Trade> getClosedTrades() {
        return tradeRepository.findByStatus("CLOSED");
    }

    /**
     * Delete a trade by ID
     */
    public void deleteTrade(String id) {
        tradeRepository.deleteById(id);
        log.info("Trade deleted: {}", id);
    }

    /**
     * Get all trades
     */
    public List<Trade> getAllTrades() {
        return tradeRepository.findAll();
    }

    /**
     * Calculate statistics for trades by symbol
     */
    public TradeStatistics calculateStatistics(String symbol) {
        List<Trade> trades = getClosedTradesBySymbol(symbol);
        
        int totalTrades = trades.size();
        int winningTrades = (int) trades.stream()
            .filter(t -> t.getProfitLoss() != null && t.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
            .count();
        int losingTrades = totalTrades - winningTrades;
        
        BigDecimal totalProfitLoss = trades.stream()
            .map(t -> t.getProfitLoss() != null ? t.getProfitLoss() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        double winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100 : 0;
        
        return TradeStatistics.builder()
            .symbol(symbol)
            .totalTrades(totalTrades)
            .winningTrades(winningTrades)
            .losingTrades(losingTrades)
            .winRate(winRate)
            .totalProfitLoss(totalProfitLoss)
            .averageProfitLoss(totalTrades > 0 ? totalProfitLoss.divide(BigDecimal.valueOf(totalTrades)) : BigDecimal.ZERO)
            .build();
    }

}
