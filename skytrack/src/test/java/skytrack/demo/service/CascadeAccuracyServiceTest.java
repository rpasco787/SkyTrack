package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.CascadeAccuracySummary;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.model.CascadeHop;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CascadeAccuracyServiceTest {

    private final CascadeAccuracyService service = new CascadeAccuracyService();

    @Test
    void computesHopLevelMaeOverBacktestableHops() {
        var chain = CascadeChain.of("UAL100", "ORD", 7200L, List.of(
                new CascadeHop("UA", "200", "N1", "ORD", "DEN", 1000L, 6300L, 1500L), // err 4800
                new CascadeHop("UA", "300", "N1", "DEN", "SFO", 2000L, 5400L, 3000L), // err 2400
                new CascadeHop("UA", "400", "N1", "SFO", "PDX", 3000L, 1200L, null)   // no actual
        ), Instant.EPOCH);

        CascadeAccuracySummary summary = service.summarize("ORD", List.of(chain));

        assertThat(summary.totalChains()).isEqualTo(1);
        assertThat(summary.totalHops()).isEqualTo(3);
        assertThat(summary.backtestableHops()).isEqualTo(2);
        assertThat(summary.hopLevelMaeSeconds()).isEqualTo(3600.0); // (4800+2400)/2
        assertThat(summary.avgChainLength()).isEqualTo(3.0);
    }

    @Test
    void handlesNoChains() {
        var summary = service.summarize("ORD", List.of());
        assertThat(summary.totalChains()).isZero();
        assertThat(summary.hopLevelMaeSeconds()).isZero();
        assertThat(summary.avgChainLength()).isZero();
    }
}
