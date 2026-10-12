package ai.arena.qatar.boot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine readable startup evidence. The on-device self test writes this to the app's external files
 * directory so a QA run can prove which build actually started, with which world epoch and which
 * resource counts -- never a stale PASSED file.
 */
public final class StartupReport {

    public final String versionName;
    public final String glDescription;
    public final long timestampUtc;
    private final Map<String, String> fields = new LinkedHashMap<>();
    private final java.util.List<String> warnings = new java.util.ArrayList<>();

    public StartupReport(String versionName, String glDescription) {
        this.versionName = versionName;
        this.glDescription = glDescription;
        this.timestampUtc = System.currentTimeMillis();
    }

    public StartupReport put(String key, String value) {
        fields.put(key, value);
        return this;
    }

    public StartupReport put(String key, long value) {
        return put(key, Long.toString(value));
    }

    public StartupReport warn(String message) {
        warnings.add(message);
        return this;
    }

    public Map<String, String> fields() {
        return java.util.Collections.unmodifiableMap(fields);
    }

    public java.util.List<String> warnings() {
        return java.util.Collections.unmodifiableList(warnings);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("QATAR: THE PENINSULA startup report\n");
        sb.append("version=").append(versionName).append('\n');
        sb.append("gl=").append(glDescription).append('\n');
        sb.append("timestamp_utc=").append(timestampUtc).append('\n');
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        for (String w : warnings) {
            sb.append("WARNING=").append(w).append('\n');
        }
        return sb.toString();
    }
}
