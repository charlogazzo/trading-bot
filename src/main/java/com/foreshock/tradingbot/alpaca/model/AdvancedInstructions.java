package com.foreshock.tradingbot.alpaca.model;

import com.foreshock.tradingbot.alpaca.model.enums.Algorithm;
import com.foreshock.tradingbot.alpaca.model.enums.Destination;

import java.time.LocalDateTime;

public class AdvancedInstructions {
    private Algorithm algorithm;
    private Destination destination;
    private String displayQty;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String maxPercentage;
}
