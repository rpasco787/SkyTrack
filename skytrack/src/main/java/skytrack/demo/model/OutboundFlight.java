package skytrack.demo.model;

public record OutboundFlight(
        String carrierIata,
        String flightNumber,
        String tailNumber,
        String departureAirportIata,
        long scheduledDepEpoch,
        Long actualDepDelaySeconds) {}
