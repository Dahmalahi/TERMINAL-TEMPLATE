import javax.microedition.lcdui.*;
import javax.microedition.rms.*;
import java.util.*;

/**
 * InstallWizard v1.2.2 - First-run install wizard for DashCMD.
 * Auto-detects JSR-75 storage roots with free space display.
 *
 * Sequence:
 *  0. Boot splash (animated progress + storage detection)
 *  1. Welcome screen + Storage selection (with free space)
 *  2. Username + password setup
 *  3. Install progress (creating /Terminal/ structure)
 *  4. Done -> launch terminal
 */
public class InstallWizard extends Canvas implements CommandListener {

    private TerminalOS midlet;
    private int            step;
    private int            progress;
    private String         statusMsg;
    private boolean        installing;

    // User setup
    private String         setupUser = "user";
    private String         setupPass = "1234";
    private int            selectedStorage = 0;

    // JSR-75 detection
    private String[]       storageRoots;
    private long[]         storageFree;
    private boolean        jsr75Available;
    private boolean        detectionDone;
    private int            recommendedRoot;
    private String         detectionStatus;

    // Colours
    private static final int BG      = 0x0D1117;
    private static final int GREEN   = 0x00FF41;
    private static final int CYAN    = 0x00BFFF;
    private static final int WHITE   = 0xFFFFFF;
    private static final int GREY    = 0x888888;
    private static final int RED     = 0xFF4444;
    private static final int YELLOW  = 0xFFFF00;
    private static final int DGREEN  = 0x1A3A1A;

    private Font fontS, fontM;
    private int  W, H;

    // Install log
    private Vector installLog;

    // Commands
    private Command nextCmd;
    private Command backCmd;
    private Command inputCmd;

    // Animation
    private Thread animThread;
    private volatile boolean animRunning;

    public InstallWizard(TerminalOS midlet) {
        this.midlet        = midlet;
        this.step          = 0;
        this.progress      = 0;
        this.statusMsg     = "Initializing...";
        this.installing    = false;
        this.installLog    = new Vector();
        this.detectionDone = false;
        this.detectionStatus = "";
        this.recommendedRoot = -1;
        this.storageRoots  = new String[0];
        this.storageFree   = new long[0];

        fontS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontM = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD,  Font.SIZE_MEDIUM);

        nextCmd  = new Command("Next",  Command.OK,     1);
        backCmd  = new Command("Back",  Command.BACK,   2);
        inputCmd = new Command("Input", Command.SCREEN, 3);

        addCommand(nextCmd);
        setCommandListener(this);

