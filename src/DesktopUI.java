import javax.microedition.lcdui.*;
import java.util.*;

/**
 * DesktopUI v1.2.0 - Enhanced Canvas-drawn graphical desktop for DashCMD.
 *
 * Enhancements over v1.1.1:
 *  - 5 selectable themes (Midnight, Ocean, Forest, Sunset, Hacker)
 *  - Animated starfield wallpaper (toggleable)
 *  - Window open/close animations
 *  - Toast notification system
 *  - Taskbar shows open apps, battery icon, memory indicator
 *  - Calculator app with full grid UI
 *  - System Monitor with live CPU graph & memory bar
 *  - File browser: context menu (rename, delete, new folder/file)
 *  - Editor: line numbers, scrollbar, line/char count
 *  - Settings: theme cycling, animation toggle, username/hostname edit
 *  - Network: URL history
 *  - Icon glow/pulse animation on selection
 *  - Drop shadows on icons and windows
 *  - Gradient title bars on app windows
 *  - Scrollbar indicators in all list views
 *  - Desktop clock widget (top-right)
 *  - Uptime tracker
 */
public class DesktopUI extends Canvas implements CommandListener {

    private TerminalOS midlet;
    private VirtualFS      fs;

    /* =========================================================
     *  THEME SYSTEM - 5 built-in colour palettes
     * ========================================================= */
    private int currentTheme = 0;
    private static final int THEME_COUNT = 5;

    //  { bgTop,     bgBot,     taskbar,   accent,    iconBg   }
    private int[][] themeColors = {
        {0x1A2A4A, 0x0D1117, 0x0D0D0D, 0x00BFFF, 0x1E3A5A}, // Midnight
        {0x0A3D62, 0x0C2461, 0x0A1931, 0x00D2FF, 0x1B4F72}, // Ocean
        {0x1B4332, 0x081C15, 0x0A1F0E, 0x2ECC71, 0x1E5631}, // Forest
        {0x4A1942, 0x1A0A1A, 0x120812, 0xFF6B6B, 0x5A2050}, // Sunset
        {0x0A0A0A, 0x000000, 0x050505, 0x00FF41, 0x0F0F0F}, // Hacker
    };

    private static final String[] THEME_NAMES = {
        "Midnight", "Ocean", "Forest", "Sunset", "Hacker"
    };

    // Active palette (set by applyTheme)
    private int BG_TOP, BG_BOT, TASKBAR_C, ACCENT, ICON_BG_C;

    // Fixed colours
    private static final int WHITE     = 0xFFFFFF;
    private static final int GREEN     = 0x00FF41;
    private static final int GREY      = 0x666666;
    private static final int DARK_GREY = 0x333333;
    private static final int CYAN      = 0x00BFFF;
    private static final int YELLOW    = 0xFFFF00;
    private static final int RED       = 0xFF4444;
    private static final int ORANGE    = 0xFF8800;
    private static final int PINK      = 0xFF69B4;

    private Font fontS, fontM;
    private int  W, H;

    /* =========================================================
     *  DESKTOP ICONS - 8 apps
     * ========================================================= */
    private int selectedIcon = 0;

    private String[] iconLabels = {
        "Terminal", "Files", "Editor", "Network",
        "Settings", "Logs",  "Calc",   "SysMon"
    };

    // 8×8 pixel-art bitmaps (one int per row, MSB = leftmost pixel)
    private int[][] iconBmps = {
        {0x00,0x7E,0x42,0x52,0x4A,0x42,0x7E,0x00}, // Terminal  >_
        {0x00,0x70,0x7E,0x7F,0x7F,0x7F,0x7E,0x00}, // Files     folder
        {0x3C,0x24,0x3C,0x24,0x24,0x24,0x3C,0x00}, // Editor    notepad
        {0x3C,0x42,0xFF,0x42,0x42,0xFF,0x42,0x3C}, // Network   globe
        {0x18,0x7E,0x5A,0xDB,0xDB,0x5A,0x7E,0x18}, // Settings  gear
        {0x3E,0x22,0x3A,0x22,0x3A,0x22,0x3E,0x00}, // Logs      doc
        {0x7E,0x7E,0x42,0x5A,0x5A,0x42,0x7E,0x00}, // Calc      keypad
        {0x00,0x40,0x60,0x50,0x48,0x44,0x42,0x7F}, // SysMon    graph
    };

    private int[] iconColors = {
        GREEN, CYAN, YELLOW, 0x00FF88, ORANGE, RED, PINK, 0x00FFAA
    };

    /* =========================================================
     *  ANIMATED STARFIELD
     * ========================================================= */
    private static final int NUM_STARS = 25;
    private int[] starX, starY, starSpd;
    private boolean wallpaperAnim = true;
    private Random rng = new Random();

    /* =========================================================
     *  TOAST NOTIFICATION SYSTEM
     * ========================================================= */
    private String toastMsg   = "";
    private int    toastColor = GREEN;
    private long   toastExp   = 0;

    /* =========================================================
     *  WINDOW / ANIMATION STATE
     * ========================================================= */
    private int     animFrame     = 0;
    private int     winAnimStep   = 10;   // 0→10 = opening
    private boolean winAnimating  = false;

    private int     openApp       = -1;   // -1 = desktop
    private Vector  openApps      = new Vector(); // taskbar indicators

    /* =========================================================
     *  APP-SPECIFIC STATE
     * ========================================================= */

    // ---- Files ----
    private int     fileIdx        = 0;
    private int     fileScroll     = 0;
    private boolean fileCtxMenu    = false;
    private int     fileCtxIdx     = 0;
    private String[] fileCtxOpts   = {
        "Open", "Rename", "Delete", "New Folder", "New File"
    };

    // ---- Editor ----
    private String  editorContent  = "";
    private boolean editorDirty    = false;
    private String  editorFile     = "";
    private int     editorScrollY  = 0;

    // ---- Network ----
    private String  netResult      = "";
    private Vector  netHistory     = new Vector();

    // ---- Settings ----
    private int     settIdx        = 0;
    private String[] settItems     = {
        "Theme", "Animation", "Username", "Hostname", "About"
    };

    // ---- Calculator ----
    private String  calcDisp    = "0";
    private String  calcOper    = "";
    private char    calcOp      = ' ';
    private boolean calcNewNum  = true;
    private int     calcSelBtn  = 0;
    private static final String[] CALC_BTNS = {
         "C","(",")","/",
        "7","8","9","*",
        "4","5","6","-",
        "1","2","3","+",
        "0",".","=","BS"
    };
    private static final int CALC_COLS = 4;

    // ---- SysMon ----
    private int[] cpuHist    = new int[30];
    private int   cpuHistIdx = 0;

    /* =========================================================
     *  MISC
     * ========================================================= */
    private long    uptimeStart;
    private Thread  tickThread;
    private volatile boolean running;

