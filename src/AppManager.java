import java.util.*;

/**
 * AppManager v1.2.2 - App ecosystem for DashCMD.
 * Apps are folders in VirtualFS: /home/user/apps/<name>/
 *   manifest.txt  - app metadata
 *   main.lua / main.sh / main.bsh  - entry point
 *   icon.txt      - pixel-art icon (8x8 grid, '#' = pixel)
 *
 * Built-in apps are seeded into VirtualFS on first run.
 */
public class AppManager {

    private VirtualFS   fs;
    private ScriptEngine engine;
    private String      appsDir;

    // App entry cache
    private Vector      appList; // AppEntry[]

    public static class AppEntry {
        public String name;
        public String path;       // full path to app folder
        public String entryPoint; // main.lua / main.sh / main.bsh
        public String lang;       // lua / sh / bsh
        public String version;
        public String author;
        public String description;
        public String iconData;   // 8 lines of 8 chars ('#' or ' ')
        public boolean canvasMode;// true = runs in ScriptCanvas

        public AppEntry(String name, String path) {
            this.name        = name;
            this.path        = path;
            this.version     = "1.0";
            this.author      = "user";
            this.description = "";
            this.iconData    = "";
            this.canvasMode  = false;
        }
    }

    public AppManager(VirtualFS fs, ScriptEngine engine) {
        this.fs      = fs;
        this.engine  = engine;
        this.appsDir = "/home/" + fs.getUsername() + "/apps";
        this.appList = new Vector();
        seedBuiltinApps();
        scanApps();
    }

    // ==================== BUILT-IN APPS ====================

