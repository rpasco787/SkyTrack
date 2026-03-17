package skytrack.demo.model;

public record Airport(
        String ident,
        String icaoCode,
        String iataCode,
        String name,
        double latitude,
        double longitude,
        String type) {}
