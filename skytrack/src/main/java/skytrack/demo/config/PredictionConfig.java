package skytrack.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import skytrack.demo.service.AirportTimeZoneResolver;
import skytrack.demo.service.BtsScheduleRepository;

@Configuration
public class PredictionConfig {

    @Bean
    BtsScheduleRepository btsScheduleRepository(PredictionProperties props,
                                                        AirportTimeZoneResolver tz) {
        if (!props.enabled()) return BtsScheduleRepository.empty();
        return BtsScheduleRepository.fromCsv(props.btsCsvPath(), tz);
    }
}
