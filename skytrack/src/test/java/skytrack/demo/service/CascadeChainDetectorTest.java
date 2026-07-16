package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.config.DisruptionScoreProperties;
import skytrack.demo.config.PredictionProperties;
import skytrack.demo.model.BtsFlightRecord;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.model.ResolvedArrival;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CascadeChainDetectorTest {

    private static final long H = 3600L;
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    // props: window, bucket, delayThreshold=15m, cascadeThreshold=30m, propFactor, recovery, maxHops
    private DisruptionScoreProperties props(double recovery, int maxHops) {
        return new DisruptionScoreProperties(60, 1, 15, 30, 0.85, recovery, maxHops);
    }

    // Inbound UA100 lands ORD; tail N1 then flies ORD->DEN (UA200), DEN->SFO (UA300).
    private BtsScheduleRepository repo(long baseDep) {
        var inbound = new BtsFlightRecord("UA", "100", "N1", "IAH", "ORD",
                baseDep, baseDep + H, null, false);                 // sched arr ORD = baseDep+1h
        var leg1 = new BtsFlightRecord("UA", "200", "N1", "ORD", "DEN",
                baseDep + 2 * H, baseDep + 4 * H, 1500L, false);   // dep +2h, arr +4h
        var leg2 = new BtsFlightRecord("UA", "300", "N1", "DEN", "SFO",
                baseDep + 5 * H, baseDep + 7 * H, 600L, false);    // dep +5h, arr +7h
        return TestRepos.of(inbound, leg1, leg2);
    }

    private ResolvedArrival arrival(long actualArrivalEpoch, long delaySeconds) {
        return new ResolvedArrival("abc", "UAL100", "UA", "100",
                "KORD", "ORD", actualArrivalEpoch, actualArrivalEpoch - delaySeconds,
                delaySeconds, "BTS_REPLAY");
    }

    private CascadeChainDetector detector(BtsScheduleRepository r, double recovery, int maxHops) {
        return new CascadeChainDetector(new CallsignParser(), r,
                new TurnaroundEstimator(new PredictionProperties(true, "x", 45, 15)),
                props(recovery, maxHops), clock);
    }

    @Test
    void propagatesDelayAlongTailRotationUntilAbsorbed() {
        long base = 100_000L;
        var det = detector(repo(base), 0.0, 8);      // recovery off for clean arithmetic

        // Inbound scheduled arr = base+1h. Arrival delay 2h (7200s), well over 30m gate.
        // leg1 slack = dep(base+2h) - schedArr(base+1h) - turnaround(45m) = 3600-2700 = 900s
        //   depDelay1 = max(0, 7200 - 900) = 6300s  (>= 15m → included)
        // 0.0 recovery is clamped to default 0.15 by DisruptionScoreProperties compact constructor.
        //   carried = round(6300 * 0.85) = 5355 ; prevSchedArr = base+4h
        // leg2 slack = dep(base+5h) - arr(base+4h) - 2700 = 3600-2700 = 900s
        //   depDelay2 = max(0, 5355 - 900) = 4455s  (>= 15m → included)
        Optional<CascadeChain> out = det.detect(arrival(base + H + 7200, 7200));

        assertThat(out).isPresent();
        var chain = out.get();
        assertThat(chain.hops()).hasSize(2);
        assertThat(chain.hops().get(0).predictedDepDelaySeconds()).isEqualTo(6300L);
        assertThat(chain.hops().get(0).destIata()).isEqualTo("DEN");
        assertThat(chain.hops().get(0).actualDepDelaySeconds()).isEqualTo(1500L);
        assertThat(chain.hops().get(1).predictedDepDelaySeconds()).isEqualTo(4455L);
        assertThat(chain.totalPredictedDelaySeconds()).isEqualTo(10755L);
    }

    @Test
    void stopsWhenSlackAbsorbsDelay() {
        long base = 100_000L;
        var det = detector(repo(base), 0.0, 8);
        // Small arrival delay (31m=1860s) just over the 30m gate but leg1 slack 900s →
        // depDelay1 = 960s = 16m (included). carried 960 → leg2 depDelay = max(0,960-900)=60s
        // 60s < 15m threshold → chain stops after 1 hop.
        Optional<CascadeChain> out = det.detect(arrival(base + H + 1860, 1860));
        assertThat(out).isPresent();
        assertThat(out.get().hops()).hasSize(1);
    }

    @Test
    void appliesEnRouteRecoveryBetweenHops() {
        long base = 100_000L;
        var det = detector(repo(base), 0.5, 8);      // 50% recovery in the air
        // depDelay1 = 6300s ; carried = round(6300 * 0.5) = 3150s
        // depDelay2 = max(0, 3150 - 900) = 2250s
        var chain = det.detect(arrival(base + H + 7200, 7200)).orElseThrow();
        assertThat(chain.hops().get(1).predictedDepDelaySeconds()).isEqualTo(2250L);
    }

    @Test
    void ignoresDelayBelowCascadeThreshold() {
        long base = 100_000L;
        var det = detector(repo(base), 0.0, 8);
        assertThat(det.detect(arrival(base + H + 1500, 1500))).isEmpty();  // 25m < 30m gate
    }

    @Test
    void respectsMaxHops() {
        long base = 100_000L;
        var det = detector(repo(base), 0.0, 1);      // cap at 1
        assertThat(det.detect(arrival(base + H + 7200, 7200)).orElseThrow().hops()).hasSize(1);
    }

    @Test
    void emptyWhenCallsignUnparseable() {
        long base = 100_000L;
        var det = detector(repo(base), 0.0, 8);
        var bad = new ResolvedArrival("abc", "???", "UA", "100", "KORD", "ORD",
                base + H + 7200, base, 7200L, "BTS_REPLAY");
        assertThat(det.detect(bad)).isEmpty();
    }
}
