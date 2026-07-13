package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class AirportTimeZoneResolverTest {

    private final AirportTimeZoneResolver resolver = new AirportTimeZoneResolver();

    @Test
    void resolvesKnownAirport() {
        assertThat(resolver.zoneFor("ORD")).contains(ZoneId.of("America/Chicago"));
    }

    @Test
    void returnsEmptyForUnknown() {
        assertThat(resolver.zoneFor("ZZZ")).isEqualTo(Optional.empty());
    }
}
