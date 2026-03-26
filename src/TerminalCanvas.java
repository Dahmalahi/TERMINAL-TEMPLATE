import javax.microedition.lcdui.*;
import java.util.*;

/**
 * TerminalCanvas v1.2.2 - DashCMD Terminal
 * CLDC 1.1 / MIDP 2.0
 *
 * v1.2.2 changes:
 *  - TextBox input ONLY (no T9, no QWERTY keyboard - uses device native input)
 *    5 = open TextBox to type, # = submit, * = backspace
 *  - Theme-aware rendering (ThemeManager)
 *  - AI integration: 'ai <q>' sends question inline
 *  - App system: 'app <n>' launches apps
 *  - Script canvas: 'run <f>' opens ScriptCanvas
 *  - Desktop command
 *  - Real time clock in header
 *  - Shared VirtualFS across sessions
 */
public class TerminalCanvas extends Canvas {

    private static final int MAX_LINES    = 500;
    private static final int CURSOR_BLINK = 500;
    private static final int MARGIN_X     = 3;

    // Systems
    private TerminalOS      os;
    private Shell           shell;
    private VirtualFS       fs;
    private AppManager      appManager;
    private AITerminal      ai;
    private ThemeManager    theme;
    private ScriptEngine    scriptEngine;

    // Terminal state
    private Vector          screenBuffer;  // Object[]{String text, Integer color}
    private String          currentInput;
    private int             cursorPos;
    private int             scrollOffset;
    private boolean         cursorVisible;
    private int             historyIdx;
    private String          savedInput;

    // Font
    private Font            font;
    private int             fontW, fontH;
    private int             screenW, screenH;
    private int             cols, rows;

    // Input
    private javax.microedition.lcdui.TextBox inputBox;

    // Blink
    private Thread          blinkThread;
    private volatile boolean running;

    // AI typing indicator
    private boolean         aiTyping;
    private String          aiStatus;

