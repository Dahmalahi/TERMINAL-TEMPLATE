import javax.microedition.lcdui.*;
import javax.microedition.rms.*;
import java.util.*;

/**
 * InstallWizard v1.1.1 - First-run install wizard for DashCMD.
 * Shown when the app has never been installed on this device.
 *
 * Sequence:
 *  1. Boot splash (animated progress)
 *  2. Welcome screen
 *  3. Storage selection (RMS only in MIDP 2.0)
 *  4. Username + password setup
 *  5. Install progress (copying /Terminal/ structure)
 *  6. Done -> launch terminal
 */
public class InstallWizard extends Canvas implements CommandListener {

    private TerminalMIDlet midlet;
    private int            step;        // 0=splash, 1=welcome, 2=user, 3=install, 4=done
    private int            progress;    // 0-100
    private String         statusMsg;
    private String         logMsg;
    private boolean        installing;

    // User setup inputs
    private String         setupUser = "user";
    private String         setupPass = "1234";
    private int            selectedStorage = 0; // 0=RMS

    // Colours
    private static final int BG      = 0x0D1117;
    private static final int GREEN   = 0x00FF41;
    private static final int CYAN    = 0x00BFFF;
    private static final int WHITE   = 0xFFFFFF;
    private static final int GREY    = 0x888888;
    private static final int RED     = 0xFF4444;
    private static final int YELLOW  = 0xFFFF00;

    private Font fontS, fontM;
    private int  W, H;

    // Install log lines shown during install
    private Vector installLog;

    // Commands
    private Command nextCmd;
    private Command backCmd;
    private Command inputCmd;

    // Timer for splash animation
    private Thread animThread;
    private volatile boolean animRunning;

    public InstallWizard(TerminalMIDlet midlet) {
        this.midlet     = midlet;
        this.step       = 0;
        this.progress   = 0;
        this.statusMsg  = "Initializing...";
        this.logMsg     = "";
        this.installing = false;
        this.installLog = new Vector();

        fontS = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontM = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD,  Font.SIZE_MEDIUM);

        nextCmd  = new Command("Next",   Command.OK,     1);
        backCmd  = new Command("Back",   Command.BACK,   2);
        inputCmd = new Command("Input",  Command.SCREEN, 3);

        addCommand(nextCmd);
        addCommand(inputCmd);
        setCommandListener(this);

