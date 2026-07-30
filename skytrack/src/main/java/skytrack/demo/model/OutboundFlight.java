package skytrack.demo.model;

public record OutboundFlight(
        String carrierIata,
        String flightNumber,
        String tailNumber,
        String departureAirportIata,
        String destAirportIata,
        long scheduledDepEpoch,
        Long actualDepDelaySeconds) {}