    public TerminalCanvas(TerminalOS os, String user, String pass,
                          VirtualFS fs, AppManager appManager, AITerminal ai) {
        this.os          = os;
        this.fs          = fs;
        this.appManager  = appManager;
        this.ai          = ai;
        this.theme       = ThemeManager.getInstance();
        this.screenBuffer= new Vector();
        this.currentInput= "";
        this.cursorPos   = 0;
        this.scrollOffset= 0;
        this.cursorVisible = true;
        this.historyIdx  = -1;
        this.savedInput  = "";
        this.aiTyping    = false;
        this.aiStatus    = "";

        // Create shell with FS and TerminalOS reference so desktop/session cmds work
        this.shell = new Shell(fs, os);
        this.scriptEngine = new ScriptEngine(shell, fs);

        // Login
        String err = fs.login(user, pass);
        if (err != null) AppStorage.logBoot("WARN", "Login: " + err);

        font  = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontW = font.charWidth('M');
        fontH = font.getHeight();

        printBanner();

        // Show motd
        String motd = fs.readFile("/etc/motd");
        if (motd != null && motd.trim().length() > 0) {
            String[] ml = splitLines(motd.trim());
            for (int i = 0; i < ml.length; i++) addLine(ml[i], theme.GREY);
        }
        addLine(shell.getPrompt(), theme.PROMPT);

        // Blink thread
        running = true;
        blinkThread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try { Thread.sleep(CURSOR_BLINK); } catch (InterruptedException e) { return; }
                    cursorVisible = !cursorVisible;
                    repaint();
                }
            }
        });
        blinkThread.start();
    }

    // ==================== BANNER ====================

    private void printBanner() {
        addLine("", theme.FG);
        addLine("DashCMD v1.2.2", theme.PROMPT);
        addLine("Terminal OS | J2ME MIDP 2.0", theme.ACCENT);
        addLine("", theme.FG);
        addLine("'help'      = command list", theme.FG);
        addLine("'desktop'   = Desktop UI", theme.FG);
        addLine("'apps'      = installed apps", theme.FG);
        addLine("'ai <q>'    = ask AI inline", theme.FG);
        addLine("'neofetch'  = system info", theme.FG);
        addLine("5=input  #=enter  *=back", theme.GREY);
        addLine("", theme.FG);
    }

    // ==================== PAINT ====================

    protected void paint(Graphics g) {
        screenW = getWidth();
        screenH = getHeight();
        cols = (screenW - MARGIN_X * 2 - 6) / fontW;
        if (cols < 1) cols = 1;

        // Background
        g.setColor(theme.BG);
        g.fillRect(0, 0, screenW, screenH);

        // Header bar
        g.setColor(theme.HEADER);
        g.fillRect(0, 0, screenW, fontH + 4);
        g.setColor(theme.ACCENT);
        g.setFont(font);
        // Show username and real time
        String time = AppStorage.formatHMS(System.currentTimeMillis());
        String hdr  = fs.getUsername() + "@kali";
        if (font.stringWidth(hdr) + font.stringWidth(time) + 10 < screenW) {
            g.drawString(hdr, MARGIN_X, 2, Graphics.TOP | Graphics.LEFT);
            g.setColor(theme.GREY);
            g.drawString(time, screenW - MARGIN_X - font.stringWidth(time), 2, Graphics.TOP|Graphics.LEFT);
        } else {
            g.drawString("DashCMD", MARGIN_X, 2, Graphics.TOP | Graphics.LEFT);
        }

        // Pixel-art banner (only when at very top)
        int startY = fontH + 6;
        if (scrollOffset == 0 && screenBuffer.size() < 20) {
            int px = Math.max(1, (screenW - MARGIN_X * 2) / 38);
            int bw = 7 * (4 * px + px);
            int bx = (screenW - bw) / 2;
            if (bx < MARGIN_X) bx = MARGIN_X;
            drawPixelBanner(g, bx, startY, px);
            startY += 5 * px + px * 2;
        }

        rows = (screenH - startY - fontH - 6) / fontH;
        if (rows < 1) rows = 1;

        // Build display lines from screen buffer
        int endBuf = screenBuffer.size() - scrollOffset;
        if (endBuf < 0) endBuf = 0;
        if (endBuf > screenBuffer.size()) endBuf = screenBuffer.size();

        Vector dispLines  = new Vector();
        Vector dispColors = new Vector();
        for (int i = endBuf - 1; i >= 0 && dispLines.size() < rows * 3; i--) {
            Object[] entry = (Object[]) screenBuffer.elementAt(i);
            String   text  = (String) entry[0];
            int      color = ((Integer) entry[1]).intValue();
            Vector   wrapped = new Vector();
            if (text.length() == 0) {
                wrapped.addElement(new Object[]{"", new Integer(color)});
            } else {
                while (text.length() > cols) {
                    wrapped.addElement(new Object[]{text.substring(0, cols), new Integer(color)});
                    text = " " + text.substring(cols);
                }
                wrapped.addElement(new Object[]{text, new Integer(color)});
            }
            for (int w = wrapped.size() - 1; w >= 0; w--) {
                Object[] wl = (Object[]) wrapped.elementAt(w);
                dispLines.insertElementAt(wl[0], 0);
                dispColors.insertElementAt(wl[1], 0);
            }
        }

        int dispStart = dispLines.size() - rows;
        if (dispStart < 0) dispStart = 0;
        g.setFont(font);
        for (int i = dispStart; i < dispLines.size(); i++) {
            String text  = (String) dispLines.elementAt(i);
            int    color = ((Integer) dispColors.elementAt(i)).intValue();
            int    y     = startY + (i - dispStart) * fontH;
            g.setColor(color);
            g.drawString(text, MARGIN_X, y, Graphics.TOP | Graphics.LEFT);
        }

        // Scrollbar
        if (screenBuffer.size() > rows) {
            int sbH = screenH - fontH * 3;
            int sbX = screenW - 3;
            g.setColor(theme.SCROLLBAR);
            g.fillRect(sbX, fontH + 4, 2, sbH);
            int thumbH = Math.max(6, sbH * rows / screenBuffer.size());
            int thumbY = fontH + 4 + (sbH - thumbH) *
                (screenBuffer.size() - rows - scrollOffset) /
                Math.max(1, screenBuffer.size() - rows);
            g.setColor(theme.FG);
            g.fillRect(sbX, thumbY, 2, thumbH);
        }

        // AI typing indicator
        if (aiTyping) {
            int ay = screenH - fontH * 2 - 8;
            g.setColor(theme.ACCENT);
            g.setFont(font);
            g.drawString("AI: " + (cursorVisible ? "thinking..." : "thinking.  "), MARGIN_X, ay, Graphics.TOP|Graphics.LEFT);
        }

        // Input bar
        int inputY = screenH - fontH - 4;
        g.setColor(theme.INPUT_BG);
        g.fillRect(0, inputY - 2, screenW, fontH + 6);
        g.setColor(theme.GREY);
        g.drawLine(0, inputY - 2, screenW, inputY - 2);

        // Prompt
        String prompt = shell.getPrompt();
        g.setColor(theme.PROMPT);
        g.setFont(font);
        int promptW = font.stringWidth(prompt);
        if (promptW >= screenW - fontW * 4) { prompt = "$"; promptW = font.stringWidth(prompt); }
        g.drawString(prompt, MARGIN_X, inputY, Graphics.TOP | Graphics.LEFT);

        // Current input text
        int inputX  = MARGIN_X + promptW;
        int maxInputW = screenW - inputX - fontW - 4;
        int inputDispStart = 0;
        if (currentInput.length() * fontW > maxInputW) {
            inputDispStart = cursorPos - (maxInputW / fontW) + 2;
            if (inputDispStart < 0) inputDispStart = 0;
        }
        String visInput = currentInput.substring(inputDispStart);
        if (visInput.length() * fontW > maxInputW)
            visInput = visInput.substring(0, maxInputW / fontW);

        g.setColor(0xFFFFFF);
        g.drawString(visInput, inputX, inputY, Graphics.TOP | Graphics.LEFT);

        // Cursor
        if (cursorVisible) {
            int cx = inputX + (cursorPos - inputDispStart) * fontW;
            if (cx >= inputX && cx < screenW - fontW) {
                g.setColor(theme.CURSOR);
                g.fillRect(cx, inputY, fontW, fontH - 1);
                if (cursorPos < currentInput.length()) {
                    g.setColor(theme.BG);
                    g.drawChar(currentInput.charAt(cursorPos), cx, inputY, Graphics.TOP|Graphics.LEFT);
                }
            }
        }
    }

    // ==================== KEY HANDLING ====================

    protected void keyPressed(int keyCode) {
        int ga = -1;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}

        // Navigation
        if (ga == UP   || keyCode == -1) { scrollOrHistory(true);  return; }
        if (ga == DOWN || keyCode == -2) { scrollOrHistory(false); return; }
        if (ga == LEFT || keyCode == -3) { if (cursorPos > 0) cursorPos--; repaint(); return; }
        if (ga == RIGHT|| keyCode == -4) { if (cursorPos < currentInput.length()) cursorPos++; repaint(); return; }

        // 5 = Open TextBox for input (BEFORE FIRE check)
        if (keyCode == Canvas.KEY_NUM5) { openTextInput(); return; }

        // FIRE / Enter = execute
        if (ga == FIRE || keyCode == 10 || keyCode == 13) { executeCommand(); return; }

        // * = backspace
        if (keyCode == Canvas.KEY_STAR || keyCode == 42) {
            if (cursorPos > 0) {
                currentInput = currentInput.substring(0, cursorPos - 1)
                             + currentInput.substring(cursorPos);
                cursorPos--;
            }
            repaint(); return;
        }

        // # = Enter
        if (keyCode == Canvas.KEY_POUND || keyCode == 35) { executeCommand(); return; }

        // Soft keys = shortcuts
        if (keyCode == -6 || keyCode == -7) { showShortcutsMenu(); return; }

        // Direct printable ASCII (keyboard / emulator)
        if (keyCode >= 32 && keyCode <= 126) {
            char c = (char) keyCode;
            currentInput = currentInput.substring(0, cursorPos) + c
                         + currentInput.substring(cursorPos);
            cursorPos++;
            repaint();
        }
    }

    // ==================== TEXT INPUT ====================

    private void openTextInput() {
        inputBox = new javax.microedition.lcdui.TextBox(
            "Command", currentInput, 512, javax.microedition.lcdui.TextField.ANY);
        final javax.microedition.lcdui.Command okCmd =
            new javax.microedition.lcdui.Command("OK",     javax.microedition.lcdui.Command.OK,     1);
        final javax.microedition.lcdui.Command sendCmd =
            new javax.microedition.lcdui.Command("Send",   javax.microedition.lcdui.Command.SCREEN, 2);
        final javax.microedition.lcdui.Command cancelCmd =
            new javax.microedition.lcdui.Command("Cancel", javax.microedition.lcdui.Command.CANCEL, 3);
        inputBox.addCommand(okCmd);
        inputBox.addCommand(sendCmd);
        inputBox.addCommand(cancelCmd);
        inputBox.setCommandListener(new javax.microedition.lcdui.CommandListener() {
            public void commandAction(javax.microedition.lcdui.Command c,
                                      javax.microedition.lcdui.Displayable d) {
                if (c.getCommandType() == javax.microedition.lcdui.Command.OK) {
                    String typed = ((javax.microedition.lcdui.TextBox)d).getString();
                    if (typed != null) { currentInput = typed; cursorPos = currentInput.length(); }
                    inputBox = null;
                    os.getDisplay().setCurrent(TerminalCanvas.this);
                    repaint();
                } else if (c == sendCmd) {
                    // Type AND execute immediately
                    String typed = ((javax.microedition.lcdui.TextBox)d).getString();
                    if (typed != null) { currentInput = typed; cursorPos = currentInput.length(); }
                    inputBox = null;
                    os.getDisplay().setCurrent(TerminalCanvas.this);
                    executeCommand();
                } else {
                    inputBox = null;
                    os.getDisplay().setCurrent(TerminalCanvas.this);
                    repaint();
                }
            }
        });
        os.getDisplay().setCurrent(inputBox);
    }

    // ==================== COMMAND EXECUTION ====================

    private void executeCommand() {
        String cmd = currentInput.trim();
        addLine(shell.getPrompt() + currentInput, theme.PROMPT);
        currentInput = ""; cursorPos = 0; historyIdx = -1; savedInput = "";

        if (cmd.length() == 0) {
            addLine("", theme.FG);
            addLine(shell.getPrompt(), theme.PROMPT);
            repaint(); return;
        }

        // ---- v1.2.2 local intercepts ----

        // 'desktop' - switch to desktop UI
        if (cmd.equals("desktop")) {
            os.showDesktop();
            return;
        }

        // 'aichat' - open full AI chat canvas
        if (cmd.equals("aichat")) {
            os.showAIChat();
            return;
        }

        // 'ai <question>' - inline AI query
        // 'ai reset' - clear AI context
        if (cmd.equals("ai reset") || cmd.equals("ai clear")) {
            ai.reset();
            addLine("AI: conversation context cleared.", theme.ACCENT);
            addLine("", theme.FG);
            addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'ai history' - show conversation history
        if (cmd.equals("ai history")) {
            addOutputLines(ai.getHistory(), cmd);
            addLine("", theme.FG);
            addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'ai' alone - prompt usage
        if (cmd.equals("ai")) {
            addLine("Usage: ai <question>  |  aichat  |  ai reset  |  ai history", theme.ACCENT);
            addLine("", theme.FG);
            addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'ai <question>' - inline AI query
        if (cmd.startsWith("ai ") && cmd.length() > 3) {
            final String question = cmd.substring(3).trim();
            addLine("AI: thinking...", theme.ACCENT);
            aiTyping  = true;
            aiStatus  = "thinking";
            repaint();
            ai.askAsync(question, new AITerminal.AICallback() {
                public void onResult(String response) {
                    aiTyping = false;
                    aiStatus = "";
                    String[] lines = splitLines(response);
                    for (int i = 0; i < lines.length; i++) {
                        addLine("AI: " + lines[i], theme.ACCENT);
                    }
                    addLine("", theme.FG);
                    addLine(shell.getPrompt(), theme.PROMPT);
                    scrollOffset = 0;
                    repaint();
                }
            });
            return;
        }

        // 'apps' - list installed apps
        if (cmd.equals("apps")) {
            addOutputLines(appManager.listApps(), cmd);
            addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'app <name>' or 'run <name>' - launch app
        if ((cmd.startsWith("app ") || cmd.startsWith("run ")) && cmd.length() > 4) {
            String appName = cmd.substring(4).trim();
            // Check if it's a file to run in ScriptCanvas
            String resolved = fs.resolvePath(appName);
            if (fs.isFile(resolved)) {
                boolean canvasMode = false;
                if (resolved.endsWith(".lua")) {
                    String content = fs.readFile(resolved);
                    canvasMode = content != null && content.indexOf("canvas = true") >= 0;
                }
                os.showScript(resolved, canvasMode);
                return;
            }
            // Try app manager
            AppManager.AppEntry app = appManager.findApp(appName);
            if (app != null) {
                if (app.canvasMode) {
                    os.showScript(app.entryPoint, true);
                } else {
                    addOutputLines(appManager.launchApp(app), cmd);
                    addLine("", theme.FG);
                    addLine(shell.getPrompt(), theme.PROMPT);
                    scrollOffset = 0; repaint();
                }
                return;
            }
            // Not found
            addLine("run: '" + appName + "': no such file or app", theme.ERROR);
            addLine("", theme.FG);
            addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'theme <number|load|save|list>' - theme commands
        if (cmd.startsWith("theme")) {
            addOutputLines(handleThemeCmd(cmd), cmd);
            addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'install <script> <name>' - install app from script
        if (cmd.startsWith("install ")) {
            String[] parts = splitArgs(cmd);
            if (parts.length >= 3) {
                addOutputLines(appManager.installScript(parts[1], parts[2]), cmd);
            } else {
                addLine("Usage: install <script.lua> <appname>", theme.ERROR);
            }
            addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // ---- v1.2.2 new commands ----

        // 'sysinfo' / 'neofetch' - rich system info
        if (cmd.equals("sysinfo") || cmd.equals("neofetch") || cmd.equals("screenfetch")) {
            addOutputLines(buildSysInfo(), cmd);
            addLine("", theme.FG); addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'storage' - VirtualFS + JSR75 storage info
        if (cmd.equals("storage")) {
            addOutputLines(buildStorageInfo(), cmd);
            addLine("", theme.FG); addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'scripts' - list all scripts in home
        if (cmd.equals("scripts")) {
            addOutputLines(buildScriptList(), cmd);
            addLine("", theme.FG); addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'pkg' - built-in package info/installer
        if (cmd.startsWith("pkg")) {
            addOutputLines(handlePkgCmd(cmd), cmd);
            addLine("", theme.FG); addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'savefs' - persist filesystem to RMS
        if (cmd.equals("savefs")) {
            fs.saveToRMS();
            addLine("Filesystem saved to RMS.", theme.ACCENT);
            addLine("", theme.FG); addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'help' / '?' - local help showing v1.2.2 commands
        if (cmd.equals("help") || cmd.equals("?")) {
            addOutputLines(buildHelp(), cmd);
            addLine("", theme.FG); addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // 'ver' / 'version' / 'dashcmd'
        if (cmd.equals("ver") || cmd.equals("version") || cmd.equals("dashcmd")) {
            addLine("DashCMD v1.2.2 | J2ME CLDC1.1/MIDP2.0", theme.ACCENT);
            addLine("Desktop + AI + Scripts + Apps + Storage", theme.FG);
            addLine("", theme.FG); addLine(shell.getPrompt(), theme.PROMPT);
            scrollOffset = 0; repaint(); return;
        }

        // Standard shell execute
        String output = shell.execute(cmd);

        if (output != null && output.equals("\033[CLEAR]")) {
            screenBuffer.removeAllElements();
            addLine(shell.getPrompt(), theme.PROMPT);
            repaint(); return;
        }
        if (output != null && output.equals("\033[EXIT]")) {
            os.destroyApp(true); return;
        }

        addOutputLines(output, cmd);
        addLine("", theme.FG);
        addLine(shell.getPrompt(), theme.PROMPT);
        scrollOffset = 0; repaint();
    }

    private void addOutputLines(String output, String cmd) {
        if (output == null || output.length() == 0) return;
        String[] lines = splitLines(output);
        for (int i = 0; i < lines.length; i++) {
            addLine(lines[i], getLineColor(lines[i], cmd));
        }
    }

    private String handleThemeCmd(String cmd) {
        String[] parts = splitArgs(cmd);
        if (parts.length == 1 || parts[1].equals("list")) return theme.listThemes();
        if (parts[1].equals("load") && parts.length >= 3) {
            String path = fs.resolvePath(parts[2]);
            if (!fs.isFile(path)) return "theme: file not found: " + parts[2];
            return theme.applyFromFile(fs.readFile(path));
        }
        if (parts[1].equals("save") && parts.length >= 3) {
            String path = fs.resolvePath(parts[2]);
            fs.writeFile(path, theme.exportTheme());
            fs.saveToRMS();
            return "theme: saved to " + parts[2];
        }
        // theme <number>
        try {
            int idx = Integer.parseInt(parts[1]);
            theme.applyBuiltin(idx);
            return "theme: applied '" + theme.name + "'";
        } catch (NumberFormatException e) {
            return "theme: unknown command '" + parts[1] + "'\n" + theme.listThemes();
        }
    }

    private int getLineColor(String line, String cmd) {
        if (line == null) return theme.FG;
        String lower = line.toLowerCase();
        if (lower.startsWith("error") || lower.startsWith("bash:") ||
            lower.indexOf("not found") >= 0 || lower.indexOf("denied") >= 0)
            return theme.ERROR;
        if (lower.startsWith("warning") || lower.indexOf("failed") >= 0) return theme.WARNING;
        if (line.startsWith("#")) return theme.GREY;
        if (lower.indexOf("http") >= 0 && lower.indexOf("200") >= 0) return theme.ACCENT;
        if (cmd.startsWith("ls") && line.startsWith("d")) return theme.DIR_COLOR;
        if (lower.startsWith("drw") || lower.startsWith("lrw")) return theme.DIR_COLOR;
        return theme.FG;
    }

    // ==================== SHORTCUTS ====================

    private void showShortcutsMenu() {
        String[] shortcuts = {
            "ls -la","cd ~","pwd","apps","desktop","aichat",
            "neofetch","clear","uptime","df -h","theme list",
            "storage","scripts","pkg list","savefs","bootlog"
        };
        int idx = (int)(System.currentTimeMillis() / 300 % shortcuts.length);
        currentInput = shortcuts[idx];
        cursorPos = currentInput.length();
        repaint();
    }

    // ==================== HISTORY / SCROLL ====================

    private void scrollOrHistory(boolean up) {
        if (up) {
            if (scrollOffset == 0 && historyIdx < shell.getHistory().size() - 1) {
                if (historyIdx == -1) savedInput = currentInput;
                historyIdx++;
                int idx = shell.getHistory().size() - 1 - historyIdx;
                currentInput = (String) shell.getHistory().elementAt(idx);
                cursorPos = currentInput.length();
                repaint();
            } else {
                int maxScroll = Math.max(0, screenBuffer.size() - rows);
                if (scrollOffset < maxScroll) { scrollOffset = Math.min(scrollOffset + 3, maxScroll); repaint(); }
            }
        } else {
            if (scrollOffset > 0) { scrollOffset = Math.max(0, scrollOffset - 3); repaint(); }
            else if (historyIdx >= 0) {
                historyIdx--;
                if (historyIdx < 0) currentInput = savedInput;
                else {
                    int idx = shell.getHistory().size() - 1 - historyIdx;
                    currentInput = (String) shell.getHistory().elementAt(idx);
                }
                cursorPos = currentInput.length();
                repaint();
            }
        }
    }

    // ==================== BUFFER ====================

    private void addLine(String text, int color) {
        if (text == null) text = "";
        screenBuffer.addElement(new Object[]{text, new Integer(color)});
        while (screenBuffer.size() > MAX_LINES) screenBuffer.removeElementAt(0);
    }

    // ==================== PIXEL BANNER ====================

    private void drawPixelBanner(Graphics g, int ox, int oy, int px) {
        g.setColor(theme.FG);
        int[][] D={{1,1,1,0},{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,1,1,0}};
        int[][] A={{0,1,1,0},{1,0,0,1},{1,1,1,1},{1,0,0,1},{1,0,0,1}};
        int[][] S={{0,1,1,1},{1,0,0,0},{0,1,1,0},{0,0,0,1},{1,1,1,0}};
        int[][] H={{1,0,0,1},{1,0,0,1},{1,1,1,1},{1,0,0,1},{1,0,0,1}};
        int[][] C={{0,1,1,1},{1,0,0,0},{1,0,0,0},{1,0,0,0},{0,1,1,1}};
        int[][] M={{1,0,0,1},{1,1,1,1},{1,0,0,1},{1,0,0,1},{1,0,0,1}};
        int[][][] letters = {D,A,S,H,C,M,D};
        int gap = px, letterW = 4 * px;
        for (int l = 0; l < letters.length; l++) {
            int lx = ox + l * (letterW + gap);
            int[][] bmp = letters[l];
            for (int row = 0; row < 5; row++)
                for (int col = 0; col < 4; col++)
                    if (bmp[row][col] == 1)
                        g.fillRect(lx + col * px, oy + row * px, px, px);
        }
    }

    // ==================== UTILITIES ====================

    private static String[] splitLines(String s) {
        if (s == null || s.length() == 0) return new String[0];
        Vector v = new Vector(); int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '\n') {
                v.addElement(s.substring(start, i)); start = i + 1;
            }
        }
        String[] a = new String[v.size()]; v.copyInto(a); return a;
    }

    private static String[] splitArgs(String cmd) {
        Vector v = new Vector(); int i = 0; cmd = cmd.trim();
        while (i < cmd.length()) {
            while (i < cmd.length() && cmd.charAt(i) == ' ') i++;
            int j = i;
            while (j < cmd.length() && cmd.charAt(j) != ' ') j++;
            if (j > i) v.addElement(cmd.substring(i, j));
            i = j;
        }
        String[] a = new String[v.size()]; v.copyInto(a); return a;
    }

    // ==================== ACCESSORS ====================

    public VirtualFS    getFS()           { return fs; }
    public ScriptEngine getScriptEngine() { return scriptEngine; }
    public Shell        getShell()        { return shell; }

    /** Called by TerminalOS.injectCommand() — pre-fill input and execute immediately. */
    public void injectAndRun(String cmd) {
        if (cmd == null || cmd.trim().length() == 0) return;
        currentInput = cmd.trim();
        cursorPos    = currentInput.length();
        executeCommand();
    }

    // ==================== v1.2.2 COMMAND HELPERS ====================

    private String buildSysInfo() {
        long now      = System.currentTimeMillis();
        long upMs     = now - os.getBootTime();
        long freeMem  = Runtime.getRuntime().freeMemory()  / 1024;
        long totalMem = Runtime.getRuntime().totalMemory() / 1024;
        long usedMem  = totalMem - freeMem;
        String upStr  = AppStorage.formatUptime(upMs);
        String user   = fs.getUsername();
        String host   = fs.getHostname();

        StringBuffer sb = new StringBuffer();
        sb.append(user).append("@").append(host).append("\n");
        sb.append("-------------------------\n");
        sb.append("OS:      DashCMD v1.2.2\n");
        sb.append("Shell:   DashCMD Terminal\n");
        sb.append("Theme:   ").append(theme.name).append("\n");
        sb.append("Uptime:  ").append(upStr).append("\n");
        sb.append("Memory:  ").append(usedMem).append("K / ").append(totalMem).append("K\n");
        sb.append("Apps:    ").append(appManager.getAppCount()).append(" installed\n");
        sb.append("FS:      VirtualFS (RMS-backed)\n");
        sb.append("JSR-75:  ").append(JSR75Storage.isAvailable() ? "available" : "unavailable").append("\n");
        sb.append("AI:      DashCMD AI v1.2.2\n");
        sb.append("Sess:    ").append(os.getSessionCount()).append("/3\n");
        sb.append("Time:    ").append(AppStorage.formatTime(now));
        return sb.toString();
    }

    private String buildStorageInfo() {
        StringBuffer sb = new StringBuffer();
        sb.append("=== Storage v1.2.2 ===\n");
        sb.append("VirtualFS (RMS):\n");
        sb.append("  Home: ").append(fs.getHomeDir()).append("\n");
        sb.append("  CWD:  ").append(fs.getCurrentPath()).append("\n");
        // Count files
        String[] homeChildren = fs.listChildren(fs.getHomeDir());
        sb.append("  Items in home: ").append(homeChildren.length).append("\n");
        sb.append("  Apps dir: ").append(fs.getHomeDir()).append("/apps\n");
        sb.append("\nJSR-75 Device FS:\n");
        if (JSR75Storage.isAvailable()) {
            sb.append(JSR75Storage.getRootsInfo());
        } else {
            sb.append("  Not available on this device\n");
        }
        sb.append("\nMemory:\n");
        long free  = Runtime.getRuntime().freeMemory()  / 1024;
        long total = Runtime.getRuntime().totalMemory() / 1024;
        sb.append("  Used:  ").append(total - free).append(" KB\n");
        sb.append("  Free:  ").append(free).append(" KB\n");
        sb.append("  Total: ").append(total).append(" KB");
        return sb.toString();
    }

    private String buildScriptList() {
        StringBuffer sb = new StringBuffer();
        sb.append("=== Scripts in home ===\n");
        // Search home dir recursively for scripts
        String home = fs.getHomeDir();
        String[] children = fs.listChildren(home);
        int count = 0;
        for (int i = 0; i < children.length; i++) {
            String name = fs.nameOf(children[i]);
            if (name.endsWith(".lua") || name.endsWith(".sh") || name.endsWith(".bsh")) {
                sb.append("  ").append(name).append(" [").append(getLang(name)).append("]\n");
                count++;
            }
        }
        // Also list apps
        sb.append("\nInstalled apps (").append(appManager.getAppCount()).append("):\n");
        sb.append(appManager.listApps());
        if (count == 0) sb.append("  (no loose scripts in home)\n");
        sb.append("\nRun: run <name>  |  app <name>  |  lua/sh/bsh <file>");
        return sb.toString();
    }

    private String getLang(String name) {
        if (name.endsWith(".lua")) return "LUA";
        if (name.endsWith(".bsh")) return "BSH";
        return "SH";
    }

    private String handlePkgCmd(String cmd) {
        String[] parts = splitArgs(cmd);
        if (parts.length < 2 || parts[1].equals("list") || parts[1].equals("ls")) {
            StringBuffer sb = new StringBuffer();
            sb.append("=== DashCMD Package Manager ===\n");
            sb.append("Installed apps: ").append(appManager.getAppCount()).append("\n\n");
            sb.append(appManager.listApps()).append("\n\n");
            sb.append("Commands:\n");
            sb.append("  pkg list           - list all apps\n");
            sb.append("  pkg info <name>    - show app info\n");
            sb.append("  pkg remove <name>  - uninstall app\n");
            sb.append("  install <f> <n>    - install from script file");
            return sb.toString();
        }
        if (parts[1].equals("info") && parts.length >= 3) {
            AppManager.AppEntry app = appManager.findApp(parts[2]);
            if (app == null) return "pkg: app '" + parts[2] + "' not found";
            StringBuffer sb = new StringBuffer();
            sb.append("Name:    ").append(app.name).append("\n");
            sb.append("Version: ").append(app.version).append("\n");
            sb.append("Author:  ").append(app.author).append("\n");
            sb.append("Lang:    ").append(app.lang.toUpperCase()).append("\n");
            sb.append("Canvas:  ").append(app.canvasMode ? "yes" : "no").append("\n");
            sb.append("Path:    ").append(app.entryPoint).append("\n");
            sb.append("Desc:    ").append(app.description);
            return sb.toString();
        }
        if (parts[1].equals("remove") && parts.length >= 3) {
            AppManager.AppEntry app = appManager.findApp(parts[2]);
            if (app == null) return "pkg: app '" + parts[2] + "' not found";
            fs.deleteRecursive(app.path);
            appManager.scanApps();
            fs.saveToRMS();
            return "pkg: removed '" + parts[2] + "'";
        }
        return "pkg: unknown command '" + parts[1] + "'\nUsage: pkg list | info <n> | remove <n>";
    }

    private String buildHelp() {
        StringBuffer sb = new StringBuffer();
        sb.append("DashCMD v1.2.2 Commands\n");
        sb.append("=======================\n\n");
        sb.append("TERMINAL:\n");
        sb.append("  help / ?          this help\n");
        sb.append("  clear / cls       clear screen\n");
        sb.append("  ver / version     version info\n");
        sb.append("  exit / quit       exit\n\n");
        sb.append("FILESYSTEM:\n");
        sb.append("  ls [-la]  cd  pwd  mkdir  rm  cp  mv\n");
        sb.append("  cat  nano  touch  find  grep  stat\n");
        sb.append("  savefs            save FS to RMS\n\n");
        sb.append("SYSTEM:\n");
        sb.append("  neofetch/sysinfo  system info\n");
        sb.append("  storage           storage info\n");
        sb.append("  uptime  free  df  top  ps  uname\n");
        sb.append("  date  cal  uptime  bootlog\n\n");
        sb.append("APPS & SCRIPTS:\n");
        sb.append("  apps              list installed apps\n");
        sb.append("  app <name>        launch app\n");
        sb.append("  run <file>        run script file\n");
        sb.append("  scripts           list scripts\n");
        sb.append("  pkg list/info/remove\n");
        sb.append("  install <f> <n>   install from file\n\n");
        sb.append("AI:\n");
        sb.append("  ai <question>     ask AI inline\n");
        sb.append("  aichat            open AI chat UI\n");
        sb.append("  ai reset          clear AI context\n");
        sb.append("  ai history        show history\n\n");
        sb.append("UI:\n");
        sb.append("  desktop           open Desktop UI\n");
        sb.append("  theme list/N/load/save\n\n");
        sb.append("NETWORK:\n");
        sb.append("  ping  curl  wget  ifconfig  netstat\n\n");
        sb.append("5=input  #=enter  *=backspace  UP/DN=scroll");
        return sb.toString();
    }

    public void shutdown() {
        running = false;
        if (blinkThread != null) blinkThread.interrupt();
    }
}
