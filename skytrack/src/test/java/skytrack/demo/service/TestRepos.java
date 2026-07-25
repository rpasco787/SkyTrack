package skytrack.demo.service;

import skytrack.demo.model.BtsFlightRecord;
import java.util.List;

public final class TestRepos {
    public static BtsScheduleRepository of(BtsFlightRecord... records) {
        return new BtsScheduleRepository(List.of(records));
    }
}
