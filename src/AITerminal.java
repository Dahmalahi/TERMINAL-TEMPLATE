import javax.microedition.io.*;
import javax.microedition.lcdui.*;
import java.io.*;
import java.util.*;

/**
 * AITerminal v1.2.2 - AI chat integration for DashCMD.
 * Uses the same API as AIChatBot: api-dl-j2meuploader.ndukadavid70.workers.dev
 *
 * Features:
 *  - ai <question>  - quick inline question, returns answer to terminal
 *  - aichat         - opens full AI chat canvas
 *  - history stored in RMS
 *  - context-aware (remembers last N exchanges)
 *  - 'ai reset' clears context
 */
public class AITerminal {

    private static final String API_URL =
        "http://api-dl-j2meuploader.ndukadavid70.workers.dev/api/ai/chatgpt?text=";
    private static final String RMS_STORE = "dashcmd_ai_hist";
    private static final int    MAX_CONTEXT = 5; // exchanges to keep

    private Vector  history;    // String[]{role, message}
    private String  userName;
    private boolean busy;

    public AITerminal(String userName) {
        this.userName = userName;
        this.history  = new Vector();
        this.busy     = false;
        loadHistory();
    }

    // ==================== QUICK QUERY ====================

    /**
     * Send a quick question to the AI and return the response.
     * BLOCKING - call from background thread.
     */
    public String ask(String question) {
        if (question == null || question.trim().length() == 0)
            return "ai: empty question";
        if (busy) return "ai: busy, please wait...";
        busy = true;
        try {
            String context = buildContext();
            String full    = context.length() > 0
                ? "Context:\n" + context + "\n\nQuestion:\n" + question
                : question;
            String response = httpGet(API_URL + encodeURL(full));
            String parsed   = parseResponse(response);

            // Store in history
            history.addElement(new String[]{"U", question});
            history.addElement(new String[]{"A", parsed});
            while (history.size() > MAX_CONTEXT * 2) history.removeElementAt(0);
            saveHistory();

            return parsed;
        } catch (Exception e) {
            return "ai: error: " + e.getMessage();
        } finally {
            busy = false;
        }
    }

    /** Async version - calls callback when done. */
    public void askAsync(final String question, final AICallback callback) {
        if (busy) { callback.onResult("ai: busy..."); return; }
        new Thread(new Runnable() {
            public void run() {
                callback.onResult(ask(question));
            }
        }).start();
    }

    /** Callback interface for async AI responses. */
    public interface AICallback {
        void onResult(String response);
    }

    /** Clear conversation context. */
    public void reset() {
        history.removeAllElements();
        clearHistory();
    }

