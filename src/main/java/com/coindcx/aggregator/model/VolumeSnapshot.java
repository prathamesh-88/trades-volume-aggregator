package com.coindcx.aggregator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Mutable value object stored off-heap inside Chronicle Map.
 * Chronicle Map requires the value to implement {@link Serializable} and provide
 * average size hints during map creation.
 */
public final class VolumeSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private long userId;
    private double totalVolume;
    private long lastUpdatedMs;
    private long snapshotTimestampMs;

    public VolumeSnapshot() {
    }

    @JsonCreator
    public VolumeSnapshot(
            @JsonProperty("userId") long userId,
            @JsonProperty("totalVolume") double totalVolume,
            @JsonProperty("lastUpdatedMs") long lastUpdatedMs,
            @JsonProperty("snapshotTimestampMs") long snapshotTimestampMs) {
        this.userId = userId;
        this.totalVolume = totalVolume;
        this.lastUpdatedMs = lastUpdatedMs;
        this.snapshotTimestampMs = snapshotTimestampMs;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public double getTotalVolume() {
        return totalVolume;
    }

    public void setTotalVolume(double totalVolume) {
        this.totalVolume = totalVolume;
    }

    public long getLastUpdatedMs() {
        return lastUpdatedMs;
    }

    public void setLastUpdatedMs(long lastUpdatedMs) {
        this.lastUpdatedMs = lastUpdatedMs;
    }

    public long getSnapshotTimestampMs() {
        return snapshotTimestampMs;
    }

    public void setSnapshotTimestampMs(long snapshotTimestampMs) {
        this.snapshotTimestampMs = snapshotTimestampMs;
    }

    public VolumeSnapshot copy() {
        return new VolumeSnapshot(userId, totalVolume, lastUpdatedMs, snapshotTimestampMs);
    }

    @Override
    public String toString() {
        return "VolumeSnapshot{userId=" + userId +
                ", totalVolume=" + totalVolume +
                ", lastUpdatedMs=" + lastUpdatedMs +
                ", snapshotTimestampMs=" + snapshotTimestampMs + '}';
    }
}
