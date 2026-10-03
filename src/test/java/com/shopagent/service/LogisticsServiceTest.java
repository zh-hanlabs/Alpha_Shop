package com.shopagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LogisticsServiceTest {

    @Autowired
    private LogisticsService logisticsService;

    @Test
    void queryLogistics_returns_parsed_tracks_for_in_transit_order() {
        LogisticsService.LogisticsDetail detail = logisticsService.queryLogistics("10001");

        assertThat(detail).isNotNull();
        assertThat(detail.carrier()).isEqualTo("顺丰速运");
        assertThat(detail.status()).isEqualTo("IN_TRANSIT");
        assertThat(detail.tracks()).hasSize(3);
        assertThat(detail.tracks().get(0).desc()).contains("揽收");
        assertThat(detail.tracks().get(0).location()).contains("杭州");
        assertThat(detail.tracks().get(2).time()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void queryLogistics_returns_null_when_not_found() {
        assertThat(logisticsService.queryLogistics("99999")).isNull();
    }
}
