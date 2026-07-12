package za.co.fnb.dcre.cde.data.model;

/** Non-PASS verdict projection for exclusion-visibility WARNs (R-38). */
public record ExcludedVerdict(int sequence, String e2e, String outcome) {
}
