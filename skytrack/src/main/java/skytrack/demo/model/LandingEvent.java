package skytrack.demo.model;

public record LandingEvent(
        String icao24,
        String callsign,
        String arrivalAirportIcao,
        String arrivalAirportIata,
        long arrivalTime,
        double latitude,
        double longitude) {}