    /** Get history as formatted string. */
    public String getHistory() {
        if (history.size() == 0) return "(no AI conversation history)";
        StringBuffer sb = new StringBuffer();
        sb.append("=== AI Chat History ===\n");
        for (int i = 0; i < history.size(); i++) {
            String[] entry = (String[]) history.elementAt(i);
            sb.append(entry[0].equals("U") ? "You: " : "AI:  ");
            sb.append(entry[1]).append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== FULL CHAT CANVAS ====================

    /**
     * Open the full AI chat canvas.
     * Returns a ChatCanvas displayable that the caller should set as current.
     */
    public AIChatCanvas openChat(TerminalOS os) {
        return new AIChatCanvas(os, this);
    }

    // ==================== HTTP ====================

    private String httpGet(String url) throws Exception {
        HttpConnection conn = null;
        InputStream    in   = null;
        try {
            conn = (HttpConnection) Connector.open(url, Connector.READ, true);
            conn.setRequestMethod(HttpConnection.GET);
            conn.setRequestProperty("User-Agent", "DashCMD/1.2.2");
            conn.setRequestProperty("Connection", "close");

            int code = conn.getResponseCode();
            in = conn.openInputStream();
            StringBuffer sb = new StringBuffer();
            byte[] buf = new byte[512]; int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n));
                if (sb.length() > 32768) { sb.append("...(truncated)"); break; }
            }
            return sb.toString();
        } finally {
            try { if (in   != null) in.close();  } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }
    }

    // ==================== CONTEXT ====================

    private String buildContext() {
        if (history.size() == 0) return "";
        StringBuffer sb = new StringBuffer();
        int start = Math.max(0, history.size() - MAX_CONTEXT * 2);
        for (int i = start; i < history.size(); i++) {
            String[] e = (String[]) history.elementAt(i);
            sb.append(e[0].equals("U") ? "User: " : "AI: ");
            String msg = e[1];
            if (msg.length() > 100) msg = msg.substring(0, 97) + "...";
            sb.append(msg).append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== RESPONSE PARSING ====================

    private String parseResponse(String raw) {
        if (raw == null || raw.length() == 0) return "[Empty response]";
        // Try <pre> block
        int preS = raw.indexOf("<pre>"), preE = raw.indexOf("</pre>");
        if (preS >= 0 && preE > preS) return cleanText(raw.substring(preS+5, preE));
        // Try JSON keys
        String[] keys = {"result","answer","text","content","response","message"};
        for (int k = 0; k < keys.length; k++) {
            String key = "\"" + keys[k] + "\"";
            int ki = raw.indexOf(key);
            if (ki >= 0) {
                int ci = raw.indexOf(":", ki);
                if (ci >= 0) {
                    int sq = raw.indexOf("\"", ci+1);
                    if (sq >= 0) {
                        int eq = findEndQuote(raw, sq+1);
                        if (eq > sq)
                            return cleanText(unescapeJson(raw.substring(sq+1, eq)));
                    }
                }
            }
        }
        // Fallback: strip HTML
        return cleanText(stripHtml(raw));
    }

    private int findEndQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i-1) != '\\')) return i;
        }
        return -1;
    }

    private String unescapeJson(String s) {
        StringBuffer sb = new StringBuffer(); int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i+1 < s.length()) {
                char n = s.charAt(i+1);
                switch (n) {
                    case 'n': sb.append('\n'); i+=2; continue;
                    case 'r': sb.append('\r'); i+=2; continue;
                    case 't': sb.append('\t'); i+=2; continue;
                    case '"': sb.append('"');  i+=2; continue;
                    case '\\':sb.append('\\'); i+=2; continue;
                }
            }
            sb.append(c); i++;
        }
        return sb.toString();
    }

    private String stripHtml(String s) {
        StringBuffer sb = new StringBuffer(); boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') in = true;
            else if (c == '>') in = false;
            else if (!in) sb.append(c);
        }
        return sb.toString();
    }

    private String cleanText(String s) {
        if (s == null) return "";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 32 && c <= 126) || (c >= 160 && c <= 255) ||
                c == '\n' || c == '\r' || c == '\t') sb.append(c);
            else if (c > 255) sb.append('?');
        }
        return sb.toString().trim();
    }

    private String encodeURL(String s) {
        if (s == null) return "";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9') ||
                c=='-'||c=='_'||c=='.'||c=='~') sb.append(c);
            else if (c == ' ') sb.append("%20");
            else {
                sb.append('%');
                String hex = Integer.toHexString(c & 0xFF).toUpperCase();
                if (hex.length() < 2) sb.append('0');
                sb.append(hex);
            }
        }
        return sb.toString();
    }

    // ==================== RMS HISTORY ====================

    private void saveHistory() {
        try {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < history.size(); i++) {
                String[] e = (String[]) history.elementAt(i);
                sb.append(e[0]).append("\u0001").append(e[1]).append("\n");
            }
            AppStorage.saveSetting(RMS_STORE, sb.toString());
        } catch (Exception e) {}
    }

    private void loadHistory() {
        try {
            String data = AppStorage.loadSetting(RMS_STORE, null);
            if (data == null) return;
            String[] lines = splitLines(data);
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                int sep = l.indexOf('\u0001');
                if (sep > 0) {
                    history.addElement(new String[]{l.substring(0, sep), l.substring(sep+1)});
                }
            }
        } catch (Exception e) {}
    }

    private void clearHistory() {
        AppStorage.saveSetting(RMS_STORE, "");
    }

    private static String[] splitLines(String s) {
        Vector v = new Vector(); int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '\n') {
                v.addElement(s.substring(start, i)); start = i + 1;
            }
        }
        String[] a = new String[v.size()]; v.copyInto(a); return a;
    }
}

// ====================================================================
// AIChatCanvas - Full-screen AI chat UI embedded in DashCMD
// ====================================================================
class AIChatCanvas extends Canvas implements CommandListener {

    private TerminalOS   os;
    private AITerminal   ai;
    private ThemeManager theme;

    // Chat messages: Vector of String[]{role, text}
    private Vector       messages;
    private int          scrollY;

    private Font         font;
    private int          fontW, fontH;
    private int          W, H;

    private boolean      typing;    // AI is typing
    private boolean      blinkState;
    private Thread       blinkThread;
    private volatile boolean blinkRunning;

    private javax.microedition.lcdui.TextBox inputBox;
    private Command backCmd, inputCmd, resetCmd;

