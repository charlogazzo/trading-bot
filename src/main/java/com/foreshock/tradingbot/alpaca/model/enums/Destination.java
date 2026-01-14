package com.foreshock.tradingbot.alpaca.model.enums;

public enum Destination {
    NYSE("NYSE"),
    NASDAQ("NASDAQ"),
    ARCA("ARCA");

    private final String value;

    Destination(String value) {
        this.value = value;
    }

    public String getValue() {
        return this.value;
    }
}
