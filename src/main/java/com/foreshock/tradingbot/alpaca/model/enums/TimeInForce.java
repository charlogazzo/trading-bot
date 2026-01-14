package com.foreshock.tradingbot.alpaca.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TimeInForce {
    DAY("day"),
    GTC("gtc"),
    OPG("opg"),
    CLS("cls"),
    IOC("ioc"),
    FOK("fok");

    private final String value;

    TimeInForce(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return this.value;
    }
}
