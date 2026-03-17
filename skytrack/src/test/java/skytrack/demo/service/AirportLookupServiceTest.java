package skytrack.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import skytrack.demo.model.Airport;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AirportLookupServiceTest {

    private AirportLookupService service;

    @BeforeEach
    void setUp() throws Exception {
        // Uses real airports.csv from project root
        service = new AirportLookupService("data/airports/airports.csv");
        service.loadAirports();
    }

    @Test
    void shouldLoadUsAirports() {
        assertThat(service.count()).isGreaterThan(100);
    }

    @Test
    void shouldFindLaxByIata() {
        Optional<Airport> lax = service.findByIata("LAX");
        assertThat(lax).isPresent();
        assertThat(lax.get().ident()).isEqualTo("KLAX");
    }

    @Test
    void shouldFindNearestAirportToLaxCoords() {
        // LAX coordinates
        Optional<Airport> nearest = service.findNearest(33.9425, -118.4081, 5.0);
        assertThat(nearest).isPresent();
        assertThat(nearest.get().iataCode()).isEqualTo("LAX");
    }

    @Test
    void shouldReturnEmptyWhenTooFarFromAnyAirport() {
        // Middle of the Pacific Ocean
        Optional<Airport> nearest = service.findNearest(30.0, -150.0, 5.0);
        assertThat(nearest).isEmpty();
    }

    @Test
    void shouldComputeHaversineDistanceLaxToSfo() {
        // LAX to SFO is approximately 543 km
        double dist = AirportLookupService.haversineKm(33.9425, -118.4081, 37.6213, -122.3790);
        assertThat(dist).isCloseTo(543.0, within(10.0));
    }

    @Test
    void shouldComputeHaversineDistanceZeroForSamePoint() {
        double dist = AirportLookupService.haversineKm(40.0, -74.0, 40.0, -74.0);
        assertThat(dist).isEqualTo(0.0);
    }

    @Test
    void shouldFindByIcaoCode() {
        Optional<Airport> ord = service.findByIcao("KORD");
        assertThat(ord).isPresent();
        assertThat(ord.get().iataCode()).isEqualTo("ORD");
    }

    @Test
    void shouldReturnEmptyForUnknownIata() {
        assertThat(service.findByIata("ZZZ")).isEmpty();
    }
}