        startSplash();
    }

    // ==================== SPLASH + DETECTION ====================

    private void startSplash() {
        step        = 0;
        progress    = 0;
        statusMsg   = "Booting DashCMD v1.2.2...";
        animRunning = true;

        animThread = new Thread(new Runnable() {
            public void run() {
                // Phase 1: Boot messages
                String[] msgs = {
                    "Loading kernel...",
                    "Mounting filesystems...",
                    "Starting services...",
                    "Initializing display..."
                };
                for (int i = 0; i < msgs.length && animRunning; i++) {
                    statusMsg = msgs[i];
                    logBoot("INFO", msgs[i]);
                    int target = (i + 1) * 10;
                    animateTo(target, 25);
                }

                // Phase 2: JSR-75 detection
                statusMsg = "Detecting storage...";
                logBoot("INFO", "Starting storage detection");
                repaint();
                animateTo(45, 20);

                detectStorage();

                // Phase 3: Post-detection
                statusMsg = "Checking RMS storage...";
                logBoot("INFO", "RMS storage available");
                animateTo(70, 25);

                if (jsr75Available && storageRoots.length > 0) {
                    statusMsg = "Found " + storageRoots.length + " storage root(s)";
                    logBoot("INFO", statusMsg);
                } else if (jsr75Available) {
                    statusMsg = "JSR-75 OK, no external roots";
                    logBoot("WARN", statusMsg);
                } else {
                    statusMsg = "JSR-75 not available, using RMS";
                    logBoot("INFO", statusMsg);
                }
                animateTo(85, 25);

                statusMsg = "Loading DashCMD v1.2.2...";
                logBoot("INFO", statusMsg);
                animateTo(95, 20);

                statusMsg = "Ready.";
                logBoot("INFO", "Boot complete");
                animateTo(100, 20);

                pause(400);

                if (animRunning) {
                    step = 1;
                    updateCommands();
                    repaint();
                }
            }
        });
        animThread.start();
    }

    /** Detect JSR-75 storage roots and free space */
    private void detectStorage() {
        // Check JSR-75 availability
        statusMsg = "Checking JSR-75 API...";
        repaint();
        jsr75Available = JSR75Storage.isAvailable();
        logBoot("INFO", "JSR-75: " + (jsr75Available ? "available" : "not available"));

        if (!jsr75Available) {
            storageRoots = new String[0];
            storageFree  = new long[0];
            detectionDone = true;
            return;
        }

        // Detect roots
        statusMsg = "Scanning storage roots...";
        repaint();

        storageRoots = JSR75Storage.listRoots();
        storageFree  = new long[storageRoots.length];

        // Get free space for each root
        long bestSpace = -1;
        recommendedRoot = -1;

        for (int i = 0; i < storageRoots.length && animRunning; i++) {
            String name = formatRoot(storageRoots[i]);
            statusMsg = "Probing " + name + "...";
            detectionStatus = "Checking " + (i + 1) + "/" + storageRoots.length;
            repaint();

            storageFree[i] = JSR75Storage.getAvailableSpace(storageRoots[i]);
            logBoot("INFO", "Root: " + name +
                    " (" + formatSize(storageFree[i]) + " free)");

            // Track best (most free space, prefer external)
            boolean isExternal = isExternalStorage(storageRoots[i]);
            if (storageFree[i] > bestSpace ||
                (isExternal && storageFree[i] > 0 && recommendedRoot < 0)) {
                bestSpace = storageFree[i];
                recommendedRoot = i;
            }

            // Animate progress during detection
            int detProgress = 45 + ((i + 1) * 20) / Math.max(1, storageRoots.length);
            progress = Math.min(detProgress, 65);
            repaint();
            pause(100);
        }

        detectionDone = true;
        detectionStatus = storageRoots.length + " root(s) detected";

        if (recommendedRoot >= 0) {
            logBoot("INFO", "Recommended: " + formatRoot(storageRoots[recommendedRoot]));
            // Auto-select recommended if JSR-75 available
            selectedStorage = recommendedRoot + 1;
        }
    }

    /** Check if a root looks like external storage */
    private boolean isExternalStorage(String root) {
        String lower = root.toLowerCase();
        return lower.indexOf("sdcard") >= 0 ||
               lower.indexOf("sd card") >= 0 ||
               lower.indexOf("memorycard") >= 0 ||
               lower.indexOf("memory card") >= 0 ||
               lower.indexOf("mmc") >= 0 ||
               lower.indexOf("/e:/") >= 0 ||
               lower.indexOf("/d:/") >= 0 ||
               lower.indexOf("external") >= 0;
    }

    // ==================== PAINT ====================

    protected void paint(Graphics g) {
        W = getWidth();
        H = getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        switch (step) {
            case 0: paintSplash(g);   break;
            case 1: paintWelcome(g);  break;
            case 2: paintSetup(g);    break;
            case 3: paintInstall(g);  break;
            case 4: paintDone(g);     break;
        }
    }

    private void paintSplash(Graphics g) {
        int px = Math.max(1, (W - 8) / 38);
        int bw = 7 * (4 * px + px);
        int bx = (W - bw) / 2;
        int by = H / 5;
        drawPixelBanner(g, bx, by, px, GREEN);

        g.setFont(fontS);
        g.setColor(CYAN);
        String ver = "v1.1.1";
        g.drawString(ver, (W - fontS.stringWidth(ver)) / 2,
                     by + 5 * px + px * 2 + 4, Graphics.TOP | Graphics.LEFT);

        // Status
        int sy = H / 2;
        g.setColor(GREY);
        g.drawString(statusMsg, 4, sy, Graphics.TOP | Graphics.LEFT);

        // Detection status
        if (detectionStatus.length() > 0) {
            g.setColor(CYAN);
            g.drawString(detectionStatus, 4, sy + fontS.getHeight() + 1,
                         Graphics.TOP | Graphics.LEFT);
        }

        // Progress bar
        int barY = H / 2 + fontS.getHeight() * 2 + 8;
        drawProgressBar(g, 4, barY, W - 8, 8, progress);

        g.setColor(WHITE);
        String pct = progress + "%";
        g.drawString(pct, (W - fontS.stringWidth(pct)) / 2,
                     barY + 12, Graphics.TOP | Graphics.LEFT);
    }

    private void paintWelcome(Graphics g) {
        int y = 4;

        // Header
        g.setFont(fontM);
        g.setColor(GREEN);
        g.drawString("DashCMD v1.2.2", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontM.getHeight() + 2;

        g.setFont(fontS);
        g.setColor(CYAN);
        g.drawString("Choose install location", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 6;

        // Divider
        g.setColor(0x333333);
        g.drawLine(4, y, W - 4, y);
        y += 4;

        // Option 0: RMS (always available)
        g.setColor(WHITE);
        g.drawString("Storage options:", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 3;

        boolean isSelected = (selectedStorage == 0);
        g.setColor(isSelected ? GREEN : GREY);
        String rmsLabel = (isSelected ? "> " : "  ") + "[RMS] Device memory";
        g.drawString(rmsLabel, 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 1;

        if (isSelected) {
            g.setColor(0x666666);
            g.drawString("    Always available, limited size", 4, y,
                         Graphics.TOP | Graphics.LEFT);
        }
        y += fontS.getHeight() + 2;

        // JSR-75 roots
        for (int i = 0; i < storageRoots.length; i++) {
            isSelected = (selectedStorage == i + 1);
            boolean isRecommended = (i == recommendedRoot);

            String name = formatRoot(storageRoots[i]);
            String space = formatSize(storageFree[i]);

            // Root name with indicator
            g.setColor(isSelected ? GREEN : GREY);
            String prefix = isSelected ? "> " : "  ";
            String label = prefix + "[FC] " + name;
            g.drawString(label, 4, y, Graphics.TOP | Graphics.LEFT);

            // Free space on same line if room
            String spaceLabel = "(" + space + ")";
            int spaceX = W - fontS.stringWidth(spaceLabel) - 4;
            if (spaceX > fontS.stringWidth(label) + 8) {
                g.setColor(isSelected ? CYAN : 0x666666);
                g.drawString(spaceLabel, spaceX, y, Graphics.TOP | Graphics.LEFT);
            }
            y += fontS.getHeight() + 1;

            // Recommended tag
            if (isRecommended) {
                g.setColor(YELLOW);
                g.drawString("    * Recommended", 4, y,
                             Graphics.TOP | Graphics.LEFT);
                y += fontS.getHeight() + 1;
            }

            y += 1;
        }

        // Divider
        y += 2;
        g.setColor(0x333333);
        g.drawLine(4, y, W - 4, y);
        y += 4;

        // JSR-75 status
        if (jsr75Available) {
            g.setColor(GREEN);
            g.drawString("JSR-75: Available", 4, y, Graphics.TOP | Graphics.LEFT);
            y += fontS.getHeight();
            g.setColor(CYAN);
            g.drawString(storageRoots.length + " storage root(s) found", 4, y,
                         Graphics.TOP | Graphics.LEFT);
        } else {
            g.setColor(YELLOW);
            g.drawString("JSR-75: Not available", 4, y,
                         Graphics.TOP | Graphics.LEFT);
            y += fontS.getHeight();
            g.setColor(GREY);
            g.drawString("Only RMS storage available", 4, y,
                         Graphics.TOP | Graphics.LEFT);
        }
        y += fontS.getHeight() + 4;

        // Install path preview
        g.setColor(WHITE);
        g.drawString("Install path:", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight();
        g.setColor(CYAN);
        String path = getInstallPathPreview();
        if (fontS.stringWidth(path) > W - 8) {
            path = "..." + path.substring(path.length() - 25);
        }
        g.drawString("  " + path, 4, y, Graphics.TOP | Graphics.LEFT);

        // Bottom hint
        g.setColor(CYAN);
        g.drawString("Up/Dn=select  Next=continue", 4,
                     H - fontS.getHeight() - 2, Graphics.TOP | Graphics.LEFT);
    }

    private void paintSetup(Graphics g) {
        int y = 4;

        g.setFont(fontM);
        g.setColor(GREEN);
        g.drawString("Create account", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontM.getHeight() + 4;

        g.setFont(fontS);

        // Username
        g.setColor(GREY);
        g.drawString("Username:", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight();
        g.setColor(WHITE);
        g.drawString("  " + setupUser, 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 4;

        // Password
        g.setColor(GREY);
        g.drawString("Password:", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight();
        g.setColor(WHITE);
        g.drawString("  " + maskPass(setupPass), 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 8;

        // Default hint
        g.setColor(0x666666);
        g.drawString("Press 'Input' to change values.", 4, y,
                     Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 2;
        g.drawString("Default: user / 1234", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 8;

        // Root account info
        g.setColor(0x333333);
        g.drawLine(4, y, W - 4, y);
        y += 4;
        g.setColor(YELLOW);
        g.drawString("Root account:", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight();
        g.drawString("  root / toor", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 2;
        g.setColor(GREY);
        g.drawString("  (change with passwd later)", 4, y,
                     Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 8;

        // Storage summary
        g.setColor(0x333333);
        g.drawLine(4, y, W - 4, y);
        y += 4;
        g.setColor(CYAN);
        g.drawString("Storage: " + getStorageLabel(), 4, y,
                     Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight();
        g.setColor(GREY);
        String path = getInstallPathPreview();
        if (fontS.stringWidth("Path: " + path) > W - 8) {
            path = "..." + path.substring(path.length() - 20);
        }
        g.drawString("Path: " + path, 4, y, Graphics.TOP | Graphics.LEFT);

        // Bottom
        g.setColor(CYAN);
        g.drawString("Next=install  Back=storage", 4,
                     H - fontS.getHeight() * 2 - 2, Graphics.TOP | Graphics.LEFT);
        g.drawString("Input=change user/pass", 4,
                     H - fontS.getHeight() - 2, Graphics.TOP | Graphics.LEFT);
    }

    private void paintInstall(Graphics g) {
        int y = 4;

        g.setFont(fontM);
        g.setColor(GREEN);
        g.drawString("Installing...", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontM.getHeight() + 2;

        // Storage type indicator
        g.setFont(fontS);
        g.setColor(CYAN);
        g.drawString(getStorageLabel(), 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 4;

        // Progress bar
        drawProgressBar(g, 4, y, W - 8, 10, progress);
        y += 14;

        // Status line
        g.setColor(WHITE);
        g.drawString(progress + "%  " + statusMsg, 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 6;

        // Log lines with color coding
        int maxLines = (H - y - 4) / fontS.getHeight();
        int logLines = Math.min(installLog.size(), maxLines);
        int logStart = Math.max(0, installLog.size() - logLines);

        for (int i = logStart; i < installLog.size(); i++) {
            String line = (String) installLog.elementAt(i);

            if (line.indexOf("FAIL") >= 0 || line.indexOf("ERROR") >= 0) {
                g.setColor(RED);
            } else if (line.indexOf("OK") >= 0) {
                g.setColor(GREEN);
            } else if (line.indexOf("INFO") >= 0) {
                g.setColor(CYAN);
            } else if (line.indexOf("WARN") >= 0) {
                g.setColor(YELLOW);
            } else {
                g.setColor(GREY);
            }

            // Truncate long lines
            if (fontS.stringWidth(line) > W - 8) {
                while (fontS.stringWidth(line + "..") > W - 8 && line.length() > 5) {
                    line = line.substring(0, line.length() - 1);
                }
                line = line + "..";
            }

            g.drawString(line, 4, y, Graphics.TOP | Graphics.LEFT);
            y += fontS.getHeight();
        }
    }

    private void paintDone(Graphics g) {
        int y = 8;

        // Success header
        g.setFont(fontM);
        g.setColor(GREEN);
        String done = "Install Complete!";
        g.drawString(done, (W - fontM.stringWidth(done)) / 2, y,
                     Graphics.TOP | Graphics.LEFT);
        y += fontM.getHeight() + 4;

        // Checkmark
        g.setColor(GREEN);
        String check = "[OK]";
        g.drawString(check, (W - fontM.stringWidth(check)) / 2, y,
                     Graphics.TOP | Graphics.LEFT);
        y += fontM.getHeight() + 8;

        g.setFont(fontS);

        // Divider
        g.setColor(0x333333);
        g.drawLine(4, y, W - 4, y);
        y += 6;

        // Install details
        String[][] info = {
            {"Version:",  "DashCMD v1.2.2"},
            {"User:",     setupUser},
            {"Home:",     "/home/" + setupUser},
            {"Storage:",  getStorageLabel()},
            {"Path:",     getInstallPathPreview()},
            {"",          ""},
            {"Login:",    setupUser + " / " + setupPass},
            {"Root:",     "root / toor"},
        };

        for (int i = 0; i < info.length; i++) {
            if (info[i][0].length() == 0) {
                y += 4;
                continue;
            }

            // Label
            g.setColor(GREY);
            g.drawString(info[i][0], 8, y, Graphics.TOP | Graphics.LEFT);

            // Value with color
            int valX = 8 + fontS.stringWidth(info[i][0]) + 4;
            if (info[i][0].equals("Login:") || info[i][0].equals("Root:")) {
                g.setColor(YELLOW);
            } else if (info[i][0].equals("Storage:") || info[i][0].equals("Path:")) {
                g.setColor(CYAN);
            } else {
                g.setColor(WHITE);
            }

            String val = info[i][1];
            int maxW = W - valX - 4;
            if (fontS.stringWidth(val) > maxW) {
                val = "..." + val.substring(val.length() - 18);
            }
            g.drawString(val, valX, y, Graphics.TOP | Graphics.LEFT);
            y += fontS.getHeight() + 2;
        }

        // Divider
        y += 4;
        g.setColor(0x333333);
        g.drawLine(4, y, W - 4, y);
        y += 6;

        // Launch prompt
        g.setColor(GREEN);
        String launch = "Press Next to launch terminal";
        g.drawString(launch, (W - fontS.stringWidth(launch)) / 2, y,
                     Graphics.TOP | Graphics.LEFT);
    }

    // ==================== PROGRESS BAR ====================

    private void drawProgressBar(Graphics g, int x, int y, int w, int h, int pct) {
        // Background
        g.setColor(DGREEN);
        g.fillRect(x, y, w, h);

        // Fill
        g.setColor(GREEN);
        int fillW = (w * pct) / 100;
        if (fillW > 0) {
            g.fillRect(x, y, fillW, h);
        }

        // Border
        g.setColor(GREY);
        g.drawRect(x, y, w, h);
    }

    // ==================== KEY HANDLING ====================

    protected void keyPressed(int keyCode) {
        int ga = -1;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}

        if (step == 1) {
            int maxOpt = storageRoots.length;
            if (ga == UP) {
                selectedStorage--;
                if (selectedStorage < 0) selectedStorage = maxOpt;
                repaint();
            } else if (ga == DOWN) {
                selectedStorage++;
                if (selectedStorage > maxOpt) selectedStorage = 0;
                repaint();
            }
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == inputCmd) {
            openInputDialog();
        } else if (c == nextCmd || c.getCommandType() == Command.OK) {
            advance();
        } else if (c == backCmd) {
            goBack();
        }
    }

    private void advance() {
        switch (step) {
            case 0:
                animRunning = false;
                step = 1;
                updateCommands();
                repaint();
                break;
            case 1:
                step = 2;
                updateCommands();
                repaint();
                break;
            case 2:
                // Validate inputs
                if (setupUser.length() == 0) {
                    setupUser = "user";
                }
                if (setupPass.length() < 4) {
                    setupPass = "1234";
                }
                step = 3;
                updateCommands();
                startInstall();
                break;
            case 4:
                midlet.finishInstall(setupUser, setupPass);
                break;
        }
    }

    private void goBack() {
        if (step == 2) {
            step = 1;
            updateCommands();
            repaint();
        } else if (step == 4) {
            // Allow going back to see settings
            step = 2;
            updateCommands();
            repaint();
        }
    }

    /** Update visible commands based on current step */
    private void updateCommands() {
        removeCommand(nextCmd);
        removeCommand(backCmd);
        removeCommand(inputCmd);

        switch (step) {
            case 0:
                addCommand(nextCmd); // Skip splash
                break;
            case 1:
                addCommand(nextCmd);
                break;
            case 2:
                addCommand(nextCmd);
                addCommand(backCmd);
                addCommand(inputCmd);
                break;
            case 3:
                // No commands during install
                break;
            case 4:
                addCommand(nextCmd);
                break;
        }
    }

    private void openInputDialog() {
        if (step != 2) return;

        TextBox tb = new TextBox("Username", setupUser, 32, TextField.ANY);
        Command ok = new Command("OK", Command.OK, 1);
        tb.addCommand(ok);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                String s = ((TextBox) d).getString();
                if (s != null && s.trim().length() > 0) {
                    setupUser = s.trim().toLowerCase();
                    // Remove spaces and special chars
                    StringBuffer clean = new StringBuffer();
                    for (int i = 0; i < setupUser.length(); i++) {
                        char ch = setupUser.charAt(i);
                        if ((ch >= 'a' && ch <= 'z') ||
                            (ch >= '0' && ch <= '9') ||
                            ch == '_' || ch == '-') {
                            clean.append(ch);
                        }
                    }
                    if (clean.length() > 0) {
                        setupUser = clean.toString();
                    } else {
                        setupUser = "user";
                    }
                }
                midlet.getDisplay().setCurrent(InstallWizard.this);
                openPasswordDialog();
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    private void openPasswordDialog() {
        TextBox tb = new TextBox("Password (min 4 chars)", setupPass, 32,
                                 TextField.PASSWORD);
        Command ok = new Command("OK", Command.OK, 1);
        tb.addCommand(ok);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                String s = ((TextBox) d).getString();
                if (s != null && s.length() >= 4) {
                    setupPass = s;
                } else if (s != null && s.length() > 0) {
                    // Too short, keep old
                }
                midlet.getDisplay().setCurrent(InstallWizard.this);
                repaint();
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    // ==================== INSTALL ====================

    private void startInstall() {
        installing = true;
        progress   = 0;
        installLog.removeAllElements();

        new Thread(new Runnable() {
            public void run() {
                runInstall();
            }
        }).start();
    }

    private void runInstall() {
        logBoot("INFO", "Install started for user: " + setupUser);

        boolean useJSR75 = (selectedStorage > 0 &&
                            selectedStorage <= storageRoots.length);
        String jsr75Root = useJSR75 ? storageRoots[selectedStorage - 1] : null;

        // Run appropriate installer
        if (useJSR75) {
            runJSR75Install(jsr75Root);
        } else {
            runRMSInstall();
        }

        // Save settings
        logBoot("INFO", "Saving settings...");
        AppStorage.markInstalled();
        AppStorage.saveSetting("default_user", setupUser);
        AppStorage.saveSetting("default_pass", setupPass);
        AppStorage.saveSetting("storage_type", useJSR75 ? "jsr75" : "rms");

        if (useJSR75 && jsr75Root != null) {
            String termPath = jsr75Root + "Terminal/";
            AppStorage.saveSetting("jsr75_root", jsr75Root);
            AppStorage.saveSetting("install_path", termPath);
            JSR75Storage.setInstallRoot(termPath);
        } else {
            AppStorage.saveSetting("install_path", "rms://Terminal/");
        }

        logBoot("INFO", "Install complete. User: " + setupUser);

        progress   = 100;
        step       = 4;
        installing = false;
        updateCommands();
        repaint();
    }

    private void runRMSInstall() {
        String[][] tasks = {
            {"2",   "Preparing RMS storage..."},
            {"5",   "Creating /Terminal/"},
            {"8",   "Creating /bin/"},
            {"11",  "Creating /boot/"},
            {"14",  "Creating /dev/"},
            {"17",  "Creating /etc/"},
            {"20",  "Creating /home/" + setupUser + "/"},
            {"23",  "Creating /home/" + setupUser + "/Documents/"},
            {"26",  "Creating /home/" + setupUser + "/Downloads/"},
            {"29",  "Creating /lib/"},
            {"32",  "Creating /mnt/"},
            {"35",  "Creating /opt/"},
            {"38",  "Creating /proc/"},
            {"41",  "Creating /root/"},
            {"44",  "Creating /sbin/"},
            {"47",  "Creating /tmp/"},
            {"50",  "Creating /usr/bin/"},
            {"53",  "Creating /usr/lib/"},
            {"56",  "Creating /usr/local/bin/"},
            {"59",  "Creating /var/log/"},
            {"62",  "Creating /var/cache/"},
            {"65",  "Creating /var/run/"},
            {"68",  "Installing /etc/hostname"},
            {"71",  "Installing /etc/passwd"},
            {"74",  "Installing /etc/motd"},
            {"77",  "Installing /etc/profile"},
            {"80",  "Installing /proc/cpuinfo"},
            {"83",  "Installing /proc/meminfo"},
            {"86",  "Installing /boot/bootlog"},
            {"89",  "Installing ~/.bashrc"},
            {"92",  "Installing ~/readme.txt"},
            {"95",  "Setting up credentials"},
            {"97",  "Saving to RMS"},
            {"99",  "Verifying..."},
            {"100", "Installation complete!"}
        };

        for (int i = 0; i < tasks.length; i++) {
            progress  = Integer.parseInt(tasks[i][0]);
            statusMsg = tasks[i][1];
            addLog("[" + pad2(progress) + "%] " + tasks[i][1] + " OK");
            logBoot("INFO", tasks[i][1]);
            repaint();
            pause(80);
        }
    }

    private void runJSR75Install(String root) {
        String base = root + "Terminal/";

        String[] dirs = {
            "",                                        // Terminal/
            "bin/",
            "boot/",
            "dev/",
            "etc/",
            "home/",
            "home/" + setupUser + "/",
            "home/" + setupUser + "/Documents/",
            "home/" + setupUser + "/Downloads/",
            "home/" + setupUser + "/Pictures/",
            "home/" + setupUser + "/Music/",
            "home/" + setupUser + "/Desktop/",
            "lib/",
            "mnt/",
            "opt/",
            "proc/",
            "root/",
            "sbin/",
            "tmp/",
            "usr/",
            "usr/bin/",
            "usr/lib/",
            "usr/local/",
            "usr/local/bin/",
            "usr/share/",
            "var/",
            "var/log/",
            "var/tmp/",
            "var/cache/",
            "var/run/"
        };

        // File contents to write
        String[][] files = {
            {"etc/hostname",   "dashcmd\n"},
            {"etc/motd",       "Welcome to DashCMD v1.2.2\n" +
                               "Installed at: " + base + "\n" +
                               "Type 'help' for commands.\n"},
            {"etc/passwd",     "root:x:0:0:root:/root:/bin/sh\n" +
                               setupUser + ":x:1000:1000:" + setupUser +
                               ":/home/" + setupUser + ":/bin/sh\n"},
            {"etc/profile",    "# System profile\n" +
                               "export PATH=/bin:/usr/bin:/sbin\n" +
                               "export TERM=dashcmd\n"},
            {"proc/cpuinfo",   "Processor: J2ME Virtual Machine\n" +
                               "Platform: MIDP 2.0 / CLDC 1.1\n"},
            {"proc/meminfo",   "Total: " + (Runtime.getRuntime().totalMemory() / 1024) + " KB\n" +
                               "Free: " + (Runtime.getRuntime().freeMemory() / 1024) + " KB\n"},
            {"boot/bootlog",   "DashCMD v1.2.2 boot log\n" +
                               "Storage: JSR-75 FileConnection\n" +
                               "Path: " + base + "\n"},
            {"home/" + setupUser + "/.bashrc",
                               "# User profile for " + setupUser + "\n" +
                               "export HOME=/home/" + setupUser + "\n" +
                               "export USER=" + setupUser + "\n" +
                               "export SHELL=/bin/sh\n"},
            {"home/" + setupUser + "/readme.txt",
                               "DashCMD v1.2.2\n" +
                               "==============\n\n" +
                               "Terminal emulator for J2ME devices.\n\n" +
                               "Install location: " + base + "\n" +
                               "User: " + setupUser + "\n" +
                               "Home: /home/" + setupUser + "\n\n" +
                               "Type 'help' for available commands.\n"},
        };

        int totalWork  = dirs.length + files.length + 3;
        int workDone   = 0;
        int okCount    = 0;
        int failCount  = 0;

        addLog("[INFO] Target: " + formatRoot(root));
        addLog("[INFO] Path: Terminal/");
        repaint();
        pause(100);

        // Create directories
        for (int i = 0; i < dirs.length; i++) {
            workDone++;
            progress  = (workDone * 70) / totalWork;
            String dirUrl = base + dirs[i];
            String name   = dirs[i].length() == 0 ? "Terminal/" : dirs[i];
            statusMsg = "mkdir " + name;

            String err = JSR75Storage.mkdirs(dirUrl);
            if (err == null) {
                addLog("[" + pad2(progress) + "%] mkdir " + name + " OK");
                okCount++;
            } else {
                addLog("[" + pad2(progress) + "%] mkdir " + name + " FAIL");
                failCount++;
                logBoot("ERROR", "mkdir: " + dirUrl + " -> " + err);
            }
            repaint();
            pause(60);
        }

        // Write files
        for (int i = 0; i < files.length; i++) {
            workDone++;
            progress  = 70 + ((workDone - dirs.length) * 25) / (files.length + 3);
            String filePath = files[i][0];
            String content  = files[i][1];
            statusMsg = "write " + filePath;

            // Show short name
            String shortName = filePath;
            if (shortName.startsWith("home/" + setupUser + "/")) {
                shortName = "~/" + shortName.substring(
                    ("home/" + setupUser + "/").length());
            }

            String err = JSR75Storage.writeFile(base + filePath, content);
            if (err == null) {
                addLog("[" + pad2(progress) + "%] " + shortName + " OK");
                okCount++;
            } else {
                addLog("[" + pad2(progress) + "%] " + shortName + " FAIL");
                failCount++;
                logBoot("ERROR", "write: " + filePath + " -> " + err);
            }
            repaint();
            pause(60);
        }

        // Verify
        progress  = 95;
        statusMsg = "Verifying installation...";
        addLog("[95%] Verifying Terminal folder...");
        repaint();
        pause(150);

        boolean verified = JSR75Storage.exists(base);
        boolean etcOk    = JSR75Storage.exists(base + "etc/");
        boolean homeOk   = JSR75Storage.exists(base + "home/" + setupUser + "/");

        if (verified && etcOk && homeOk) {
            addLog("[96%] Verification: ALL OK");
        } else {
            String detail = "";
            if (!verified) detail += "root ";
            if (!etcOk)    detail += "/etc ";
            if (!homeOk)   detail += "/home ";
            addLog("[96%] Verification: PARTIAL (" + detail.trim() + " missing)");
            failCount++;
        }
        repaint();
        pause(100);

        // Summary
        progress  = 98;
        statusMsg = "Finalizing...";
        if (failCount == 0) {
            addLog("[98%] " + okCount + " items created successfully");
        } else {
            addLog("[WARN] " + okCount + " OK, " + failCount + " failed");
        }
        repaint();
        pause(100);

        progress  = 100;
        statusMsg = "Installation complete!";
        addLog("[100%] Terminal installed at " + formatRoot(root));
        logBoot("INFO", "JSR-75: " + okCount + " OK, " + failCount + " FAIL");
        repaint();
    }

    // ==================== HELPERS ====================

    private void addLog(String line) {
        installLog.addElement(line);
    }

    private void logBoot(String level, String msg) {
        try {
            AppStorage.logBoot(level, msg);
        } catch (Exception e) {}
    }

    private String formatRoot(String root) {
        if (root != null && root.startsWith("file:///")) {
            return root.substring(8);
        }
        return root != null ? root : "";
    }

    private String formatSize(long bytes) {
        if (bytes < 0)               return "? size";
        if (bytes < 1024)            return bytes + " B";
        if (bytes < 1024 * 1024)     return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024L * 1024 * 1024)) + " GB";
    }

    private String getStorageLabel() {
        if (selectedStorage == 0) {
            return "RMS (device memory)";
        } else if (selectedStorage <= storageRoots.length) {
            return "FC: " + formatRoot(storageRoots[selectedStorage - 1]);
        }
        return "RMS";
    }

    private String getInstallPathPreview() {
        if (selectedStorage == 0) {
            return "rms://Terminal/";
        } else if (selectedStorage <= storageRoots.length) {
            return storageRoots[selectedStorage - 1] + "Terminal/";
        }
        return "rms://Terminal/";
    }

    private String maskPass(String p) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < p.length(); i++) sb.append('*');
        return sb.toString();
    }

    private static String pad2(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private void animateTo(int target, int delay) {
        while (progress < target && animRunning) {
            progress++;
            repaint();
            pause(delay);
        }
    }

    private void pause(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }

    // ==================== PIXEL BANNER ====================

    private void drawPixelBanner(Graphics g, int ox, int oy, int px, int color) {
        g.setColor(color);
        int[][] D = {{1,1,1,0},{1,0,0,1},{1,0,0,1},{1,0,0,1},{1,1,1,0}};
        int[][] A = {{0,1,1,0},{1,0,0,1},{1,1,1,1},{1,0,0,1},{1,0,0,1}};
        int[][] S = {{0,1,1,1},{1,0,0,0},{0,1,1,0},{0,0,0,1},{1,1,1,0}};
        int[][] H = {{1,0,0,1},{1,0,0,1},{1,1,1,1},{1,0,0,1},{1,0,0,1}};
        int[][] C = {{0,1,1,1},{1,0,0,0},{1,0,0,0},{1,0,0,0},{0,1,1,1}};
        int[][] M = {{1,0,0,1},{1,1,1,1},{1,0,0,1},{1,0,0,1},{1,0,0,1}};
        int[][][] letters = {D, A, S, H, C, M, D};
        int gap = px;
        int letterW = 4 * px;
        for (int l = 0; l < letters.length; l++) {
            int lx = ox + l * (letterW + gap);
            int[][] bmp = letters[l];
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 4; col++) {
                    if (bmp[row][col] == 1) {
                        g.fillRect(lx + col * px, oy + row * px, px, px);
                    }
                }
            }
        }
    }

    public void shutdown() {
        animRunning = false;
        if (animThread != null) {
            animThread.interrupt();
        }
    }
}