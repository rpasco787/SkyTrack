package skytrack.demo.tooling;

import org.junit.jupiter.api.Test;
import skytrack.demo.tooling.DelayModel.Band;

import static org.assertj.core.api.Assertions.assertThat;

class DelayModelTest {

    @Test
    void normalBandBoundaries() {
        assertThat(DelayModel.chooseBand(0.00, false)).isEqualTo(Band.ON_TIME);
        assertThat(DelayModel.chooseBand(0.54, false)).isEqualTo(Band.ON_TIME);
        assertThat(DelayModel.chooseBand(0.55, false)).isEqualTo(Band.MINOR);
        assertThat(DelayModel.chooseBand(0.79, false)).isEqualTo(Band.MINOR);
        assertThat(DelayModel.chooseBand(0.80, false)).isEqualTo(Band.MAJOR);
        assertThat(DelayModel.chooseBand(0.91, false)).isEqualTo(Band.MAJOR);
        assertThat(DelayModel.chooseBand(0.92, false)).isEqualTo(Band.SEVERE);
    }

    @Test
    void hotspotSkewsHeavier() {
        // At r=0.60 a normal airport is MINOR, a hotspot is MAJOR.
        assertThat(DelayModel.chooseBand(0.60, false)).isEqualTo(Band.MINOR);
        assertThat(DelayModel.chooseBand(0.60, true)).isEqualTo(Band.MAJOR);
    }

    @Test
    void delayStaysWithinBandRange() {
        var rng = new java.util.SplittableRandom(42);
        for (int i = 0; i < 1000; i++) {
            long s = DelayModel.sampleDelaySeconds(Band.MAJOR, rng);
            assertThat(s).isBetween(45 * 60L, 119 * 60L);
        }
    }

    @Test
    void onTimeCanBeSlightlyNegative() {
        var rng = new java.util.SplittableRandom(1);
        boolean sawNegative = false;
        for (int i = 0; i < 200; i++) {
            if (DelayModel.sampleDelaySeconds(Band.ON_TIME, rng) < 0) sawNegative = true;
        }
        assertThat(sawNegative).isTrue();
    }
}
