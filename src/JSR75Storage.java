import java.util.*;
import java.io.*;
import javax.microedition.io.Connector;

/**
 * JSR75Storage v1.1.1 - Optional JSR-75 FileConnection layer for DashCMD.
 * CLDC 1.1 / MIDP 2.0 + JSR-75 (optional, detected at runtime).
 *
 * Allows DashCMD to read/write REAL device filesystem via file:///
 * JSR-75 is available on many Nokia, Sony Ericsson, and BlackBerry devices.
 * If JSR-75 is NOT available, all methods return graceful errors.
 *
 * Uses Class.forName() to detect JSR-75 availability at runtime.
 * All FileConnection usage is in try/catch blocks so the app compiles
 * and runs even without JSR-75 on the device.
 *
 * Storage paths detected:
 *  file:///C:/   (Symbian/Windows Mobile)
 *  file:///SDCard/  (Nokia)
 *  file:///memory card/ (Sony Ericsson)
 *  file:///Internal/  (generic)
 */
public class JSR75Storage {

    private static boolean available = false;
    private static boolean checked   = false;

    // Known storage roots to probe
    private static final String[] ROOTS = {
        "file:///C:/",
        "file:///D:/",
        "file:///E:/",
        "file:///SDCard/",
        "file:///MemoryCard/",
        "file:///Internal/",
        "file:///Phone Memory/",
        "file:///Root/"
    };

    private static String installRoot = null;  // chosen install location

