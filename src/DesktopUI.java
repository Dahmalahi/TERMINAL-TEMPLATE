import javax.microedition.lcdui.*;
import java.util.*;

/**
 * DesktopUI v1.1.1 - Canvas-drawn graphical desktop for DashCMD.
 * Launched via 'desktop' command from the terminal.
 *
 * Features:
 *  - Desktop icons: Terminal, Files, Text Editor, Network, Settings
 *  - Taskbar with clock (real device time)
 *  - Wallpaper (gradient or pixel pattern)
 *  - Icon navigation via arrow keys
 *  - Open apps via FIRE key
 *  - Return to terminal via Back/Exit
 */
public class DesktopUI extends Canvas implements CommandListener {

    private TerminalMIDlet  midlet;
    private VirtualFS       fs;

    // Colours
    private static final int BG_TOP    = 0x1A2A4A;
    private static final int BG_BOT    = 0x0D1117;
    private static final int TASKBAR   = 0x0D0D0D;
    private static final int ICON_BG   = 0x1E3A5A;
    private static final int ICON_SEL  = 0x00BFFF;
    private static final int WHITE     = 0xFFFFFF;
    private static final int GREEN     = 0x00FF41;
    private static final int GREY      = 0x666666;
    private static final int CYAN      = 0x00BFFF;
    private static final int YELLOW    = 0xFFFF00;
    private static final int RED       = 0xFF4444;

    private Font fontS, fontM;
    private int  W, H;
    private int  selectedIcon = 0;

    // Desktop icons
    private String[] iconLabels = {
        "Terminal", "Files", "Editor", "Network", "Settings", "Logs"
    };
    // Icon pixel-art bitmaps (8x8 each, rows of 8 bits)
    private int[][] iconBmps = {
        // Terminal: > _ prompt
        {0x00,0x7E,0x18,0x0C,0x0C,0x18,0x7E,0x00},
        // Files: folder shape
        {0x00,0x1C,0x3E,0x7F,0x7F,0x7F,0x7F,0x00},
        // Editor: pencil
        {0x01,0x03,0x07,0x0F,0x1F,0x3E,0x7C,0x00},
        // Network: antenna waves
        {0x1C,0x36,0x63,0x1C,0x08,0x08,0x08,0x00},
        // Settings: gear
        {0x00,0x1C,0x36,0x63,0x36,0x1C,0x00,0x00},
        // Logs: lines
        {0x00,0x7E,0x00,0x7E,0x00,0x7E,0x00,0x00}
    };
    private int[] iconColors = {GREEN, CYAN, YELLOW, 0x00FF88, 0xFFAA00, RED};

    // Clock / status
    private long    startTime;
    private Thread  clockThread;
    private volatile boolean running;

    // Open window state
    private int     openApp = -1; // -1=desktop, 0-5=app
    private String  fileContent = "";
    private String  editorContent = "";
    private boolean editorDirty = false;
    private String  editorFile = "";
    private Vector  fileListing = new Vector();
    private int     fileListIdx = 0;
    private String  networkResult = "";

    private Command exitCmd, backCmd, openCmd, saveCmd, inputCmd;