    public AIChatCanvas(TerminalOS os, AITerminal ai) {
        this.os       = os;
        this.ai       = ai;
        this.theme    = ThemeManager.getInstance();
        this.messages = new Vector();
        this.scrollY  = 0;
        this.typing   = false;

        font  = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontW = font.charWidth('M');
        fontH = font.getHeight();

        backCmd  = new Command("Back",  Command.BACK,   1);
        inputCmd = new Command("Ask",   Command.SCREEN, 2);
        resetCmd = new Command("Reset", Command.SCREEN, 3);
        addCommand(backCmd);
        addCommand(inputCmd);
        addCommand(resetCmd);
        setCommandListener(this);

        // Welcome
        addMessage("AI", "Hello! I'm DashCMD AI. Ask me anything.\nPress '5' or 'Ask' to type.");

        // Blink thread
        blinkRunning = true;
        blinkThread  = new Thread(new Runnable() {
            public void run() {
                while (blinkRunning) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                    blinkState = !blinkState;
                    repaint();
                }
            }
        });
        blinkThread.start();
    }

    protected void paint(Graphics g) {
        W = getWidth(); H = getHeight();

        // Background
        g.setColor(theme.BG); g.fillRect(0, 0, W, H);

        // Header
        g.setColor(theme.HEADER); g.fillRect(0, 0, W, fontH + 4);
        g.setColor(theme.ACCENT); g.setFont(font);
        g.drawString("DashCMD AI v1.2.1", 4, 2, Graphics.TOP | Graphics.LEFT);
        if (typing && blinkState) {
            g.setColor(theme.FG);
            g.drawString("...", W - 20, 2, Graphics.TOP | Graphics.LEFT);
        }

        // Chat area
        int startY = fontH + 6;
        int inputH = fontH + 6;
        int chatH  = H - startY - inputH;
        int visLines = chatH / fontH;

        // Build display lines from messages
        Vector dispLines  = new Vector();
        Vector dispColors = new Vector();
        int maxW = W - 8;
        for (int i = 0; i < messages.size(); i++) {
            String[] msg   = (String[]) messages.elementAt(i);
            boolean  isUser= msg[0].equals("U");
            int      color = isUser ? theme.PROMPT : theme.FG;
            String   prefix= isUser ? "You: " : "AI:  ";
            String   text  = prefix + msg[1];

            // Wrap
            while (text.length() > 0) {
                int cut = text.length();
                while (cut > 1 && font.stringWidth(text.substring(0, cut)) > maxW) cut--;
                dispLines.addElement(text.substring(0, cut));
                dispColors.addElement(new Integer(color));
                text = cut < text.length() ? "     " + text.substring(cut) : "";
            }
            dispLines.addElement("");
            dispColors.addElement(new Integer(theme.BG));
        }

        int total = dispLines.size();
        int from  = Math.max(0, total - visLines - scrollY);
        int to    = Math.min(total, from + visLines);

        g.setFont(font);
        for (int i = from; i < to; i++) {
            String l  = (String) dispLines.elementAt(i);
            int    c  = ((Integer) dispColors.elementAt(i)).intValue();
            g.setColor(c);
            g.drawString(l, 4, startY + (i - from) * fontH, Graphics.TOP | Graphics.LEFT);
        }

        // Bottom bar
        int barY = H - fontH - 4;
        g.setColor(theme.INPUT_BG); g.fillRect(0, barY - 2, W, fontH + 4);
        g.setColor(theme.GREY); g.drawLine(0, barY - 2, W, barY - 2);
        g.setColor(theme.GREY); g.setFont(font);
        g.drawString("5=Ask  Back=return  Reset=clear", 4, barY, Graphics.TOP | Graphics.LEFT);
    }

    protected void keyPressed(int keyCode) {
        int ga = -1;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}
        if (ga == UP)    { scrollY = Math.min(scrollY + 2, Math.max(0, messages.size() * 3)); repaint(); }
        if (ga == DOWN)  { scrollY = Math.max(0, scrollY - 2); repaint(); }
        if (keyCode == Canvas.KEY_NUM5) openInput();
    }

    private void openInput() {
        inputBox = new javax.microedition.lcdui.TextBox(
            "Ask AI", "", 256, javax.microedition.lcdui.TextField.ANY);
        javax.microedition.lcdui.Command ok = new javax.microedition.lcdui.Command(
            "Send", javax.microedition.lcdui.Command.OK, 1);
        inputBox.addCommand(ok);
        inputBox.setCommandListener(new javax.microedition.lcdui.CommandListener() {
            public void commandAction(javax.microedition.lcdui.Command c,
                                      javax.microedition.lcdui.Displayable d) {
                String q = ((javax.microedition.lcdui.TextBox)d).getString();
                os.getDisplay().setCurrent(AIChatCanvas.this);
                if (q != null && q.trim().length() > 0) sendQuestion(q.trim());
            }
        });
        os.getDisplay().setCurrent(inputBox);
    }

    private void sendQuestion(final String q) {
        addMessage("U", q);
        typing = true; repaint();
        ai.askAsync(q, new AITerminal.AICallback() {
            public void onResult(String response) {
                typing = false;
                addMessage("AI", response);
                scrollY = 0;
                repaint();
            }
        });
    }

    private synchronized void addMessage(String role, String text) {
        messages.addElement(new String[]{role, text});
        while (messages.size() > 50) messages.removeElementAt(0);
        scrollY = 0;
        repaint();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            shutdown();
            os.showTerminal();
        } else if (c == inputCmd) {
            openInput();
        } else if (c == resetCmd) {
            ai.reset();
            messages.removeAllElements();
            addMessage("AI", "Conversation reset. Ask me anything!");
        }
    }

    public void shutdown() {
        blinkRunning = false;
        if (blinkThread != null) blinkThread.interrupt();
    }
}
