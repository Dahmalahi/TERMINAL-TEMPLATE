import javax.microedition.rms.*;
import java.util.*;

/**
 * AppStorage v1.1.1 - RMS-backed persistent storage for DashCMD.
 * Stores the virtual filesystem, user credentials, settings, and boot logs
 * in J2ME RecordStore so data survives app restarts.
 *
 * RecordStore layout:
 *   "dashcmd_fs"       - filesystem nodes (key=path, value=node data)
 *   "dashcmd_creds"    - credentials (key=user, value=hash:uid:gid:home:shell:name)
 *   "dashcmd_settings" - app settings (key=name, value=value)
 *   "dashcmd_boot"     - boot log entries (sequential records)
 */
public class AppStorage {

    private static final String STORE_FS       = "dashcmd_fs";
    private static final String STORE_CREDS    = "dashcmd_creds";
    private static final String STORE_SETTINGS = "dashcmd_settings";
    private static final String STORE_BOOT     = "dashcmd_boot";
    private static final String SEPARATOR      = "\u0001"; // ASCII SOH as separator

    // ==================== SETTINGS ====================

    /** Save a single string setting. */
    public static void saveSetting(String key, String value) {
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE_SETTINGS, true);
            String entry = key + SEPARATOR + (value != null ? value : "");
            byte[] data = entry.getBytes();
            // Search for existing key
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            boolean found = false;
            while (re.hasNextElement()) {
                int id = re.nextRecordId();
                try {
                    String rec = new String(rs.getRecord(id));
                    if (rec.startsWith(key + SEPARATOR)) {
                        rs.setRecord(id, data, 0, data.length);
                        found = true;
                        break;
                    }
                } catch (Exception e) {}
            }
            re.destroy();
            if (!found) rs.addRecord(data, 0, data.length);
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    /** Load a single string setting. Returns defaultVal if not found. */
    public static String loadSetting(String key, String defaultVal) {
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE_SETTINGS, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            while (re.hasNextElement()) {
                int id = re.nextRecordId();
                try {
                    String rec = new String(rs.getRecord(id));
                    if (rec.startsWith(key + SEPARATOR)) {
                        re.destroy();
                        rs.closeRecordStore();
                        int sep = rec.indexOf(SEPARATOR);
                        return sep >= 0 ? rec.substring(sep + 1) : defaultVal;
                    }
                } catch (Exception e) {}
            }
            re.destroy();
            rs.closeRecordStore();
        } catch (Exception e) {}
        return defaultVal;
    }

    /** Check whether the app has been installed (first run detection). */
    public static boolean isInstalled() {
        return "1".equals(loadSetting("installed", "0"));
    }

    /** Mark the app as installed. */
    public static void markInstalled() {
        saveSetting("installed", "1");
        saveSetting("install_date", String.valueOf(System.currentTimeMillis()));
        saveSetting("version", "1.1.1");
    }

    /** Get install date millis as string. */
    public static String getInstallDate() {
        return loadSetting("install_date", "0");
    }

    // ==================== FILESYSTEM PERSISTENCE ====================

    /**
     * Save all VirtualFS nodes to RMS.
     * Each record: path + SEP + type + SEP + owner + SEP + group + SEP + perms + SEP + mtime + SEP + content
     * Content is stored without length limit (split across records if needed for very large files).
     */
    public static void saveFS(Hashtable nodes) {
        try {
            // Delete old store and recreate
            try { RecordStore.deleteRecordStore(STORE_FS); } catch (Exception e) {}
            RecordStore rs = RecordStore.openRecordStore(STORE_FS, true);
            Enumeration keys = nodes.keys();
            while (keys.hasMoreElements()) {
                String path = (String) keys.nextElement();
                String[] node = (String[]) nodes.get(path);
                StringBuffer sb = new StringBuffer();
                sb.append(path).append(SEPARATOR);
                for (int i = 0; i < node.length; i++) {
                    if (i > 0) sb.append(SEPARATOR);
                    sb.append(node[i] != null ? node[i] : "");
                }
                byte[] data = sb.toString().getBytes();
                rs.addRecord(data, 0, data.length);
            }
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    /**
     * Load VirtualFS nodes from RMS. Returns empty Hashtable if no data.
     */
    public static Hashtable loadFS() {
        Hashtable nodes = new Hashtable();
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE_FS, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            while (re.hasNextElement()) {
                int id = re.nextRecordId();
                try {
                    String rec  = new String(rs.getRecord(id));
                    int    sep  = rec.indexOf(SEPARATOR);
                    if (sep < 0) continue;
                    String path = rec.substring(0, sep);
                    String rest = rec.substring(sep + 1);
                    // Split rest by SEPARATOR into node fields
                    String[] parts = split(rest, SEPARATOR.charAt(0));
                    // Pad to 7 fields
                    String[] node = new String[7];
                    for (int i = 0; i < 7; i++) node[i] = i < parts.length ? parts[i] : "";
                    nodes.put(path, node);
                } catch (Exception e) {}
            }
            re.destroy();
            rs.closeRecordStore();
        } catch (Exception e) {}
        return nodes;
    }

    // ==================== CREDENTIALS PERSISTENCE ====================

    public static void saveCredentials(Hashtable creds) {
        try {
            try { RecordStore.deleteRecordStore(STORE_CREDS); } catch (Exception e) {}
            RecordStore rs = RecordStore.openRecordStore(STORE_CREDS, true);
            Enumeration keys = creds.keys();
            while (keys.hasMoreElements()) {
                String user  = (String) keys.nextElement();
                String[] c   = (String[]) creds.get(user);
                StringBuffer sb = new StringBuffer(user).append(SEPARATOR);
                for (int i = 0; i < c.length; i++) {
                    if (i > 0) sb.append(SEPARATOR);
                    sb.append(c[i] != null ? c[i] : "");
                }
                byte[] data = sb.toString().getBytes();
                rs.addRecord(data, 0, data.length);
            }
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    public static Hashtable loadCredentials() {
        Hashtable creds = new Hashtable();
        try {
            RecordStore rs = RecordStore.openRecordStore(STORE_CREDS, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            while (re.hasNextElement()) {
                int id = re.nextRecordId();
                try {
                    String   rec  = new String(rs.getRecord(id));
                    int      sep  = rec.indexOf(SEPARATOR);
                    if (sep < 0) continue;
                    String   user = rec.substring(0, sep);
                    String[] parts= split(rec.substring(sep + 1), SEPARATOR.charAt(0));
                    // Pad to 6 fields
                    String[] c = new String[6];
                    for (int i = 0; i < 6; i++) c[i] = i < parts.length ? parts[i] : "";
                    creds.put(user, c);
                } catch (Exception e) {}
            }
            re.destroy();
            rs.closeRecordStore();
        } catch (Exception e) {}
        return creds;
    }

    // ==================== BOOT LOG ====================

    /** Append a boot log entry. */
    public static void logBoot(String level, String msg) {
        try {
            RecordStore rs   = RecordStore.openRecordStore(STORE_BOOT, true);
            String      ts   = formatTime(System.currentTimeMillis());
            String      line = ts + " [" + level + "] " + msg;
            byte[]      data = line.getBytes();
            rs.addRecord(data, 0, data.length);
            // Keep only last 200 entries
            if (rs.getNumRecords() > 200) {
                RecordEnumeration re = rs.enumerateRecords(null, null, false);
                if (re.hasNextElement()) {
                    int firstId = re.nextRecordId();
                    rs.deleteRecord(firstId);
                }
                re.destroy();
            }
            rs.closeRecordStore();
        } catch (Exception e) {}
    }

    /** Read all boot log entries, newest last. */
    public static String readBootLog() {
        StringBuffer sb = new StringBuffer();
        try {
            RecordStore      rs = RecordStore.openRecordStore(STORE_BOOT, true);
            RecordEnumeration re = rs.enumerateRecords(null, null, false);
            while (re.hasNextElement()) {
                int id = re.nextRecordId();
                try { sb.append(new String(rs.getRecord(id))).append("\n"); }
                catch (Exception e) {}
            }
            re.destroy();
            rs.closeRecordStore();
        } catch (Exception e) {}
        return sb.length() > 0 ? sb.toString().trim() : "(boot log empty)";
    }

    /** Clear boot log. */
    public static void clearBootLog() {
        try { RecordStore.deleteRecordStore(STORE_BOOT); } catch (Exception e) {}
    }

    // ==================== HELPERS ====================

    /** Format epoch millis to human-readable string without java.util.Date. */
    public static String formatTime(long millis) {
        // millis since 1970-01-01 UTC
        long secs  = millis / 1000;
        long mins  = secs  / 60;
        long hours = mins  / 60;
        long days  = hours / 24;
        long years = days  / 365;

        long s = secs  % 60;
        long m = mins  % 60;
        long h = hours % 24;
        long d = days  % 365;
        long y = 1970 + years;

        // Very rough month/day (ignores leap years)
        int[] daysInMonth = {31,28,31,30,31,30,31,31,30,31,30,31};
        String[] monthNames = {"Jan","Feb","Mar","Apr","May","Jun",
                               "Jul","Aug","Sep","Oct","Nov","Dec"};
        int month = 0;
        long rem = d;
        while (month < 11 && rem >= daysInMonth[month]) {
            rem -= daysInMonth[month];
            month++;
        }
        long day = rem + 1;

        return monthNames[month] + " " + pad2(day) + " " + y + " " +
               pad2(h) + ":" + pad2(m) + ":" + pad2(s);
    }

    /** Format just HH:MM:SS from millis. */
    public static String formatHMS(long millis) {
        long secs  = millis / 1000;
        long mins  = secs / 60;
        long hours = mins / 60;
        return pad2(hours % 24) + ":" + pad2(mins % 60) + ":" + pad2(secs % 60);
    }

    /** Format uptime from millis difference. */
    public static String formatUptime(long uptimeMillis) {
        long secs  = uptimeMillis / 1000;
        long mins  = secs / 60;
        long hours = mins / 60;
        long days  = hours / 24;
        if (days > 0) return days + " days, " + (hours % 24) + " hours, " + (mins % 60) + " min";
        if (hours > 0) return hours + " hours, " + (mins % 60) + " min";
        return mins + " min, " + (secs % 60) + " sec";
    }

    private static String pad2(long n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static String[] split(String s, char sep) {
        Vector v = new Vector();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == sep) {
                v.addElement(s.substring(start, i));
                start = i + 1;
            }
        }
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }
}
