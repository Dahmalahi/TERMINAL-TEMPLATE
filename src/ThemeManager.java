import javax.microedition.rms.*;
import java.util.*;

/**
 * ThemeManager v1.2.2 - Loadable theme system for DashCMD.
 * Themes can be loaded from VirtualFS .theme files or set via shell.
 *
 * Theme file format (.theme):
 *   name=Matrix Green
 *   bg=000000
 *   fg=00FF41
 *   prompt=00BFFF
 *   accent=00FF88
 *   error=FF4444
 *   warning=FFA500
 *   dir=00BFFF
 *   header=001100
 *   cursor=00FF41
 *   input_bg=001100
 *   desktop_bg_top=1A2A4A
 *   desktop_bg_bot=0D1117
 *   taskbar=0D0D0D
 *   icon_bg=1E3A5A
 *   icon_sel=00BFFF
 */
public class ThemeManager {

    private static ThemeManager instance;

    // Current theme colors
    public int BG          = 0x000000;
    public int FG          = 0x00FF41;
    public int PROMPT      = 0x00BFFF;
    public int ACCENT      = 0x00FF88;
    public int ERROR       = 0xFF4444;
    public int WARNING     = 0xFFA500;
    public int DIR_COLOR   = 0x00BFFF;
    public int HEADER      = 0x001100;
    public int CURSOR      = 0x00FF41;
    public int INPUT_BG    = 0x001100;
    public int SCROLLBAR   = 0x1A4A1A;
    public int DT_BG_TOP   = 0x1A2A4A;
    public int DT_BG_BOT   = 0x0D1117;
    public int TASKBAR     = 0x0D0D0D;
    public int ICON_BG     = 0x1E3A5A;
    public int ICON_SEL    = 0x00BFFF;
    public int WHITE       = 0xFFFFFF;
    public int GREY        = 0x666666;

    public String name     = "Matrix Green";

    // Built-in themes
    public static final String[][] BUILTIN_THEMES = {
        // name, bg, fg, prompt, accent, error, header
        {"Matrix Green",   "000000","00FF41","00BFFF","00FF88","FF4444","001100"},
        {"Hacker Red",     "0A0000","FF3333","FF8888","FF6666","FFFF00","100000"},
        {"Ocean Blue",     "000A1A","00AAFF","00FFFF","0088CC","FF4444","00051A"},
        {"Amber Classic",  "0A0800","FFAA00","FFD700","FF8800","FF4444","050400"},
        {"Purple Haze",    "0A000A","CC44FF","FF88FF","8822CC","FF4444","050005"},
        {"Amoled Dark",    "000000","FFFFFF","AAAAAA","888888","FF4444","000000"},
        {"Kali Linux",     "1A1A2E","E94560","0F3460","16213E","FF4444","16213E"},
    };

    private static final String STORE = "dashcmd_theme";

    private ThemeManager() { load(); }

    public static ThemeManager getInstance() {
        if (instance == null) instance = new ThemeManager();
        return instance;
    }

    /** Apply a built-in theme by index. */
    public void applyBuiltin(int idx) {
        if (idx < 0 || idx >= BUILTIN_THEMES.length) return;
        String[] t = BUILTIN_THEMES[idx];
        name     = t[0];
        BG       = parseHex(t[1]);
        FG       = parseHex(t[2]);
        PROMPT   = parseHex(t[3]);
        ACCENT   = parseHex(t[4]);
        ERROR    = parseHex(t[5]);
        HEADER   = parseHex(t[6]);
        // Derive other colors
        CURSOR   = FG;
        INPUT_BG = blendColor(BG, HEADER, 50);
        DT_BG_TOP= blendColor(BG, PROMPT, 20);
        DT_BG_BOT= BG;
        TASKBAR  = blendColor(BG, 0x000000, 80);
        ICON_BG  = blendColor(BG, PROMPT, 15);
        ICON_SEL = PROMPT;
        SCROLLBAR= blendColor(BG, FG, 15);
        DIR_COLOR= PROMPT;
        WARNING  = 0xFFA500;
        save();
    }

