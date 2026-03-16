package skytrack.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.client.AeroApiClient;
import skytrack.demo.client.FlightScheduleApiClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class AeroApiConfig {
    @Bean
    public FlightScheduleApiClient flightScheduleApiClient(AeroApiProperties properties, ObjectMapper mapper) {
        return new AeroApiClient(properties, mapper);
    }
}
