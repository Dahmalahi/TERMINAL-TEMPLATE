import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import java.util.*;

/**
 * DashCMD v1.1.1 - TerminalMIDlet
 * CLDC 1.1 / MIDP 2.0
 *
 * v1.1.1 changes:
 *  - First-run install wizard (InstallWizard)
 *  - RMS persistence via AppStorage
 *  - Real device time throughout
 *  - Real HTTP networking (NetworkTask)
 *  - Desktop UI mode (DesktopUI)
 *  - Multi-session (up to 3 independent terminals)
 *  - Background task registry
 *  - Boot log to RMS
 */
public class TerminalMIDlet extends MIDlet implements CommandListener {

    public static final String VERSION = "1.1.1";

    private Display         display;
    private Command         exitCmd;
    private Command         newSessionCmd;
    private Command         switchSessionCmd;
    private Command         desktopCmd;

    // Sessions
    private TerminalCanvas[] sessions;
    private int              sessionCount;
    private int              activeSession;

    // Desktop
    private DesktopUI       desktop;

    // Install wizard
    private InstallWizard   wizard;

    // Background tasks
    private Vector          bgTasks;
    private int             nextTaskId;

    // Boot time for uptime calculation
    private long            bootTime;

    public void startApp() {
        display   = Display.getDisplay(this);
        bgTasks   = new Vector();
        nextTaskId = 1;
        bootTime  = System.currentTimeMillis();

        AppStorage.logBoot("INFO", "DashCMD v" + VERSION + " starting");

        if (!AppStorage.isInstalled()) {
            // First run - show install wizard
            AppStorage.logBoot("INFO", "First run detected - launching install wizard");
            wizard = new InstallWizard(this);
            display.setCurrent(wizard);
        } else {
            // Already installed - boot directly
            AppStorage.logBoot("INFO", "Existing install found - booting terminal");
            String user = AppStorage.loadSetting("default_user", "user");
            String pass = AppStorage.loadSetting("default_pass", "1234");
            launchTerminal(user, pass);
        }
    }

    /** Called by InstallWizard when install is complete. */
    public void finishInstall(String user, String pass) {
        AppStorage.logBoot("INFO", "Install finished for: " + user);
        if (wizard != null) { wizard.shutdown(); wizard = null; }
        launchTerminal(user, pass);
    }

    /** Launch the main terminal session(s). */
    private void launchTerminal(String user, String pass) {
        sessions      = new TerminalCanvas[3];
        sessionCount  = 1;
        activeSession = 0;

        sessions[0] = new TerminalCanvas(this, user, pass);

        exitCmd          = new Command("Exit",        Command.EXIT,   1);
        newSessionCmd    = new Command("New Session", Command.SCREEN, 2);
        switchSessionCmd = new Command("Switch",      Command.SCREEN, 3);
        desktopCmd       = new Command("Desktop",     Command.SCREEN, 4);

        attachCommands(sessions[0]);
        display.setCurrent(sessions[0]);

        AppStorage.logBoot("INFO", "Terminal session 1 launched");
    }

    private void attachCommands(TerminalCanvas t) {
        t.addCommand(exitCmd);
        t.addCommand(newSessionCmd);
        t.addCommand(switchSessionCmd);
        t.addCommand(desktopCmd);
        t.setCommandListener(this);
    }

    /** Return from Desktop to active terminal. */
    public void showTerminal() {
        if (sessions != null && sessions[activeSession] != null) {
            display.setCurrent(sessions[activeSession]);
        }
    }

    /** Launch or return to Desktop UI. */
    public void showDesktop(VirtualFS fs) {
        if (desktop == null) {
            desktop = new DesktopUI(this, fs);
        }
        display.setCurrent(desktop);
        AppStorage.logBoot("INFO", "Desktop mode activated");
    }

    public void pauseApp() {
        AppStorage.logBoot("INFO", "App paused");
    }

    public void destroyApp(boolean unconditional) {
        AppStorage.logBoot("INFO", "DashCMD shutting down");
        if (wizard  != null) wizard.shutdown();
        if (desktop != null) desktop.shutdown();
        for (int i = 0; i < sessionCount; i++) {
            if (sessions != null && sessions[i] != null) sessions[i].shutdown();
        }
        notifyDestroyed();
    }

    public void commandAction(Command c, Displayable d) {
        if (c.getCommandType() == Command.EXIT) {
            destroyApp(true);
        } else if (c == newSessionCmd) {
            openNewSession();
        } else if (c == switchSessionCmd) {
            switchNext();
        } else if (c == desktopCmd) {
            // Get FS from active session
            if (sessions != null && sessions[activeSession] != null) {
                showDesktop(sessions[activeSession].getFS());
            }
        }
    }

    private void openNewSession() {
        if (sessionCount >= 3) { switchNext(); return; }
        String user = AppStorage.loadSetting("default_user", "user");
        String pass = AppStorage.loadSetting("default_pass", "1234");
        TerminalCanvas t = new TerminalCanvas(this, user, pass);
        attachCommands(t);
        sessions[sessionCount] = t;
        activeSession = sessionCount;
        sessionCount++;
        display.setCurrent(t);
        AppStorage.logBoot("INFO", "Session " + sessionCount + " opened");
    }

    private void switchNext() {
        activeSession = (activeSession + 1) % sessionCount;
        display.setCurrent(sessions[activeSession]);
    }

    // ---- Background task API ----

    public int addBgTask(String name) {
        int id = nextTaskId++;
        bgTasks.addElement(new String[]{String.valueOf(id), name, "Running"});
        AppStorage.logBoot("INFO", "BG task started: " + name);
        return id;
    }

    public boolean killBgTask(int id) {
        for (int i = 0; i < bgTasks.size(); i++) {
            String[] t = (String[]) bgTasks.elementAt(i);
            if (t[0].equals(String.valueOf(id))) {
                t[2] = "Stopped";
                AppStorage.logBoot("INFO", "BG task stopped: " + t[1]);
                return true;
            }
        }
        return false;
    }

    public String getBgTaskList() {
        if (bgTasks.size() == 0) return "(no background tasks)";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < bgTasks.size(); i++) {
            String[] t = (String[]) bgTasks.elementAt(i);
            sb.append("[").append(t[0]).append("] ")
              .append(t[2]).append("  ").append(t[1]).append("\n");
        }
        return sb.toString().trim();
    }

    // ---- Accessors ----

    public Display  getDisplay()        { return display; }
    public String   getVersion()        { return VERSION; }
    public int      getSessionCount()   { return sessionCount; }
    public int      getActiveSession()  { return activeSession; }
    public long     getBootTime()       { return bootTime; }
}
