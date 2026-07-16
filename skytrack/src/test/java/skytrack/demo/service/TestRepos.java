package skytrack.demo.service;

import skytrack.demo.model.BtsFlightRecord;
import java.util.List;

final class TestRepos {
    static BtsScheduleRepository of(BtsFlightRecord... records) {
        return new BtsScheduleRepository(List.of(records));
    }
}
