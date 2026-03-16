package com.coindcx.aggregator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class TradeEvent {

    private final long userId;
    private final String symbol;
    private final double volume;
    private final long timestampMs;

    @JsonCreator
    public TradeEvent(
            @JsonProperty("userId") long userId,
            @JsonProperty("symbol") String symbol,
            @JsonProperty("volume") double volume,
            @JsonProperty("timestampMs") long timestampMs) {
        this.userId = userId;
        this.symbol = symbol;
        this.volume = volume;
        this.timestampMs = timestampMs;
    }

    public long getUserId() {
        return userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public double getVolume() {
        return volume;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    @Override
    public String toString() {
        return "TradeEvent{userId=" + userId +
                ", symbol='" + symbol + '\'' +
                ", volume=" + volume +
                ", timestampMs=" + timestampMs + '}';
    }
}
