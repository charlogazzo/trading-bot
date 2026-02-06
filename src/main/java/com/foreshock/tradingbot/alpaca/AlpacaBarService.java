package com.foreshock.tradingbot.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AlpacaBarService {

    public final String BASE_URL = "https://data.alpaca.markets/v2/stocks/bars/latest";
    public final String API_KEY_ID = System.getenv("ALPACA_API_KEY");
    public final String API_SECRET_KEY = System.getenv("ALPACA_API_SECRET");

    private static final Logger log = LoggerFactory.getLogger(AlpacaBarService.class);

    static HttpClient client = HttpClient.newHttpClient();

    public Map<String, Bar> getBarResponse(List<String> symbols) throws Exception {
        HttpRequest request = buildHttpRequestWithSymbols(symbols);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String jsonResponse = response.body();
        Map<String, Bar> bars = mapJsonNodeToBarNode(jsonResponse, symbols);

        log.info("\n ======== Response ======== \n {}", response.body());
        return bars;
    }

    private HttpRequest buildHttpRequestWithSymbols(List<String> symbols) {
        String requestUrl = BASE_URL +
                "?symbols=" +
                String.join(",", symbols);

        return HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .header("APCA-API-KEY-ID", API_KEY_ID)
                .header("APCA-API-SECRET-KEY", API_SECRET_KEY)
                .header("content-type", "application/json")
                .GET()
                .build();
    }

    private static Map<String, Bar> mapJsonNodeToBarNode(String jsonRoot, List<String> symbols) throws Exception {
        Map<String, Bar> parsedBars = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        JsonNode root = mapper.readTree(jsonRoot);
        JsonNode bars = root.path("bars");

        for (String symbol : symbols) {
            JsonNode barNode = bars.path(symbol);
            ZonedDateTime t = ZonedDateTime.parse(barNode.get("t").asText());

            Bar bar = new BaseBar(Duration.ofMinutes(1),
                    t,
                    barNode.get("o").asDouble(),
                    barNode.get("h").asDouble(),
                    barNode.get("l").asDouble(),
                    barNode.get("c").asDouble(),
                    barNode.get("v").asDouble()
            );

            parsedBars.put(symbol, bar);
        }
        return parsedBars;
    }
}
