import javax.microedition.lcdui.*;
import java.util.*;

/**
 * ScriptCanvas v1.2.2 - Canvas for running Lua/SH/BSH scripts.
 * Provides a full-screen terminal-style output while scripts run.
 *
 * Features:
 *  - Animated "running" indicator while executing
 *  - Color-coded output (errors in red, info in cyan, output in green)
 *  - Scrollable output
 *  - Script can run in "canvas mode" for pixel-art graphics
 *  - Draw API: line, rect, pixel, text at position (via Lua canvas.* calls)
 *  - Press 5 to open input for interactive scripts
 *  - Press Back to stop and return
 */
public class ScriptCanvas extends Canvas implements CommandListener {

    private TerminalOS     os;
    private ScriptEngine   engine;
    private VirtualFS      fs;
    private ThemeManager   theme;

    private String         scriptPath;
    private String         scriptName;
    private String         scriptLang;
    private boolean        canvasMode;

    // Output buffer
    private Vector         lines;     // each: int color | String text
    private Vector         colors;
    private int            scrollY;
    private static final int MAX_LINES = 300;

    // State
    private boolean        running;
    private boolean        finished;
    private String         errorMsg;
    private long           startTime;
    private long           endTime;

    // Canvas graphics state (canvas mode)
    private Vector         drawCmds;  // draw commands to replay on paint

    // Font
    private Font           font;
    private int            fontW, fontH;
    private int            W, H;

    // Commands
    private Command        backCmd;
    private Command        inputCmd;
    private Command        clearCmd;

    // Input box
    private javax.microedition.lcdui.TextBox inputBox;
    private String         inputResult;
    private boolean        waitingInput;

    // Blink thread
    private Thread         blinkThread;
    private volatile boolean blinkRunning;
    private boolean        blinkState;

    // Draw command constants
    private static final int DC_PIXEL = 0;
    private static final int DC_RECT  = 1;
    private static final int DC_LINE  = 2;
    private static final int DC_TEXT  = 3;
    private static final int DC_CLEAR = 4;

    public ScriptCanvas(TerminalOS os, VirtualFS fs, ScriptEngine engine,
                        String path, boolean canvasMode) {
        this.os          = os;
        this.fs          = fs;
        this.engine      = engine;
        this.theme       = ThemeManager.getInstance();
        this.scriptPath  = path;
        this.scriptName  = fs.nameOf(path);
        this.canvasMode  = canvasMode;
        this.lines       = new Vector();
        this.colors      = new Vector();
        this.drawCmds    = new Vector();
        this.scrollY     = 0;
        this.running     = false;
        this.finished    = false;
        this.waitingInput= false;

        // Detect language from extension
        String lo = path.toLowerCase();
        if      (lo.endsWith(".lua")) scriptLang = "lua";
        else if (lo.endsWith(".bsh")) scriptLang = "bsh";
        else                          scriptLang = "sh";

        font  = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontW = font.charWidth('M');
        fontH = font.getHeight();

        backCmd  = new Command("Stop",  Command.BACK,   1);
        inputCmd = new Command("Input", Command.SCREEN, 2);
        clearCmd = new Command("Clear", Command.SCREEN, 3);
        addCommand(backCmd);
        addCommand(inputCmd);
        addCommand(clearCmd);
        setCommandListener(this);

        // Start blink
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

        // Run script in background thread
        startTime = System.currentTimeMillis();
        runScript();
    }

    // ==================== SCRIPT EXECUTION ====================

    private void runScript() {
        running  = true;
        finished = false;
        printLine("[RUN] " + scriptName + " (" + scriptLang.toUpperCase() + ")", theme.ACCENT);
        printLine("", theme.FG);

        new Thread(new Runnable() {
            public void run() {
                try {
                    String content = fs.readFile(scriptPath);
                    if (content == null) {
                        printLine("Error: Cannot read file: " + scriptPath, theme.ERROR);
                        errorMsg = "file not found";
                    } else {
                        String result;
                        if ("lua".equals(scriptLang))      result = engine.runLua(content, scriptPath);
                        else if ("bsh".equals(scriptLang)) result = engine.runBsh(content, scriptPath);
                        else                               result = engine.runSh(content, scriptPath);
                        // Split and display result
                        if (result != null && result.length() > 0) {
                            String[] outLines = splitLines(result);
                            for (int i = 0; i < outLines.length; i++) {
                                String l = outLines[i];
                                int color = theme.FG;
                                if (l.toLowerCase().startsWith("error") || l.startsWith("ERR")) color = theme.ERROR;
                                else if (l.startsWith("[") && l.indexOf("]") > 0) color = theme.ACCENT;
                                else if (l.startsWith("#")) color = theme.GREY;
                                printLine(l, color);
                            }
                        }
                    }
                } catch (Exception e) {
                    printLine("Exception: " + e.getMessage(), theme.ERROR);
                    errorMsg = e.getMessage();
                }
                endTime  = System.currentTimeMillis();
                running  = false;
                finished = true;
                long elapsed = endTime - startTime;
                printLine("", theme.FG);
                printLine("[DONE] " + scriptName + " in " + elapsed + "ms", theme.ACCENT);
                AppStorage.logBoot("INFO", "Script done: " + scriptPath + " in " + elapsed + "ms");
                repaint();
            }
        }).start();
    }

