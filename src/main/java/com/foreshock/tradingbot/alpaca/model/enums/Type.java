package com.foreshock.tradingbot.alpaca.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Type {
    MARKET("market"),
    LIMIT("limit"),
    STOP("stop"),
    STOP_LIMIT("stop_limit"),
    TRAILING_STOP("trailing_stop");

    private final String displayName;

    Type(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return this.displayName;
    }
}
