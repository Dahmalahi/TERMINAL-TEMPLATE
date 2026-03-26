import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import java.util.*;

/**
 * TerminalOS v1.2.2 - DashCMD Main MIDlet
 * CLDC 1.1 / MIDP 2.0
 *
 * Architecture:
 *  TerminalOS (MIDlet)
 *    |-- InstallWizard  (first-run setup)
 *    |-- TerminalCanvas (terminal mode)
 *    |-- DesktopCanvas  (desktop/app launcher)
 *    |-- ScriptCanvas   (script runner with graphics)
 *    |-- AIChatCanvas   (AI chat UI)
 *    |-- FileBrowser    (JSR-75 file browser)
 *
 * All connected via TerminalOS.show*() methods.
 * Sessions: up to 3 independent terminal sessions.
 */
public class TerminalOS extends MIDlet implements CommandListener {

    public static final String VERSION = "1.2.2";
    public static final String APP_NAME = "DashCMD";

    private Display         display;

    // Screens
    private TerminalCanvas[] sessions;
    private int              sessionCount;
    private int              activeSession;
    private DesktopCanvas    desktop;
    private InstallWizard    wizard;
    private ScriptCanvas     scriptCanvas;
    private AIChatCanvas     aiCanvas;

    // Shared systems
    private VirtualFS        sharedFS;   // shared between sessions
    private AITerminal       aiTerminal;
    private ThemeManager     theme;
    private AppManager       appManager;

    // Commands
    private Command exitCmd, newSessCmd, switchCmd, desktopCmd, aiCmd;

    // Boot time
    private long bootTime;

    // ==================== LIFECYCLE ====================

    public void startApp() {
        display   = Display.getDisplay(this);
        bootTime  = System.currentTimeMillis();
        theme     = ThemeManager.getInstance();

        AppStorage.logBoot("INFO", APP_NAME + " v" + VERSION + " starting");

        if (!AppStorage.isInstalled()) {
            AppStorage.logBoot("INFO", "First run - showing install wizard");
            wizard = new InstallWizard(this);
            display.setCurrent(wizard);
        } else {
            AppStorage.logBoot("INFO", "Booting from existing install");
            String user = AppStorage.loadSetting("default_user", "user");
            String pass = AppStorage.loadSetting("default_pass", "1234");
            boot(user, pass);
        }
    }

    /** Called by InstallWizard when installation completes. */
    public void finishInstall(String user, String pass) {
        if (wizard != null) { wizard.shutdown(); wizard = null; }
        AppStorage.logBoot("INFO", "Install complete for: " + user);
        boot(user, pass);
    }

    private void boot(String user, String pass) {
        // Initialize shared FS (loads from RMS if available)
        sharedFS   = new VirtualFS(user, "kali");
        aiTerminal = new AITerminal(user);

        // Initialize app manager (seeds built-in apps)
        // ScriptEngine starts with null shell; wired to real shell after session 0 is created
        ScriptEngine eng = new ScriptEngine(null, sharedFS);
        appManager = new AppManager(sharedFS, eng);

        // Build commands
        exitCmd    = new Command("Exit",    Command.EXIT,   1);
        newSessCmd = new Command("Session+",Command.SCREEN, 2);
        switchCmd  = new Command("Switch",  Command.SCREEN, 3);
        desktopCmd = new Command("Desktop", Command.SCREEN, 4);
        aiCmd      = new Command("AI Chat", Command.SCREEN, 5);

        // Launch first session
        sessions      = new TerminalCanvas[3];
        sessionCount  = 1;
        activeSession = 0;
        sessions[0]   = new TerminalCanvas(this, user, pass, sharedFS, appManager, aiTerminal);
        // Now wire the real shell into the AppManager engine so .sh scripts execute correctly
        eng.setShell(sessions[0].getShell());
        attachCommands(sessions[0]);
        display.setCurrent(sessions[0]);

        AppStorage.logBoot("INFO", "Terminal session 1 started");
    }

    private void attachCommands(TerminalCanvas t) {
        t.addCommand(exitCmd);
        t.addCommand(newSessCmd);
        t.addCommand(switchCmd);
        t.addCommand(desktopCmd);
        t.addCommand(aiCmd);
        t.setCommandListener(this);
    }

    public void pauseApp()  { AppStorage.logBoot("INFO", "paused"); }

