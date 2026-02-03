package com.foreshock.tradingbot.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ta4j.core.*;
import org.ta4j.core.analysis.criteria.NumberOfPositionsCriterion;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class AlpacaHourlyLoader {
    // Historical bars are only available from the base endpoint "https://data.alpaca.markets"
    // Placing orders and retrieving general asset data can be performed through "https://paper-api.alpaca.markets"
    private static final String BASE_URL = "https://data.alpaca.markets";
    private static  final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;  // UTC

    private static final String API_KEY_ID = System.getenv("ALPACA_API_KEY");
    private static final String API_SECRET_KEY = System.getenv("ALPACA_API_SECRET");

    private static final Logger log = LoggerFactory.getLogger(AlpacaHourlyLoader.class);

    // TODO: refactor method to load varied time frames e.g. 5-minute, 15-minute, 1 hour e.t.c.
    public static BarSeries loadHourlyBars(String symbol, Duration duration, ZonedDateTime startInclusive,
                                           ZonedDateTime endInclusive, String apiKey, String apiSecret) throws Exception {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(symbol + "-hourly")
                .withNumTypeOf(DoubleNum::valueOf)
                .build();

        HttpClient alpacaClient = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        String timeFrame = "1Hour";
        String pageToken = null;

        String start = startInclusive.withZoneSameInstant(ZoneOffset.UTC).format(ISO_INSTANT);
        String end = endInclusive.withZoneSameInstant(ZoneOffset.UTC).format(ISO_INSTANT);

        while(true) {
            StringBuilder url = new StringBuilder(BASE_URL)
                    .append("/v2/stocks")
                    .append("/bars")
                    .append("?symbols=").append(symbol)
                    .append("&timeframe=").append(timeFrame)
                    .append("&start=").append(start)
                    .append("&end=").append(end)
                    .append("&limit=10000");
            if (pageToken != null) {
                url.append("&page_token=").append(pageToken);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("APCA-API-KEY-ID", apiKey)
                    .header("APCA-API-SECRET-KEY", apiSecret)
                    .GET()
                    .build();

            HttpResponse<String> response = alpacaClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Alpaca response " + response.statusCode() + ": " +
                        response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode bars = root.get("bars");
            if (bars.isEmpty() || bars.isNull()) {
                break;
            }
            JsonNode symbolBars = bars.get(symbol);

            for (JsonNode b : symbolBars) {
                String t = b.get("t").asText();
                double o = b.get("o").asDouble();
                double h = b.get("h").asDouble();
                double l = b.get("l").asDouble();
                double c = b.get("c").asDouble();
                double v = Optional.ofNullable(b.get("v")).map(JsonNode::asDouble).orElse(0d);

                // convert timestamp to ZonedDateTime
                Instant instant = Instant.parse(t);
                ZonedDateTime endTime = instant.atZone(ZoneId.of("UTC"));

                Bar bar = new BaseBar(Duration.ofHours(1), endTime, o, h, l, c, v);
                series.addBar(bar);
            }

            JsonNode nextToken = root.get("next_page_token");
            if (nextToken == null || nextToken.isNull()) {
                break;
            }
            pageToken = nextToken.asText();
        }
        return series;
    }

    // Simple strategy (20/60 SMA cross + RSI<80)
    static Strategy buildStrategy(BarSeries series) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(close, 20);
        SMAIndicator sma60 = new SMAIndicator(close, 60);
        RSIIndicator rsi14 = new RSIIndicator(close, 14);

        Rule entry = new CrossedUpIndicatorRule(sma20, sma60)
                .and(new UnderIndicatorRule(rsi14, series.numOf(50)));
        Rule exit = new CrossedDownIndicatorRule(sma20, sma60);
        return new BaseStrategy(entry, exit);
    }

    public static void main(String[] args) throws Exception {
        String symbol = "AAPL";

        ZonedDateTime start = ZonedDateTime.of(LocalDateTime.of(2024, 1, 2, 0, 0, 0),
                ZoneId.of("UTC"));
        ZonedDateTime end = ZonedDateTime.of(LocalDateTime.of(2024, 3, 31, 0, 0, 0),
                ZoneId.of("UTC"));

        BarSeries series = loadHourlyBars(symbol, start, end, API_KEY_ID, API_SECRET_KEY);
        log.info("Loaded bars: {}", series.getBarCount());

        Strategy strategy = buildStrategy(series);

        // Baseline unit-position backtest
        TradingRecord record = new BarSeriesManager(series).run(strategy);
        log.info("Baseline trades: {}", new NumberOfPositionsCriterion()
                .calculate(series, record));
    }
}
