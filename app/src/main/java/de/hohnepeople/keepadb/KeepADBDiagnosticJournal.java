package de.hohnepeople.keepadb;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * #566: debug-build-only, time-bounded diagnostics journal that keeps at least the last 48 hours
 * of lifecycle events and per-minute network/endpoint snapshots for the diagnostics export.
 *
 * <p>Entries live in memory and are written to disk at most once per {@link #PERSIST_INTERVAL_MS}
 * (measured against the journal file's own modification time, so a restarted process does not
 * reset the budget). A process killed between two writes loses the entries recorded since the
 * last write -- an accepted trade-off decided on #566 in exchange for not persisting every
 * heartbeat tick.
 *
 * <p>Repeated identical samples for the same {@code key} (heartbeat events, minute snapshots)
 * are coalesced into one entry carrying the sample count and the time of the last sample, but
 * only while consecutive samples are at most {@link #COALESCE_MAX_GAP_MS} apart: a gap (service
 * not running, process dead, device asleep without heartbeat) therefore always starts a new
 * entry and stays visible in the export instead of being hidden inside a counter.
 *
 * <p>Release builds never instantiate this class; see {@link KeepADBDiagnostics}.
 */
final class KeepADBDiagnosticJournal {

    static final String EXPORT_HEADER = "KeepADB diagnostics v2 debug-journal";
    static final long RETENTION_MS = 50L * 60 * 60 * 1000;
    static final long PERSIST_INTERVAL_MS = 60L * 60 * 1000;
    static final long COALESCE_MAX_GAP_MS = 150_000;
    // Bounds keep the export shareable through a single ACTION_SEND text extra, which travels
    // through a Binder transaction (~1 MB, UTF-16), even on a noisy 48h history.
    static final int MAX_ENTRIES = 4000;
    static final int MAX_CHARS = 200_000;
    static final String FILE_NAME = "keepadb_diagnostics_journal.log";
    private static final String TAG = "KeepADBJournal";
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);

    interface Clock {
        long now();
    }

    static final class Entry {
        final long firstMs;
        long lastMs;
        int samples;
        final String key;
        final String signature;
        final String text;

        Entry(long firstMs, long lastMs, int samples, String key, String signature, String text) {
            this.firstMs = firstMs;
            this.lastMs = lastMs;
            this.samples = samples;
            this.key = key;
            this.signature = signature;
            this.text = text;
        }
    }

    private static KeepADBDiagnosticJournal instance;

    private final File file;
    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> lastByKey = new HashMap<>();
    private long lastPersistMs;
    private int persistCount;

    KeepADBDiagnosticJournal(File file, Clock clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    static synchronized KeepADBDiagnosticJournal get(Context context) {
        Context appContext = context.getApplicationContext();
        File filesDir = (appContext != null ? appContext : context).getFilesDir();
        if (filesDir == null) throw new IllegalStateException("no app files directory");
        File target = new File(filesDir, FILE_NAME);
        // Keyed by path so a fresh app data directory (e.g. per Robolectric test) never sees a
        // previous directory's in-memory entries; in a real process the path never changes.
        if (instance == null || !instance.file.equals(target)) {
            instance = new KeepADBDiagnosticJournal(target, System::currentTimeMillis);
        }
        return instance;
    }

    static synchronized void resetForTesting() {
        instance = null;
    }

    /** Appends a one-off entry that is never coalesced. */
    synchronized void record(String line) {
        long now = clock.now();
        append(new Entry(now, now, 1, null, null, clean(line)));
        maybePersist(now);
    }

    /**
     * Coalesces {@code line} into the previous entry of the same {@code key} when its
     * {@code signature} is unchanged and the previous sample is recent enough; otherwise appends
     * a new entry. {@code line} is only stored for the first sample of an entry.
     */
    synchronized void recordSample(String key, String signature, String line) {
        long now = clock.now();
        Entry previous = lastByKey.get(clean(key));
        if (previous != null && previous.signature.equals(clean(signature))
                && now - previous.lastMs <= COALESCE_MAX_GAP_MS && now >= previous.lastMs) {
            previous.lastMs = now;
            previous.samples++;
        } else {
            Entry entry = new Entry(now, now, 1, clean(key), clean(signature), clean(line));
            append(entry);
            lastByKey.put(entry.key, entry);
        }
        maybePersist(now);
    }

    synchronized String render() {
        long now = clock.now();
        prune(now);
        StringBuilder result = new StringBuilder(EXPORT_HEADER).append('\n');
        result.append("coverageFrom=")
                .append(entries.isEmpty() ? "none" : format(entries.get(0).firstMs))
                .append(" renderedAt=").append(format(now))
                .append(" entries=").append(entries.size())
                .append(" retentionHours=").append(RETENTION_MS / 3_600_000L)
                .append(" lastPersistedAt=").append(lastPersistMs > 0 ? format(lastPersistMs) : "never")
                .append('\n');
        for (String line : renderEntries(entries.size())) result.append(line).append('\n');
        return result.toString();
    }

    /** The newest {@code limit} entries only, oldest first, e.g. for a compact issue report. */
    synchronized List<String> renderEntries(int limit) {
        List<String> lines = new ArrayList<>();
        for (int i = Math.max(0, entries.size() - limit); i < entries.size(); i++) {
            Entry entry = entries.get(i);
            lines.add(renderedLine(entry));
        }
        return lines;
    }

    /** The exact text a single entry contributes to an export line (without the trailing '\n'). */
    private static String renderedLine(Entry entry) {
        return entry.samples > 1
                ? entry.text + " samples=" + entry.samples + " lastSampleAt=" + format(entry.lastMs)
                : entry.text;
    }

    synchronized int getPersistCountForTesting() {
        return persistCount;
    }

    synchronized int size() {
        return entries.size();
    }

    private void append(Entry entry) {
        entries.add(entry);
        prune(entry.firstMs);
    }

    private void prune(long now) {
        int chars = 0;
        for (Entry entry : entries) chars += renderedLine(entry).length() + 1;
        while (!entries.isEmpty() && (entries.size() > MAX_ENTRIES || chars > MAX_CHARS
                || now - entries.get(0).lastMs > RETENTION_MS)) {
            Entry removed = entries.remove(0);
            chars -= renderedLine(removed).length() + 1;
            if (removed.key != null && lastByKey.get(removed.key) == removed) {
                lastByKey.remove(removed.key);
            }
        }
    }

    private void maybePersist(long now) {
        if (lastPersistMs > 0 && now - lastPersistMs < PERSIST_INTERVAL_MS
                && now >= lastPersistMs) {
            return;
        }
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = atomicFile.startWrite();
            StringBuilder data = new StringBuilder();
            for (Entry entry : entries) {
                data.append(entry.firstMs).append('\t').append(entry.lastMs).append('\t')
                        .append(entry.samples).append('\t')
                        .append(entry.key == null ? "" : entry.key).append('\t')
                        .append(entry.signature == null ? "" : entry.signature).append('\t')
                        .append(entry.text).append('\n');
            }
            out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(out);
            lastPersistMs = now;
            persistCount++;
        } catch (IOException e) {
            if (out != null) atomicFile.failWrite(out);
            // Retry no earlier than the next interval rather than on every following record.
            lastPersistMs = now;
            Log.w(TAG, "Could not persist diagnostics journal", e);
        }
    }

    private void load() {
        if (!file.exists()) return;
        lastPersistMs = file.lastModified();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(new AtomicFile(file).getBaseFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 6);
                if (parts.length != 6) continue;
                try {
                    Entry entry = new Entry(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                            Integer.parseInt(parts[2]), parts[3].isEmpty() ? null : parts[3],
                            parts[4].isEmpty() ? null : parts[4], parts[5]);
                    entries.add(entry);
                    if (entry.key != null && entry.signature != null) lastByKey.put(entry.key, entry);
                } catch (NumberFormatException ignored) {
                    // Skip a corrupt line; the rest of the journal stays usable.
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not load diagnostics journal", e);
        }
        prune(clock.now());
    }

    private static String clean(String line) {
        return line == null ? "" : line.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String format(long ms) {
        synchronized (TIME_FORMAT) {
            return TIME_FORMAT.format(new Date(ms));
        }
    }
}
