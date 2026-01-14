package com.foreshock.tradingbot.alpaca.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Algorithm {
    DMA("DMA"),
    TWAP("TWAP"),
    VWAP("VWAP");

    private final String value;

    Algorithm(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return this.value;
    }
}