        // Start splash animation
        startSplash();
    }

    private void startSplash() {
        step       = 0;
        progress   = 0;
        statusMsg  = "Booting DashCMD v1.1.1...";
        animRunning = true;
        animThread  = new Thread(new Runnable() {
            public void run() {
                String[] msgs = {
                    "Loading kernel...",
                    "Mounting filesystems...",
                    "Starting services...",
                    "Checking RMS storage...",
                    "Loading DashCMD v1.1.1...",
                    "Ready."
                };
                for (int i = 0; i < msgs.length && animRunning; i++) {
                    statusMsg = msgs[i];
                    AppStorage.logBoot("INFO", msgs[i]);
                    int target = (i + 1) * 16;
                    while (progress < target && animRunning) {
                        progress++;
                        repaint();
                        try { Thread.sleep(30); } catch (InterruptedException e) { return; }
                    }
                }
                // Pause then advance to welcome
                try { Thread.sleep(400); } catch (InterruptedException e) {}
                if (animRunning) {
                    step = 1;
                    repaint();
                }
            }
        });
        animThread.start();
    }

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

    // ---- Step 0: Boot splash ----
    private void paintSplash(Graphics g) {
        // Draw pixel-art D A S H C M D banner
        int px = Math.max(1, (W - 8) / 38);
        int bw = 7 * (4 * px + px);
        int bx = (W - bw) / 2;
        int by = H / 5;
        drawPixelBanner(g, bx, by, px, GREEN);

        // Version
        g.setFont(fontS);
        g.setColor(CYAN);
        String ver = "v1.1.1";
        g.drawString(ver, (W - fontS.stringWidth(ver)) / 2, by + 5 * px + px * 2 + 4,
                     Graphics.TOP | Graphics.LEFT);

        // Status message
        g.setColor(GREY);
        g.setFont(fontS);
        g.drawString(statusMsg, 4, H / 2, Graphics.TOP | Graphics.LEFT);

        // Progress bar
        int barY = H / 2 + fontS.getHeight() + 6;
        int barW = W - 8;
        g.setColor(0x1A3A1A);
        g.fillRect(4, barY, barW, 8);
        g.setColor(GREEN);
        g.fillRect(4, barY, barW * progress / 100, 8);
        g.setColor(GREY);
        g.drawRect(4, barY, barW, 8);

        // Progress %
        g.setColor(WHITE);
        String pct = progress + "%";
        g.drawString(pct, (W - fontS.stringWidth(pct)) / 2, barY + 12, Graphics.TOP | Graphics.LEFT);
    }

    // ---- Step 1: Welcome / Storage selection ----
    private void paintWelcome(Graphics g) {
        int y = 4;
        g.setFont(fontM);
        g.setColor(GREEN);
        g.drawString("DashCMD v1.1.1", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontM.getHeight() + 2;

        g.setFont(fontS);
        g.setColor(CYAN);
        g.drawString("First time setup", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 6;

        g.setColor(WHITE);
        g.drawString("Storage location:", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 2;

        // Storage options
        String[] opts = {"[RMS] Device memory (recommended)", "[FC]  FileConnection (if supported)"};
        for (int i = 0; i < opts.length; i++) {
            if (i == selectedStorage) {
                g.setColor(GREEN);
                g.drawString("> " + opts[i], 4, y, Graphics.TOP | Graphics.LEFT);
            } else {
                g.setColor(GREY);
                g.drawString("  " + opts[i], 4, y, Graphics.TOP | Graphics.LEFT);
            }
            y += fontS.getHeight() + 2;
        }

        y += 6;
        g.setColor(GREY);
        g.drawString("Installing to /Terminal/", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight();
        g.drawString("Press Next to continue", 4, y, Graphics.TOP | Graphics.LEFT);

        // Bottom hint
        g.setColor(CYAN);
        g.drawString("Up/Dn=select  Next=confirm", 4, H - fontS.getHeight() - 2,
                     Graphics.TOP | Graphics.LEFT);
    }

    // ---- Step 2: User setup ----
    private void paintSetup(Graphics g) {
        int y = 4;
        g.setFont(fontM);
        g.setColor(GREEN);
        g.drawString("Create account", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontM.getHeight() + 4;

        g.setFont(fontS);
        g.setColor(WHITE);
        g.drawString("Username: " + setupUser, 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 2;
        g.drawString("Password: " + maskPass(setupPass), 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 8;

        g.setColor(GREY);
        g.drawString("Press 'Input' to change.", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 2;
        g.drawString("Default: user / 1234", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 8;

        g.setColor(YELLOW);
        g.drawString("root password: toor", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 2;
        g.setColor(GREY);
        g.drawString("(change with passwd after)", 4, y, Graphics.TOP | Graphics.LEFT);

        g.setColor(CYAN);
        g.drawString("Next=install  Input=change", 4, H - fontS.getHeight() - 2,
                     Graphics.TOP | Graphics.LEFT);
    }

    // ---- Step 3: Install progress ----
    private void paintInstall(Graphics g) {
        int y = 4;
        g.setFont(fontM);
        g.setColor(GREEN);
        g.drawString("Installing...", 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontM.getHeight() + 4;

        // Progress bar
        int barW = W - 8;
        g.setColor(0x1A3A1A);
        g.fillRect(4, y, barW, 10);
        g.setColor(GREEN);
        g.fillRect(4, y, barW * progress / 100, 10);
        g.setColor(GREY);
        g.drawRect(4, y, barW, 10);
        y += 14;

        g.setFont(fontS);
        g.setColor(WHITE);
        g.drawString(progress + "%  " + statusMsg, 4, y, Graphics.TOP | Graphics.LEFT);
        y += fontS.getHeight() + 6;

        // Install log
        g.setColor(GREEN);
        int logLines = Math.min(installLog.size(), (H - y - 20) / fontS.getHeight());
        int logStart = Math.max(0, installLog.size() - logLines);
        for (int i = logStart; i < installLog.size(); i++) {
            g.drawString((String) installLog.elementAt(i), 4, y, Graphics.TOP | Graphics.LEFT);
            y += fontS.getHeight();
        }
    }

    // ---- Step 4: Done ----
    private void paintDone(Graphics g) {
        int y = H / 4;
        g.setFont(fontM);
        g.setColor(GREEN);
        String done = "Install Complete!";
        g.drawString(done, (W - fontM.stringWidth(done)) / 2, y, Graphics.TOP | Graphics.LEFT);
        y += fontM.getHeight() + 8;

        g.setFont(fontS);
        g.setColor(WHITE);
        String[] lines = {
            "DashCMD v1.1.1 installed.",
            "User: " + setupUser,
            "Home: /home/" + setupUser,
            "",
            "Login: " + setupUser + " / " + setupPass,
            "Root:  root / toor",
            "",
            "Press Next to launch."
        };
        for (int i = 0; i < lines.length; i++) {
            int color = lines[i].startsWith("Login") || lines[i].startsWith("Root") ? YELLOW : WHITE;
            g.setColor(color);
            g.drawString(lines[i], 8, y, Graphics.TOP | Graphics.LEFT);
            y += fontS.getHeight() + 1;
        }
    }

    // ==================== KEY HANDLING ====================

    protected void keyPressed(int keyCode) {
        int ga = -1;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}

        if (step == 1) {
            if (ga == UP)   selectedStorage = 0;
            if (ga == DOWN) selectedStorage = 1;
            repaint();
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == inputCmd) {
            openInputDialog();
        } else if (c == nextCmd || c.getCommandType() == Command.OK) {
            advance();
        } else if (c == backCmd) {
            if (step > 1) { step--; repaint(); }
        }
    }

    private void advance() {
        if (step == 0) { step = 1; repaint(); }
        else if (step == 1) { step = 2; repaint(); }
        else if (step == 2) { step = 3; startInstall(); }
        else if (step == 4) { midlet.finishInstall(setupUser, setupPass); }
    }

    private void openInputDialog() {
        if (step != 2) return;
        TextBox tb = new TextBox("Username", setupUser, 32, TextField.ANY);
        Command ok = new Command("OK", Command.OK, 1);
        tb.addCommand(ok);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                String s = ((TextBox)d).getString();
                if (s != null && s.length() > 0) setupUser = s.trim();
                midlet.getDisplay().setCurrent(InstallWizard.this);
                openPasswordDialog();
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    private void openPasswordDialog() {
        TextBox tb = new TextBox("Password", setupPass, 32, TextField.PASSWORD);
        Command ok = new Command("OK", Command.OK, 1);
        tb.addCommand(ok);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                String s = ((TextBox)d).getString();
                if (s != null && s.length() >= 4) setupPass = s;
                midlet.getDisplay().setCurrent(InstallWizard.this);
                repaint();
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    // ==================== INSTALL THREAD ====================

    private void startInstall() {
        installing  = true;
        progress    = 0;
        installLog.removeAllElements();
        new Thread(new Runnable() {
            public void run() {
                runInstall();
            }
        }).start();
    }

    private void runInstall() {
        String[][] steps = {
            {"2",  "Preparing storage..."},
            {"5",  "Creating /Terminal/"},
            {"8",  "Creating /bin/"},
            {"11", "Creating /boot/"},
            {"14", "Creating /dev/"},
            {"17", "Creating /etc/"},
            {"20", "Creating /home/" + setupUser + "/"},
            {"24", "Creating /lib/"},
            {"27", "Creating /mnt/"},
            {"30", "Creating /opt/"},
            {"33", "Creating /proc/"},
            {"36", "Creating /root/"},
            {"39", "Creating /sbin/"},
            {"42", "Creating /tmp/"},
            {"45", "Creating /usr/bin/"},
            {"48", "Creating /usr/lib/"},
            {"51", "Creating /usr/local/bin/"},
            {"54", "Creating /var/log/"},
            {"57", "Creating /var/cache/"},
            {"60", "Installing /etc/hostname"},
            {"63", "Installing /etc/passwd"},
            {"66", "Installing /etc/motd"},
            {"69", "Installing /etc/profile"},
            {"72", "Installing /proc/cpuinfo"},
            {"75", "Installing /proc/meminfo"},
            {"78", "Installing /boot/bootlog"},
            {"81", "Installing ~/.bashrc"},
            {"84", "Installing ~/readme.txt"},
            {"87", "Setting up credentials"},
            {"90", "Writing boot log"},
            {"93", "Saving filesystem to RMS"},
            {"96", "Verifying installation"},
            {"99", "Finalizing..."},
            {"100","Installation complete!"}
        };

        AppStorage.logBoot("INFO", "Install started for user: " + setupUser);

        for (int i = 0; i < steps.length; i++) {
            progress  = Integer.parseInt(steps[i][0]);
            statusMsg = steps[i][1];
            String logLine = "[" + pad2(progress) + "%] " + steps[i][1];
            installLog.addElement(logLine);
            AppStorage.logBoot("INFO", steps[i][1]);
            repaint();
            try { Thread.sleep(120); } catch (InterruptedException e) {}
        }

        AppStorage.logBoot("INFO", "Install complete. User: " + setupUser);
        AppStorage.markInstalled();
        AppStorage.saveSetting("default_user", setupUser);
        AppStorage.saveSetting("default_pass", setupPass);

        progress  = 100;
        step      = 4;
        installing = false;
        repaint();
    }

    // ==================== PIXEL ART BANNER ====================

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
                    if (bmp[row][col] == 1)
                        g.fillRect(lx + col * px, oy + row * px, px, px);
                }
            }
        }
    }

    // ==================== HELPERS ====================

    private String maskPass(String p) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < p.length(); i++) sb.append('*');
        return sb.toString();
    }

    private static String pad2(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    public void shutdown() {
        animRunning = false;
        if (animThread != null) animThread.interrupt();
    }
}
