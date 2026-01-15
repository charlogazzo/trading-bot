package com.foreshock.tradingbot;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.Bar;

@Component
public class StartupRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);
    private final LiveTrader liveTrader;

    public StartupRunner(LiveTrader liveTrader) {
        this.liveTrader = liveTrader;
    }

    @Override
    public void run(String... args) throws Exception {
        Bar bar = liveTrader.fetchLatestBarFromBroker("AAPL");
        log.info(String.valueOf(bar.getClosePrice()));
    }
}