    private void seedBuiltinApps() {
        if (fs.isDir(appsDir)) return; // already seeded

        // Create apps dir
        fs.createDir(appsDir);

        // --- Hello World app ---
        createApp("hello",
            "Hello World",
            "lua",
            "-- Hello World app\n" +
            "-- DashCMD v1.2.2\n" +
            "canvas = true\n" +
            "bg_color = 0x000000\n" +
            "fg_color = 0x00FF41\n\n" +
            "print(\"Hello, World!\")\n" +
            "print(\"Running on DashCMD v1.2.2\")\n" +
            "print(\"Lua 5.0 scripting engine\")\n" +
            "for i=1,5 do\n" +
            "  print(\"Line \" .. i .. \" of 5\")\n" +
            "end\n" +
            "print(\"Done!\")\n",
            "1.0", "DashCMD",
            "Simple Hello World demo",
            "########\n" +
            "#  HW  #\n" +
            "# Hell #\n" +
            "# Wrl! #\n" +
            "########",
            false);

        // --- Calculator app ---
        createApp("calc",
            "Calculator",
            "bsh",
            "// DashCMD Calculator v1.0\n" +
            "// BeanShell script\n" +
            "print(\"=== DashCMD Calculator ===\");\n" +
            "int a = 42;\n" +
            "int b = 58;\n" +
            "print(a + \" + \" + b + \" = \" + (a+b));\n" +
            "print(a + \" * \" + b + \" = \" + (a*b));\n" +
            "print(\"100 / 4 = \" + (100/4));\n" +
            "print(\"sqrt(144) = 12\");\n" +
            "print(\"Done.\");\n",
            "1.0", "DashCMD",
            "Basic calculator demo",
            "  ####  \n" +
            " #    # \n" +
            " # +x/ #\n" +
            " # -=% #\n" +
            "  ####  ",
            false);

        // --- System Info app ---
        createApp("sysinfo",
            "System Info",
            "sh",
            "#!/bin/sh\n" +
            "# DashCMD System Info\n" +
            "echo '=== DashCMD v1.2.2 ==='\n" +
            "echo 'Platform: J2ME MIDP 2.0'\n" +
            "echo ''\n" +
            "echo '--- Memory ---'\n" +
            "free -h\n" +
            "echo ''\n" +
            "echo '--- CPU ---'\n" +
            "cat /proc/cpuinfo\n" +
            "echo ''\n" +
            "echo '--- Uptime ---'\n" +
            "uptime\n" +
            "echo ''\n" +
            "echo '--- Disk ---'\n" +
            "df -h\n",
            "1.0", "DashCMD",
            "Display system information",
            "########\n" +
            "#  SYS #\n" +
            "# INFO #\n" +
            "########",
            false);

        // --- Canvas Demo app ---
        createApp("canvas_demo",
            "Canvas Demo",
            "lua",
            "-- Canvas Demo - DashCMD v1.2.2\n" +
            "-- This app runs in Canvas mode\n" +
            "canvas = true\n" +
            "title = \"Canvas Demo\"\n\n" +
            "print(\"Canvas mode active\")\n" +
            "print(\"Drawing graphics...\")\n" +
            "print(\"\")\n" +
            "-- Pixel art patterns\n" +
            "local pattern = {\n" +
            "  \"  ***  \",\n" +
            "  \" ***** \",\n" +
            "  \"*******\",\n" +
            "  \" ***** \",\n" +
            "  \"  ***  \",\n" +
            "}\n" +
            "for i=1,5 do\n" +
            "  print(pattern[i])\n" +
            "end\n" +
            "print(\"\")\n" +
            "print(\"Graphics engine ready!\")\n",
            "1.0", "DashCMD",
            "Canvas graphics demonstration",
            "########\n" +
            "#  **  #\n" +
            "# *  * #\n" +
            "#  **  #\n" +
            "########",
            true);

        // --- Network Test app ---
        createApp("nettest",
            "Network Test",
            "sh",
            "#!/bin/sh\n" +
            "# Network connectivity test\n" +
            "echo '=== Network Test ==='\n" +
            "echo ''\n" +
            "echo 'Testing 8.8.8.8 (Google DNS)...'\n" +
            "ping -c 3 8.8.8.8\n" +
            "echo ''\n" +
            "echo 'Testing example.com...'\n" +
            "ping -c 2 example.com\n" +
            "echo ''\n" +
            "echo 'Network interfaces:'\n" +
            "ifconfig\n" +
            "echo ''\n" +
            "echo 'Test complete.'\n",
            "1.0", "DashCMD",
            "Test network connectivity",
            "  >>>>  \n" +
            " >    > \n" +
            ">  NET >\n" +
            " >    > \n" +
            "  >>>>  ",
            false);

        // --- AI Chat app ---
        createApp("ai",
            "AI Chat",
            "lua",
            "-- AI Chat App\n" +
            "-- Opens the AI chat interface\n" +
            "canvas = false\n" +
            "print(\"DashCMD AI v1.2.1\")\n" +
            "print(\"\")\n" +
            "print(\"AI chat is available via:\")\n" +
            "print(\"  ai <your question>\")\n" +
            "print(\"  aichat\")\n" +
            "print(\"\")\n" +
            "print(\"The AI uses the DashCMD API.\")\n" +
            "print(\"Type your question and press enter.\")\n",
            "1.2", "DashCMD",
            "AI assistant chat interface",
            "  ####  \n" +
            " # AI # \n" +
            "#  >>  #\n" +
            " #    # \n" +
            "  ####  ",
            false);

        // --- File Manager app ---
        createApp("files",
            "File Manager",
            "sh",
            "#!/bin/sh\n" +
            "# File Manager launcher\n" +
            "echo '=== File Manager ==='\n" +
            "echo 'Current directory: '$(pwd)\n" +
            "echo ''\n" +
            "ls -la\n" +
            "echo ''\n" +
            "echo 'Commands: ls cd cat mkdir rm cp mv'\n" +
            "echo 'Desktop: Use Files icon for visual mode'\n",
            "1.0", "DashCMD",
            "Browse and manage files",
            "########\n" +
            "# [F]  #\n" +
            "# [F]  #\n" +
            "# [F]  #\n" +
            "########",
            false);

        // --- Snake game (canvas) ---
        createApp("snake",
            "Snake Game",
            "lua",
            "-- Snake v1.0 DashCMD v1.2.2\n" +
            "-- Canvas game demo\n" +
            "canvas = true\n" +
            "title = 'Snake'\n" +
            "print('=== Snake Game ===')\n" +
            "print('Use DPAD to move snake')\n" +
            "print('Eat * to grow')\n" +
            "print('')\n" +
            "-- Simple text-based snake board\n" +
            "local board = {}\n" +
            "for i=1,8 do board[i] = '........' end\n" +
            "board[4] = '...O....'\n" +
            "board[4] = '...OO...'\n" +
            "board[2] = '....*...'\n" +
            "print('+--------+')\n" +
            "for i=1,8 do print('|'..board[i]..'|') end\n" +
            "print('+--------+')\n" +
            "print('')\n" +
            "print('Score: 0 | Length: 2')\n" +
            "print('(Interactive mode in v1.3)')\n",
            "1.0", "DashCMD",
            "Classic snake game demo",
            "########\n" +
            "#......#\n" +
            "#.OO.*.#\n" +
            "#......#\n" +
            "########",
            true);

        // --- Clock / Timer app ---
        createApp("clock",
            "Clock",
            "sh",
            "#!/bin/sh\n" +
            "# DashCMD Clock v1.0\n" +
            "echo '=== DashCMD Clock ==='\n" +
            "echo ''\n" +
            "date\n" +
            "echo ''\n" +
            "uptime\n" +
            "echo ''\n" +
            "echo 'System time above (real device clock)'\n" +
            "echo 'Use: date +\"%H:%M:%S\" for time only'\n",
            "1.0", "DashCMD",
            "Real-time clock display",
            "  ####  \n" +
            " #    # \n" +
            " # 12 # \n" +
            " #    # \n" +
            "  ####  ",
            false);

        // --- Text editor app ---
        createApp("editor",
            "Text Editor",
            "sh",
            "#!/bin/sh\n" +
            "# DashCMD Text Editor v1.0\n" +
            "echo '=== Text Editor ==='\n" +
            "echo ''\n" +
            "echo 'Edit files with:'\n" +
            "echo '  nano <filename>   - view/edit'\n" +
            "echo '  cat <file>        - read file'\n" +
            "echo '  echo text > file  - write'\n" +
            "echo '  echo text >> file - append'\n" +
            "echo ''\n" +
            "echo 'Files in current dir:'\n" +
            "ls -la\n",
            "1.0", "DashCMD",
            "Text editor and file tools",
            "########\n" +
            "#======#\n" +
            "#= Aa =#\n" +
            "#======#\n" +
            "########",
            false);

        // --- Network Monitor ---
        createApp("netmon",
            "Net Monitor",
            "sh",
            "#!/bin/sh\n" +
            "# DashCMD Network Monitor v1.0\n" +
            "echo '=== Network Monitor ==='\n" +
            "echo ''\n" +
            "echo '--- Interfaces ---'\n" +
            "ifconfig\n" +
            "echo ''\n" +
            "echo '--- Connections ---'\n" +
            "netstat\n" +
            "echo ''\n" +
            "echo '--- Routing ---'\n" +
            "route\n" +
            "echo ''\n" +
            "echo '--- DNS Test ---'\n" +
            "ping -c 2 8.8.8.8\n",
            "1.0", "DashCMD",
            "Network status monitor",
            "  >>>>  \n" +
            " >    > \n" +
            ">  <>  >\n" +
            " >    > \n" +
            "  >>>>  ",
            false);

        AppStorage.logBoot("INFO", "Built-in apps seeded to " + appsDir);
    }