    // ==================== PAINT ====================

    protected void paint(Graphics g) {
        W = getWidth();
        H = getHeight();
        g.setColor(theme.BG);
        g.fillRect(0, 0, W, H);

        if (canvasMode && drawCmds.size() > 0) {
            paintCanvas(g);
        } else {
            paintTerminal(g);
        }
    }

    private void paintTerminal(Graphics g) {
        // Header
        g.setColor(theme.HEADER);
        g.fillRect(0, 0, W, fontH + 4);
        g.setColor(theme.ACCENT);
        g.setFont(font);
        String title = scriptName + (running ? " ..." : " done");
        g.drawString(title, 4, 2, Graphics.TOP | Graphics.LEFT);

        // Running indicator
        if (running && blinkState) {
            g.setColor(theme.FG);
            g.fillRect(W - 8, 4, 4, fontH - 2);
        }

        // Output lines
        int startY = fontH + 6;
        int inputBarH = fontH + 6;
        int contentH = H - startY - inputBarH;
        int visLines = contentH / fontH;

        int total = lines.size();
        int from  = Math.max(0, total - visLines - scrollY);
        int to    = Math.max(0, total - scrollY);

        g.setFont(font);
        for (int i = from; i < to; i++) {
            String line = (String) lines.elementAt(i);
            int    col  = ((Integer) colors.elementAt(i)).intValue();
            int    y    = startY + (i - from) * fontH;

            // Truncate if too wide
            if (font.stringWidth(line) > W - 4) {
                int maxChars = (W - 8) / fontW;
                if (maxChars > 0 && line.length() > maxChars)
                    line = line.substring(0, maxChars);
            }
            g.setColor(col);
            g.drawString(line, 2, y, Graphics.TOP | Graphics.LEFT);
        }

        // Scrollbar
        if (total > visLines) {
            int sbH   = contentH;
            int sbX   = W - 3;
            int thumbH= Math.max(6, sbH * visLines / total);
            int pos   = total > visLines ? (total - visLines - scrollY) : 0;
            int thumbY= startY + (sbH - thumbH) * pos / Math.max(1, total - visLines);
            g.setColor(theme.SCROLLBAR);
            g.fillRect(sbX, startY, 2, sbH);
            g.setColor(theme.FG);
            g.fillRect(sbX, thumbY, 2, thumbH);
        }

        // Bottom status bar
        int statusY = H - fontH - 4;
        g.setColor(theme.INPUT_BG);
        g.fillRect(0, statusY - 2, W, fontH + 4);
        g.setColor(theme.GREY);
        g.drawLine(0, statusY - 2, W, statusY - 2);
        g.setColor(finished ? theme.ACCENT : theme.FG);
        g.setFont(font);
        String status = finished
            ? "Done (" + ((endTime - startTime)) + "ms) | Back=exit"
            : "Running... | Back=stop";
        g.drawString(status, 4, statusY, Graphics.TOP | Graphics.LEFT);
    }

    private void paintCanvas(Graphics g) {
        // Replay all draw commands
        for (int i = 0; i < drawCmds.size(); i++) {
            Object[] cmd = (Object[]) drawCmds.elementAt(i);
            int type = ((Integer) cmd[0]).intValue();
            switch (type) {
                case DC_CLEAR:
                    g.setColor(((Integer)cmd[1]).intValue());
                    g.fillRect(0, 0, W, H);
                    break;
                case DC_PIXEL:
                    g.setColor(((Integer)cmd[1]).intValue());
                    g.fillRect(((Integer)cmd[2]).intValue(), ((Integer)cmd[3]).intValue(), 1, 1);
                    break;
                case DC_RECT:
                    g.setColor(((Integer)cmd[1]).intValue());
                    g.fillRect(((Integer)cmd[2]).intValue(), ((Integer)cmd[3]).intValue(),
                               ((Integer)cmd[4]).intValue(), ((Integer)cmd[5]).intValue());
                    break;
                case DC_LINE:
                    g.setColor(((Integer)cmd[1]).intValue());
                    g.drawLine(((Integer)cmd[2]).intValue(), ((Integer)cmd[3]).intValue(),
                               ((Integer)cmd[4]).intValue(), ((Integer)cmd[5]).intValue());
                    break;
                case DC_TEXT:
                    g.setColor(((Integer)cmd[1]).intValue());
                    g.setFont(font);
                    g.drawString((String)cmd[2],
                                 ((Integer)cmd[3]).intValue(), ((Integer)cmd[4]).intValue(),
                                 Graphics.TOP | Graphics.LEFT);
                    break;
            }
        }
        // Overlay any text output
        if (lines.size() > 0) {
            int y = H - lines.size() * fontH - 4;
            g.setFont(font);
            for (int i = 0; i < lines.size(); i++) {
                g.setColor(((Integer)colors.elementAt(i)).intValue());
                g.drawString((String)lines.elementAt(i), 2, y + i * fontH, Graphics.TOP|Graphics.LEFT);
            }
        }
    }

