package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.BtsFlightRecord;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class BtsScheduleRepositoryTest {

    private final BtsFlightRecord inbound  = new BtsFlightRecord("UA","1234","N12345","DEN","ORD", 1773080000L, 1773083600L, 600L, false);
    private final BtsFlightRecord outbound = new BtsFlightRecord("UA","5678","N12345","ORD","LAX", 1773090000L, 1773093600L, 900L, false);
    private final BtsFlightRecord otherTail= new BtsFlightRecord("AA","99","N999","ORD","MIA",     1773091000L, 1773094600L, 0L,   false);

    private final BtsScheduleRepository repo =
            new BtsScheduleRepository(List.of(inbound, outbound, otherTail));

    @Test
    void findsInboundLegByCarrierFlightAndDest() {
        Optional<BtsFlightRecord> rec = repo.findInboundLeg("UA","1234","ORD", 1773081000L);
        assertThat(rec).contains(inbound);
        assertThat(rec.get().tailNumber()).isEqualTo("N12345");
    }

    @Test
    void findsNextDepartureForTailAfterLanding() {
        Optional<BtsFlightRecord> next = repo.findNextDeparture("N12345","ORD", 1773085000L);
        assertThat(next).contains(outbound);
    }

    @Test
    void noNextDepartureWhenAllBeforeCutoff() {
        assertThat(repo.findNextDeparture("N12345","ORD", 1773099999L)).isEmpty();
    }
}