    private void createApp(String dirName, String displayName, String lang,
                           String mainScript, String version, String author,
                           String description, String iconData, boolean canvasMode) {
        String appPath = appsDir + "/" + dirName;
        fs.createDir(appPath);

        // manifest.txt
        String manifest =
            "name=" + displayName + "\n" +
            "version=" + version + "\n" +
            "author=" + author + "\n" +
            "description=" + description + "\n" +
            "lang=" + lang + "\n" +
            "main=main." + lang + "\n" +
            "canvas=" + (canvasMode ? "true" : "false") + "\n";
        fs.writeFile(appPath + "/manifest.txt", manifest);

        // main script
        fs.writeFile(appPath + "/main." + lang, mainScript);

        // icon
        if (iconData.length() > 0) {
            fs.writeFile(appPath + "/icon.txt", iconData);
        }
    }

    // ==================== SCANNING ====================

    /** Scan apps directory and rebuild appList. */
    public void scanApps() {
        appList.removeAllElements();
        if (!fs.isDir(appsDir)) return;

        String[] children = fs.listChildren(appsDir);
        for (int i = 0; i < children.length; i++) {
            if (!fs.isDir(children[i])) continue;
            String manifestPath = children[i] + "/manifest.txt";
            if (!fs.isFile(manifestPath)) continue;

            String manifest = fs.readFile(manifestPath);
            if (manifest == null) continue;

            AppEntry app = parseManifest(manifest, children[i]);
            if (app != null) appList.addElement(app);
        }
        AppStorage.logBoot("INFO", "Apps scanned: " + appList.size());
    }