    // ==================== DRAW API (called by scripts) ====================

    public void drawClear(int color) {
        drawCmds.removeAllElements();
        drawCmds.addElement(new Object[]{new Integer(DC_CLEAR), new Integer(color)});
        repaint();
    }

    public void drawPixel(int color, int x, int y) {
        drawCmds.addElement(new Object[]{new Integer(DC_PIXEL), new Integer(color),
                                          new Integer(x), new Integer(y)});
    }

    public void drawRect(int color, int x, int y, int w, int h) {
        drawCmds.addElement(new Object[]{new Integer(DC_RECT), new Integer(color),
                                          new Integer(x), new Integer(y),
                                          new Integer(w), new Integer(h)});
    }

    public void drawLine(int color, int x1, int y1, int x2, int y2) {
        drawCmds.addElement(new Object[]{new Integer(DC_LINE), new Integer(color),
                                          new Integer(x1), new Integer(y1),
                                          new Integer(x2), new Integer(y2)});
    }

    public void drawText(int color, String text, int x, int y) {
        drawCmds.addElement(new Object[]{new Integer(DC_TEXT), new Integer(color),
                                          text, new Integer(x), new Integer(y)});
    }

    public void flushDraw() { repaint(); }
    public int  screenWidth()  { return getWidth(); }
    public int  screenHeight() { return getHeight(); }

    // ==================== OUTPUT ====================

    public synchronized void printLine(String text, int color) {
        if (text == null) text = "";
        lines.addElement(text);
        colors.addElement(new Integer(color));
        while (lines.size() > MAX_LINES) {
            lines.removeElementAt(0);
            colors.removeElementAt(0);
        }
        scrollY = 0; // auto-scroll to bottom
        repaint();
    }

    // ==================== KEY HANDLING ====================

    protected void keyPressed(int keyCode) {
        int ga = -1;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}

        if (ga == UP)    { scrollY = Math.min(scrollY + 2, Math.max(0, lines.size() - 5)); repaint(); }
        if (ga == DOWN)  { scrollY = Math.max(0, scrollY - 2); repaint(); }
        if (keyCode == Canvas.KEY_NUM5) { openInput(); }
    }

    private void openInput() {
        inputBox = new javax.microedition.lcdui.TextBox(
            "Script input", "", 256, javax.microedition.lcdui.TextField.ANY);
        javax.microedition.lcdui.Command ok = new javax.microedition.lcdui.Command(
            "OK", javax.microedition.lcdui.Command.OK, 1);
        inputBox.addCommand(ok);
        inputBox.setCommandListener(new javax.microedition.lcdui.CommandListener() {
            public void commandAction(javax.microedition.lcdui.Command c,
                                      javax.microedition.lcdui.Displayable d) {
                inputResult = ((javax.microedition.lcdui.TextBox)d).getString();
                waitingInput = false;
                os.getDisplay().setCurrent(ScriptCanvas.this);
                repaint();
            }
        });
        os.getDisplay().setCurrent(inputBox);
    }

    // ==================== COMMANDS ====================

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            shutdown();
            os.showTerminal();
        } else if (c == inputCmd) {
            openInput();
        } else if (c == clearCmd) {
            lines.removeAllElements();
            colors.removeAllElements();
            drawCmds.removeAllElements();
            scrollY = 0;
            repaint();
        }
    }

    public void shutdown() {
        blinkRunning = false;
        if (blinkThread != null) blinkThread.interrupt();
    }

    // ==================== HELPERS ====================

    private static String[] splitLines(String s) {
        if (s == null || s.length() == 0) return new String[0];
        Vector v = new Vector();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '\n') {
                v.addElement(s.substring(start, i));
                start = i + 1;
            }
        }
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }
}
