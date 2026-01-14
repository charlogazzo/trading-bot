package com.foreshock.tradingbot.alpaca.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PositionIntent {
    BUY_TO_OPEN("buy_to_open"),
    BUY_TO_CLOSE("buy_to_close"),
    SELL_TO_OPEN("sell_to_open"),
    SELL_TO_CLOSE("sell_to_close");

    private final String value;

    PositionIntent(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return this.value;
    }
}
