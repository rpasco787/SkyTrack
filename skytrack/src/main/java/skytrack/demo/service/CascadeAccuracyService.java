package skytrack.demo.service;

import org.springframework.stereotype.Service;
import skytrack.demo.model.CascadeAccuracySummary;
import skytrack.demo.model.CascadeChain;
import skytrack.demo.model.CascadeHop;

import java.util.List;

@Service
public class CascadeAccuracyService {

    public CascadeAccuracySummary summarize(String airportIata, List<CascadeChain> chains) {
        int totalHops = 0;
        int backtestable = 0;
        double totalError = 0.0;
        int truePositives = 0;
        int falsePositives = 0;

        for (CascadeChain chain : chains) {
            for (CascadeHop hop : chain.hops()) {
                totalHops++;
                if (hop.actualDepDelaySeconds() != null) {
                    backtestable++;
                    totalError += Math.abs(hop.predictedDepDelaySeconds() - hop.actualDepDelaySeconds());
                }
                if (hop.lateAircraftDelaySeconds() != null) {
                    if (hop.lateAircraftDelaySeconds() > 0) truePositives++;
                    else falsePositives++;
                }
            }
        }

        double mae = backtestable == 0 ? 0.0 : totalError / backtestable;
        double avgLen = chains.isEmpty() ? 0.0 : (double) totalHops / chains.size();
        int denominator = truePositives + falsePositives;
        double precision = denominator == 0 ? 0.0 : (double) truePositives / denominator;

        return new CascadeAccuracySummary(
                airportIata, chains.size(), totalHops, backtestable, mae, avgLen,
                truePositives, falsePositives, precision);
    }
}