    public DesktopUI(TerminalMIDlet midlet, VirtualFS fs) {
        this.midlet    = midlet;
        this.fs        = fs;
        this.startTime = System.currentTimeMillis();
        this.running   = true;

        fontS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN,  Font.SIZE_SMALL);
        fontM = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD,   Font.SIZE_MEDIUM);

        exitCmd  = new Command("Terminal", Command.EXIT,   1);
        backCmd  = new Command("Back",     Command.BACK,   2);
        openCmd  = new Command("Open",     Command.OK,     3);
        saveCmd  = new Command("Save",     Command.SCREEN, 4);
        inputCmd = new Command("Input",    Command.SCREEN, 5);

        addCommand(exitCmd);
        addCommand(openCmd);
        setCommandListener(this);

        // Clock tick thread
        clockThread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    repaint();
                }
            }
        });
        clockThread.start();
    }

    // ==================== PAINT ====================

    protected void paint(Graphics g) {
        W = getWidth();
        H = getHeight();

        if (openApp >= 0) {
            paintApp(g);
            return;
        }
        paintDesktop(g);
    }

    private void paintDesktop(Graphics g) {
        // Gradient background
        for (int y = 0; y < H - 16; y++) {
            int r = ((BG_TOP >> 16) & 0xFF) + (((BG_BOT >> 16) & 0xFF) - ((BG_TOP >> 16) & 0xFF)) * y / (H - 16);
            int gr= ((BG_TOP >>  8) & 0xFF) + (((BG_BOT >>  8) & 0xFF) - ((BG_TOP >>  8) & 0xFF)) * y / (H - 16);
            int b = ( BG_TOP        & 0xFF) + (( BG_BOT        & 0xFF) - ( BG_TOP        & 0xFF)) * y / (H - 16);
            g.setColor((r << 16) | (gr << 8) | b);
            g.drawLine(0, y, W, y);
        }

        // Draw icons in a grid
        int iconSize = Math.max(20, Math.min(32, (W - 8) / 3 - 4));
        int cols     = Math.max(2, (W - 4) / (iconSize + 4));
        int iconW    = (W - 4) / cols;

        for (int i = 0; i < iconLabels.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int ix  = 4 + col * iconW + (iconW - iconSize) / 2;
            int iy  = 4 + row * (iconSize + fontS.getHeight() + 6);

            // Icon background
            if (i == selectedIcon) {
                g.setColor(ICON_SEL);
                g.fillRoundRect(ix - 2, iy - 2, iconSize + 4, iconSize + 4, 4, 4);
            } else {
                g.setColor(ICON_BG);
                g.fillRoundRect(ix - 2, iy - 2, iconSize + 4, iconSize + 4, 4, 4);
            }

            // Draw pixel-art icon bitmap
            drawIcon(g, ix, iy, iconSize, iconBmps[i], iconColors[i]);

            // Label
            g.setFont(fontS);
            g.setColor(i == selectedIcon ? WHITE : GREY);
            int lw = fontS.stringWidth(iconLabels[i]);
            g.drawString(iconLabels[i],
                4 + col * iconW + (iconW - lw) / 2,
                iy + iconSize + 2,
                Graphics.TOP | Graphics.LEFT);
        }

        // Taskbar
        paintTaskbar(g);
    }

    private void paintTaskbar(Graphics g) {
        int tbY = H - fontS.getHeight() - 4;
        g.setColor(TASKBAR);
        g.fillRect(0, tbY - 2, W, H - tbY + 2);
        g.setColor(GREY);
        g.drawLine(0, tbY - 2, W, tbY - 2);

        // Clock from real device time
        long now = System.currentTimeMillis();
        String time = AppStorage.formatHMS(now);
        g.setFont(fontS);
        g.setColor(GREEN);
        g.drawString(time, W - fontS.stringWidth(time) - 2, tbY, Graphics.TOP | Graphics.LEFT);

        // App name
        g.setColor(CYAN);
        g.drawString("DashCMD v1.1.1 Desktop", 2, tbY, Graphics.TOP | Graphics.LEFT);
    }

    private void paintApp(Graphics g) {
        // App title bar
        g.setColor(TASKBAR);
        g.fillRect(0, 0, W, fontM.getHeight() + 4);
        g.setColor(CYAN);
        g.setFont(fontM);
        String title = openApp < iconLabels.length ? iconLabels[openApp] : "App";
        g.drawString(title, 4, 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(RED);
        g.drawString("X", W - fontM.charWidth('X') - 4, 2, Graphics.TOP | Graphics.LEFT);

        int contentY = fontM.getHeight() + 6;
        g.setColor(BG_BOT);
        g.fillRect(0, contentY, W, H - contentY - fontS.getHeight() - 4);

        switch (openApp) {
            case 0: paintTerminalApp(g, contentY);   break;
            case 1: paintFilesApp(g, contentY);      break;
            case 2: paintEditorApp(g, contentY);     break;
            case 3: paintNetworkApp(g, contentY);    break;
            case 4: paintSettingsApp(g, contentY);   break;
            case 5: paintLogsApp(g, contentY);       break;
        }

        // Bottom taskbar
        paintTaskbar(g);
    }

    private void paintTerminalApp(Graphics g, int y) {
        g.setFont(fontS);
        g.setColor(GREEN);
        g.drawString("Opening terminal...", 4, y + 4, Graphics.TOP | Graphics.LEFT);
        g.setColor(GREY);
        g.drawString("Press Back to return to desktop", 4, y + fontS.getHeight() + 8, Graphics.TOP | Graphics.LEFT);
    }

    private void paintFilesApp(Graphics g, int y) {
        g.setFont(fontS);
        g.setColor(CYAN);
        g.drawString("Files - " + fs.getCurrentPath(), 4, y + 2, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 4;

        String[] children = fs.listChildren(fs.getCurrentPath());
        int shown = Math.min(children.length, (H - y - 20) / fontS.getHeight());
        for (int i = 0; i < shown; i++) {
            boolean sel = (i == fileListIdx);
            g.setColor(sel ? WHITE : (fs.isDir(children[i]) ? CYAN : GREEN));
            String name = (fs.isDir(children[i]) ? "d " : "- ") + fs.nameOf(children[i]);
            if (sel) {
                g.setColor(ICON_SEL);
                g.fillRect(0, y, W, fontS.getHeight());
                g.setColor(WHITE);
            }
            g.drawString(name, 4, y, Graphics.TOP | Graphics.LEFT);
            y += fontS.getHeight();
        }
    }

    private void paintEditorApp(Graphics g, int y) {
        g.setFont(fontS);
        g.setColor(editorDirty ? YELLOW : CYAN);
        g.drawString("Editor: " + (editorFile.length() > 0 ? editorFile : "(new)") +
                     (editorDirty ? " *" : ""), 4, y + 2, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 4;
        g.setColor(WHITE);
        if (editorContent.length() == 0) {
            g.setColor(GREY); g.drawString("(empty - press Input to type)", 4, y, Graphics.TOP | Graphics.LEFT);
        } else {
            String[] lines = splitLines(editorContent);
            int showLines = Math.min(lines.length, (H - y - 20) / fontS.getHeight());
            for (int i = 0; i < showLines; i++) {
                g.setColor(WHITE);
                g.drawString(lines[i], 4, y, Graphics.TOP | Graphics.LEFT);
                y += fontS.getHeight();
            }
        }
    }

    private void paintNetworkApp(Graphics g, int y) {
        g.setFont(fontS);
        g.setColor(CYAN);
        g.drawString("Network", 4, y + 2, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 4;
        if (networkResult.length() == 0) {
            g.setColor(GREY);
            g.drawString("Press Input to enter URL", 4, y, Graphics.TOP | Graphics.LEFT);
        } else {
            g.setColor(GREEN);
            String[] lines = splitLines(networkResult);
            int showLines = Math.min(lines.length, (H - y - 20) / fontS.getHeight());
            for (int i = 0; i < showLines; i++) {
                g.drawString(lines[i], 4, y, Graphics.TOP | Graphics.LEFT);
                y += fontS.getHeight();
            }
        }
    }

    private void paintSettingsApp(Graphics g, int y) {
        g.setFont(fontS);
        g.setColor(CYAN);
        g.drawString("Settings", 4, y + 2, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 6;
        String[] info = {
            "User:    " + fs.getUsername(),
            "Host:    " + fs.getHostname(),
            "Path:    " + fs.getCurrentPath(),
            "Version: DashCMD v1.1.1",
            "Storage: RMS (device memory)",
            "Time:    " + AppStorage.formatTime(System.currentTimeMillis()),
            "Root:    " + (fs.isRoot() ? "yes" : "no")
        };
        for (int i = 0; i < info.length; i++) {
            g.setColor(i % 2 == 0 ? WHITE : GREY);
            g.drawString(info[i], 4, y, Graphics.TOP | Graphics.LEFT);
            y += fontS.getHeight() + 1;
        }
    }

    private void paintLogsApp(Graphics g, int y) {
        g.setFont(fontS);
        g.setColor(CYAN);
        g.drawString("Boot Log", 4, y + 2, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 4;
        String log = AppStorage.readBootLog();
        String[] lines = splitLines(log);
        int showLines = Math.min(lines.length, (H - y - 20) / fontS.getHeight());
        int startLine = Math.max(0, lines.length - showLines);
        for (int i = startLine; i < lines.length; i++) {
            g.setColor(lines[i].indexOf("ERR") >= 0 ? RED :
                       lines[i].indexOf("WARN") >= 0 ? YELLOW : GREEN);
            g.drawString(lines[i], 4, y, Graphics.TOP | Graphics.LEFT);
            y += fontS.getHeight();
        }
    }

    // ==================== KEY HANDLING ====================

    protected void keyPressed(int keyCode) {
        int ga = -1;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}

        if (openApp < 0) {
            // Desktop navigation
            int cols = Math.max(2, (W - 4) / (Math.max(20, Math.min(32, (W - 8) / 3 - 4)) + 4));
            if (ga == LEFT  && selectedIcon > 0)                     selectedIcon--;
            if (ga == RIGHT && selectedIcon < iconLabels.length - 1) selectedIcon++;
            if (ga == UP    && selectedIcon >= cols)                  selectedIcon -= cols;
            if (ga == DOWN  && selectedIcon < iconLabels.length - cols) selectedIcon += cols;
            if (ga == FIRE || keyCode == Canvas.KEY_NUM5) openIcon();
            repaint();
        } else {
            // App key handling
            if (openApp == 1) { // Files
                int children = fs.listChildren(fs.getCurrentPath()).length;
                if (ga == UP   && fileListIdx > 0)           fileListIdx--;
                if (ga == DOWN && fileListIdx < children - 1) fileListIdx++;
                if (ga == FIRE || keyCode == Canvas.KEY_NUM5) openSelectedFile();
                if (ga == LEFT) { fs.cd(".."); fileListIdx = 0; }
                repaint();
            }
        }
    }

    private void openIcon() {
        openApp = selectedIcon;
        removeCommand(openCmd);
        addCommand(backCmd);
        if (openApp == 2) { addCommand(saveCmd); addCommand(inputCmd); }
        if (openApp == 3) { addCommand(inputCmd); }
        if (openApp == 0) {
            // Return to terminal immediately
            openApp = -1;
            shutdown();
            midlet.showTerminal();
        }
        repaint();
    }

    private void openSelectedFile() {
        String[] children = fs.listChildren(fs.getCurrentPath());
        if (fileListIdx >= children.length) return;
        String path = children[fileListIdx];
        if (fs.isDir(path)) {
            fs.cd(path);
            fileListIdx = 0;
        } else {
            // Open in editor
            openApp = 2;
            editorFile    = path;
            editorContent = fs.readFile(path);
            if (editorContent == null) editorContent = "";
            editorDirty   = false;
        }
        repaint();
    }

    // ==================== COMMANDS ====================

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            shutdown();
            midlet.showTerminal();
        } else if (c == backCmd) {
            if (openApp >= 0) {
                openApp = -1;
                removeCommand(backCmd);
                removeCommand(saveCmd);
                removeCommand(inputCmd);
                addCommand(openCmd);
                repaint();
            } else {
                shutdown();
                midlet.showTerminal();
            }
        } else if (c == openCmd) {
            openIcon();
        } else if (c == saveCmd && openApp == 2) {
            saveEditorFile();
        } else if (c == inputCmd) {
            handleInput();
        }
    }

    private void saveEditorFile() {
        if (editorFile.length() == 0) {
            // Ask for filename
            TextBox tb = new TextBox("Save As", "document.txt", 64, TextField.ANY);
            Command ok = new Command("OK", Command.OK, 1);
            tb.addCommand(ok);
            tb.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    String name = ((TextBox)d).getString();
                    if (name != null && name.length() > 0) {
                        editorFile = "/home/" + fs.getUsername() + "/Documents/" + name;
                    }
                    midlet.getDisplay().setCurrent(DesktopUI.this);
                    if (editorFile.length() > 0) {
                        fs.writeFile(editorFile, editorContent);
                        AppStorage.logBoot("INFO", "Editor saved: " + editorFile);
                        editorDirty = false;
                    }
                    repaint();
                }
            });
            midlet.getDisplay().setCurrent(tb);
        } else {
            fs.writeFile(editorFile, editorContent);
            AppStorage.logBoot("INFO", "Editor saved: " + editorFile);
            editorDirty = false;
            repaint();
        }
    }

    private void handleInput() {
        if (openApp == 2) {
            // Editor input
            TextBox tb = new TextBox("Edit", editorContent, 2048, TextField.ANY);
            Command ok = new Command("OK", Command.OK, 1);
            tb.addCommand(ok);
            tb.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    String s = ((TextBox)d).getString();
                    if (s != null) { editorContent = s; editorDirty = true; }
                    midlet.getDisplay().setCurrent(DesktopUI.this);
                    repaint();
                }
            });
            midlet.getDisplay().setCurrent(tb);
        } else if (openApp == 3) {
            // Network URL input
            TextBox tb = new TextBox("URL", "http://", 256, TextField.URL);
            Command ok = new Command("OK", Command.OK, 1);
            tb.addCommand(ok);
            tb.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    final String url = ((TextBox)d).getString();
                    midlet.getDisplay().setCurrent(DesktopUI.this);
                    if (url != null && url.startsWith("http")) {
                        networkResult = "Connecting to " + url + "...\n";
                        repaint();
                        NetworkTask.httpGet(url, new NetworkTask.Callback() {
                            public void onResult(String r) {
                                networkResult = r.length() > 500 ? r.substring(0, 500) + "..." : r;
                                NetworkTask.downloadToFS(url, fs);
                                repaint();
                            }
                            public void onError(String e) {
                                networkResult = "Error: " + e;
                                repaint();
                            }
                        });
                    }
                }
            });
            midlet.getDisplay().setCurrent(tb);
        }
    }

    // ==================== ICON DRAWING ====================

    private void drawIcon(Graphics g, int x, int y, int size, int[] bmp, int color) {
        int px = size / 8;
        if (px < 1) px = 1;
        for (int row = 0; row < 8 && row < bmp.length; row++) {
            for (int col = 0; col < 8; col++) {
                if ((bmp[row] & (0x80 >> col)) != 0) {
                    g.setColor(color);
                    g.fillRect(x + col * px, y + row * px, px, px);
                }
            }
        }
    }

    // ==================== HELPERS ====================

    private String[] splitLines(String s) {
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

    public void shutdown() {
        running = false;
        if (clockThread != null) clockThread.interrupt();
    }
}