    /** Load theme from a .theme file content string. */
    public String applyFromFile(String content) {
        if (content == null || content.length() == 0) return "Empty theme file";
        String[] lines = splitLines(content);
        Hashtable props = new Hashtable();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#") || line.length() == 0) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.put(line.substring(0, eq).trim().toLowerCase(),
                          line.substring(eq + 1).trim());
            }
        }
        if (props.containsKey("name"))           name      = (String) props.get("name");
        if (props.containsKey("bg"))             BG        = parseHex((String)props.get("bg"));
        if (props.containsKey("fg"))             FG        = parseHex((String)props.get("fg"));
        if (props.containsKey("prompt"))         PROMPT    = parseHex((String)props.get("prompt"));
        if (props.containsKey("accent"))         ACCENT    = parseHex((String)props.get("accent"));
        if (props.containsKey("error"))          ERROR     = parseHex((String)props.get("error"));
        if (props.containsKey("warning"))        WARNING   = parseHex((String)props.get("warning"));
        if (props.containsKey("dir"))            DIR_COLOR = parseHex((String)props.get("dir"));
        if (props.containsKey("header"))         HEADER    = parseHex((String)props.get("header"));
        if (props.containsKey("cursor"))         CURSOR    = parseHex((String)props.get("cursor"));
        if (props.containsKey("input_bg"))       INPUT_BG  = parseHex((String)props.get("input_bg"));
        if (props.containsKey("desktop_bg_top")) DT_BG_TOP = parseHex((String)props.get("desktop_bg_top"));
        if (props.containsKey("desktop_bg_bot")) DT_BG_BOT = parseHex((String)props.get("desktop_bg_bot"));
        if (props.containsKey("taskbar"))        TASKBAR   = parseHex((String)props.get("taskbar"));
        if (props.containsKey("icon_bg"))        ICON_BG   = parseHex((String)props.get("icon_bg"));
        if (props.containsKey("icon_sel"))       ICON_SEL  = parseHex((String)props.get("icon_sel"));
        if (props.containsKey("scrollbar"))      SCROLLBAR = parseHex((String)props.get("scrollbar"));
        save();
        return "Theme loaded: " + name;
    }

    /** Generate a theme file content string for saving. */
    public String exportTheme() {
        StringBuffer sb = new StringBuffer();
        sb.append("# DashCMD v1.2.2 Theme File\n");
        sb.append("name=").append(name).append("\n");
        sb.append("bg=").append(toHex(BG)).append("\n");
        sb.append("fg=").append(toHex(FG)).append("\n");
        sb.append("prompt=").append(toHex(PROMPT)).append("\n");
        sb.append("accent=").append(toHex(ACCENT)).append("\n");
        sb.append("error=").append(toHex(ERROR)).append("\n");
        sb.append("warning=").append(toHex(WARNING)).append("\n");
        sb.append("dir=").append(toHex(DIR_COLOR)).append("\n");
        sb.append("header=").append(toHex(HEADER)).append("\n");
        sb.append("cursor=").append(toHex(CURSOR)).append("\n");
        sb.append("input_bg=").append(toHex(INPUT_BG)).append("\n");
        sb.append("desktop_bg_top=").append(toHex(DT_BG_TOP)).append("\n");
        sb.append("desktop_bg_bot=").append(toHex(DT_BG_BOT)).append("\n");
        sb.append("taskbar=").append(toHex(TASKBAR)).append("\n");
        sb.append("icon_bg=").append(toHex(ICON_BG)).append("\n");
        sb.append("icon_sel=").append(toHex(ICON_SEL)).append("\n");
        sb.append("scrollbar=").append(toHex(SCROLLBAR)).append("\n");
        return sb.toString();
    }

    /** List all built-in theme names. */
    public String listThemes() {
        StringBuffer sb = new StringBuffer();
        sb.append("Built-in themes:\n");
        for (int i = 0; i < BUILTIN_THEMES.length; i++) {
            sb.append("  ").append(i).append(") ").append(BUILTIN_THEMES[i][0]).append("\n");
        }
        sb.append("\nCurrent: ").append(name).append("\n");
        sb.append("Use: theme <number>  or  theme load <file.theme>\n");
        sb.append("     theme save <file.theme>  to export current theme");
        return sb.toString();
    }

    // ==================== PERSISTENCE ====================

    private void save() {
        AppStorage.saveSetting("theme_name",    name);
        AppStorage.saveSetting("theme_bg",      toHex(BG));
        AppStorage.saveSetting("theme_fg",      toHex(FG));
        AppStorage.saveSetting("theme_prompt",  toHex(PROMPT));
        AppStorage.saveSetting("theme_accent",  toHex(ACCENT));
        AppStorage.saveSetting("theme_error",   toHex(ERROR));
        AppStorage.saveSetting("theme_header",  toHex(HEADER));
        AppStorage.saveSetting("theme_cursor",  toHex(CURSOR));
        AppStorage.saveSetting("theme_input_bg",toHex(INPUT_BG));
        AppStorage.saveSetting("theme_dt_top",  toHex(DT_BG_TOP));
        AppStorage.saveSetting("theme_dt_bot",  toHex(DT_BG_BOT));
        AppStorage.saveSetting("theme_taskbar", toHex(TASKBAR));
        AppStorage.saveSetting("theme_icon_bg", toHex(ICON_BG));
        AppStorage.saveSetting("theme_icon_sel",toHex(ICON_SEL));
    }

    private void load() {
        String n = AppStorage.loadSetting("theme_name", null);
        if (n == null) return; // use defaults
        name      = n;
        BG        = parseHexSetting("theme_bg",       BG);
        FG        = parseHexSetting("theme_fg",       FG);
        PROMPT    = parseHexSetting("theme_prompt",   PROMPT);
        ACCENT    = parseHexSetting("theme_accent",   ACCENT);
        ERROR     = parseHexSetting("theme_error",    ERROR);
        HEADER    = parseHexSetting("theme_header",   HEADER);
        CURSOR    = parseHexSetting("theme_cursor",   CURSOR);
        INPUT_BG  = parseHexSetting("theme_input_bg", INPUT_BG);
        DT_BG_TOP = parseHexSetting("theme_dt_top",   DT_BG_TOP);
        DT_BG_BOT = parseHexSetting("theme_dt_bot",   DT_BG_BOT);
        TASKBAR   = parseHexSetting("theme_taskbar",  TASKBAR);
        ICON_BG   = parseHexSetting("theme_icon_bg",  ICON_BG);
        ICON_SEL  = parseHexSetting("theme_icon_sel", ICON_SEL);
    }

    private int parseHexSetting(String key, int def) {
        String v = AppStorage.loadSetting(key, null);
        return v != null ? parseHex(v) : def;
    }

    // ==================== HELPERS ====================

    private static int parseHex(String s) {
        if (s == null || s.length() == 0) return 0;
        s = s.trim();
        if (s.length() > 0 && s.charAt(0) == '#') s = s.substring(1);
        try { return (int) Long.parseLong(s, 16); }
        catch (Exception e) { return 0; }
    }

    private static String toHex(int color) {
        String h = Integer.toHexString(color & 0xFFFFFF).toUpperCase();
        while (h.length() < 6) h = "0" + h;
        return h;
    }

    private static int blendColor(int a, int b, int pctA) {
        int ra = (a >> 16) & 0xFF, rb = (b >> 16) & 0xFF;
        int ga = (a >>  8) & 0xFF, gb = (b >>  8) & 0xFF;
        int ba =  a        & 0xFF, bb =  b        & 0xFF;
        int r = ra + (rb - ra) * (100 - pctA) / 100;
        int g = ga + (gb - ga) * (100 - pctA) / 100;
        int bl= ba + (bb - ba) * (100 - pctA) / 100;
        return (r << 16) | (g << 8) | bl;
    }

    private static String[] splitLines(String s) {
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