    /** Check if JSR-75 is available on this device. */
    public static boolean isAvailable() {
        if (checked) return available;
        checked = true;
        try {
            Class.forName("javax.microedition.io.file.FileConnection");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        return available;
    }

    /**
     * List available file roots (storage locations).
     * Returns empty array if JSR-75 not available.
     */
    public static String[] listRoots() {
        if (!isAvailable()) return new String[0];
        Vector found = new Vector();
        try {
            for (int i = 0; i < ROOTS.length; i++) {
                if (probeRoot(ROOTS[i])) {
                    found.addElement(ROOTS[i]);
                }
            }
        } catch (Exception e) {}
        String[] arr = new String[found.size()];
        found.copyInto(arr);
        return arr;
    }

    private static boolean probeRoot(String url) {
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ);
            if (conn == null) return false;
            // Cast to FileConnection - if JSR-75 is present this works
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            boolean exists = fc.exists();
            fc.close();
            return exists;
        } catch (Throwable e) {
            // Throwable catches NoClassDefFoundError too
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return false;
        }
    }

    /**
     * Choose install root. Probes all roots and returns list for user choice.
     * Returns formatted string for display.
     */
    public static String getAvailableRoots() {
        if (!isAvailable()) {
            return "JSR-75 not available on this device.\n" +
                   "Using RMS (device memory) for storage.\n" +
                   "Install location: device RMS store";
        }
        String[] roots = listRoots();
        if (roots.length == 0) {
            return "JSR-75 available but no storage roots found.\n" +
                   "Falling back to RMS storage.";
        }
        StringBuffer sb = new StringBuffer();
        sb.append("Available storage locations:\n");
        for (int i = 0; i < roots.length; i++) {
            sb.append("  [").append(i).append("] ").append(roots[i]).append("\n");
        }
        sb.append("\nUse 'install <number>' to choose storage.");
        return sb.toString();
    }

    /** Set the install root (file:/// path). */
    public static void setInstallRoot(String root) {
        installRoot = root;
        AppStorage.saveSetting("install_root", root);
    }

    /** Get the current install root. */
    public static String getInstallRoot() {
        if (installRoot == null) {
            installRoot = AppStorage.loadSetting("install_root", null);
        }
        return installRoot;
    }

    /**
     * Write a file to the real device filesystem.
     * Path like: file:///C:/Terminal/home/user/readme.txt
     * Returns null on success, error string on failure.
     */
    public static String writeFile(String url, String content) {
        if (!isAvailable()) return "JSR-75 not available";
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ_WRITE);
            if (conn == null) return "Cannot open: " + url;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            // Create if not exists
            if (!fc.exists()) {
                fc.create();
            }
            // Truncate
            fc.truncate(0);
            // Open output stream and write
            OutputStream os = fc.openOutputStream();
            byte[] bytes = content.getBytes();
            os.write(bytes);
            os.close();
            fc.close();
            return null;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return "JSR-75 write error: " + e.getMessage();
        }
    }

    /**
     * Read a file from the real device filesystem.
     * Returns content string, or null on error.
     */
    public static String readFile(String url) {
        if (!isAvailable()) return null;
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ);
            if (conn == null) return null;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            if (!fc.exists()) {
                fc.close();
                return null;
            }
            InputStream is = fc.openInputStream();
            StringBuffer sb = new StringBuffer();
            byte[] buf = new byte[256];
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n));
            }
            is.close();
            fc.close();
            return sb.toString();
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return null;
        }
    }

    /**
     * List directory contents at a JSR-75 URL.
     */
    public static String listDir(String url) {
        if (!isAvailable()) return "JSR-75 not available";
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ);
            if (conn == null) return "Cannot open: " + url;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            Enumeration en = fc.list();
            StringBuffer sb = new StringBuffer();
            while (en.hasMoreElements()) {
                sb.append((String) en.nextElement()).append("\n");
            }
            fc.close();
            return sb.toString().trim();
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return "JSR-75 list error: " + e.getMessage();
        }
    }

    /**
     * Create a directory at JSR-75 path.
     * Returns null on success, error string on failure.
     */
    public static String mkdirs(String url) {
        if (!isAvailable()) return "JSR-75 not available";
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ_WRITE);
            if (conn == null) return "Cannot open: " + url;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            if (!fc.exists()) {
                fc.mkdir();
            }
            fc.close();
            return null;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return "JSR-75 mkdir error: " + e.getMessage();
        }
    }

    /**
     * Delete a file or empty directory at JSR-75 path.
     * Returns null on success, error string on failure.
     */
    public static String deleteFile(String url) {
        if (!isAvailable()) return "JSR-75 not available";
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ_WRITE);
            if (conn == null) return "Cannot open: " + url;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            if (fc.exists()) {
                fc.delete();
            }
            fc.close();
            return null;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return "JSR-75 delete error: " + e.getMessage();
        }
    }

    /**
     * Check if a file or directory exists at a JSR-75 path.
     */
    public static boolean exists(String url) {
        if (!isAvailable()) return false;
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ);
            if (conn == null) return false;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            boolean ex = fc.exists();
            fc.close();
            return ex;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return false;
        }
    }

    /**
     * Helper: replace all occurrences of a substring within a string.
     * CLDC 1.1 String has no replace(CharSequence, CharSequence).
     */
    private static String replaceAll(String source, String find, String replacement) {
        if (source == null || find == null || find.length() == 0) {
            return source;
        }
        StringBuffer result = new StringBuffer();
        int start = 0;
        int idx;
        while ((idx = source.indexOf(find, start)) != -1) {
            result.append(source.substring(start, idx));
            result.append(replacement);
            start = idx + find.length();
        }
        result.append(source.substring(start));
        return result.toString();
    }

    /**
     * Install DashCMD filesystem tree to a real device path.
     * Called during install wizard when user chooses a storage root.
     *
     * Creates: <root>Terminal/ directory tree
     */
    public static String installToPath(String root, String username) {
        if (!isAvailable()) {
            return "JSR-75 not available. Using RMS storage only.\n" +
                   "Your files are stored in device memory (RMS).";
        }
        StringBuffer log = new StringBuffer();
        String base = root + "Terminal/";
        String[] dirs = {
            base,
            base + "bin/",
            base + "boot/",
            base + "dev/",
            base + "etc/",
            base + "home/",
            base + "home/" + username + "/",
            base + "home/" + username + "/Documents/",
            base + "home/" + username + "/Downloads/",
            base + "home/" + username + "/Pictures/",
            base + "home/" + username + "/Music/",
            base + "home/" + username + "/Desktop/",
            base + "lib/",
            base + "mnt/",
            base + "opt/",
            base + "proc/",
            base + "root/",
            base + "sbin/",
            base + "tmp/",
            base + "usr/",
            base + "usr/bin/",
            base + "usr/lib/",
            base + "usr/local/",
            base + "usr/local/bin/",
            base + "usr/share/",
            base + "var/",
            base + "var/log/",
            base + "var/tmp/",
            base + "var/cache/",
            base + "var/run/"
        };
        int success = 0;
        int fail = 0;
        for (int i = 0; i < dirs.length; i++) {
            String err = mkdirs(dirs[i]);
            String shortName = replaceAll(dirs[i], base, "");
            if (err == null) {
                log.append("  mkdir ").append(shortName).append(" OK\n");
                success++;
            } else {
                log.append("  mkdir ").append(shortName).append(" FAIL\n");
                fail++;
            }
        }
        // Write marker files
        writeFile(base + "etc/hostname", "dashcmd\n");
        writeFile(base + "etc/motd",
            "Welcome to DashCMD v1.1.1\nInstalled at: " + base + "\n");
        writeFile(base + "home/" + username + "/readme.txt",
            "DashCMD v1.1.1 installed here: " + base + "\n");

        setInstallRoot(base);
        AppStorage.logBoot("INFO",
            "JSR-75 install: " + success + " dirs OK, " + fail + " failed at: " + base);

        return "JSR-75 install to " + base + "\n" +
               success + " directories created, " + fail + " failed.\n" +
               "Install root: " + base + "\n" + log.toString().trim();
    }

    /** Get human-readable status string. */
    public static String getStatus() {
        if (!isAvailable()) {
            return "JSR-75: NOT available (using RMS only)";
        }
        String root = getInstallRoot();
        return "JSR-75: available\n" +
               "Install root: " +
               (root != null ? root : "(not set - run install command)") + "\n" +
               "Run 'storage' to see available roots\n" +
               "Run 'install <root>' to install to device storage";
    }
}