import java.util.*;
import java.io.*;
import javax.microedition.io.Connector;

/**
 * JSR75Storage v1.1.2 - Enhanced auto-detection for DashCMD.
 * CLDC 1.1 / MIDP 2.0 + JSR-75 (optional, detected at runtime).
 */
public class JSR75Storage {

    private static boolean available = false;
    private static boolean checked   = false;
    private static String[] cachedRoots = null;

    private static String installRoot = null;

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
     * List available file roots using OFFICIAL JSR-75 API.
     * FileSystemRegistry.listRoots() returns actual mounted filesystems.
     */
    public static String[] listRoots() {
        if (!isAvailable()) return new String[0];
        if (cachedRoots != null) return cachedRoots;

        Vector found = new Vector();

        // Method 1: Use official FileSystemRegistry (best method)
        try {
            Enumeration roots = javax.microedition.io.file.FileSystemRegistry.listRoots();
            while (roots.hasMoreElements()) {
                String root = (String) roots.nextElement();
                String url = "file:///" + root;
                found.addElement(url);
                AppStorage.logBoot("INFO", "JSR-75 detected root: " + url);
            }
        } catch (Throwable e) {
            // FileSystemRegistry not available, fall back to probing
            AppStorage.logBoot("WARN", "FileSystemRegistry failed: " + e.getMessage());
        }

        // Method 2: If no roots found, probe common paths
        if (found.size() == 0) {
            String[] probePaths = {
                // Symbian / Nokia
                "file:///C:/",
                "file:///D:/",
                "file:///E:/",
                "file:///F:/",
                // Memory cards
                "file:///SDCard/",
                "file:///TFCard/",
                "file:///sdcard/",
                "file:///MemoryCard/",
                "file:///Memory Card/",
                "file:///memorycard/",
                "file:///MMC/",
                "file:///mmc/",
                // Internal storage
                "file:///Internal/",
                "file:///internal/",
                "file:///Phone/",
                "file:///Phone Memory/",
                "file:///PhoneMemory/",
                // Sony Ericsson
                "file:///a:/",
                "file:///b:/",
                "file:///c:/",
                "file:///d:/",
                "file:///e:/",
                // Samsung
                "file:///Mmc/",
                "file:///Card/",
                "file:///My Files/",
                // Motorola
                "file:///motorola/",
                "file:///MOTOROLA/",
                // LG
                "file:///LG/",
                // Generic
                "file:///Root/",
                "file:///root/",
                "file:///Storage/",
                "file:///storage/",
                "file:///Media/",
                "file:///media/",
                "file:///fs/",
                "file:///store/",
                "file:///Store/"
            };

            for (int i = 0; i < probePaths.length; i++) {
                if (probeRoot(probePaths[i])) {
                    // Avoid duplicates
                    boolean exists = false;
                    for (int j = 0; j < found.size(); j++) {
                        if (((String)found.elementAt(j)).equalsIgnoreCase(probePaths[i])) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        found.addElement(probePaths[i]);
                        AppStorage.logBoot("INFO", "JSR-75 probed root: " + probePaths[i]);
                    }
                }
            }
        }

        // Convert to array
        cachedRoots = new String[found.size()];
        found.copyInto(cachedRoots);
        return cachedRoots;
    }