    private AppEntry parseManifest(String content, String appPath) {
        String[] lines = splitLines(content);
        Hashtable props = new Hashtable();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("#") || l.length() == 0) continue;
            int eq = l.indexOf('=');
            if (eq > 0) props.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
        }
        String name = (String) props.get("name");
        if (name == null) name = fs.nameOf(appPath);

        AppEntry app    = new AppEntry(name, appPath);
        app.version     = get(props, "version", "1.0");
        app.author      = get(props, "author",  "user");
        app.description = get(props, "description", "");
        app.lang        = get(props, "lang", "sh");
        app.canvasMode  = "true".equals(get(props, "canvas", "false"));

        String mainFile = get(props, "main", "main." + app.lang);
        app.entryPoint  = appPath + "/" + mainFile;

        // Load icon
        String iconPath = appPath + "/icon.txt";
        if (fs.isFile(iconPath)) app.iconData = fs.readFile(iconPath);

        return app;
    }

    // ==================== LAUNCHING ====================

    /** Launch an app by name. Returns output string. */
    public String launch(String name) {
        AppEntry app = findApp(name);
        if (app == null) return "app: '" + name + "' not found\nRun 'apps' to list installed apps.";
        return launchApp(app);
    }

    /** Launch app entry directly. */
    public String launchApp(AppEntry app) {
        if (app == null) return "app: null entry";
        if (!fs.isFile(app.entryPoint))
            return "app: entry point not found: " + app.entryPoint;
        AppStorage.logBoot("INFO", "Launching app: " + app.name);
        String content = fs.readFile(app.entryPoint);
        if (content == null) return "app: cannot read " + app.entryPoint;
        if ("lua".equals(app.lang))      return engine.runLua(content, app.entryPoint);
        else if ("bsh".equals(app.lang)) return engine.runBsh(content, app.entryPoint);
        else                             return engine.runSh(content, app.entryPoint);
    }

    /** List all installed apps as a formatted string. */
    public String listApps() {
        if (appList.size() == 0) return "No apps installed.\nApps go in: " + appsDir + "/";
        StringBuffer sb = new StringBuffer();
        sb.append("Installed apps (").append(appList.size()).append("):\n");
        for (int i = 0; i < appList.size(); i++) {
            AppEntry a = (AppEntry) appList.elementAt(i);
            sb.append("  ").append(fs.nameOf(a.path))
              .append("\t").append(a.name)
              .append(" v").append(a.version)
              .append(" [").append(a.lang.toUpperCase()).append("]");
            if (a.canvasMode) sb.append(" [CANVAS]");
            sb.append("\n");
            if (a.description.length() > 0)
                sb.append("    ").append(a.description).append("\n");
        }
        sb.append("\nRun: app <name>  or  run <name>");
        return sb.toString().trim();
    }

    /** Install a new app from a script file. */
    public String installScript(String scriptPath, String appName) {
        if (!fs.isFile(scriptPath)) return "install: file not found: " + scriptPath;
        String content = fs.readFile(scriptPath);
        if (content == null) return "install: cannot read file";

        // Detect language from extension
        String lang = "sh";
        if (scriptPath.endsWith(".lua")) lang = "lua";
        else if (scriptPath.endsWith(".bsh")) lang = "bsh";

        String appPath = appsDir + "/" + appName;
        if (fs.isDir(appPath)) return "install: app '" + appName + "' already exists";

        fs.createDir(appPath);
        fs.writeFile(appPath + "/main." + lang, content);
        fs.writeFile(appPath + "/manifest.txt",
            "name=" + appName + "\n" +
            "version=1.0\nauthor=user\nlang=" + lang + "\nmain=main." + lang + "\ncanvas=false\n");

        scanApps(); // refresh
        fs.saveToRMS();
        return "install: '" + appName + "' installed to " + appPath;
    }

    /** Get app list as Vector for DesktopCanvas. */
    public Vector getAppList() { return appList; }

    /** Find app by name (case-insensitive). */
    public AppEntry findApp(String name) {
        if (name == null) return null;
        String lo = name.toLowerCase();
        for (int i = 0; i < appList.size(); i++) {
            AppEntry a = (AppEntry) appList.elementAt(i);
            if (fs.nameOf(a.path).toLowerCase().equals(lo) ||
                a.name.toLowerCase().equals(lo)) return a;
        }
        return null;
    }

    public int getAppCount() { return appList.size(); }

    // ==================== HELPERS ====================

    private static String get(Hashtable h, String key, String def) {
        String v = (String) h.get(key);
        return v != null ? v : def;
    }

    private static String[] splitLines(String s) {
        if (s == null) return new String[0];
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
