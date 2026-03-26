import javax.microedition.io.*;
import java.io.*;
import java.util.*;

/**
 * NetworkTask v1.2.2 - Real HTTP networking for DashCMD.
 * Uses J2ME MIDP 2.0 HttpConnection (GCF).
 *
 * All calls are SYNCHRONOUS (blocking) ? call from a background thread.
 * The caller provides a callback interface for results.
 */
public class NetworkTask {

    public static final int TIMEOUT_MS = 15000; // 15 sec

    /** Callback interface for async results. */
    public interface Callback {
        void onResult(String result);
        void onError(String error);
    }

    // ==================== HTTP GET ====================

    /**
     * Perform HTTP GET. Returns response body as String.
     * Runs synchronously ? wrap in Thread for non-blocking use.
     */
    public static void httpGet(final String url, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                HttpConnection conn = null;
                InputStream    in   = null;
                try {
                    conn = (HttpConnection) Connector.open(url, Connector.READ, true);
                    conn.setRequestMethod(HttpConnection.GET);
                    conn.setRequestProperty("User-Agent",
                        "DashCMD/1.1.1 J2ME CLDC1.1/MIDP2.0");
                    conn.setRequestProperty("Connection", "close");

                    int code = conn.getResponseCode();
                    if (code != HttpConnection.HTTP_OK) {
                        cb.onError("HTTP " + code + " " + conn.getResponseMessage());
                        return;
                    }

                    in = conn.openInputStream();
                    StringBuffer sb  = new StringBuffer();
                    byte[]       buf = new byte[512];
                    int          n;
                    while ((n = in.read(buf)) != -1) {
                        sb.append(new String(buf, 0, n));
                        if (sb.length() > 65536) { sb.append("\n...(truncated)"); break; }
                    }
                    cb.onResult(sb.toString());
                } catch (Exception e) {
                    cb.onError("Network error: " + e.getMessage());
                } finally {
                    try { if (in   != null) in.close();   } catch (Exception e) {}
                    try { if (conn != null) conn.close();  } catch (Exception e) {}
                }
            }
        }).start();
    }

    /**
     * Synchronous HTTP GET ? blocks calling thread. Returns result string.
     */
    public static String httpGetSync(String url) {
        HttpConnection conn = null;
        InputStream    in   = null;
        try {
            conn = (HttpConnection) Connector.open(url, Connector.READ, true);
            conn.setRequestMethod(HttpConnection.GET);
            conn.setRequestProperty("User-Agent", "DashCMD/1.1.1 J2ME CLDC1.1/MIDP2.0");
            conn.setRequestProperty("Connection", "close");

            int code = conn.getResponseCode();
            if (code != HttpConnection.HTTP_OK) {
                return "curl: (22) HTTP error " + code + " " + conn.getResponseMessage();
            }

            long   len = conn.getLength();
            in = conn.openInputStream();
            StringBuffer sb  = new StringBuffer();
            sb.append("HTTP/1.1 ").append(code).append(" ").append(conn.getResponseMessage()).append("\n");
            sb.append("Content-Type: ").append(conn.getType()).append("\n");
            if (len > 0) sb.append("Content-Length: ").append(len).append("\n");
            sb.append("\n");

            byte[] buf = new byte[512];
            int    n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n));
                if (sb.length() > 32768) { sb.append("\n...(truncated at 32KB)"); break; }
            }
            return sb.toString();
        } catch (Exception e) {
            return "curl: network error: " + e.getMessage();
        } finally {
            try { if (in   != null) in.close();  } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }
    }

    /**
     * HTTP POST with form data.
     */
    public static String httpPostSync(String url, String postData) {
        HttpConnection conn = null;
        OutputStream   out  = null;
        InputStream    in   = null;
        try {
            conn = (HttpConnection) Connector.open(url, Connector.READ_WRITE, true);
            conn.setRequestMethod(HttpConnection.POST);
            conn.setRequestProperty("User-Agent", "DashCMD/1.1.1 J2ME CLDC1.1/MIDP2.0");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Connection", "close");
            byte[] postBytes = postData.getBytes();
            conn.setRequestProperty("Content-Length", String.valueOf(postBytes.length));

            out = conn.openOutputStream();
            out.write(postBytes);
            out.flush();

            int code = conn.getResponseCode();
            in = conn.openInputStream();
            StringBuffer sb = new StringBuffer();
            sb.append("HTTP/1.1 ").append(code).append(" ").append(conn.getResponseMessage()).append("\n\n");
            byte[] buf = new byte[512];
            int    n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n));
                if (sb.length() > 32768) break;
            }
            return sb.toString();
        } catch (Exception e) {
            return "curl: POST error: " + e.getMessage();
        } finally {
            try { if (out  != null) out.close();  } catch (Exception e) {}
            try { if (in   != null) in.close();   } catch (Exception e) {}
            try { if (conn != null) conn.close();  } catch (Exception e) {}
        }
    }

    /**
     * Ping simulation ? does an HTTP HEAD to the host.
     * Real ICMP ping is not available in J2ME without native extensions.
     */
    public static String pingHost(String host, int count) {
        StringBuffer sb = new StringBuffer();
        sb.append("PING ").append(host).append(":\n");
        String url = host.startsWith("http") ? host : "http://" + host;
        for (int i = 0; i < count; i++) {
            long start = System.currentTimeMillis();
            HttpConnection conn = null;
            try {
                conn = (HttpConnection) Connector.open(url, Connector.READ, true);
                conn.setRequestMethod(HttpConnection.HEAD);
                conn.setRequestProperty("User-Agent", "DashCMD/1.1.1");
                conn.setRequestProperty("Connection", "close");
                int code  = conn.getResponseCode();
                long  rtt = System.currentTimeMillis() - start;
                sb.append("64 bytes from ").append(host)
                  .append(": icmp_seq=").append(i+1)
                  .append(" ttl=64 time=").append(rtt).append(" ms\n");
            } catch (Exception e) {
                sb.append("Request timeout for icmp_seq ").append(i+1).append("\n");
            } finally {
                try { if (conn != null) conn.close(); } catch (Exception ex) {}
            }
            // Brief pause between pings
            try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
        }
        sb.append("\n--- ").append(host).append(" ping statistics ---\n");
        sb.append(count).append(" packets transmitted.");
        return sb.toString();
    }

    /**
     * Download file to VirtualFS Downloads folder.
     * Returns status string.
     */
    public static String downloadToFS(String url, VirtualFS fs) {
        String filename = url.substring(url.lastIndexOf('/') + 1);
        if (filename.length() == 0) filename = "download";
        String dest = "/home/" + fs.getUsername() + "/Downloads/" + filename;

        HttpConnection conn = null;
        InputStream    in   = null;
        try {
            conn = (HttpConnection) Connector.open(url, Connector.READ, true);
            conn.setRequestMethod(HttpConnection.GET);
            conn.setRequestProperty("User-Agent", "DashCMD/1.1.1");
            conn.setRequestProperty("Connection", "close");

            int code = conn.getResponseCode();
            if (code != HttpConnection.HTTP_OK) {
                return "wget: server returned HTTP " + code;
            }

            long len = conn.getLength();
            in = conn.openInputStream();
            StringBuffer content = new StringBuffer();
            byte[] buf = new byte[512];
            int    n;
            long   received = 0;
            while ((n = in.read(buf)) != -1) {
                content.append(new String(buf, 0, n));
                received += n;
                if (received > 512000) { content.append("\n...(truncated at 500KB)"); break; }
            }

            fs.writeFile(dest, content.toString());
            return "Saving to: " + dest + "\n" +
                   received + " bytes saved [" + dest + "]";
        } catch (Exception e) {
            return "wget: error: " + e.getMessage();
        } finally {
            try { if (in   != null) in.close();  } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }
    }
}
