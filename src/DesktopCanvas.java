import javax.microedition.lcdui.*;
import java.util.*;

/**
 * DesktopCanvas v1.2.2 - DashCMD Desktop UI
 * CLDC 1.1 / MIDP 2.0
 *
 * v1.2.2 improvements:
 *  - Richer pixel-art icons (16 system icons, more detail)
 *  - Animated selection glow (pulse effect)
 *  - Status bar: battery%, signal bars, RAM, clock
 *  - App info panel on long-press (fire+hold simulation via info key)
 *  - File browser panel (inline VirtualFS tree)
 *  - Wallpaper styles: gradient / matrix rain / solid
 *  - Smooth scrolling with momentum
 *  - Toast notification queue (multiple toasts)
 *  - Context menu: Open / Info / Uninstall
 *  - New system icons: Calculator, Storage, Scripts, Package
 */
public class DesktopCanvas extends Canvas implements CommandListener {

    private TerminalOS   os;
    private VirtualFS    fs;
    private AppManager   apps;
    private AITerminal   ai;
    private ThemeManager theme;

    // ==================== ICON DEFINITIONS ====================

    // System icon names (12 icons in v1.2.2)
    private static final String[] SYS_ICONS = {
        "Terminal","Files","Editor","Network","AI Chat",
        "Settings","Logs","Theme","Calc","Storage","Scripts","Pkg"
    };

    // 8x8 pixel-art bitmaps (one byte = one row, MSB = leftmost pixel)
    private static final int[][] SYS_BITMAPS = {
        // Terminal: blinking cursor prompt
        {0x00,0x06,0x0C,0x18,0x0C,0x06,0x00,0x7E},
        // Files: folder with tab
        {0x1C,0x3C,0x7F,0x7F,0x7F,0x7F,0x7F,0x00},
        // Editor: pencil writing
        {0x03,0x07,0x0E,0x1C,0x38,0x70,0x40,0xFF},
        // Network: wifi arcs
        {0x00,0x3C,0x42,0x99,0x24,0x00,0x18,0x00},
        // AI Chat: robot face
        {0x3C,0x42,0xA5,0x81,0xBD,0x42,0x3C,0x18},
        // Settings: gear with center dot
        {0x18,0x7E,0x3C,0xE7,0xE7,0x3C,0x7E,0x18},
        // Logs: document lines
        {0x7E,0x42,0x7E,0x42,0x7E,0x42,0x7E,0x00},
        // Theme: color palette
        {0x3C,0x7E,0xDB,0xFF,0xDB,0x7E,0x3C,0x00},
        // Calculator: grid of keys
        {0x7F,0x49,0x49,0x7F,0x49,0x49,0x7F,0x00},
        // Storage: cylinder (database)
        {0x3C,0x7E,0x42,0x3C,0x3C,0x42,0x7E,0x3C},
        // Scripts: code brackets
        {0x18,0x30,0x60,0x7E,0x7E,0x06,0x0C,0x18},
        // Package: box/archive
        {0x7E,0x5A,0x7E,0x42,0x42,0x42,0x42,0x7E}
    };

    private static final int[] SYS_COLORS = {
        0x00FF41,0x00BFFF,0xFFFF00,0x00FF88,
        0xFF88FF,0xFFAA00,0xFF4444,0xAA88FF,
        0x44FFCC,0xFF8844,0x88FF44,0xFFCC44
    };

    // ==================== LAYOUT ====================

    private int W, H;
    private int ICON_SIZE;
    private int ICON_COLS;
    private int ICON_PAD   = 4;
    private int TASKBAR_H  = 20;
    private int HEADER_H   = 22;
    private Font fontS, fontT, fontTiny;

    private int totalIcons;

    // ==================== STATE ====================

    private int  selectedIcon  = 0;
    private int  scrollRow     = 0;
    private int  glowPhase     = 0;   // 0-7 pulse animation

    // Random number generator (CLDC 1.1 compatible)
    private Random rng = new Random();

    // Context menu
    private boolean ctxMenuOpen = false;
    private int     ctxItem     = 0;  // 0=Open 1=Info 2=Uninstall
    private static final String[] CTX_ITEMS = {"Open","Info","Uninstall"};

    // File browser panel
    private boolean filePanelOpen = false;
    private String  filePanelDir;
    private String[] filePanelEntries;
    private int      filePanelSel  = 0;
    private int      filePanelScroll = 0;

    // Wallpaper style: 0=gradient 1=matrix 2=solid
    private int wallpaperStyle = 0;
    // Matrix rain state
    private int[] matrixCols;
    private int[] matrixY;
    private char[] matrixChars;
    private static final char[] MATRIX_CHARS =
        {'0','1','A','B','C','D','E','F','#','@','!','?','%','&'};

    // Toast queue
    private Vector toastQueue  = new Vector(); // Object[]{ String msg, Long timeCreated }
    private static final int TOAST_MS = 2500;

