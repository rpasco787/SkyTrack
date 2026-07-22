package skytrack.demo.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CascadeChainTest {

    @Test
    void summarizesHopCountAndTotalPredictedDelay() {
        var h1 = new CascadeHop("UA", "200", "N1", "ORD", "DEN", 1000L, 1800L, 1500L, null);
        var h2 = new CascadeHop("UA", "300", "N1", "DEN", "SFO", 2000L, 900L, null, null);
        var chain = CascadeChain.of("UAL100", "ORD", 3600L, List.of(h1, h2), Instant.EPOCH);

        assertThat(chain.flightsAffected()).isEqualTo(2);
        assertThat(chain.totalPredictedDelaySeconds()).isEqualTo(2700L);
    }

    @Test
    void emptyHopsMeansNoCascade() {
        var chain = CascadeChain.of("UAL100", "ORD", 3600L, List.of(), Instant.EPOCH);
        assertThat(chain.flightsAffected()).isZero();
        assertThat(chain.totalPredictedDelaySeconds()).isZero();
    }
}
