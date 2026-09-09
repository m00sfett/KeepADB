package de.hohnepeople.keepadb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory {@link KeepADBRegisterClient.HttpTransport} fake for testing failure injection,
 * latency, and recording network calls deterministically without a real network socket (#326).
 */
final class KeepADBFakeHttpTransport implements KeepADBRegisterClient.HttpTransport {

    static final class Request {
        final String method;
        final String url;
        final String payload;
        final String logLabel;

        Request(String method, String url, String payload, String logLabel) {
            this.method = method;
            this.url = url;
            this.payload = payload;
            this.logLabel = logLabel;
        }

        @Override
        public String toString() {
            return method + " " + url + (payload != null ? " payload=" + payload : "");
        }
    }

    interface RequestCallback {
        void onRequest(Request request);
    }

    private volatile boolean postSuccess = true;
    private volatile boolean deleteSuccess = true;
    private volatile int postResponseCode = 200;
    private volatile int deleteResponseCode = 200;
    private volatile long simulatedLatencyMs = 0;
    private volatile RequestCallback requestCallback;
    private volatile Runnable failureCallback;
    private volatile String failingUrl;

    final List<Request> recordedRequests = Collections.synchronizedList(new ArrayList<>());

    KeepADBFakeHttpTransport() {
    }

    KeepADBFakeHttpTransport(boolean defaultSuccess) {
        this.postSuccess = defaultSuccess;
        this.deleteSuccess = defaultSuccess;
    }

    void setPostSuccess(boolean postSuccess) {
        this.postSuccess = postSuccess;
    }

    void setDeleteSuccess(boolean deleteSuccess) {
        this.deleteSuccess = deleteSuccess;
    }

    void setAllSuccess(boolean success) {
        this.postSuccess = success;
        this.deleteSuccess = success;
    }

    void setPostResponseCode(int code) {
        this.postResponseCode = code;
        this.postSuccess = code >= 200 && code < 300;
    }

    void setDeleteResponseCode(int code) {
        this.deleteResponseCode = code;
        this.deleteSuccess = code >= 200 && code < 300;
    }

    int getPostResponseCode() {
        return postResponseCode;
    }

    int getDeleteResponseCode() {
        return deleteResponseCode;
    }

    /**
     * Fails requests to exactly this URL while all other URLs keep their configured outcome
     * (#317: a URL migration where only the superseded endpoint is unreachable).
     */
    void setFailingUrl(String url) {
        this.failingUrl = url;
    }

    void setSimulatedLatencyMs(long ms) {
        this.simulatedLatencyMs = ms;
    }

    void setRequestCallback(RequestCallback callback) {
        this.requestCallback = callback;
    }

    void setFailureCallback(Runnable callback) {
        this.failureCallback = callback;
    }

    void clearRequests() {
        recordedRequests.clear();
    }

    int getRequestCount() {
        return recordedRequests.size();
    }

    Request getLastRequest() {
        synchronized (recordedRequests) {
            return recordedRequests.isEmpty() ? null : recordedRequests.get(recordedRequests.size() - 1);
        }
    }

    @Override
    public boolean postJson(String targetUrl, String payload, String logLabel) {
        Request req = new Request("POST", targetUrl, payload, logLabel);
        recordedRequests.add(req);

        if (simulatedLatencyMs > 0) {
            try {
                Thread.sleep(simulatedLatencyMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (requestCallback != null) {
            requestCallback.onRequest(req);
        }

        if (!postSuccess || targetUrl != null && targetUrl.equals(failingUrl)) {
            if (failureCallback != null) {
                failureCallback.run();
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean delete(String targetUrl) {
        Request req = new Request("DELETE", targetUrl, null, null);
        recordedRequests.add(req);

        if (simulatedLatencyMs > 0) {
            try {
                Thread.sleep(simulatedLatencyMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (requestCallback != null) {
            requestCallback.onRequest(req);
        }

        if (!deleteSuccess || targetUrl != null && targetUrl.equals(failingUrl)) {
            if (failureCallback != null) {
                failureCallback.run();
            }
            return false;
        }
        return true;
    }
}
