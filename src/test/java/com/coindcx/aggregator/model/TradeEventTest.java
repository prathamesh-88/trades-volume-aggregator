package com.coindcx.aggregator.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jsonRoundTrip() throws Exception {
        String json = """
                {"userId":42,"symbol":"BTCUSDT","volume":1.5,"timestampMs":1700000000000}
                """;
        TradeEvent event = mapper.readValue(json, TradeEvent.class);
        assertThat(event.getUserId()).isEqualTo(42L);
        assertThat(event.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(event.getVolume()).isEqualTo(1.5);
        assertThat(event.getTimestampMs()).isEqualTo(1_700_000_000_000L);

        TradeEvent roundTrip = mapper.readValue(mapper.writeValueAsString(event), TradeEvent.class);
        assertThat(roundTrip.getUserId()).isEqualTo(event.getUserId());
        assertThat(roundTrip.getSymbol()).isEqualTo(event.getSymbol());
        assertThat(roundTrip.getVolume()).isEqualTo(event.getVolume());
        assertThat(roundTrip.getTimestampMs()).isEqualTo(event.getTimestampMs());
    }

    @Test
    void toStringContainsFields() {
        TradeEvent e = new TradeEvent(1L, "ETH", 2.25, 99L);
        assertThat(e.toString())
                .contains("userId=1")
                .contains("ETH")
                .contains("2.25")
                .contains("timestampMs=99");
    }
}
