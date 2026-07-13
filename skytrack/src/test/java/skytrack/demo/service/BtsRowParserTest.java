package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import skytrack.demo.model.BtsFlightRecord;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class BtsRowParserTest {

    private final BtsRowParser parser = new BtsRowParser(iata ->
            iata.equals("ORD") ? Optional.of(ZoneId.of("America/Chicago")) : Optional.empty());

    private final Map<String,Integer> idx = Map.of(
            "FL_DATE",0,"OP_UNIQUE_CARRIER",1,"OP_CARRIER_FL_NUM",2,"TAIL_NUM",3,
            "ORIGIN",4,"DEST",5,"CRS_DEP_TIME",6,"DEP_DELAY",7,"CRS_ARR_TIME",8,"CANCELLED",9);

    @Test
    void parsesScheduledDepartureToUtcEpoch() {
        // 2026-03-09 is after DST start (Mar 8): Chicago is CDT (UTC-5)
        // 14:30 CDT → 19:30 UTC = 1773084600
        String[] row = {"2026-03-09","UA","1234","N12345","ORD","LAX","1430","12.00","1650","0.00"};
        Optional<BtsFlightRecord> rec = parser.parse(row, idx);
        assertThat(rec).isPresent();
        assertThat(rec.get().tailNumber()).isEqualTo("N12345");
        assertThat(rec.get().scheduledDepEpoch()).isEqualTo(1773084600L);
        assertThat(rec.get().actualDepDelaySeconds()).isEqualTo(720L); // 12 min
        assertThat(rec.get().cancelled()).isFalse();
    }

    @Test
    void skipsRowWithUnknownAirportTimezone() {
        String[] row = {"2026-03-09","UA","9","N1","XXX","LAX","1430","5.00","1650","0.00"};
        assertThat(parser.parse(row, idx)).isEmpty();
    }

    @Test
    void cancelledRowHasNullDelay() {
        String[] row = {"2026-03-09","UA","1234","N12345","ORD","LAX","1430","","1650","1.00"};
        assertThat(parser.parse(row, idx).orElseThrow().actualDepDelaySeconds()).isNull();
    }
}
