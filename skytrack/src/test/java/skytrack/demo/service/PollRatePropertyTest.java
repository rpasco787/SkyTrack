package skytrack.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = PollRatePropertyTest.Probe.class,
        properties = "opensky.poll-rate-ms=1500")
class PollRatePropertyTest {

    static class Probe {
        @Value("${opensky.poll-rate-ms:30000}")
        long pollRateMs;
    }

    @Test
    void resolvesConfiguredPollRate(@org.springframework.beans.factory.annotation.Autowired Probe probe) {
        assertThat(probe.pollRateMs).isEqualTo(1500L);
    }
}
