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

    // Load bars for any supported Alpaca timeframe (e.g. "5Min", "1Hour", "1Day", "1Week", "3Month")
    public static BarSeries loadBars(String symbol, String alpacaTimeFrame, ZonedDateTime startInclusive,
                                     ZonedDateTime endInclusive, String apiKey, String apiSecret) throws Exception {
        String seriesName = symbol + "-" + alpacaTimeFrame;
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(seriesName)
                .withNumTypeOf(DoubleNum::valueOf)
                .build();

        HttpClient alpacaClient = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        // validate/normalize timeframe
        String timeFrame = alpacaTimeFrame;
        if (!isValidAlpacaTimeFrame(timeFrame)) {
            throw new IllegalArgumentException("Unsupported Alpaca timeframe: " + timeFrame);
        }

        String pageToken = null;

        String start = startInclusive.withZoneSameInstant(ZoneOffset.UTC).format(ISO_INSTANT);
        String end = endInclusive.withZoneSameInstant(ZoneOffset.UTC).format(ISO_INSTANT);

        Duration barDuration = parseAlpacaTimeFrameToDuration(timeFrame);

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
            if (bars == null || bars.isEmpty() || bars.isNull()) {
                break;
            }
            JsonNode symbolBars = bars.get(symbol);
            if (symbolBars == null || symbolBars.isNull()) {
                break;
            }

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

                Bar bar = new BaseBar(barDuration, endTime, o, h, l, c, v);
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

    // Convenience overload: map a Duration to common Alpaca timeframe strings for quick calls.
    public static BarSeries loadBars(String symbol, Duration duration, ZonedDateTime startInclusive,
                                     ZonedDateTime endInclusive, String apiKey, String apiSecret) throws Exception {
        String tf;
        if (duration.equals(Duration.ofMinutes(1))) tf = "1Min";
        else if (duration.equals(Duration.ofMinutes(5))) tf = "5Min";
        else if (duration.equals(Duration.ofMinutes(15))) tf = "15Min";
        else if (duration.equals(Duration.ofMinutes(30))) tf = "30Min";
        else if (duration.equals(Duration.ofHours(1))) tf = "1Hour";
        else if (duration.equals(Duration.ofHours(4))) tf = "4Hour";
        else if (duration.equals(Duration.ofDays(1))) tf = "1Day";
        else throw new IllegalArgumentException("Unsupported Duration -> Alpaca timeframe mapping: " + duration);
        return loadBars(symbol, tf, startInclusive, endInclusive, apiKey, apiSecret);
    }

    static boolean isValidAlpacaTimeFrame(String tf) {
        if (tf == null) return false;
        // allowed patterns: [1-59]Min, [0-24]Hour, 1Day, 1Week, [1,2,3,4,6,12]Month
            return tf.matches("^(?:[1-9]|[1-5][0-9])Min$")
                || tf.matches("^(?:[1-9]|1[0-9]|2[0-4])Hour$")
                || tf.equals("1Day")
                || tf.equals("1Week")
                || tf.matches("^(1|2|3|4|6|12)Month$");
    }

    public static Duration parseAlpacaTimeFrameToDuration(String tf) {
        if (tf.endsWith("Min")) {
            int m = Integer.parseInt(tf.substring(0, tf.length() - 3));
            return Duration.ofMinutes(m);
        }
        if (tf.endsWith("Hour")) {
            int h = Integer.parseInt(tf.substring(0, tf.length() - 4));
            return Duration.ofHours(h);
        }
        if (tf.equals("1Day")) return Duration.ofDays(1);
        if (tf.equals("1Week")) return Duration.ofDays(7);
        if (tf.endsWith("Month")) {
            int months = Integer.parseInt(tf.substring(0, tf.length() - 5));
            // Duration doesn't have months; approximate a month as 30 days for bar length purposes
            return Duration.ofDays(30L * months);
        }
        // fallback
        throw new IllegalArgumentException("Cannot parse timeframe to duration: " + tf);
    }

    // Package-private helper: map a Duration to an Alpaca timeframe string for common intervals.
    static String durationToAlpacaTimeframe(Duration duration) {
        if (duration.equals(Duration.ofMinutes(1))) return "1Min";
        if (duration.equals(Duration.ofMinutes(5))) return "5Min";
        if (duration.equals(Duration.ofMinutes(15))) return "15Min";
        if (duration.equals(Duration.ofMinutes(30))) return "30Min";
        if (duration.equals(Duration.ofHours(1))) return "1Hour";
        if (duration.equals(Duration.ofHours(4))) return "4Hour";
        if (duration.equals(Duration.ofDays(1))) return "1Day";
        throw new IllegalArgumentException("Unsupported Duration -> Alpaca timeframe mapping: " + duration);
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

    BarSeries series = loadBars(symbol, "1Hour", start, end, API_KEY_ID, API_SECRET_KEY);
        log.info("Loaded bars: {}", series.getBarCount());

        Strategy strategy = buildStrategy(series);

        // Baseline unit-position backtest
        TradingRecord record = new BarSeriesManager(series).run(strategy);
        log.info("Baseline trades: {}", new NumberOfPositionsCriterion()
                .calculate(series, record));
    }
}
