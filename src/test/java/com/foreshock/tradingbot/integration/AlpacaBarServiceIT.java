package com.foreshock.tradingbot.integration;

import com.foreshock.tradingbot.alpaca.AlpacaBarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.Bar;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {AlpacaBarService.class})
public class AlpacaBarServiceIT {

    @Autowired
    AlpacaBarService alpacaBarService;

    @Test
    void testBarRetrievedForOneSymbol() throws Exception {
        String symbolToRetrieve = "AAPL";

        Map<String, Bar> retrievedBar = alpacaBarService.getBarResponse(List.of(symbolToRetrieve));
        assertNotNull(retrievedBar);
        assertEquals(1, retrievedBar.size());
        assertInstanceOf(Bar.class, retrievedBar.get(symbolToRetrieve));
    }

    @Test
    void testBarRetrievedForMultipleSymbols() throws Exception {
        List<String> symbolsToRetrieve = List.of("AAPL", "TSLA", "PLTR");
        Map<String, Bar> retrievedBars = alpacaBarService.getBarResponse(symbolsToRetrieve);
        assertNotNull(retrievedBars);
        assertEquals(symbolsToRetrieve.size(), retrievedBars.size());
        retrievedBars.forEach((symbol, bar) ->
                assertInstanceOf(Bar.class, bar)
        );
    }
}
