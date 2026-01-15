package com.foreshock.tradingbot;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;

@Component
public class StartupRunner implements CommandLineRunner {

    private final LiveTrader liveTrader;

    public StartupRunner(LiveTrader liveTrader) {
        this.liveTrader = liveTrader;
    }

    @Override
    public void run(String... args) throws Exception {
        Bar bar = liveTrader.fetchLatestBarFromBroker("AAPL");
        System.out.println(bar.getClosePrice());
    }
}
