# MongoDB Integration for Trading Bot

This document explains how to use MongoDB to store and retrieve trades in your trading bot application.

## Configuration

MongoDB is configured to connect to `mongodb://localhost:27017/tradingbot` by default. The configuration is set in [src/main/resources/application.properties](src/main/resources/application.properties).

### Properties
```properties
# MongoDB
spring.data.mongodb.uri=mongodb://localhost:27017/tradingbot
spring.data.mongodb.database=tradingbot
```

If you need to use a different MongoDB instance or credentials, update these properties accordingly:
```properties
spring.data.mongodb.uri=mongodb://username:password@host:port/tradingbot
```

## Database Structure

### Collections
- **trades** - Stores all trade transactions

## Trade Document Model

The `Trade` class represents a single trade transaction in MongoDB with the following fields:

```java
- id              : String    (MongoDB ObjectId - auto-generated)
- symbol          : String    (Stock symbol, e.g., "AAPL")
- side            : String    ("BUY" or "SELL")
- quantity        : BigDecimal
- entryPrice      : BigDecimal
- exitPrice       : BigDecimal
- profitLoss      : BigDecimal
- profitLossPercentage : BigDecimal
- orderId         : String    (Alpaca order ID)
- clientOrderId   : String
- entryTime       : LocalDateTime
- exitTime        : LocalDateTime
- status          : String    ("OPEN", "CLOSED", "CANCELLED")
- strategy        : String    (e.g., "SMA_RSI")
- commission      : BigDecimal
- notes           : String
- createdAt       : LocalDateTime (auto-set)
- updatedAt       : LocalDateTime (auto-updated)
```

## Usage Examples

### Injecting TradeService

```java
@Service
public class YourService {
    
    @Autowired
    private TradeService tradeService;
    
    // Use tradeService methods...
}
```

### Creating a Trade

```java
Trade trade = Trade.builder()
    .symbol("AAPL")
    .side("BUY")
    .quantity(new BigDecimal("10"))
    .entryPrice(new BigDecimal("150.00"))
    .entryTime(LocalDateTime.now())
    .status("OPEN")
    .strategy("SMA_RSI")
    .clientOrderId("my-order-123")
    .build();

Trade savedTrade = tradeService.saveTrade(trade);
```

### Retrieving Trades

```java
// Get all trades for a symbol
List<Trade> trades = tradeService.getTradesBySymbol("AAPL");

// Get open trades for a symbol
List<Trade> openTrades = tradeService.getOpenTradesBySymbol("AAPL");

// Get closed trades for a symbol
List<Trade> closedTrades = tradeService.getClosedTradesBySymbol("AAPL");

// Get all trades by strategy
List<Trade> smaRsiTrades = tradeService.getTradesByStrategy("SMA_RSI");

// Get trades within a date range
List<Trade> recentTrades = tradeService.getTradesByDateRange(
    LocalDateTime.now().minusDays(7),
    LocalDateTime.now()
);

// Get all open trades
List<Trade> allOpen = tradeService.getOpenTrades();

// Get a specific trade by ID
Optional<Trade> trade = tradeService.getTradeById("60c5e1a8b1a2c3d4e5f6g7h8");
```

### Closing a Trade

```java
Optional<Trade> tradeOpt = tradeService.getTradeById("tradeId");
if (tradeOpt.isPresent()) {
    Trade trade = tradeOpt.get();
    trade.setExitPrice(new BigDecimal("155.00"));
    trade.setExitTime(LocalDateTime.now());
    trade.setStatus("CLOSED");
    
    // Calculate profit/loss
    BigDecimal profitLoss = trade.getExitPrice()
        .subtract(trade.getEntryPrice())
        .multiply(trade.getQuantity());
    trade.setProfitLoss(profitLoss);
    
    // Calculate percentage
    BigDecimal percentage = profitLoss
        .divide(trade.getEntryPrice().multiply(trade.getQuantity()), 4, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100"));
    trade.setProfitLossPercentage(percentage);
    
    tradeService.updateTrade(trade);
}
```

### Calculating Trade Statistics

```java
TradeStatistics stats = tradeService.calculateStatistics("AAPL");
System.out.println("Total Trades: " + stats.getTotalTrades());
System.out.println("Winning Trades: " + stats.getWinningTrades());
System.out.println("Losing Trades: " + stats.getLosingTrades());
System.out.println("Win Rate: " + stats.getWinRate() + "%");
System.out.println("Total P&L: " + stats.getTotalProfitLoss());
System.out.println("Average P&L: " + stats.getAverageProfitLoss());
```

## Classes Overview

### Trade
- **Location**: [src/main/java/com/foreshock/tradingbot/mongodb/Trade.java](src/main/java/com/foreshock/tradingbot/mongodb/Trade.java)
- **Purpose**: MongoDB document entity representing a trade transaction

### TradeRepository
- **Location**: [src/main/java/com/foreshock/tradingbot/mongodb/TradeRepository.java](src/main/java/com/foreshock/tradingbot/mongodb/TradeRepository.java)
- **Purpose**: Spring Data MongoDB repository for database operations

### TradeService
- **Location**: [src/main/java/com/foreshock/tradingbot/mongodb/TradeService.java](src/main/java/com/foreshock/tradingbot/mongodb/TradeService.java)
- **Purpose**: Business logic service for trade management and statistics

### TradeStatistics
- **Location**: [src/main/java/com/foreshock/tradingbot/mongodb/TradeStatistics.java](src/main/java/com/foreshock/tradingbot/mongodb/TradeStatistics.java)
- **Purpose**: DTO for trade performance statistics

### MongoDBConfiguration
- **Location**: [src/main/java/com/foreshock/tradingbot/mongodb/MongoDBConfiguration.java](src/main/java/com/foreshock/tradingbot/mongodb/MongoDBConfiguration.java)
- **Purpose**: Spring configuration for MongoDB repositories

## Dependencies Added

The following dependency was added to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

## Next Steps

1. **Start MongoDB**: Ensure MongoDB is running on `localhost:27017`
2. **Integrate with Your Trading Logic**: Inject `TradeService` into your trading classes (e.g., `LiveTrader`, `BacktestHourly`, etc.)
3. **Log Trades**: When orders are executed, create and save `Trade` objects to MongoDB
4. **Monitor Performance**: Use `TradeStatistics` to track trading performance
5. **Query History**: Query past trades for analysis and backtesting

## Example Integration Points

Consider integrating trade logging in:
- `AlpacaOrderService` - Log trades when orders are executed
- `LiveTrader` - Save trades during live trading sessions
- `BacktestHourly` / `WalkForwardTester` - Save trades during backtesting for later analysis

---

For more information about Spring Data MongoDB, refer to the [official documentation](https://spring.io/projects/spring-data-mongodb).