    /**
     * Probe if a root path exists and is accessible.
     */
    private static boolean probeRoot(String url) {
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ);
            if (conn == null) return false;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            boolean exists = fc.exists() && fc.isDirectory();
            fc.close();
            return exists;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return false;
        }
    }

    /**
     * Get the best available root (prefers SD card over internal).
     * Returns null if no JSR-75 storage available.
     */
    public static String getBestRoot() {
        String[] roots = listRoots();
        if (roots.length == 0) return null;

        // Prefer external storage (SD card) over internal
        String[] preferOrder = {
            "sdcard", "sd card", "memorycard", "memory card", "mmc",
            "e:/", "d:/", "card", "external"
        };

        for (int p = 0; p < preferOrder.length; p++) {
            for (int r = 0; r < roots.length; r++) {
                if (roots[r].toLowerCase().indexOf(preferOrder[p]) >= 0) {
                    return roots[r];
                }
            }
        }

        // Return first available
        return roots[0];
    }

    /**
     * Get root with most free space (if supported).
     */
    public static String getRootWithMostSpace() {
        String[] roots = listRoots();
        if (roots.length == 0) return null;
        if (roots.length == 1) return roots[0];

        String bestRoot = roots[0];
        long bestSpace = 0;

        for (int i = 0; i < roots.length; i++) {
            long space = getAvailableSpace(roots[i]);
            if (space > bestSpace) {
                bestSpace = space;
                bestRoot = roots[i];
            }
        }

        return bestRoot;
    }

    /**
     * Get available space on a root (in bytes).
     * Returns -1 if cannot determine.
     */
    public static long getAvailableSpace(String url) {
        if (!isAvailable()) return -1;
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ);
            if (conn == null) return -1;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            long space = fc.availableSize();
            fc.close();
            return space;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return -1;
        }
    }

    /**
     * Format bytes to human readable string.
     */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024 * 1024 * 1024)) + " GB";
    }

    /**
     * Get detailed info about all roots.
     */
    public static String getRootsInfo() {
        if (!isAvailable()) {
            return "JSR-75: Not available\nUsing RMS storage only.";
        }

        String[] roots = listRoots();
        if (roots.length == 0) {
            return "JSR-75: Available but no storage roots found.\nUsing RMS storage.";
        }

        StringBuffer sb = new StringBuffer();
        sb.append("JSR-75 Storage Detected:\n");
        sb.append("========================\n");

        for (int i = 0; i < roots.length; i++) {
            long space = getAvailableSpace(roots[i]);
            String spaceTxt = formatSize(space);
            sb.append("[").append(i).append("] ").append(formatRootName(roots[i]));
            sb.append(" (").append(spaceTxt).append(" free)\n");
        }

        String best = getBestRoot();
        if (best != null) {
            sb.append("\nRecommended: ").append(formatRootName(best));
        }

        return sb.toString();
    }

    /**
     * Format root URL for display.
     */
    public static String formatRootName(String url) {
        if (url == null) return "Unknown";
        // Remove file:/// prefix
        if (url.startsWith("file:///")) {
            return url.substring(8);
        }
        return url;
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
     * Install DashCMD filesystem tree to a real device path.
     * Called from Shell when user runs "install"
     */
    public static String installToPath(String root, String username) {
        if (!isAvailable()) {
            return "JSR-75 not available. Using RMS storage only.";
        }

        String base = root.endsWith("/") ? root + "Terminal/" : root + "/Terminal/";
        StringBuffer log = new StringBuffer();
        int success = 0, fail = 0;

        String[] dirs = {
            base,
            base + "bin/", base + "boot/", base + "dev/", base + "etc/",
            base + "home/", base + "home/" + username + "/",
            base + "home/" + username + "/Documents/",
            base + "home/" + username + "/Downloads/",
            base + "home/" + username + "/Pictures/",
            base + "home/" + username + "/Music/",
            base + "home/" + username + "/Desktop/",
            base + "lib/", base + "mnt/", base + "opt/",
            base + "proc/", base + "root/", base + "sbin/",
            base + "tmp/", base + "usr/", base + "usr/bin/",
            base + "usr/lib/", base + "usr/local/bin/",
            base + "var/", base + "var/log/", base + "var/tmp/"
        };

        for (int i = 0; i < dirs.length; i++) {
            String err = mkdirs(dirs[i]);
            if (err == null) {
                log.append("  mkdir ").append(dirs[i].substring(base.length())).append(" OK\n");
                success++;
            } else {
                log.append("  mkdir ").append(dirs[i].substring(base.length())).append(" FAIL\n");
                fail++;
            }
        }

        // Write basic files
        writeFile(base + "etc/hostname", "dashcmd\n");
        writeFile(base + "etc/motd", "Welcome to DashCMD v1.1.1\nInstalled at: " + base + "\n");
        writeFile(base + "home/" + username + "/readme.txt",
            "DashCMD v1.1.1 installed here.\nType 'help' for commands.\n");

        setInstallRoot(base);
        AppStorage.logBoot("INFO", "JSR-75 install: " + success + " OK, " + fail + " failed at " + base);

        return "Installation finished!\n" +
               success + " directories created, " + fail + " failed.\n" +
               "Install root: " + base + "\n" + log.toString().trim();
    }

    /**
     * Write a file to the real device filesystem.
     */
    public static String writeFile(String url, String content) {
        if (!isAvailable()) return "JSR-75 not available";
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ_WRITE);
            if (conn == null) return "Cannot open: " + url;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            if (!fc.exists()) {
                fc.create();
            }
            fc.truncate(0);
            OutputStream os = fc.openOutputStream();
            byte[] bytes = content.getBytes("UTF-8");
            os.write(bytes);
            os.flush();
            os.close();
            fc.close();
            return null;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return "Write error: " + e.getMessage();
        }
    }

    /**
     * Read a file from the real device filesystem.
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
            byte[] buf = new byte[512];
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
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
     * List directory contents.
     */
    public static String[] listDirectory(String url) {
        if (!isAvailable()) return new String[0];
        javax.microedition.io.Connection conn = null;
        try {
            // Ensure URL ends with /
            if (!url.endsWith("/")) url = url + "/";
            
            conn = Connector.open(url, Connector.READ);
            if (conn == null) return new String[0];
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            
            if (!fc.exists() || !fc.isDirectory()) {
                fc.close();
                return new String[0];
            }
            
            Enumeration en = fc.list();
            Vector items = new Vector();
            while (en.hasMoreElements()) {
                items.addElement((String) en.nextElement());
            }
            fc.close();
            
            String[] result = new String[items.size()];
            items.copyInto(result);
            return result;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return new String[0];
        }
    }

    /**
     * Create a directory (and parent directories if needed).
     */
    public static String mkdirs(String url) {
        if (!isAvailable()) return "JSR-75 not available";
        
        // Ensure URL ends with /
        if (!url.endsWith("/")) url = url + "/";
        
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
            // Try creating parent directories first
            return createParentDirs(url);
        }
    }

    /**
     * Create parent directories recursively.
     */
    private static String createParentDirs(String url) {
        // Find the root
        int rootEnd = url.indexOf("/", 8); // After file:///
        if (rootEnd < 0) return "Invalid path";
        
        String root = url.substring(0, rootEnd + 1);
        String path = url.substring(rootEnd + 1);
        
        // Split path and create each directory
        Vector parts = new Vector();
        int start = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                if (i > start) {
                    parts.addElement(path.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < path.length()) {
            parts.addElement(path.substring(start));
        }
        
        // Create directories one by one
        String current = root;
        for (int i = 0; i < parts.size(); i++) {
            current = current + (String)parts.elementAt(i) + "/";
            javax.microedition.io.Connection conn = null;
            try {
                conn = Connector.open(current, Connector.READ_WRITE);
                javax.microedition.io.file.FileConnection fc =
                    (javax.microedition.io.file.FileConnection) conn;
                if (!fc.exists()) {
                    fc.mkdir();
                }
                fc.close();
            } catch (Throwable e) {
                if (conn != null) {
                    try { conn.close(); } catch (Exception ex) {}
                }
                return "Cannot create: " + current + " - " + e.getMessage();
            }
        }
        return null;
    }

    /**
     * Delete a file or empty directory.
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
            return "Delete error: " + e.getMessage();
        }
    }

    /**
     * Check if a file or directory exists.
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
     * Check if path is a directory.
     */
    public static boolean isDirectory(String url) {
        if (!isAvailable()) return false;
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ);
            if (conn == null) return false;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            boolean isDir = fc.exists() && fc.isDirectory();
            fc.close();
            return isDir;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return false;
        }
    }

    /**
     * Get file size in bytes.
     */
    public static long getFileSize(String url) {
        if (!isAvailable()) return -1;
        javax.microedition.io.Connection conn = null;
        try {
            conn = Connector.open(url, Connector.READ);
            if (conn == null) return -1;
            javax.microedition.io.file.FileConnection fc =
                (javax.microedition.io.file.FileConnection) conn;
            long size = fc.fileSize();
            fc.close();
            return size;
        } catch (Throwable e) {
            if (conn != null) {
                try { conn.close(); } catch (Exception ex) {}
            }
            return -1;
        }
    }

    /** Get human-readable status string. */
    public static String getStatus() {
        return getRootsInfo();
    }

    /**
     * Clear cached roots (force re-detection).
     */
    public static void clearCache() {
        cachedRoots = null;
    }
}