    private Command cmdExit, cmdBack, cmdOpen, cmdSave, cmdInput, cmdMenu;

    // =====================================================
    //  CONSTRUCTOR
    // =====================================================

    public DesktopUI(TerminalOS midlet, VirtualFS fs) {
        this.midlet      = midlet;
        this.fs          = fs;
        this.running     = true;
        this.uptimeStart = System.currentTimeMillis();

        fontS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontM = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD,  Font.SIZE_MEDIUM);

        applyTheme(currentTheme);
        initStars();

        cmdExit  = new Command("Terminal", Command.EXIT,   1);
        cmdBack  = new Command("Back",     Command.BACK,   2);
        cmdOpen  = new Command("Open",     Command.OK,     3);
        cmdSave  = new Command("Save",     Command.SCREEN, 4);
        cmdInput = new Command("Input",    Command.SCREEN, 5);
        cmdMenu  = new Command("Menu",     Command.SCREEN, 6);

        addCommand(cmdExit);
        addCommand(cmdOpen);
        setCommandListener(this);

        // 150 ms tick for clock, stars, animations
        tickThread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try { Thread.sleep(150); } catch (InterruptedException e) { return; }
                    animFrame++;
                    if (wallpaperAnim && openApp < 0) moveStars();
                    if (winAnimating) {
                        winAnimStep += 2;
                        if (winAnimStep >= 10) { winAnimStep = 10; winAnimating = false; }
                    }
                    // Fake CPU sample every ~1 s
                    if (animFrame % 7 == 0) {
                        cpuHist[cpuHistIdx] = 10 + Math.abs(rng.nextInt() % 60);
                        cpuHistIdx = (cpuHistIdx + 1) % cpuHist.length;
                    }
                    repaint();
                }
            }
        });
        tickThread.start();
    }

    // =====================================================
    //  THEME / STARS HELPERS
    // =====================================================

    private void applyTheme(int t) {
        currentTheme = t % THEME_COUNT;
        BG_TOP    = themeColors[currentTheme][0];
        BG_BOT    = themeColors[currentTheme][1];
        TASKBAR_C = themeColors[currentTheme][2];
        ACCENT    = themeColors[currentTheme][3];
        ICON_BG_C = themeColors[currentTheme][4];
    }

    private void initStars() {
        starX   = new int[NUM_STARS];
        starY   = new int[NUM_STARS];
        starSpd = new int[NUM_STARS];
        for (int i = 0; i < NUM_STARS; i++) resetStar(i, true);
    }

    private void resetStar(int i, boolean randomY) {
        starX[i]   = Math.abs(rng.nextInt() % 240);
        starY[i]   = randomY ? Math.abs(rng.nextInt() % 320) : 0;
        starSpd[i] = 1 + Math.abs(rng.nextInt() % 3);
    }

    private void moveStars() {
        for (int i = 0; i < NUM_STARS; i++) {
            starY[i] += starSpd[i];
            if (starY[i] > 320) resetStar(i, false);
        }
    }

    private void showToast(String msg, int color) {
        toastMsg   = msg;
        toastColor = color;
        toastExp   = System.currentTimeMillis() + 2500;
    }

    // =====================================================
    //  PAINT DISPATCH
    // =====================================================

    protected void paint(Graphics g) {
        W = getWidth();
        H = getHeight();

        if (openApp >= 0) {
            paintWindow(g);
        } else {
            paintDesktop(g);
        }

        // Toast overlay
        long now = System.currentTimeMillis();
        if (toastMsg.length() > 0 && now < toastExp) {
            paintToast(g);
        } else if (toastMsg.length() > 0) {
            toastMsg = "";
        }
    }

    // =====================================================
    //  DESKTOP
    // =====================================================

    private void paintDesktop(Graphics g) {
        int bgH = H - fontS.getHeight() - 8;

        // --- gradient ---
        for (int y = 0; y < bgH; y++) {
            g.setColor(lerpColor(BG_TOP, BG_BOT, y, bgH));
            g.drawLine(0, y, W, y);
        }

        // --- animated stars ---
        if (wallpaperAnim) {
            for (int i = 0; i < NUM_STARS; i++) {
                if (starY[i] < bgH) {
                    int b = 0x44 + starSpd[i] * 0x30;
                    if (b > 0xFF) b = 0xFF;
                    g.setColor((b << 16) | (b << 8) | b);
                    g.fillRect(starX[i] % W, starY[i], starSpd[i], starSpd[i]);
                }
            }
        }

        // --- desktop clock widget (top-right) ---
        paintClockWidget(g);

        // --- icon grid ---
        int iconSz = Math.max(20, Math.min(32, (W - 8) / 3 - 4));
        int cols   = Math.max(2, (W - 4) / (iconSz + 8));
        int cellW  = (W - 4) / cols;
        int gridY  = fontM.getHeight() + 10;

        // Pulsing factor for selected icon: 0-8-0-8 …
        int pulse = animFrame % 16;
        if (pulse > 8) pulse = 16 - pulse;

        for (int i = 0; i < iconLabels.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int ix  = 4 + col * cellW + (cellW - iconSz) / 2;
            int iy  = gridY + row * (iconSz + fontS.getHeight() + 12);
            boolean sel = (i == selectedIcon);

            // --- selection glow ---
            if (sel) {
                int glowExtra = pulse / 2;
                g.setColor(ACCENT);
                g.drawRoundRect(ix - 4 - glowExtra, iy - 4 - glowExtra,
                        iconSz + 8 + glowExtra * 2,
                        iconSz + 8 + glowExtra * 2, 6, 6);
                g.drawRoundRect(ix - 3, iy - 3, iconSz + 6, iconSz + 6, 5, 5);
            }

            // --- shadow ---
            g.setColor(0x050505);
            g.fillRoundRect(ix + 1, iy + 2, iconSz, iconSz, 4, 4);

            // --- icon bg ---
            g.setColor(sel ? ACCENT : ICON_BG_C);
            g.fillRoundRect(ix - 1, iy - 1, iconSz + 2, iconSz + 2, 4, 4);

            // --- bitmap ---
            drawBitmap(g, ix, iy, iconSz, iconBmps[i], iconColors[i]);

            // --- label (with shadow) ---
            g.setFont(fontS);
            int lw = fontS.stringWidth(iconLabels[i]);
            int lx = 4 + col * cellW + (cellW - lw) / 2;
            int ly = iy + iconSz + 3;
            g.setColor(0x000000);
            g.drawString(iconLabels[i], lx + 1, ly + 1, Graphics.TOP | Graphics.LEFT);
            g.setColor(sel ? WHITE : GREY);
            g.drawString(iconLabels[i], lx, ly, Graphics.TOP | Graphics.LEFT);
        }

        // --- taskbar ---
        paintTaskbar(g);
    }

    private void paintClockWidget(Graphics g) {
        String time = AppStorage.formatHMS(System.currentTimeMillis());
        g.setFont(fontM);
        int tw = fontM.stringWidth(time);
        g.setColor(0x111111);
        g.fillRoundRect(W - tw - 14, 2, tw + 10, fontM.getHeight() + 4, 5, 5);
        g.setColor(ACCENT);
        g.drawRoundRect(W - tw - 14, 2, tw + 10, fontM.getHeight() + 4, 5, 5);
        g.setColor(WHITE);
        g.drawString(time, W - tw - 9, 4, Graphics.TOP | Graphics.LEFT);
    }

    // =====================================================
    //  TASKBAR  (bottom bar with status area)
    // =====================================================

    private void paintTaskbar(Graphics g) {
        int tbH = fontS.getHeight() + 8;
        int tbY = H - tbH;
        g.setFont(fontS);

        // background
        g.setColor(TASKBAR_C);
        g.fillRect(0, tbY, W, tbH);
        // accent line
        g.setColor(ACCENT);
        g.drawLine(0, tbY, W, tbY);

        // --- "DashOS" button ---
        int btnW = fontS.stringWidth("DashOS") + 8;
        g.setColor(ACCENT);
        g.fillRoundRect(2, tbY + 2, btnW, tbH - 4, 3, 3);
        g.setColor(WHITE);
        g.drawString("DashOS", 6, tbY + 4, Graphics.TOP | Graphics.LEFT);

        // --- open-app pills ---
        int pillX = btnW + 6;
        for (int i = 0; i < openApps.size(); i++) {
            int ai = ((Integer) openApps.elementAt(i)).intValue();
            if (ai >= iconLabels.length) continue;
            boolean active = (ai == openApp);
            int pw = fontS.stringWidth(iconLabels[ai]) + 6;
            g.setColor(active ? ACCENT : DARK_GREY);
            g.fillRoundRect(pillX, tbY + 2, pw, tbH - 4, 2, 2);
            g.setColor(active ? WHITE : GREY);
            g.drawString(iconLabels[ai], pillX + 3, tbY + 4, Graphics.TOP | Graphics.LEFT);
            pillX += pw + 2;
        }

        // --- right-side status cluster ---
        int rx = W - 2;

        // clock
        String time = AppStorage.formatHMS(System.currentTimeMillis());
        int timeW = fontS.stringWidth(time);
        g.setColor(GREEN);
        g.drawString(time, rx - timeW, tbY + 4, Graphics.TOP | Graphics.LEFT);
        rx -= timeW + 6;

        // memory
        long freeMem = Runtime.getRuntime().freeMemory() / 1024;
        String memTxt = freeMem + "K";
        g.setColor(freeMem < 50 ? RED : (freeMem < 200 ? YELLOW : GREY));
        int mw = fontS.stringWidth(memTxt);
        g.drawString(memTxt, rx - mw, tbY + 4, Graphics.TOP | Graphics.LEFT);
        rx -= mw + 6;

        // battery icon (decorative)
        int batW = 14, batH = tbH - 6;
        int batX = rx - batW - 2, batY2 = tbY + 3;
        g.setColor(GREY);
        g.drawRect(batX, batY2, batW, batH);
        g.fillRect(batX + batW + 1, batY2 + batH / 4, 2, batH / 2);
        g.setColor(GREEN);
        g.fillRect(batX + 1, batY2 + 1, batW - 3, batH - 1);
    }

    // =====================================================
    //  TOAST OVERLAY
    // =====================================================

    private void paintToast(Graphics g) {
        g.setFont(fontS);
        int pw = fontS.stringWidth(toastMsg) + 16;
        int ph = fontS.getHeight() + 10;
        int px = (W - pw) / 2;
        int py = H - fontS.getHeight() - 12 - ph - 6;
        // shadow
        g.setColor(0x000000);
        g.fillRoundRect(px + 2, py + 2, pw, ph, 6, 6);
        // bg
        g.setColor(DARK_GREY);
        g.fillRoundRect(px, py, pw, ph, 6, 6);
        // border
        g.setColor(toastColor);
        g.drawRoundRect(px, py, pw, ph, 6, 6);
        // text
        g.setColor(toastColor);
        g.drawString(toastMsg, px + 8, py + 5, Graphics.TOP | Graphics.LEFT);
    }

    // =====================================================
    //  WINDOW FRAME  (title-bar + border + content clip)
    // =====================================================

    private void paintWindow(Graphics g) {
        int s = winAnimating ? winAnimStep : 10;

        // scaled rect during animation
        int wx = W * (10 - s) / 20;
        int wy = H * (10 - s) / 20;
        int ww = W * s / 10;
        int wh = H * s / 10;

        if (s < 10) {
            g.setColor(BG_BOT);
            g.fillRect(0, 0, W, H);
        }

        // shadow
        g.setColor(0x050505);
        g.fillRect(wx + 3, wy + 3, ww, wh);

        // body
        g.setColor(BG_BOT);
        g.fillRect(wx, wy, ww, wh);

        // title bar gradient
        int titleH = fontM.getHeight() + 6;
        for (int y = 0; y < titleH; y++) {
            g.setColor(lerpColor(TASKBAR_C, ICON_BG_C, y, titleH));
            g.drawLine(wx, wy + y, wx + ww, wy + y);
        }

        // border
        g.setColor(ACCENT);
        g.drawRect(wx, wy, ww - 1, wh - 1);
        g.drawLine(wx, wy + titleH, wx + ww, wy + titleH);

        // title text
        g.setFont(fontM);
        g.setColor(WHITE);
        String title = (openApp < iconLabels.length) ? iconLabels[openApp] : "App";
        g.drawString(title, wx + 6, wy + 3, Graphics.TOP | Graphics.LEFT);

        // close [X] button
        int btnSz = titleH - 4;
        g.setColor(RED);
        g.fillRoundRect(wx + ww - btnSz - 6, wy + 2, btnSz, btnSz, 3, 3);
        g.setColor(WHITE);
        g.setFont(fontS);
        g.drawString("X", wx + ww - btnSz - 3, wy + 4, Graphics.TOP | Graphics.LEFT);

        // minimise [-] button
        g.setColor(YELLOW);
        g.fillRoundRect(wx + ww - btnSz * 2 - 10, wy + 2, btnSz, btnSz, 3, 3);
        g.setColor(0x000000);
        g.drawString("-", wx + ww - btnSz * 2 - 7, wy + 4, Graphics.TOP | Graphics.LEFT);

        if (s < 10) { paintTaskbar(g); return; }

        int cy = wy + titleH + 2;
        int ch = wh - titleH - fontS.getHeight() - 12;

        g.setClip(wx + 1, cy, ww - 2, ch);

        switch (openApp) {
            case 0: paintAppTerminal(g, wx, cy, ww);         break;
            case 1: paintAppFiles(g, wx, cy, ww, ch);        break;
            case 2: paintAppEditor(g, wx, cy, ww, ch);       break;
            case 3: paintAppNetwork(g, wx, cy, ww, ch);      break;
            case 4: paintAppSettings(g, wx, cy, ww, ch);     break;
            case 5: paintAppLogs(g, wx, cy, ww, ch);         break;
            case 6: paintAppCalc(g, wx, cy, ww, ch);         break;
            case 7: paintAppSysMon(g, wx, cy, ww, ch);       break;
        }

        g.setClip(0, 0, W, H);
        paintTaskbar(g);
    }

    // =====================================================
    //  APP 0 — TERMINAL (stub → returns to CLI)
    // =====================================================

    private void paintAppTerminal(Graphics g, int x, int y, int w) {
        g.setFont(fontS);
        g.setColor(GREEN);
        g.drawString("Returning to CLI...", x + 6, y + 6, Graphics.TOP | Graphics.LEFT);
        g.setColor(GREY);
        g.drawString("Press Back for desktop", x + 6, y + fontS.getHeight() + 10,
                Graphics.TOP | Graphics.LEFT);
    }

    // =====================================================
    //  APP 1 — FILE BROWSER
    // =====================================================

    private void paintAppFiles(Graphics g, int wx, int y, int ww, int wh) {
        g.setFont(fontS);
        int fh = fontS.getHeight();
        String path = fs.getCurrentPath();

        // breadcrumb bar
        g.setColor(DARK_GREY);
        g.fillRect(wx + 2, y, ww - 4, fh + 4);
        g.setColor(ACCENT);
        g.drawString(" " + path, wx + 4, y + 2, Graphics.TOP | Graphics.LEFT);
        y += fh + 6;

        // context menu overlay
        if (fileCtxMenu) {
            paintFileCtx(g, wx, y, ww);
            return;
        }

        String[] ch = fs.listChildren(path);
        int maxShow = (wh - fh - 14) / (fh + 2);
        if (maxShow < 1) maxShow = 1;

        // keep selection visible
        if (fileIdx >= fileScroll + maxShow) fileScroll = fileIdx - maxShow + 1;
        if (fileIdx < fileScroll)            fileScroll = fileIdx;

        int end = Math.min(ch.length, fileScroll + maxShow);
        for (int i = fileScroll; i < end; i++) {
            boolean sel   = (i == fileIdx);
            boolean isDir = fs.isDir(ch[i]);
            String  name  = fs.nameOf(ch[i]);

            if (sel) {
                g.setColor(ICON_BG_C);
                g.fillRect(wx + 2, y, ww - 4, fh + 1);
                g.setColor(ACCENT);
                g.fillRect(wx + 2, y, 2, fh + 1);
            }

            // type badge
            g.setColor(isDir ? CYAN : GREEN);
            g.drawString(isDir ? "[D]" : "[F]", wx + 6, y, Graphics.TOP | Graphics.LEFT);

            // name
            g.setColor(sel ? WHITE : (isDir ? CYAN : GREEN));
            g.drawString(name, wx + 6 + fontS.stringWidth("[D] "), y,
                    Graphics.TOP | Graphics.LEFT);

            y += fh + 2;
        }

        // scrollbar
        if (ch.length > maxShow) {
            int sbH     = maxShow * (fh + 2);
            int sbTop   = y - (end - fileScroll) * (fh + 2);
            int thumb   = Math.max(8, sbH * maxShow / ch.length);
            int thumbY  = sbTop + sbH * fileScroll / ch.length;
            g.setColor(DARK_GREY);
            g.fillRect(wx + ww - 7, sbTop, 4, sbH);
            g.setColor(ACCENT);
            g.fillRect(wx + ww - 7, thumbY, 4, thumb);
        }

        // footer
        g.setColor(GREY);
        g.drawString(ch.length + " items  FIRE:Open 7:Menu", wx + 6, y + 4,
                Graphics.TOP | Graphics.LEFT);
    }

    private void paintFileCtx(Graphics g, int wx, int y, int ww) {
        int fh   = fontS.getHeight();
        int mw   = ww * 2 / 3;
        int mx   = wx + (ww - mw) / 2;
        int mh   = fileCtxOpts.length * (fh + 4) + 6;

        g.setColor(0x000000);
        g.fillRect(mx + 2, y + 2, mw, mh);
        g.setColor(DARK_GREY);
        g.fillRect(mx, y, mw, mh);
        g.setColor(ACCENT);
        g.drawRect(mx, y, mw, mh);

        for (int i = 0; i < fileCtxOpts.length; i++) {
            int iy = y + 3 + i * (fh + 4);
            if (i == fileCtxIdx) {
                g.setColor(ACCENT);
                g.fillRect(mx + 1, iy, mw - 2, fh + 3);
                g.setColor(WHITE);
            } else {
                g.setColor(GREY);
            }
            g.drawString(fileCtxOpts[i], mx + 8, iy + 1, Graphics.TOP | Graphics.LEFT);
        }
    }

    // =====================================================
    //  APP 2 — TEXT EDITOR (line numbers, scrollbar)
    // =====================================================

    private void paintAppEditor(Graphics g, int wx, int y, int ww, int wh) {
        g.setFont(fontS);
        int fh = fontS.getHeight();

        // info bar
        g.setColor(DARK_GREY);
        g.fillRect(wx + 2, y, ww - 4, fh + 2);
        g.setColor(editorDirty ? YELLOW : ACCENT);
        String fn = editorFile.length() > 0 ? fs.nameOf(editorFile) : "(new)";
        g.drawString(" " + fn + (editorDirty ? " [modified]" : ""),
                wx + 4, y + 1, Graphics.TOP | Graphics.LEFT);
        y += fh + 4;

        if (editorContent.length() == 0) {
            g.setColor(GREY);
            g.drawString("(empty - press Input)", wx + 6, y, Graphics.TOP | Graphics.LEFT);
            return;
        }

        String[] lines = splitLines(editorContent);
        int maxLn = (wh - fh - 10) / fh;
        if (maxLn < 1) maxLn = 1;
        if (editorScrollY > Math.max(0, lines.length - maxLn))
            editorScrollY = Math.max(0, lines.length - maxLn);

        int gutterW = fontS.stringWidth("999") + 6;

        for (int i = editorScrollY;
             i < Math.min(lines.length, editorScrollY + maxLn); i++) {
            int ly = y + (i - editorScrollY) * fh;

            // gutter
            g.setColor(0x111111);
            g.fillRect(wx + 2, ly, gutterW, fh);
            g.setColor(GREY);
            String ln = String.valueOf(i + 1);
            g.drawString(ln, wx + gutterW - fontS.stringWidth(ln) - 2, ly,
                    Graphics.TOP | Graphics.LEFT);

            // content
            g.setColor(WHITE);
            g.drawString(lines[i], wx + gutterW + 4, ly, Graphics.TOP | Graphics.LEFT);
        }

        // scrollbar
        if (lines.length > maxLn) {
            int sbH   = maxLn * fh;
            int thumb  = Math.max(6, sbH * maxLn / lines.length);
            int thumbY = y + sbH * editorScrollY / lines.length;
            g.setColor(DARK_GREY);
            g.fillRect(wx + ww - 7, y, 4, sbH);
            g.setColor(ACCENT);
            g.fillRect(wx + ww - 7, thumbY, 4, thumb);
        }

        // status
        g.setColor(GREY);
        g.drawString("L:" + lines.length + " C:" + editorContent.length(),
                wx + gutterW + 4, y + maxLn * fh + 2, Graphics.TOP | Graphics.LEFT);
    }

    // =====================================================
    //  APP 3 — NETWORK
    // =====================================================

    private void paintAppNetwork(Graphics g, int wx, int y, int ww, int wh) {
        g.setFont(fontS);
        int fh = fontS.getHeight();

        g.setColor(ACCENT);
        g.drawString("Network Browser", wx + 6, y + 2, Graphics.TOP | Graphics.LEFT);
        y += fh + 4;

        // history
        if (netHistory.size() > 0) {
            g.setColor(GREY);
            g.drawString("History:", wx + 6, y, Graphics.TOP | Graphics.LEFT);
            y += fh + 2;
            int shown = Math.min(3, netHistory.size());
            for (int i = netHistory.size() - shown; i < netHistory.size(); i++) {
                g.setColor(DARK_GREY);
                g.drawString("  " + (String) netHistory.elementAt(i),
                        wx + 6, y, Graphics.TOP | Graphics.LEFT);
                y += fh;
            }
            y += 4;
        }

        if (netResult.length() == 0) {
            g.setColor(GREY);
            g.drawString("Press Input to enter URL", wx + 6, y, Graphics.TOP | Graphics.LEFT);
        } else {
            String[] lines = splitLines(netResult);
            int show = Math.min(lines.length, (wh - (y - (H - wh))) / fh);
            for (int i = 0; i < show; i++) {
                g.setColor(lines[i].startsWith("Err") ? RED : GREEN);
                g.drawString(lines[i], wx + 6, y, Graphics.TOP | Graphics.LEFT);
                y += fh;
            }
        }
    }

    // =====================================================
    //  APP 4 — SETTINGS
    // =====================================================

    private void paintAppSettings(Graphics g, int wx, int y, int ww, int wh) {
        g.setFont(fontS);
        int fh = fontS.getHeight();

        for (int i = 0; i < settItems.length; i++) {
            int iy = y + i * (fh + 8);
            boolean sel = (i == settIdx);

            if (sel) {
                g.setColor(ICON_BG_C);
                g.fillRect(wx + 2, iy, ww - 4, fh + 6);
                g.setColor(ACCENT);
                g.fillRect(wx + 2, iy, 3, fh + 6);
            }

            g.setColor(sel ? WHITE : GREY);
            g.drawString(settItems[i], wx + 12, iy + 3, Graphics.TOP | Graphics.LEFT);

            // current value on the right
            g.setColor(ACCENT);
            String v = "";
            switch (i) {
                case 0: v = THEME_NAMES[currentTheme];          break;
                case 1: v = wallpaperAnim ? "ON" : "OFF";       break;
                case 2: v = fs.getUsername();                    break;
                case 3: v = fs.getHostname();                   break;
                case 4: v = "v1.2.0";                           break;
            }
            if (v.length() > 0) {
                g.drawString(v, wx + ww - fontS.stringWidth(v) - 10,
                        iy + 3, Graphics.TOP | Graphics.LEFT);
            }
        }

        // divider
        int divY = y + settItems.length * (fh + 8) + 4;
        g.setColor(GREY);
        g.drawLine(wx + 12, divY, wx + ww - 12, divY);
        divY += 8;

        // system info
        String[] info = {
            "Path:    " + fs.getCurrentPath(),
            "Storage: RMS",
            "Uptime:  " + fmtUptime(),
            "Root:    " + (fs.isRoot() ? "yes" : "no"),
            "Memory:  " + (Runtime.getRuntime().freeMemory() / 1024) + "K free",
            "Time:    " + AppStorage.formatTime(System.currentTimeMillis())
        };
        for (int i = 0; i < info.length; i++) {
            g.setColor(i % 2 == 0 ? WHITE : GREY);
            g.drawString(info[i], wx + 12, divY, Graphics.TOP | Graphics.LEFT);
            divY += fh + 2;
        }
    }

    // =====================================================
    //  APP 5 — LOGS
    // =====================================================

    private void paintAppLogs(Graphics g, int wx, int y, int ww, int wh) {
        g.setFont(fontS);
        int fh = fontS.getHeight();

        g.setColor(ACCENT);
        g.drawString("System Log", wx + 6, y + 2, Graphics.TOP | Graphics.LEFT);
        y += fh + 4;

        String log = AppStorage.readBootLog();
        String[] lines = splitLines(log);
        int maxShow = (wh - fh - 6) / fh;
        int start   = Math.max(0, lines.length - maxShow);

        for (int i = start; i < lines.length; i++) {
            String l = lines[i];
            if      (l.indexOf("ERR")  >= 0) g.setColor(RED);
            else if (l.indexOf("WARN") >= 0) g.setColor(YELLOW);
            else if (l.indexOf("INFO") >= 0) g.setColor(GREEN);
            else                             g.setColor(GREY);
            g.drawString(l, wx + 6, y, Graphics.TOP | Graphics.LEFT);
            y += fh;
        }
    }

    // =====================================================
    //  APP 6 — CALCULATOR
    // =====================================================

    private void paintAppCalc(Graphics g, int wx, int y, int ww, int wh) {
        g.setFont(fontM);
        int fh = fontM.getHeight();

        // display
        g.setColor(0x111122);
        g.fillRect(wx + 6, y + 2, ww - 12, fh + 10);
        g.setColor(ACCENT);
        g.drawRect(wx + 6, y + 2, ww - 12, fh + 10);
        g.setColor(WHITE);
        int dw = fontM.stringWidth(calcDisp);
        g.drawString(calcDisp, wx + ww - dw - 10, y + 6, Graphics.TOP | Graphics.LEFT);
        // show operator
        if (calcOp != ' ') {
            g.setColor(GREY);
            g.drawString("" + calcOp, wx + 10, y + 6, Graphics.TOP | Graphics.LEFT);
        }
        y += fh + 16;

        // button grid
        g.setFont(fontS);
        int btnW = (ww - 16) / CALC_COLS;
        int btnH = Math.min((wh - fh - 24) / 5, fontS.getHeight() + 10);

        for (int i = 0; i < CALC_BTNS.length; i++) {
            int col = i % CALC_COLS;
            int row = i / CALC_COLS;
            int bx  = wx + 6 + col * btnW + 1;
            int by  = y + row * btnH + 1;
            boolean sel = (i == calcSelBtn);

            boolean isOp = isCalcOp(CALC_BTNS[i]);

            // button bg
            g.setColor(sel ? ACCENT : (isOp ? DARK_GREY : ICON_BG_C));
            g.fillRoundRect(bx, by, btnW - 2, btnH - 2, 3, 3);

            // label
            g.setColor(sel ? WHITE : (isOp ? ORANGE : WHITE));
            int sw = fontS.stringWidth(CALC_BTNS[i]);
            g.drawString(CALC_BTNS[i],
                    bx + (btnW - sw) / 2,
                    by + (btnH - fontS.getHeight()) / 2,
                    Graphics.TOP | Graphics.LEFT);
        }
    }

    private boolean isCalcOp(String b) {
        return b.equals("C") || b.equals("BS") || b.equals("+") ||
               b.equals("-") || b.equals("*") || b.equals("/") ||
               b.equals("=") || b.equals("(") || b.equals(")");
    }

    // =====================================================
    //  APP 7 — SYSTEM MONITOR  (CPU graph + memory bar)
    // =====================================================

    private void paintAppSysMon(Graphics g, int wx, int y, int ww, int wh) {
        g.setFont(fontS);
        int fh = fontS.getHeight();

        // CPU title
        g.setColor(ACCENT);
        g.drawString("CPU Usage", wx + 6, y + 2, Graphics.TOP | Graphics.LEFT);
        y += fh + 4;

        // graph area
        int gW = ww - 16;
        int gH = 44;

        g.setColor(0x0A0A0A);
        g.fillRect(wx + 6, y, gW, gH);
        g.setColor(DARK_GREY);
        g.drawRect(wx + 6, y, gW, gH);

        // grid lines at 25%, 50%, 75%
        for (int q = 1; q < 4; q++) {
            g.setColor(0x1A1A1A);
            int gy = y + gH * q / 4;
            g.drawLine(wx + 7, gy, wx + 6 + gW - 1, gy);
        }

        // bars
        int barW = Math.max(1, gW / cpuHist.length);
        for (int i = 0; i < cpuHist.length; i++) {
            int idx  = (cpuHistIdx + i) % cpuHist.length;
            int barH = cpuHist[idx] * gH / 100;
            int barC = cpuHist[idx] > 80 ? RED : (cpuHist[idx] > 50 ? YELLOW : GREEN);
            g.setColor(barC);
            g.fillRect(wx + 6 + i * barW, y + gH - barH, barW - 1, barH);
        }
        y += gH + 8;

        // Memory
        Runtime rt   = Runtime.getRuntime();
        long total   = rt.totalMemory();
        long free    = rt.freeMemory();
        long used    = total - free;

        g.setColor(ACCENT);
        g.drawString("Memory", wx + 6, y, Graphics.TOP | Graphics.LEFT);
        y += fh + 2;

        int mbW = ww - 16;
        g.setColor(0x0A0A0A);
        g.fillRect(wx + 6, y, mbW, 14);
        g.setColor(DARK_GREY);
        g.drawRect(wx + 6, y, mbW, 14);

        int usedW = (int)(mbW * used / total);
        g.setColor(usedW > mbW * 3 / 4 ? RED : (usedW > mbW / 2 ? YELLOW : CYAN));
        g.fillRect(wx + 7, y + 1, usedW - 1, 12);
        y += 18;

        g.setColor(WHITE);
        g.drawString("Used: " + (used / 1024) + "K / " + (total / 1024) + "K",
                wx + 6, y, Graphics.TOP | Graphics.LEFT);
        y += fh + 2;
        g.setColor(GREEN);
        g.drawString("Free: " + (free / 1024) + "K", wx + 6, y, Graphics.TOP | Graphics.LEFT);
        y += fh + 8;

        // uptime
        g.setColor(GREY);
        g.drawString("Uptime:  " + fmtUptime(), wx + 6, y, Graphics.TOP | Graphics.LEFT);
        y += fh + 2;
        g.drawString("Threads: " + Thread.activeCount(), wx + 6, y, Graphics.TOP | Graphics.LEFT);
    }

    // =====================================================
    //  KEY HANDLING
    // =====================================================

    protected void keyPressed(int keyCode) {
        int ga = -1;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}

        if (openApp < 0) {
            desktopKeys(ga, keyCode);
        } else {
            appKeys(ga, keyCode);
        }
        repaint();
    }

    private void desktopKeys(int ga, int keyCode) {
        int iconSz = Math.max(20, Math.min(32, (W - 8) / 3 - 4));
        int cols   = Math.max(2, (W - 4) / (iconSz + 8));

        if (ga == LEFT  && selectedIcon > 0)                       selectedIcon--;
        if (ga == RIGHT && selectedIcon < iconLabels.length - 1)   selectedIcon++;
        if (ga == UP    && selectedIcon >= cols)                    selectedIcon -= cols;
        if (ga == DOWN  && selectedIcon + cols < iconLabels.length) selectedIcon += cols;
        if (ga == FIRE || keyCode == Canvas.KEY_NUM5) openIcon();
    }

    private void appKeys(int ga, int keyCode) {
        switch (openApp) {
            case 1: keysFiles(ga, keyCode);    break;
            case 2: keysEditor(ga, keyCode);   break;
            case 4: keysSettings(ga, keyCode); break;
            case 6: keysCalc(ga, keyCode);     break;
            default: break;
        }
    }

    // --- Files keys ---

    private void keysFiles(int ga, int keyCode) {
        if (fileCtxMenu) {
            if (ga == UP   && fileCtxIdx > 0) fileCtxIdx--;
            if (ga == DOWN && fileCtxIdx < fileCtxOpts.length - 1) fileCtxIdx++;
            if (ga == FIRE || keyCode == Canvas.KEY_NUM5) execFileCtx();
            if (ga == LEFT || keyCode == Canvas.KEY_NUM7) fileCtxMenu = false;
            return;
        }

        String[] ch = fs.listChildren(fs.getCurrentPath());
        if (ga == UP   && fileIdx > 0)            fileIdx--;
        if (ga == DOWN && fileIdx < ch.length - 1) fileIdx++;
        if (ga == FIRE || keyCode == Canvas.KEY_NUM5) openSelectedFile();
        if (ga == LEFT) { fs.cd(".."); fileIdx = 0; fileScroll = 0; }
        if (keyCode == Canvas.KEY_NUM7) { fileCtxMenu = true; fileCtxIdx = 0; }
    }

    // --- Editor keys ---

    private void keysEditor(int ga, int keyCode) {
        String[] lines = splitLines(editorContent);
        int maxLn = Math.max(1, (H - 80) / fontS.getHeight());
        if (ga == UP   && editorScrollY > 0)                     editorScrollY--;
        if (ga == DOWN && editorScrollY < lines.length - maxLn)  editorScrollY++;
    }

    // --- Settings keys ---

    private void keysSettings(int ga, int keyCode) {
        if (ga == UP   && settIdx > 0)                    settIdx--;
        if (ga == DOWN && settIdx < settItems.length - 1) settIdx++;
        if (ga == FIRE || keyCode == Canvas.KEY_NUM5)     execSettings();
    }

    // --- Calculator keys ---

    private void keysCalc(int ga, int keyCode) {
        int total = CALC_BTNS.length;
        if (ga == LEFT  && calcSelBtn > 0)                  calcSelBtn--;
        if (ga == RIGHT && calcSelBtn < total - 1)          calcSelBtn++;
        if (ga == UP    && calcSelBtn >= CALC_COLS)          calcSelBtn -= CALC_COLS;
        if (ga == DOWN  && calcSelBtn + CALC_COLS < total)   calcSelBtn += CALC_COLS;
        if (ga == FIRE  || keyCode == Canvas.KEY_NUM5)      pressCalcBtn();

        // direct number keys
        if (keyCode >= Canvas.KEY_NUM0 && keyCode <= Canvas.KEY_NUM9) {
            calcDigit((char) ('0' + (keyCode - Canvas.KEY_NUM0)));
        }
        if (keyCode == Canvas.KEY_STAR)  calcDoOp('*');
        if (keyCode == Canvas.KEY_POUND) calcEquals();
    }

    // =====================================================
    //  ACTION IMPLEMENTATIONS
    // =====================================================

    private void openIcon() {
        openApp      = selectedIcon;
        winAnimStep  = 0;
        winAnimating = true;

        Integer ai = new Integer(openApp);
        if (!openApps.contains(ai)) openApps.addElement(ai);

        removeCommand(cmdOpen);
        addCommand(cmdBack);

        switch (openApp) {
            case 1: addCommand(cmdMenu);  break;
            case 2: addCommand(cmdSave); addCommand(cmdInput); break;
            case 3: addCommand(cmdInput); break;
        }

        if (openApp == 0) {
            openApp = -1;
            shutdown();
            midlet.showTerminal();
        }
    }

    private void closeApp() {
        openApps.removeElement(new Integer(openApp));
        openApp = -1;
        removeCommand(cmdBack);
        removeCommand(cmdSave);
        removeCommand(cmdInput);
        removeCommand(cmdMenu);
        addCommand(cmdOpen);
    }

    private void openSelectedFile() {
        String[] ch = fs.listChildren(fs.getCurrentPath());
        if (fileIdx >= ch.length) return;
        String path = ch[fileIdx];

        if (fs.isDir(path)) {
            fs.cd(path);
            fileIdx = 0;
            fileScroll = 0;
        } else {
            // switch to editor
            openApp       = 2;
            editorFile    = path;
            editorContent = fs.readFile(path);
            if (editorContent == null) editorContent = "";
            editorDirty   = false;
            editorScrollY = 0;
            winAnimStep   = 0;
            winAnimating  = true;

            Integer ai = new Integer(2);
            if (!openApps.contains(ai)) openApps.addElement(ai);

            removeCommand(cmdMenu);
            addCommand(cmdSave);
            addCommand(cmdInput);
        }
    }

    // --- File context menu ---

    private void execFileCtx() {
        fileCtxMenu = false;
        String[] ch = fs.listChildren(fs.getCurrentPath());

        switch (fileCtxIdx) {
            case 0: // Open
                openSelectedFile();
                break;
            case 1: // Rename
                if (fileIdx < ch.length) renameFile(ch[fileIdx]);
                break;
            case 2: // Delete
                if (fileIdx < ch.length) {
                    fs.deleteRecursive(ch[fileIdx]);
                    showToast("Deleted", RED);
                    if (fileIdx > 0) fileIdx--;
                }
                break;
            case 3: // New Folder
                newItem(true);
                break;
            case 4: // New File
                newItem(false);
                break;
        }
    }

    private void renameFile(final String path) {
        String old = fs.nameOf(path);
        TextBox tb = new TextBox("Rename", old, 64, TextField.ANY);
        Command ok = new Command("OK", Command.OK, 1);
        tb.addCommand(ok);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                String nn = ((TextBox) d).getString();
                if (nn != null && nn.length() > 0) {
                    String content = fs.readFile(path);
                    String parent  = fs.getCurrentPath();
                    String np = parent + (parent.endsWith("/") ? "" : "/") + nn;
                    if (content != null) {
                        fs.writeFile(np, content);
                        fs.deleteRecursive(path);
                        showToast("Renamed → " + nn, GREEN);
                    }
                }
                midlet.getDisplay().setCurrent(DesktopUI.this);
                repaint();
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    private void newItem(final boolean dir) {
        String def = dir ? "NewFolder" : "newfile.txt";
        TextBox tb = new TextBox(dir ? "Folder name" : "File name", def, 64, TextField.ANY);
        Command ok = new Command("OK", Command.OK, 1);
        tb.addCommand(ok);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                String name = ((TextBox) d).getString();
                if (name != null && name.length() > 0) {
                    String parent = fs.getCurrentPath();
                    String p = parent + (parent.endsWith("/") ? "" : "/") + name;
                    if (dir) { fs.createDir(p); showToast("Folder created", GREEN); }
                    else     { fs.writeFile(p, ""); showToast("File created", GREEN); }
                }
                midlet.getDisplay().setCurrent(DesktopUI.this);
                repaint();
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    // --- Settings actions ---

    private void execSettings() {
        switch (settIdx) {
            case 0: // cycle theme
                applyTheme(currentTheme + 1);
                showToast("Theme: " + THEME_NAMES[currentTheme], ACCENT);
                break;
            case 1: // animation toggle
                wallpaperAnim = !wallpaperAnim;
                showToast("Animation: " + (wallpaperAnim ? "ON" : "OFF"), ACCENT);
                break;
            case 2: editField("Username", fs.getUsername(), 0); break;
            case 3: editField("Hostname", fs.getHostname(), 1); break;
            case 4: showToast("DashCMD v1.2.0 Desktop", ACCENT); break;
        }
    }

    private void editField(String title, String cur, final int type) {
        TextBox tb = new TextBox(title, cur, 32, TextField.ANY);
        Command ok = new Command("OK", Command.OK, 1);
        tb.addCommand(ok);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                String v = ((TextBox) d).getString();
                if (v != null && v.length() > 0) {
                    if (type == 0) { fs.setUsername(v); showToast("User → " + v, GREEN); }
                    else           { fs.setHostname(v); showToast("Host → " + v, GREEN); }
                }
                midlet.getDisplay().setCurrent(DesktopUI.this);
                repaint();
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    // --- Calculator logic ---

    private void pressCalcBtn() {
        if (calcSelBtn >= CALC_BTNS.length) return;
        String b = CALC_BTNS[calcSelBtn];

        if      (b.equals("C"))  { calcDisp = "0"; calcOper = ""; calcOp = ' '; calcNewNum = true; }
        else if (b.equals("BS")) { calcDisp = calcDisp.length() > 1 ? calcDisp.substring(0, calcDisp.length()-1) : "0"; }
        else if (b.equals("=")) calcEquals();
        else if (b.equals("+") || b.equals("-") || b.equals("*") || b.equals("/"))
            calcDoOp(b.charAt(0));
        else if (b.equals(".")) { if (calcDisp.indexOf('.') < 0) { calcDisp += "."; calcNewNum = false; } }
        else calcDigit(b.charAt(0));
    }

    private void calcDigit(char c) {
        if (calcNewNum) { calcDisp = "" + c; calcNewNum = false; }
        else if (calcDisp.length() < 15) calcDisp += c;
    }

    private void calcDoOp(char op) {
        calcOper   = calcDisp;
        calcOp     = op;
        calcNewNum = true;
    }

    private void calcEquals() {
        if (calcOp == ' ' || calcOper.length() == 0) return;
        try {
            double a = Double.parseDouble(calcOper);
            double b = Double.parseDouble(calcDisp);
            double r = 0;
            switch (calcOp) {
                case '+': r = a + b; break;
                case '-': r = a - b; break;
                case '*': r = a * b; break;
                case '/':
                    if (b == 0) { calcDisp = "Err:Div0"; calcNewNum = true; return; }
                    r = a / b; break;
            }
            calcDisp = (r == (long) r) ? String.valueOf((long) r) : String.valueOf(r);
            if (calcDisp.length() > 14) calcDisp = calcDisp.substring(0, 14);
        } catch (Exception e) {
            calcDisp = "Error";
        }
        calcOp     = ' ';
        calcOper   = "";
        calcNewNum = true;
    }

    // =====================================================
    //  COMMAND HANDLER
    // =====================================================

    public void commandAction(Command c, Displayable d) {
        if (c == cmdExit) {
            shutdown(); midlet.showTerminal();
        } else if (c == cmdBack) {
            if (openApp >= 0) closeApp(); else { shutdown(); midlet.showTerminal(); }
            repaint();
        } else if (c == cmdOpen) {
            openIcon(); repaint();
        } else if (c == cmdSave && openApp == 2) {
            saveEditor();
        } else if (c == cmdInput) {
            handleInput();
        } else if (c == cmdMenu && openApp == 1) {
            fileCtxMenu = true; fileCtxIdx = 0; repaint();
        }
    }

    private void saveEditor() {
        if (editorFile.length() == 0) {
            TextBox tb = new TextBox("Save As", "document.txt", 64, TextField.ANY);
            Command ok = new Command("OK", Command.OK, 1);
            tb.addCommand(ok);
            tb.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    String n = ((TextBox) d).getString();
                    if (n != null && n.length() > 0) {
                        String p = fs.getCurrentPath();
                        editorFile = p + (p.endsWith("/") ? "" : "/") + n;
                    }
                    midlet.getDisplay().setCurrent(DesktopUI.this);
                    if (editorFile.length() > 0) {
                        fs.writeFile(editorFile, editorContent);
                        editorDirty = false;
                        showToast("Saved: " + fs.nameOf(editorFile), GREEN);
                        AppStorage.logBoot("INFO", "Saved: " + editorFile);
                    }
                    repaint();
                }
            });
            midlet.getDisplay().setCurrent(tb);
        } else {
            fs.writeFile(editorFile, editorContent);
            editorDirty = false;
            showToast("Saved!", GREEN);
            AppStorage.logBoot("INFO", "Saved: " + editorFile);
            repaint();
        }
    }

    private void handleInput() {
        if (openApp == 2) {
            TextBox tb = new TextBox("Edit", editorContent, 4096, TextField.ANY);
            Command ok = new Command("OK", Command.OK, 1);
            tb.addCommand(ok);
            tb.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    String s = ((TextBox) d).getString();
                    if (s != null) { editorContent = s; editorDirty = true; }
                    midlet.getDisplay().setCurrent(DesktopUI.this);
                    repaint();
                }
            });
            midlet.getDisplay().setCurrent(tb);
        } else if (openApp == 3) {
            TextBox tb = new TextBox("URL", "http://", 256, TextField.URL);
            Command ok = new Command("OK", Command.OK, 1);
            tb.addCommand(ok);
            tb.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    final String url = ((TextBox) d).getString();
                    midlet.getDisplay().setCurrent(DesktopUI.this);
                    if (url != null && url.startsWith("http")) {
                        netHistory.addElement(url);
                        netResult = "Connecting...\n";
                        repaint();
                        NetworkTask.httpGet(url, new NetworkTask.Callback() {
                            public void onResult(String r) {
                                netResult = r.length() > 800 ? r.substring(0, 800) + "..." : r;
                                NetworkTask.downloadToFS(url, fs);
                                showToast("Download OK", GREEN);
                                repaint();
                            }
                            public void onError(String e) {
                                netResult = "Error: " + e;
                                showToast("Net error", RED);
                                repaint();
                            }
                        });
                    }
                }
            });
            midlet.getDisplay().setCurrent(tb);
        }
    }

    // =====================================================
    //  DRAWING HELPERS
    // =====================================================

    /** Draw an 8×8 1-bit bitmap scaled to `size` pixels. */
    private void drawBitmap(Graphics g, int x, int y, int size, int[] bmp, int color) {
        int px = size / 8;
        if (px < 1) px = 1;
        g.setColor(color);
        for (int r = 0; r < 8 && r < bmp.length; r++) {
            for (int c = 0; c < 8; c++) {
                if ((bmp[r] & (0x80 >> c)) != 0) {
                    g.fillRect(x + c * px, y + r * px, px, px);
                }
            }
        }
    }

    /** Linear interpolation between two 0xRRGGBB colours. */
    private int lerpColor(int from, int to, int pos, int total) {
        if (total <= 0) return from;
        int r = interp((from >> 16) & 0xFF, (to >> 16) & 0xFF, pos, total);
        int g = interp((from >>  8) & 0xFF, (to >>  8) & 0xFF, pos, total);
        int b = interp( from        & 0xFF,  to        & 0xFF, pos, total);
        return (r << 16) | (g << 8) | b;
    }

    private int interp(int a, int b, int pos, int total) {
        return a + (b - a) * pos / total;
    }

    private String fmtUptime() {
        long s = (System.currentTimeMillis() - uptimeStart) / 1000;
        return (s / 3600) + "h " + ((s / 60) % 60) + "m " + (s % 60) + "s";
    }

    private String[] splitLines(String s) {
        if (s == null || s.length() == 0) return new String[0];
        Vector v = new Vector();
        int st = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '\n') {
                v.addElement(s.substring(st, i));
                st = i + 1;
            }
        }
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    public void shutdown() {
        running = false;
        if (tickThread != null) tickThread.interrupt();
    }
}