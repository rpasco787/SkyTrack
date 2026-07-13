package skytrack.demo.model;

public record BtsFlightRecord(
        String carrierIata,
        String flightNumber,
        String tailNumber,
        String origin,
        String dest,
        long scheduledDepEpoch,
        Long actualDepDelaySeconds,
        boolean cancelled) {}
