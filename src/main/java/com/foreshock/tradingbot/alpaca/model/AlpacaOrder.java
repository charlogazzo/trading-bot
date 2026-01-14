package com.foreshock.tradingbot.alpaca.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import org.ta4j.core.BarSeries;
import java.util.List;

import com.foreshock.tradingbot.alpaca.model.enums.*;

/**
 * Model class for alpaca orders
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlpacaOrder {

    private BarSeries series;
    private String symbol;
    private String qty;
    private String notional;
    private Side side;
    private Type type;
    @JsonProperty("time_in_force")
    private TimeInForce timeInForce;  // in the request object, this field is represented as (time_in_force)
    @JsonProperty("limit_price")
    private String limitPrice;
    @JsonProperty("stop_price")
    private String stopPrice;
    @JsonProperty("trail_price")
    private String trailPrice;
    @JsonProperty("trail_percent")
    private String trailPercent;
    @JsonProperty("extended_hours")
    private Boolean extendedHours;
    @JsonProperty("client_order_id")
    private String clientOrderId;
    @JsonProperty("order_class")
    private OrderClass orderClass;  // enum
    @JsonProperty("legs")
    private List<Leg> legs;
    @JsonProperty("take_profit")
    private TakeProfit takeProfit;
    @JsonProperty("stop_loss")
    private StopLoss stopLoss;
    @JsonProperty("position_intent")
    private PositionIntent positionIntent;  // enum
    @JsonProperty("advanced_instructions")
    private AdvancedInstructions advancedInstructions;

}
