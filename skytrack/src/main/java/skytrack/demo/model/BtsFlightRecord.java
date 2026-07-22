package skytrack.demo.model;

public record BtsFlightRecord(
        String carrierIata,
        String flightNumber,
        String tailNumber,
        String origin,
        String dest,
        long scheduledDepEpoch,
        Long scheduledArrEpoch,
        Long actualDepDelaySeconds,
        boolean cancelled,
        Long arrDelaySeconds,
        Long lateAircraftDelaySeconds) {}
