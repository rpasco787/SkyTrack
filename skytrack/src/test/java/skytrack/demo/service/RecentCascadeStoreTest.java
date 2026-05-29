package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.CascadeAlert;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentCascadeStoreTest {

    private static CascadeAlert alert(String iata, String callsign) {
        return new CascadeAlert(callsign, iata, 2400L, 2040L, 0.85, Instant.now());
    }

    @Test
    void shouldStoreAndReturnAlertsByAirport() {
        var store = new RecentCascadeStore();
        store.add(alert("ORD", "UAL1"));
        store.add(alert("ORD", "UAL2"));
        store.add(alert("ATL", "DAL9"));

        assertThat(store.getRecent("ORD")).hasSize(2);
        assertThat(store.getRecent("ATL")).hasSize(1);
    }

    @Test
    void shouldReturnEmptyForUnknownAirport() {
        assertThat(new RecentCascadeStore().getRecent("ZZZ")).isEmpty();
    }

    @Test
    void shouldCapEntriesPerAirport() {
        var store = new RecentCascadeStore();
        for (int i = 0; i < 60; i++) {
            store.add(alert("ORD", "UAL" + i));
        }
        List<CascadeAlert> recent = store.getRecent("ORD");
        assertThat(recent).hasSize(50);
        assertThat(recent).extracting(CascadeAlert::sourceCallsign).contains("UAL59");
        assertThat(recent).extracting(CascadeAlert::sourceCallsign).doesNotContain("UAL0");
    }

    @Test
    void shouldIgnoreNullAirport() {
        var store = new RecentCascadeStore();
        store.add(new CascadeAlert("UAL1", null, 2400L, 2040L, 0.85, Instant.now()));
        assertThat(store.getRecent(null)).isEmpty();
    }
}