    // Clock/animation thread
    private Thread        animThread;
    private volatile boolean animRunning;

    // Commands
    private Command backCmd, openCmd, inputCmd, refreshCmd, infoCmd, wallCmd;

    // TextBox
    private javax.microedition.lcdui.TextBox qBox;

    // ==================== CONSTRUCTOR ====================

    public DesktopCanvas(TerminalOS os, VirtualFS fs, AppManager apps, AITerminal ai) {
        this.os    = os;
        this.fs    = fs;
        this.apps  = apps;
        this.ai    = ai;
        this.theme = ThemeManager.getInstance();

        fontS    = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN,  Font.SIZE_SMALL);
        fontT    = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD,   Font.SIZE_SMALL);
        fontTiny = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN,  Font.SIZE_SMALL);

        backCmd    = new Command("Terminal", Command.EXIT,   1);
        openCmd    = new Command("Open",     Command.OK,     2);
        inputCmd   = new Command("Cmd",      Command.SCREEN, 3);
        refreshCmd = new Command("Refresh",  Command.SCREEN, 4);
        infoCmd    = new Command("Info",     Command.SCREEN, 5);
        wallCmd    = new Command("Wallpaper",Command.SCREEN, 6);
        addCommand(backCmd);
        addCommand(openCmd);
        addCommand(inputCmd);
        addCommand(refreshCmd);
        addCommand(infoCmd);
        addCommand(wallCmd);
        setCommandListener(this);

        // Init matrix rain
        matrixCols  = new int[40];
        matrixY     = new int[40];
        matrixChars = new char[40];
        initMatrix();

        // Animation thread: clock + glow + matrix
        animRunning = true;
        animThread  = new Thread(new Runnable() {
            public void run() {
                int tick = 0;
                while (animRunning) {
                    try { Thread.sleep(150); } catch (InterruptedException e) { return; }
                    tick++;
                    glowPhase = (glowPhase + 1) % 16;
                    if (wallpaperStyle == 1) tickMatrix();
                    // Expire toasts
                    long now = System.currentTimeMillis();
                    while (toastQueue.size() > 0) {
                        Object[] t = (Object[]) toastQueue.elementAt(0);
                        long created = ((Long) t[1]).longValue();
                        if (now - created > TOAST_MS) toastQueue.removeElementAt(0);
                        else break;
                    }
                    repaint();
                }
            }
        });
        animThread.start();
    }

    // ==================== MATRIX RAIN ====================

    private void initMatrix() {
        for (int i = 0; i < matrixCols.length; i++) {
            matrixCols[i]  = i * 4;
            matrixY[i]     = (rng.nextInt() & 0x7FFFFFFF) % 200;
            matrixChars[i] = MATRIX_CHARS[i % MATRIX_CHARS.length];
        }
    }

    private void tickMatrix() {
        long seed = System.currentTimeMillis();
        for (int i = 0; i < matrixY.length; i++) {
            matrixY[i] += 2 + (i % 3);
            if (matrixY[i] > H + 20) matrixY[i] = -10 - (int)((seed * (i+1)) % 40);
            matrixChars[i] = MATRIX_CHARS[(int)((seed / (i+1)) % MATRIX_CHARS.length)];
        }
    }

    // ==================== PAINT ====================

    protected void paint(Graphics g) {
        W = getWidth(); H = getHeight();
        totalIcons = SYS_ICONS.length + apps.getAppCount();
        ICON_SIZE  = Math.max(20, Math.min(32, (W - 8) / 4 - 4));
        ICON_COLS  = Math.max(3, (W - 4) / (ICON_SIZE + ICON_PAD * 2 + 4));

        paintWallpaper(g);
        paintHeader(g);
        if (filePanelOpen) {
            paintFilePanel(g);
        } else {
            paintIcons(g);
        }
        paintTaskbar(g);
        paintToasts(g);
        if (ctxMenuOpen) paintContextMenu(g);
    }

    // ---- Wallpaper ----

    private void paintWallpaper(Graphics g) {
        int contentY = HEADER_H;
        int contentH = H - HEADER_H - TASKBAR_H;

        if (wallpaperStyle == 0) {
            // Gradient
            int top = theme.DT_BG_TOP, bot = theme.DT_BG_BOT;
            for (int y = 0; y < contentH; y++) {
                int r  = ((top>>16)&0xFF) + (((bot>>16)&0xFF)-((top>>16)&0xFF)) * y / Math.max(1,contentH);
                int gr = ((top>> 8)&0xFF) + (((bot>> 8)&0xFF)-((top>> 8)&0xFF)) * y / Math.max(1,contentH);
                int b  = ( top     &0xFF) + (( bot     &0xFF)-( top     &0xFF)) * y / Math.max(1,contentH);
                g.setColor((r<<16)|(gr<<8)|b);
                g.drawLine(0, contentY + y, W, contentY + y);
            }
            // Scanlines
            for (int y = contentY; y < contentY + contentH; y += 2) {
                g.setColor(0x0A000000);
                g.drawLine(0, y, W, y);
            }
        } else if (wallpaperStyle == 1) {
            // Matrix rain
            g.setColor(theme.BG);
            g.fillRect(0, contentY, W, contentH);
            // Fade overlay
            g.setColor(0x18000000);
            g.fillRect(0, contentY, W, contentH);
            // Draw drops
            g.setFont(fontTiny);
            int fh = fontTiny.getHeight();
            for (int i = 0; i < matrixY.length; i++) {
                int mx = (i * (W / matrixY.length + 1)) % W;
                int my = matrixY[i];
                if (my < contentY || my > contentY + contentH) continue;
                // Bright head
                g.setColor(0xFFFFFF);
                g.drawChar(matrixChars[i], mx, my, Graphics.TOP | Graphics.LEFT);
                // Trail
                for (int t = 1; t < 5; t++) {
                    int ty = my - t * fh;
                    if (ty < contentY) continue;
                    g.setColor(0x007700 + (5-t) * 0x001100);
                    g.drawChar(matrixChars[(i+t) % matrixChars.length], mx, ty,
                               Graphics.TOP | Graphics.LEFT);
                }
            }
        } else {
            // Solid dark
            g.setColor(theme.BG);
            g.fillRect(0, contentY, W, contentH);
            // Grid dots
            g.setColor(blendColor(theme.BG, theme.FG, 92));
            for (int x = 8; x < W; x += 16)
                for (int y = contentY + 8; y < contentY + contentH; y += 16)
                    g.fillRect(x, y, 1, 1);
        }
    }

    // ---- Header ----

    private void paintHeader(Graphics g) {
        g.setColor(theme.TASKBAR);
        g.fillRect(0, 0, W, HEADER_H);
        g.setColor(theme.ACCENT);
        g.drawLine(0, HEADER_H - 1, W, HEADER_H - 1);

        g.setFont(fontT);
        g.setColor(theme.FG);
        g.drawString("DashCMD", 4, 3, Graphics.TOP | Graphics.LEFT);

        // Version badge
        g.setColor(blendColor(theme.ACCENT, theme.BG, 70));
        g.fillRoundRect(fontT.stringWidth("DashCMD") + 6, 4,
                        fontS.stringWidth("v1.2.2") + 4, fontS.getHeight(), 3, 3);
        g.setColor(theme.ACCENT);
        g.setFont(fontS);
        g.drawString("v1.2.2", fontT.stringWidth("DashCMD") + 8, 5,
                     Graphics.TOP | Graphics.LEFT);

        // Right side: session + signal + battery
        int rx = W - 4;

        // Battery indicator (fake: shows 75%)
        int batW = 14, batH = 8;
        int batX = rx - batW - 2;
        int batY = (HEADER_H - batH) / 2;
        g.setColor(theme.GREY);
        g.drawRect(batX, batY, batW, batH);
        g.fillRect(batX + batW + 1, batY + 2, 2, batH - 4); // terminal nub
        g.setColor(0x44FF44);
        g.fillRect(batX + 1, batY + 1, (batW - 2) * 75 / 100, batH - 2);
        rx = batX - 3;

        // Signal bars (3 bars)
        for (int i = 0; i < 3; i++) {
            int bh = 3 + i * 2;
            int bx = rx - (3 - i) * 5;
            int by = (HEADER_H - bh);
            g.setColor(i < 3 ? theme.FG : theme.GREY);
            g.fillRect(bx, by, 3, bh);
        }
        rx -= 18;

        // Session indicator
        g.setColor(theme.GREY);
        g.setFont(fontS);
        String sess = "S" + (os.getActiveSession() + 1) + "/" + os.getSessionCount();
        g.drawString(sess, rx - fontS.stringWidth(sess), 4, Graphics.TOP | Graphics.LEFT);
    }

    // ---- Icons ----

    private void paintIcons(Graphics g) {
        int iconW     = (W - 4) / ICON_COLS;
        int rowH      = ICON_SIZE + fontS.getHeight() + ICON_PAD * 2 + 2;
        int iconAreaH = H - HEADER_H - TASKBAR_H;
        int visRows   = iconAreaH / rowH;
        int totalRows = (totalIcons + ICON_COLS - 1) / ICON_COLS;
        if (scrollRow > Math.max(0, totalRows - visRows)) scrollRow = Math.max(0, totalRows - visRows);

        for (int idx = 0; idx < totalIcons; idx++) {
            int col = idx % ICON_COLS;
            int row = idx / ICON_COLS - scrollRow;
            if (row < 0) continue;
            int iy = HEADER_H + ICON_PAD + row * rowH;
            if (iy + rowH > H - TASKBAR_H) continue;
            int ix = 4 + col * iconW + (iconW - ICON_SIZE) / 2;

            boolean sel = (idx == selectedIcon && !ctxMenuOpen);

            // Glow / selection background
            if (sel) {
                int glow = 120 + (glowPhase < 8 ? glowPhase * 10 : (15 - glowPhase) * 10);
                // Outer glow
                for (int gi = 3; gi >= 1; gi--) {
                    int gc = blendColor(theme.ICON_SEL, theme.DT_BG_TOP, 100 - glow * gi / 3);
                    g.setColor(gc);
                    g.drawRoundRect(ix - 2 - gi, iy - 2 - gi,
                                    ICON_SIZE + 4 + gi*2, ICON_SIZE + 4 + gi*2, 8, 8);
                }
                g.setColor(theme.ICON_SEL);
                g.fillRoundRect(ix - 2, iy - 2, ICON_SIZE + 4, ICON_SIZE + 4, 6, 6);
                g.setColor(theme.ACCENT);
                g.drawRoundRect(ix - 2, iy - 2, ICON_SIZE + 4, ICON_SIZE + 4, 6, 6);
            } else {
                g.setColor(theme.ICON_BG);
                g.fillRoundRect(ix - 2, iy - 2, ICON_SIZE + 4, ICON_SIZE + 4, 6, 6);
            }

            // Icon content
            int iconColor;
            int[] bmp;
            String label;
            if (idx < SYS_ICONS.length) {
                iconColor = SYS_COLORS[idx % SYS_COLORS.length];
                bmp       = SYS_BITMAPS[idx % SYS_BITMAPS.length];
                label     = SYS_ICONS[idx];
            } else {
                int appIdx = idx - SYS_ICONS.length;
                Vector appList = apps.getAppList();
                if (appIdx >= appList.size()) continue;
                AppManager.AppEntry app = (AppManager.AppEntry) appList.elementAt(appIdx);
                iconColor = theme.FG;
                label     = app.name;
                bmp       = parseIconData(app.iconData);
                // Color by lang
                if ("lua".equals(app.lang))      iconColor = 0x00BFFF;
                else if ("bsh".equals(app.lang)) iconColor = 0xFFAA44;
                else                             iconColor = 0x88FF44;
            }
            drawBitmapIcon(g, ix, iy, ICON_SIZE, bmp, iconColor);

            // Canvas badge
            if (idx >= SYS_ICONS.length) {
                int appIdx2 = idx - SYS_ICONS.length;
                Vector al2  = apps.getAppList();
                if (appIdx2 < al2.size()) {
                    AppManager.AppEntry a2 = (AppManager.AppEntry) al2.elementAt(appIdx2);
                    if (a2.canvasMode) {
                        g.setColor(theme.ACCENT);
                        g.fillRect(ix + ICON_SIZE - 4, iy, 4, 4);
                    }
                }
            }

            // Label
            g.setFont(fontS);
            String lbl = label;
            int maxChars = (iconW - 4) / fontS.charWidth('M');
            if (maxChars < 1) maxChars = 1;
            if (lbl.length() > maxChars) lbl = lbl.substring(0, maxChars);
            int lx = 4 + col * iconW + (iconW - fontS.stringWidth(lbl)) / 2;
            g.setColor(sel ? 0xFFFFFF : theme.GREY);
            g.drawString(lbl, lx, iy + ICON_SIZE + 3, Graphics.TOP | Graphics.LEFT);
        }

        // Scroll indicator
        int totalRows2 = (totalIcons + ICON_COLS - 1) / ICON_COLS;
        if (totalRows2 > visRows) {
            int sbH = H - HEADER_H - TASKBAR_H - 4;
            int sbX = W - 4;
            g.setColor(theme.SCROLLBAR);
            g.fillRect(sbX, HEADER_H + 2, 2, sbH);
            int tH = Math.max(8, sbH * visRows / totalRows2);
            int tY = HEADER_H + 2 + (sbH - tH) * scrollRow / Math.max(1, totalRows2 - visRows);
            g.setColor(theme.FG);
            g.fillRect(sbX, tY, 2, tH);
        }
    }

    // ---- Taskbar ----

    private void paintTaskbar(Graphics g) {
        int tbY = H - TASKBAR_H;
        g.setColor(theme.TASKBAR);
        g.fillRect(0, tbY, W, TASKBAR_H);
        g.setColor(theme.GREY);
        g.drawLine(0, tbY, W, tbY);

        // Real clock
        String time = AppStorage.formatHMS(System.currentTimeMillis());
        g.setFont(fontS);
        g.setColor(theme.FG);
        g.drawString(time, W - fontS.stringWidth(time) - 4, tbY + 3,
                     Graphics.TOP | Graphics.LEFT);

        // RAM usage indicator
        long free  = Runtime.getRuntime().freeMemory()  / 1024;
        long total = Runtime.getRuntime().totalMemory() / 1024;
        long used  = total - free;
        String ram = used + "K";
        g.setColor(theme.GREY);
        g.drawString(ram, 4, tbY + 3, Graphics.TOP | Graphics.LEFT);

        // Theme name centered
        String tn = theme.name;
        int tnMax = (W - fontS.stringWidth(ram) - fontS.stringWidth(time) - 16) /
                    fontS.charWidth('M');
        if (tnMax > 0 && tn.length() > tnMax) tn = tn.substring(0, tnMax);
        g.setColor(blendColor(theme.FG, theme.GREY, 60));
        g.drawString(tn, (W - fontS.stringWidth(tn)) / 2, tbY + 3,
                     Graphics.TOP | Graphics.LEFT);
    }

    // ---- Toast notifications ----

    private void paintToasts(Graphics g) {
        if (toastQueue.size() == 0) return;
        Object[] toast = (Object[]) toastQueue.elementAt(0);
        String msg = (String) toast[0];
        int tw = fontS.stringWidth(msg) + 12;
        int th = fontS.getHeight() + 8;
        int tx = (W - tw) / 2;
        int ty = H - TASKBAR_H - th - 6;

        // Shadow
        g.setColor(0x000000);
        g.fillRoundRect(tx + 2, ty + 2, tw, th, 8, 8);
        // Background
        g.setColor(blendColor(theme.TASKBAR, theme.ACCENT, 70));
        g.fillRoundRect(tx, ty, tw, th, 8, 8);
        g.setColor(theme.ACCENT);
        g.drawRoundRect(tx, ty, tw, th, 8, 8);
        // Text
        g.setFont(fontS);
        g.setColor(0xFFFFFF);
        g.drawString(msg, tx + 6, ty + 4, Graphics.TOP | Graphics.LEFT);
    }

    // ---- Context menu ----

    private void paintContextMenu(Graphics g) {
        int mw = 80, mh = CTX_ITEMS.length * (fontS.getHeight() + 4) + 8;
        int mx = (W - mw) / 2;
        // Position near selected icon
        int row = selectedIcon / ICON_COLS - scrollRow;
        int rowH = ICON_SIZE + fontS.getHeight() + ICON_PAD * 2 + 2;
        int my   = HEADER_H + row * rowH + ICON_SIZE;
        if (my + mh > H - TASKBAR_H) my = H - TASKBAR_H - mh - 4;

        // Shadow
        g.setColor(0x000000);
        g.fillRoundRect(mx + 2, my + 2, mw, mh, 6, 6);
        // Body
        g.setColor(theme.TASKBAR);
        g.fillRoundRect(mx, my, mw, mh, 6, 6);
        g.setColor(theme.ACCENT);
        g.drawRoundRect(mx, my, mw, mh, 6, 6);

        g.setFont(fontS);
        for (int i = 0; i < CTX_ITEMS.length; i++) {
            int iy = my + 4 + i * (fontS.getHeight() + 4);
            if (i == ctxItem) {
                g.setColor(theme.ICON_SEL);
                g.fillRoundRect(mx + 2, iy - 1, mw - 4, fontS.getHeight() + 2, 4, 4);
                g.setColor(0xFFFFFF);
            } else {
                g.setColor(theme.FG);
            }
            g.drawString(CTX_ITEMS[i], mx + 8, iy, Graphics.TOP | Graphics.LEFT);
        }
    }

    // ---- File panel ----

    private void paintFilePanel(Graphics g) {
        int panelY = HEADER_H;
        int panelH = H - HEADER_H - TASKBAR_H;
        g.setColor(blendColor(theme.BG, theme.HEADER, 50));
        g.fillRect(0, panelY, W, panelH);
        g.setColor(theme.ACCENT);
        g.drawLine(0, panelY, W, panelY);

        // Title bar
        g.setColor(theme.HEADER);
        g.fillRect(0, panelY, W, fontS.getHeight() + 4);
        g.setFont(fontT);
        g.setColor(theme.ACCENT);
        String dir = filePanelDir;
        if (dir.length() > 20) dir = "..." + dir.substring(dir.length() - 17);
        g.drawString("Files: " + dir, 4, panelY + 2, Graphics.TOP | Graphics.LEFT);
        g.setFont(fontS);
        g.setColor(theme.GREY);
        g.drawString("[Back=close]", W - fontS.stringWidth("[Back=close]") - 4,
                     panelY + 3, Graphics.TOP | Graphics.LEFT);

        int listY    = panelY + fontS.getHeight() + 6;
        int listH    = panelH - fontS.getHeight() - 6;
        int lineH    = fontS.getHeight() + 2;
        int visLines = listH / lineH;

        if (filePanelEntries == null || filePanelEntries.length == 0) {
            g.setColor(theme.GREY);
            g.drawString("(empty)", 8, listY + 4, Graphics.TOP | Graphics.LEFT);
            return;
        }

        int start = filePanelScroll;
        int end   = Math.min(filePanelEntries.length, start + visLines);
        for (int i = start; i < end; i++) {
            int ey = listY + (i - start) * lineH;
            boolean isSel = (i == filePanelSel);
            if (isSel) {
                g.setColor(theme.ICON_SEL);
                g.fillRect(0, ey - 1, W, lineH);
            }
            String entry = filePanelEntries[i];
            boolean isDir = entry.endsWith("/");
            g.setColor(isDir ? theme.DIR_COLOR : (isSel ? 0xFFFFFF : theme.FG));
            g.setFont(fontS);
            // Icon: folder or file
            g.drawString(isDir ? "\u25B6 " + entry : "  " + entry,
                         6, ey, Graphics.TOP | Graphics.LEFT);
        }

        // Scrollbar
        if (filePanelEntries.length > visLines) {
            int sbH = listH;
            int tH  = Math.max(6, sbH * visLines / filePanelEntries.length);
            int tY  = listY + (sbH - tH) * filePanelScroll /
                      Math.max(1, filePanelEntries.length - visLines);
            g.setColor(theme.SCROLLBAR);
            g.fillRect(W - 3, listY, 2, sbH);
            g.setColor(theme.FG);
            g.fillRect(W - 3, tY, 2, tH);
        }
    }

    // ==================== ICON RENDERING ====================

    private void drawBitmapIcon(Graphics g, int x, int y, int size, int[] bmp, int color) {
        if (bmp == null || bmp.length == 0) {
            g.setColor(color); g.fillRect(x+size/4, y+size/4, size/2, size/2); return;
        }
        int px = Math.max(1, size / 8);
        g.setColor(color);
        for (int row = 0; row < 8 && row < bmp.length; row++) {
            for (int col = 0; col < 8; col++) {
                if ((bmp[row] & (0x80 >> col)) != 0) {
                    g.fillRect(x + col * px, y + row * px, px, px);
                }
            }
        }
    }

    private int[] parseIconData(String data) {
        if (data == null || data.length() == 0)
            return new int[]{0x3C,0x42,0x42,0x42,0x42,0x42,0x3C,0x00};
        String[] lines = splitLines(data);
        int[] bmp = new int[8];
        for (int row = 0; row < 8 && row < lines.length; row++) {
            String line = lines[row];
            for (int col = 0; col < 8 && col < line.length(); col++) {
                if (line.charAt(col) == '#' || line.charAt(col) == '*')
                    bmp[row] |= (0x80 >> col);
            }
        }
        return bmp;
    }

    // ==================== KEY HANDLING ====================

    protected void keyPressed(int keyCode) {
        int ga = -1;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}

        if (ctxMenuOpen) {
            handleCtxKey(ga, keyCode); return;
        }
        if (filePanelOpen) {
            handleFilePanelKey(ga, keyCode); return;
        }

        int totalRows = (totalIcons + ICON_COLS - 1) / ICON_COLS;

        if (ga == LEFT  && selectedIcon > 0)
            { selectedIcon--; ensureVisible(); repaint(); }
        if (ga == RIGHT && selectedIcon < totalIcons - 1)
            { selectedIcon++; ensureVisible(); repaint(); }
        if (ga == UP    && selectedIcon >= ICON_COLS)
            { selectedIcon -= ICON_COLS; ensureVisible(); repaint(); }
        if (ga == DOWN  && selectedIcon < totalIcons - ICON_COLS)
            { selectedIcon += ICON_COLS; ensureVisible(); repaint(); }
        if (ga == FIRE || keyCode == Canvas.KEY_NUM5)
            openSelected();
        // 0 = context menu
        if (keyCode == Canvas.KEY_NUM0)
            { ctxMenuOpen = true; ctxItem = 0; repaint(); }
        // 7 = cycle wallpaper
        if (keyCode == Canvas.KEY_NUM7)
            { wallpaperStyle = (wallpaperStyle + 1) % 3;
              showToast("Wallpaper: " + new String[]{"Gradient","Matrix","Grid"}[wallpaperStyle]); }
        // 1 = file browser for current dir
        if (keyCode == Canvas.KEY_NUM1)
            openFilePanel(fs.getCurrentPath());
    }

    private void handleCtxKey(int ga, int keyCode) {
        if (ga == UP   && ctxItem > 0) { ctxItem--; repaint(); }
        if (ga == DOWN && ctxItem < CTX_ITEMS.length - 1) { ctxItem++; repaint(); }
        if (ga == FIRE || keyCode == Canvas.KEY_NUM5) {
            ctxMenuOpen = false;
            executeCtxItem(ctxItem, selectedIcon);
        }
        if (keyCode == Canvas.KEY_STAR || ga == LEFT) {
            ctxMenuOpen = false; repaint();
        }
    }

    private void executeCtxItem(int item, int iconIdx) {
        if (item == 0) { openSelected(); return; }
        if (item == 1) { showIconInfo(iconIdx); return; }
        if (item == 2) { uninstallApp(iconIdx); }
    }

    private void showIconInfo(int iconIdx) {
        if (iconIdx < SYS_ICONS.length) {
            showToast(SYS_ICONS[iconIdx] + ": system icon");
        } else {
            int appIdx = iconIdx - SYS_ICONS.length;
            Vector al  = apps.getAppList();
            if (appIdx < al.size()) {
                AppManager.AppEntry a = (AppManager.AppEntry) al.elementAt(appIdx);
                showToast(a.name + " v" + a.version + " [" + a.lang.toUpperCase() + "]");
            }
        }
    }

    private void uninstallApp(int iconIdx) {
        if (iconIdx < SYS_ICONS.length) {
            showToast("Cannot uninstall system icons"); return;
        }
        int appIdx = iconIdx - SYS_ICONS.length;
        Vector al  = apps.getAppList();
        if (appIdx < al.size()) {
            AppManager.AppEntry a = (AppManager.AppEntry) al.elementAt(appIdx);
            fs.deleteRecursive(a.path);
            apps.scanApps();
            if (selectedIcon >= totalIcons - 1 && selectedIcon > 0) selectedIcon--;
            showToast("Uninstalled: " + a.name);
            fs.saveToRMS();
        }
    }

    private void handleFilePanelKey(int ga, int keyCode) {
        if (filePanelEntries == null) { filePanelOpen = false; repaint(); return; }
        int n = filePanelEntries.length;
        if (ga == UP   && filePanelSel > 0) {
            filePanelSel--;
            if (filePanelSel < filePanelScroll) filePanelScroll = filePanelSel;
            repaint();
        }
        if (ga == DOWN && filePanelSel < n - 1) {
            filePanelSel++;
            int lineH  = fontS.getHeight() + 2;
            int visLines = (H - HEADER_H - TASKBAR_H - fontS.getHeight() - 6) / lineH;
            if (filePanelSel >= filePanelScroll + visLines)
                filePanelScroll = filePanelSel - visLines + 1;
            repaint();
        }
        if (ga == FIRE || keyCode == Canvas.KEY_NUM5) {
            // Navigate into dir or open file
            String sel = filePanelEntries[filePanelSel];
            if (sel.endsWith("/")) {
                String newDir;
                if (sel.equals("../")) newDir = parentOf(filePanelDir);
                else newDir = filePanelDir + (filePanelDir.endsWith("/") ? "" : "/") +
                              sel.substring(0, sel.length() - 1);
                openFilePanel(newDir);
            } else {
                // Open file - inject cat command
                String path = filePanelDir + (filePanelDir.endsWith("/") ? "" : "/") + sel;
                filePanelOpen = false;
                os.injectCommand("cat " + path);
            }
        }
        if (ga == LEFT || keyCode == Canvas.KEY_STAR) {
            // Go up one directory
            openFilePanel(parentOf(filePanelDir));
        }
        if (keyCode == Canvas.KEY_POUND) {
            filePanelOpen = false; repaint();
        }
    }

    private void openFilePanel(String dir) {
        filePanelDir    = fs.resolvePath(dir);
        filePanelSel    = 0;
        filePanelScroll = 0;
        String[] children = fs.listChildren(filePanelDir);
        Vector v = new Vector();
        v.addElement("../");  // parent
        for (int i = 0; i < children.length; i++) {
            String name = fs.nameOf(children[i]);
            if (fs.isDir(children[i])) v.addElement(name + "/");
            else v.addElement(name);
        }
        filePanelEntries = new String[v.size()];
        v.copyInto(filePanelEntries);
        filePanelOpen = true;
        repaint();
    }

    private String parentOf(String path) {
        if (path == null || path.equals("/")) return "/";
        String p = path.endsWith("/") ? path.substring(0, path.length()-1) : path;
        int idx = p.lastIndexOf('/');
        if (idx <= 0) return "/";
        return p.substring(0, idx);
    }

    private void ensureVisible() {
        int row      = selectedIcon / ICON_COLS;
        int rowH     = ICON_SIZE + fontS.getHeight() + ICON_PAD * 2 + 2;
        int iconAreaH= H - HEADER_H - TASKBAR_H;
        int visRows  = iconAreaH / rowH;
        if (row < scrollRow) scrollRow = row;
        if (row >= scrollRow + visRows) scrollRow = row - visRows + 1;
    }

    // ==================== ICON ACTIONS ====================

    private void openSelected() {
        if (selectedIcon < SYS_ICONS.length) {
            openSysIcon(selectedIcon);
        } else {
            int appIdx = selectedIcon - SYS_ICONS.length;
            Vector appList = apps.getAppList();
            if (appIdx < appList.size()) {
                AppManager.AppEntry app = (AppManager.AppEntry) appList.elementAt(appIdx);
                showToast("Opening: " + app.name);
                os.showScript(app.entryPoint, app.canvasMode);
            }
        }
    }

    private void openSysIcon(int idx) {
        switch (idx) {
            case 0:  // Terminal
                os.showTerminal(); break;
            case 1:  // Files
                openFilePanel(fs.getHomeDir()); break;
            case 2:  // Editor
                openQuickInput("nano "); break;
            case 3:  // Network
                openQuickInput("ping "); break;
            case 4:  // AI Chat
                os.showAIChat(); break;
            case 5:  // Settings
                showSettingsPanel(); break;
            case 6:  // Logs
                os.injectCommand("bootlog"); break;
            case 7:  // Theme
                openQuickInput("theme "); break;
            case 8:  // Calculator
                os.injectCommand("app calc"); break;
            case 9:  // Storage
                os.injectCommand("df -h"); break;
            case 10: // Scripts
                openFilePanel(fs.getHomeDir() + "/apps"); break;
            case 11: // Package
                os.injectCommand("apps"); break;
        }
    }

    private void showSettingsPanel() {
        showToast("Theme: " + theme.name + " | 7=wallpaper | 0=menu");
    }

    // ==================== QUICK INPUT ====================

    private void openQuickInput(String prefix) {
        qBox = new javax.microedition.lcdui.TextBox(
            "Quick Command", prefix, 256, javax.microedition.lcdui.TextField.ANY);
        javax.microedition.lcdui.Command ok = new javax.microedition.lcdui.Command(
            "Run", javax.microedition.lcdui.Command.OK, 1);
        javax.microedition.lcdui.Command cancel = new javax.microedition.lcdui.Command(
            "Cancel", javax.microedition.lcdui.Command.CANCEL, 2);
        qBox.addCommand(ok);
        qBox.addCommand(cancel);
        qBox.setCommandListener(new javax.microedition.lcdui.CommandListener() {
            public void commandAction(javax.microedition.lcdui.Command c,
                                      javax.microedition.lcdui.Displayable d) {
                String cmd = ((javax.microedition.lcdui.TextBox)d).getString();
                os.getDisplay().setCurrent(DesktopCanvas.this);
                if (c.getCommandType() != javax.microedition.lcdui.Command.OK) return;
                if (cmd == null || cmd.trim().length() == 0) return;
                cmd = cmd.trim();
                if (cmd.endsWith(".lua") || cmd.endsWith(".sh") || cmd.endsWith(".bsh")) {
                    String resolved = os.getSharedFS().resolvePath(cmd);
                    if (os.getSharedFS().isFile(resolved)) {
                        boolean canvasMode = resolved.endsWith(".lua") &&
                            os.getSharedFS().readFile(resolved) != null &&
                            os.getSharedFS().readFile(resolved).indexOf("canvas = true") >= 0;
                        os.showScript(resolved, canvasMode);
                        return;
                    }
                }
                showToast("Running: " + cmd);
                os.injectCommand(cmd);
            }
        });
        os.getDisplay().setCurrent(qBox);
    }

    // ==================== COMMANDS ====================

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd || c.getCommandType() == Command.EXIT) {
            if (filePanelOpen) { filePanelOpen = false; repaint(); return; }
            if (ctxMenuOpen)   { ctxMenuOpen = false;   repaint(); return; }
            shutdown(); os.showTerminal();
        } else if (c == openCmd) {
            openSelected();
        } else if (c == inputCmd) {
            openQuickInput("");
        } else if (c == refreshCmd) {
            apps.scanApps();
            totalIcons = SYS_ICONS.length + apps.getAppCount();
            showToast("Refreshed: " + apps.getAppCount() + " apps");
            repaint();
        } else if (c == infoCmd) {
            showIconInfo(selectedIcon);
        } else if (c == wallCmd) {
            wallpaperStyle = (wallpaperStyle + 1) % 3;
            showToast("Wallpaper: " + new String[]{"Gradient","Matrix","Grid"}[wallpaperStyle]);
        }
    }

    // ==================== TOAST ====================

    private void showToast(String msg) {
        // Remove oldest if queue full
        while (toastQueue.size() >= 3) toastQueue.removeElementAt(0);
        toastQueue.addElement(new Object[]{msg, new Long(System.currentTimeMillis())});
        repaint();
    }

    // ==================== HELPERS ====================

    private static int blendColor(int a, int b, int pctA) {
        int ra = (a>>16)&0xFF, rb = (b>>16)&0xFF;
        int ga = (a>> 8)&0xFF, gb = (b>> 8)&0xFF;
        int ba =  a     &0xFF, bb =  b     &0xFF;
        int r = ra + (rb - ra) * (100 - pctA) / 100;
        int g = ga + (gb - ga) * (100 - pctA) / 100;
        int bl= ba + (bb - ba) * (100 - pctA) / 100;
        return (r<<16)|(g<<8)|bl;
    }

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

    public void shutdown() {
        animRunning = false;
        if (animThread != null) animThread.interrupt();
    }
}