package com.foreshock.tradingbot.alpaca.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum  OrderClass {
    SIMPLE("simple"),
    BRACKET("bracket"),
    OCO("oco"),
    OTO("oto"),
    MLEG("mleg");

    private final String value;

    OrderClass(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return this.value;
    }

}
