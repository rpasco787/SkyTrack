package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.CascadeAccuracySummary;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.model.CascadeHop;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CascadeAccuracyServiceTest {

    private final CascadeAccuracyService service = new CascadeAccuracyService();

    @Test
    void computesPrecisionFromLateAircraftFlag() {
        // hop1: predicted cascade, BTS confirms (lateAircraftDelay=1500s > 0) → TP
        // hop2: predicted cascade, BTS says no late aircraft (lateAircraftDelay=0)  → FP
        // hop3: no BTS data (lateAircraftDelay=null) → not counted in precision
        var chain = CascadeChain.of("UAL100", "ORD", 7200L, List.of(
                new CascadeHop("UA","200","N1","ORD","DEN",1000L, 6300L, 1500L, 1500L),
                new CascadeHop("UA","300","N1","DEN","SFO",2000L, 5400L, 3000L,    0L),
                new CascadeHop("UA","400","N1","SFO","PDX",3000L, 1200L, null,   null)
        ), Instant.EPOCH);

        var summary = new CascadeAccuracyService().summarize("ORD", List.of(chain));

        assertThat(summary.truePositives()).isEqualTo(1);
        assertThat(summary.falsePositives()).isEqualTo(1);
        assertThat(summary.precision()).isCloseTo(0.5, within(0.001));
        // MAE still computed: |6300-1500| + |5400-3000| = 4800+2400=7200; /2 = 3600
        assertThat(summary.hopLevelMaeSeconds()).isEqualTo(3600.0);
    }

    @Test
    void handlesNoChains() {
        var summary = service.summarize("ORD", List.of());
        assertThat(summary.totalChains()).isZero();
        assertThat(summary.hopLevelMaeSeconds()).isZero();
        assertThat(summary.avgChainLength()).isZero();
    }
}
