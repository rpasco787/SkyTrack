package skytrack.demo.backtest;

public record CoverageFunnel(int landings, int callsignParsed, int inboundLegFound,
                             int nextDepartureFound, int groundTruthPresent) {

    public String asTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("| Stage | Count | % of landings |\n");
        sb.append("|---|---|---|\n");
        sb.append(row("Landings", landings));
        sb.append(row("Callsign parsed", callsignParsed));
        sb.append(row("Inbound leg found", inboundLegFound));
        sb.append(row("Next departure found", nextDepartureFound));
        sb.append(row("Ground truth present", groundTruthPresent));
        return sb.toString();
    }

    private String row(String label, int count) {
        double pct = landings == 0 ? 0.0 : 100.0 * count / landings;
        return "| %s | %d | %.1f%% |%n".formatted(label, count, pct);
    }
}
