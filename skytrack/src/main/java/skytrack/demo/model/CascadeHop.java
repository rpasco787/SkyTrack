package skytrack.demo.model;

/**
 * One predicted downstream leg on a delayed aircraft's rotation.
 *
 * @param predictedDepDelaySeconds model output for this leg's departure delay
 * @param actualDepDelaySeconds    BTS ground truth (null if unknown / not backtestable)
 */
public record CascadeHop(
        String carrierIata,
        String flightNumber,
        String tailNumber,
        String originIata,
        String destIata,
        long scheduledDepEpoch,
        long predictedDepDelaySeconds,
        Long actualDepDelaySeconds,
        Long lateAircraftDelaySeconds) {}
