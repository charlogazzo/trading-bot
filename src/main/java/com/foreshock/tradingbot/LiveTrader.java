package com.foreshock.tradingbot;

import com.foreshock.tradingbot.alpaca.AlpacaBarService;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;

import java.util.List;
import java.util.Map;

@Component
public class LiveTrader {

    AlpacaBarService alpacaBarService;

    public LiveTrader(AlpacaBarService alpacaBarService) {
        this.alpacaBarService = alpacaBarService;
    }

    /**
        Call to Alpaca to get the latest bar of a symbol
        @param symbol symbol of the asset which bar is to be retrieved
        @return the latest bar of the symbol
     */
    public Bar fetchLatestBarFromBroker(String symbol) throws Exception {
        Map<String, Bar> bars = alpacaBarService.getBarResponse(List.of(symbol));

        if (bars.size() != 1) {
            throw new Exception("More than one bar was received");
        }
        return bars.get(symbol);
    }

    /**
     * Call to Alpaca to fetch multiple bars of a symbol
     * @param symbol symbol of the asset whose bar is to be retrieved
     * @return requested bars
     */
    public List<Bar> fetchLatestBarsFromBroker(String  symbol) {
        return null;
    }

    /**
     * Call to Alpaca to get the latest bars of multiple symbols
     * @param symbols symbols of the assets whose bars are to be retrieved
     * @return requested bars
     */
    public Map<String, Bar> fetchLatestBarsFromBroker(List<String> symbols) {
        return null;
    }

    public void placeBuyOrder() {}

    public void placeSellOrder() {}
}
