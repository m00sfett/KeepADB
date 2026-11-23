package de.hohnepeople.keepadb;

/**
 * #1049: how one register request ended, returned by the {@link RegisterTransport.HttpTransport}
 * (which stays stateless, #700) instead of being noted on a shared static field. {@code ok} is
 * true for a 2xx answer only; {@code outcome} is {@code http=<code>} or {@code error=<exception
 * class>} (#881), never a URL or an endpoint. Immutable.
 */
final class TransportResult {
    final boolean ok;
    final String outcome;

    private TransportResult(boolean ok, String outcome) {
        this.ok = ok;
        this.outcome = outcome;
    }

    /** An HTTP answer; success for 2xx only. */
    static TransportResult http(int code) {
        return new TransportResult(code >= 200 && code < 300, "http=" + code);
    }

    /** No HTTP answer. #881: the exception class only -- its message can carry the host. */
    static TransportResult error(Exception e) {
        return failure(e.getClass().getSimpleName());
    }

    /** No HTTP answer, named by an error class (tests and the offline test transport). */
    static TransportResult failure(String errorClass) {
        return new TransportResult(false, "error=" + errorClass);
    }

    /** #923: only an HTTP answer proves that a rejected POST registered nothing. */
    boolean isHttpAnswer() {
        return outcome.startsWith("http=");
    }

    @Override
    public String toString() {
        return outcome;
    }
}
