package com.coindcx.aggregator.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jsonRoundTrip() throws Exception {
        VolumeSnapshot s = new VolumeSnapshot(7L, 3.5, 100L, 200L);
        String json = mapper.writeValueAsString(s);
        VolumeSnapshot read = mapper.readValue(json, VolumeSnapshot.class);
        assertThat(read.getUserId()).isEqualTo(7L);
        assertThat(read.getTotalVolume()).isEqualTo(3.5);
        assertThat(read.getLastUpdatedMs()).isEqualTo(100L);
        assertThat(read.getSnapshotTimestampMs()).isEqualTo(200L);
    }

    @Test
    void defaultConstructorAndSetters() {
        VolumeSnapshot s = new VolumeSnapshot();
        s.setUserId(9L);
        s.setTotalVolume(1.0);
        s.setLastUpdatedMs(11L);
        s.setSnapshotTimestampMs(22L);
        assertThat(s.getUserId()).isEqualTo(9L);
        assertThat(s.getTotalVolume()).isEqualTo(1.0);
        assertThat(s.getLastUpdatedMs()).isEqualTo(11L);
        assertThat(s.getSnapshotTimestampMs()).isEqualTo(22L);
    }

    @Test
    void copyIsIndependent() {
        VolumeSnapshot a = new VolumeSnapshot(1L, 10.0, 1L, 2L);
        VolumeSnapshot b = a.copy();
        b.setTotalVolume(99.0);
        assertThat(a.getTotalVolume()).isEqualTo(10.0);
        assertThat(b.getTotalVolume()).isEqualTo(99.0);
    }

    @Test
    void javaSerializationRoundTrip() throws Exception {
        VolumeSnapshot original = new VolumeSnapshot(5L, 12.5, 30L, 40L);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(original);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            VolumeSnapshot read = (VolumeSnapshot) ois.readObject();
            assertThat(read.getUserId()).isEqualTo(original.getUserId());
            assertThat(read.getTotalVolume()).isEqualTo(original.getTotalVolume());
            assertThat(read.getLastUpdatedMs()).isEqualTo(original.getLastUpdatedMs());
            assertThat(read.getSnapshotTimestampMs()).isEqualTo(original.getSnapshotTimestampMs());
        }
    }

    @Test
    void toStringContainsFields() {
        VolumeSnapshot s = new VolumeSnapshot(1L, 2.0, 3L, 4L);
        assertThat(s.toString())
                .contains("userId=1")
                .contains("totalVolume=2.0")
                .contains("lastUpdatedMs=3")
                .contains("snapshotTimestampMs=4");
    }
}
