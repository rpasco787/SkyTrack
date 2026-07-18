package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.CascadeChain;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentCascadeStoreTest {

    private static CascadeChain chain(String iata, String callsign) {
        return CascadeChain.of(callsign, iata, 2400L, List.of(), Instant.now());
    }

    @Test
    void shouldStoreAndReturnAlertsByAirport() {
        var store = new RecentCascadeStore();
        store.add(chain("ORD", "UAL1"));
        store.add(chain("ORD", "UAL2"));
        store.add(chain("ATL", "DAL9"));

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
            store.add(chain("ORD", "UAL" + i));
        }
        List<CascadeChain> recent = store.getRecent("ORD");
        assertThat(recent).hasSize(50);
        assertThat(recent).extracting(CascadeChain::sourceCallsign).contains("UAL59");
        assertThat(recent).extracting(CascadeChain::sourceCallsign).doesNotContain("UAL0");
    }

    @Test
    void shouldIgnoreNullAirport() {
        var store = new RecentCascadeStore();
        store.add(CascadeChain.of("UAL1", null, 2400L, List.of(), Instant.now()));
        assertThat(store.getRecent(null)).isEmpty();
    }
}