    public void destroyApp(boolean u) {
        AppStorage.logBoot("INFO", "shutdown");
        if (wizard       != null) wizard.shutdown();
        if (desktop      != null) desktop.shutdown();
        if (scriptCanvas != null) scriptCanvas.shutdown();
        if (aiCanvas     != null) aiCanvas.shutdown();
        for (int i = 0; i < sessionCount; i++) {
            if (sessions != null && sessions[i] != null) sessions[i].shutdown();
        }
        if (sharedFS != null) sharedFS.saveToRMS();
        notifyDestroyed();
    }

    public void commandAction(Command c, Displayable d) {
        if (c.getCommandType() == Command.EXIT) { destroyApp(true); }
        else if (c == newSessCmd)  openNewSession();
        else if (c == switchCmd)   switchSession();
        else if (c == desktopCmd)  showDesktop();
        else if (c == aiCmd)       showAIChat();
    }

    // ==================== NAVIGATION ====================

    public void showTerminal() {
        if (sessions != null && sessions[activeSession] != null)
            display.setCurrent(sessions[activeSession]);
    }

    /**
     * Inject a command into the active terminal session, switch to it, and execute.
     * Used by DesktopCanvas quick-command input.
     */
    public void injectCommand(String cmd) {
        if (sessions == null || sessions[activeSession] == null) return;
        sessions[activeSession].injectAndRun(cmd);
        display.setCurrent(sessions[activeSession]);
    }

    public void showDesktop() {
        if (desktop == null) desktop = new DesktopCanvas(this, sharedFS, appManager, aiTerminal);
        display.setCurrent(desktop);
        AppStorage.logBoot("INFO", "Desktop activated");
    }

    /** Launch a script in ScriptCanvas. */
    public void showScript(String path, boolean canvasMode) {
        if (scriptCanvas != null) scriptCanvas.shutdown();
        ScriptEngine eng = sessions[activeSession] != null
            ? sessions[activeSession].getScriptEngine()
            : new ScriptEngine(null, sharedFS);
        scriptCanvas = new ScriptCanvas(this, sharedFS, eng, path, canvasMode);
        display.setCurrent(scriptCanvas);
    }

    public void showAIChat() {
        if (aiCanvas != null) aiCanvas.shutdown();
        aiCanvas = aiTerminal.openChat(this);
        display.setCurrent(aiCanvas);
    }

    private void openNewSession() {
        if (sessionCount >= 3) { switchSession(); return; }
        String user = AppStorage.loadSetting("default_user", "user");
        String pass = AppStorage.loadSetting("default_pass", "1234");
        TerminalCanvas t = new TerminalCanvas(this, user, pass, sharedFS, appManager, aiTerminal);
        attachCommands(t);
        sessions[sessionCount] = t;
        activeSession = sessionCount;
        sessionCount++;
        display.setCurrent(t);
        AppStorage.logBoot("INFO", "Session " + sessionCount + " opened");
    }

    private void switchSession() {
        activeSession = (activeSession + 1) % sessionCount;
        display.setCurrent(sessions[activeSession]);
    }

    // ==================== BACKGROUND TASKS ====================

    private Vector bgTasks   = new Vector();
    private int    nextTaskId = 1;

    public int addBgTask(String name) {
        int id = nextTaskId++;
        bgTasks.addElement(new String[]{String.valueOf(id), name, "Running"});
        return id;
    }

    public boolean killBgTask(int id) {
        for (int i = 0; i < bgTasks.size(); i++) {
            String[] t = (String[]) bgTasks.elementAt(i);
            if (t[0].equals(String.valueOf(id))) { t[2] = "Stopped"; return true; }
        }
        return false;
    }

    public String getBgTaskList() {
        if (bgTasks.size() == 0) return "(no background tasks)";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < bgTasks.size(); i++) {
            String[] t = (String[]) bgTasks.elementAt(i);
            sb.append("[").append(t[0]).append("] ").append(t[2]).append("  ").append(t[1]).append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== ACCESSORS ====================

    public Display       getDisplay()      { return display; }
    public String        getVersion()      { return VERSION; }
    public long          getBootTime()     { return bootTime; }
    public VirtualFS     getSharedFS()     { return sharedFS; }
    public AITerminal    getAI()           { return aiTerminal; }
    public ThemeManager  getTheme()        { return theme; }
    public AppManager    getAppManager()   { return appManager; }
    public int           getSessionCount() { return sessionCount; }
    public int           getActiveSession(){ return activeSession; }
}
