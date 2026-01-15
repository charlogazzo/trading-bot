package com.foreshock.tradingbot.alpaca;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.foreshock.tradingbot.alpaca.model.AlpacaOrder;
import com.foreshock.tradingbot.alpaca.model.enums.Side;
import com.foreshock.tradingbot.alpaca.model.enums.TimeInForce;
import com.foreshock.tradingbot.alpaca.model.enums.Type;
import lombok.NoArgsConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@NoArgsConstructor
public class AlpacaOrderService {

    private static final String BASE_URL = "https://paper-api.alpaca.markets/v2/orders";
    private static final String API_KEY_ID = System.getenv("ALPACA_API_KEY");
    private static final String API_SECRET_KEY = System.getenv("ALPACA_API_SECRET");

    private static final Logger log = LoggerFactory.getLogger(AlpacaOrderService.class);

    /* Order attributes */
    private static final String symbol = "AAPL";
    private static final String qty = "1";

    static AlpacaOrder alpacaOrderObject = AlpacaOrder.builder()
            .symbol(symbol)
            .qty(qty)
            .type(Type.MARKET)
            .timeInForce(TimeInForce.FOK)
            .side(Side.BUY)
            .build();

    private static String buildJsonBody (AlpacaOrder orderObject) {
        String jsonBody = "";

        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            jsonBody = mapper.writeValueAsString(orderObject);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        System.out.println(jsonBody);
        return jsonBody;
    }

    static HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL))
            .header("APCA-API-KEY-ID", API_KEY_ID)
            .header("APCA-API-SECRET-KEY", API_SECRET_KEY)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildJsonBody(alpacaOrderObject)))
            .build();

    static HttpClient client = HttpClient.newHttpClient();

    public static HttpResponse<String> sendOrderRequest (HttpRequest request) throws Exception{
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static void main(String[] args) throws Exception {
        HttpResponse<String> response = sendOrderRequest(request);
        System.out.println("===== Response =====");
        System.out.println("status code: " + response.statusCode());
        System.out.println("response body: \n" + response.body());
    }
}
