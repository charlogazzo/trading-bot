package com.foreshock.tradingbot.alpaca.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Side {
    BUY("buy"),
    SELL("sell");

    private final String value;

    Side(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return this.value;
    }
}
