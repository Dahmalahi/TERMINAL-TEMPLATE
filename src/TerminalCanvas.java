import javax.microedition.lcdui.*;
import java.util.*;

/**
 * TerminalCanvas - renders the terminal screen and handles key input.
 * Pure MIDP 2.0 / CLDC 1.1, no HTML.
 * Green-on-black theme (classic terminal aesthetic).
 */
public class TerminalCanvas extends Canvas {

    // ---- Configuration ----
    private static final int MAX_LINES    = 500;  // scrollback buffer
    private static final int MARGIN_X     = 4;
    private static final int MARGIN_Y     = 4;
    private static final int CURSOR_BLINK = 500;  // ms

    // ---- Colors ----
    private static final int COLOR_BG       = 0x000000;  // black
    private static final int COLOR_FG       = 0x00FF41;  // matrix green
    private static final int COLOR_PROMPT   = 0x00BFFF;  // bright cyan
    private static final int COLOR_ERROR    = 0xFF4444;  // red
    private static final int COLOR_INPUT    = 0xFFFFFF;  // white for current input
    private static final int COLOR_HEADER   = 0x00FF41;
    private static final int COLOR_CURSOR   = 0x00FF41;
    private static final int COLOR_SCROLLBAR= 0x1A4A1A;

    // ---- Terminal state ----
    private Vector screenBuffer;    // Vector of String[] {text, colorStr}
    private String currentInput;    // text user is typing
    private int     cursorPos;      // cursor position in currentInput
    private int     scrollOffset;   // lines scrolled from bottom (0 = at bottom)
    private boolean cursorVisible;  // blink state
    private boolean cleared;

    private Shell   shell;
    private VirtualFS fs;
    private TerminalMIDlet midlet;

    // ---- Font ----
    private Font font;
    private int  fontW, fontH;

    // ---- Dimensions ----
    private int screenW, screenH;
    private int cols, rows;

    // ---- Input mode ----
    private int     inputMode;      // 0=normal, 1=selection (for autocomplete)
    private int     historyIdx;     // -1 = current input
    private String  savedInput;     // saved line when browsing history

    // ---- Keyboard input accumulator ----
    private StringBuffer keyBuffer;

    // ---- Timer thread ----
    private Thread  blinkThread;
    private volatile boolean running;

    // ---- Native TextBox input overlay (press 5 to open) ----
    private javax.microedition.lcdui.TextBox inputBox;

    public TerminalCanvas(TerminalMIDlet midlet) {
        this.midlet     = midlet;
        this.screenBuffer = new Vector();
        this.currentInput = "";
        this.cursorPos  = 0;
        this.scrollOffset = 0;
        this.cursorVisible = true;
        this.historyIdx = -1;
        this.savedInput = "";
        this.keyBuffer  = new StringBuffer();
        this.inputMode  = 0;
        this.inputBox   = null;

        // Init filesystem and shell
        fs    = new VirtualFS("user", "kali");
        shell = new Shell(fs);

        // Pick smallest fixed-width font
        font  = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontW = font.charWidth('M');
        fontH = font.getHeight();

        // Boot screen
        printBanner();

        // Start blink thread
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

    private void printBanner() {
        // info lines only - art is drawn via Graphics in paint()
        addLine("", COLOR_FG);
        addLine("DashCMD v1.0", COLOR_PROMPT);
        addLine("J2ME CLDC1.1/MIDP2.0", COLOR_PROMPT);
        addLine("Kali/Linux/Win/Mac", 0x888888);
        addLine("", COLOR_FG);
        addLine("'help' = command list", COLOR_FG);
        addLine("5=input  #=enter", 0x888888);
        addLine("*=backspace", 0x888888);
        addLine("Up/Dn=history", 0x888888);
        addLine("", COLOR_FG);
    }

    /**
     * Draws DASHCMD as 5x5 pixel-art letters using g.fillRect().
     * px = size of one pixel block. Scales to any screen.
     */
    private void drawPixelBanner(Graphics g, int ox, int oy, int px) {
        g.setColor(COLOR_FG);
        // Each letter: 5 rows x 4 cols bitmap, packed as 5 ints (one per row, bits 3..0 = cols left to right)
        // Letters: D  A  S  H  C  M  D
        int[][] D = {
            {1,1,1,0},
            {1,0,0,1},
            {1,0,0,1},
            {1,0,0,1},
            {1,1,1,0}
        };
        int[][] A = {
            {0,1,1,0},
            {1,0,0,1},
            {1,1,1,1},
            {1,0,0,1},
            {1,0,0,1}
        };
        int[][] S = {
            {0,1,1,1},
            {1,0,0,0},
            {0,1,1,0},
            {0,0,0,1},
            {1,1,1,0}
        };
        int[][] H = {
            {1,0,0,1},
            {1,0,0,1},
            {1,1,1,1},
            {1,0,0,1},
            {1,0,0,1}
        };
        int[][] C = {
            {0,1,1,1},
            {1,0,0,0},
            {1,0,0,0},
            {1,0,0,0},
            {0,1,1,1}
        };
        int[][] M = {
            {1,0,0,1},
            {1,1,1,1},
            {1,0,0,1},
            {1,0,0,1},
            {1,0,0,1}
        };
        int[][][] letters = {D, A, S, H, C, M, D};
        int gap = px; // gap between letters
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

    private void addLine(String text, int color) {
        // Split long lines
        if (text == null) text = "";
        // Word-wrap if needed (will be computed at paint time based on cols)
        screenBuffer.addElement(new Object[]{text, new Integer(color)});
        // Trim if too long
        while (screenBuffer.size() > MAX_LINES) {
            screenBuffer.removeElementAt(0);
        }
        // Auto-scroll to bottom when new line added (if user was at bottom)
        if (scrollOffset == 0) {
            // Stay at bottom
        }
    }

    private void executeCommand() {
        String cmd = currentInput.trim();
        addLine(shell.getPrompt() + currentInput, COLOR_INPUT);
        currentInput = "";
        cursorPos    = 0;
        historyIdx   = -1;
        savedInput   = "";

        if (cmd.length() == 0) {
            addLine("", COLOR_FG);
            addLine(shell.getPrompt(), COLOR_PROMPT);
            repaint();
            return;
        }

        String output = shell.execute(cmd);

        if (output != null && output.equals("\033[CLEAR]")) {
            screenBuffer.removeAllElements();
            addLine(shell.getPrompt(), COLOR_PROMPT);
            repaint();
            return;
        }

        if (output != null && output.equals("\033[EXIT]")) {
            midlet.destroyApp(true);
            return;
        }

        if (output != null && output.length() > 0) {
            // Split output into lines and add each
            String[] lines = splitLines(output);
            for (int i = 0; i < lines.length; i++) {
                int color = getLineColor(lines[i], cmd);
                addLine(lines[i], color);
            }
        }
        addLine("", COLOR_FG);
        addLine(shell.getPrompt(), COLOR_PROMPT);
        scrollOffset = 0;
        repaint();
    }

    private int getLineColor(String line, String cmd) {
        String lower = line.toLowerCase();
        if (lower.startsWith("bash:") || lower.startsWith("error") ||
            lower.indexOf("no such file") >= 0 || lower.indexOf("permission denied") >= 0 ||
            lower.startsWith("grep:") || lower.indexOf("command not found") >= 0) {
            return COLOR_ERROR;
        }
        if (lower.startsWith("warning") || lower.indexOf("failed") >= 0) return 0xFFA500;
        if (line.startsWith("#")) return 0x888888;  // comments
        if (lower.indexOf("http") >= 0 && (lower.indexOf("200") >= 0 || lower.indexOf("ok") >= 0)) return 0x44FF44;
        if (lower.startsWith("drw") || lower.startsWith("lrw")) return 0x00BFFF; // dirs
        // ls output - directories vs files
        if (cmd.startsWith("ls") && line.startsWith("d")) return 0x00BFFF;
        return COLOR_FG;
    }

    /** Paint the terminal screen */
    protected void paint(Graphics g) {
        screenW = getWidth();
        screenH = getHeight();
        cols = (screenW - MARGIN_X * 2 - 6) / fontW;
        if (cols < 1) cols = 1;

        // Background
        g.setColor(COLOR_BG);
        g.fillRect(0, 0, screenW, screenH);

        // Header bar
        g.setColor(0x001100);
        g.fillRect(0, 0, screenW, fontH + 2);
        g.setColor(COLOR_HEADER);
        g.setFont(font);
        String header = "DashCMD [kali]";
        if (font.stringWidth(header) > screenW - MARGIN_X * 2) {
            header = "DashCMD";
        }
        g.drawString(header, MARGIN_X, 1, Graphics.TOP | Graphics.LEFT);

        // Start Y below header
        int startY = fontH + MARGIN_Y + 2;

        // Draw pixel-art DASHCMD banner when at bottom of scroll
        if (scrollOffset == 0) {
            int px = Math.max(1, (screenW - MARGIN_X * 2) / (7 * 5 + 6));
            int bannerH = 5 * px + px * 2;
            int bx = (screenW - (7 * (4 * px + px))) / 2;
            if (bx < MARGIN_X) bx = MARGIN_X;
            drawPixelBanner(g, bx, startY, px);
            startY += bannerH;
        }

        rows = (screenH - startY - fontH - MARGIN_Y) / fontH;
        if (rows < 1) rows = 1;

        // Build list of display lines (word-wrapped) from screen buffer
        // We only need the last (rows) visible lines
        int endBuf = screenBuffer.size() - scrollOffset;
        if (endBuf < 0) endBuf = 0;
        if (endBuf > screenBuffer.size()) endBuf = screenBuffer.size();

        // Expand buffer into wrapped display lines, newest last
        // Work backwards from endBuf collecting up to (rows) display lines
        Vector dispLines  = new Vector(); // each: String[2] {text, colorInt}
        for (int i = endBuf - 1; i >= 0 && dispLines.size() < rows * 3; i--) {
            Object[] entry = (Object[]) screenBuffer.elementAt(i);
            String text  = (String) entry[0];
            int    color = ((Integer) entry[1]).intValue();
            // Wrap this line
            Vector wrapped = new Vector();
            if (text.length() == 0) {
                wrapped.addElement(new Object[]{"", new Integer(color)});
            } else {
                while (text.length() > cols) {
                    wrapped.addElement(new Object[]{text.substring(0, cols), new Integer(color)});
                    text = "  " + text.substring(cols);
                }
                wrapped.addElement(new Object[]{text, new Integer(color)});
            }
            // Insert wrapped lines in correct order at front of dispLines
            for (int w = wrapped.size() - 1; w >= 0; w--) {
                dispLines.insertElementAt(wrapped.elementAt(w), 0);
            }
        }

        // Take only the last (rows) display lines
        int dispStart = dispLines.size() - rows;
        if (dispStart < 0) dispStart = 0;

        g.setFont(font);
        for (int i = dispStart; i < dispLines.size(); i++) {
            Object[] dl = (Object[]) dispLines.elementAt(i);
            String text  = (String) dl[0];
            int    color = ((Integer) dl[1]).intValue();
            int y = startY + (i - dispStart) * fontH;
            g.setColor(color);
            g.drawString(text, MARGIN_X, y, Graphics.TOP | Graphics.LEFT);
        }

        // Scrollbar
        if (screenBuffer.size() > rows) {
            int sbH = screenH - fontH * 3;
            int sbX = screenW - 4;
            g.setColor(COLOR_SCROLLBAR);
            g.fillRect(sbX, fontH + 2, 3, sbH);
            int thumbH = Math.max(8, sbH * rows / screenBuffer.size());
            int thumbY = fontH + 2 + (sbH - thumbH) * (screenBuffer.size() - rows - scrollOffset) / Math.max(1, screenBuffer.size() - rows);
            g.setColor(COLOR_FG);
            g.fillRect(sbX, thumbY, 3, thumbH);
        }

        // Input line (bottom bar)
        int inputY = screenH - fontH - MARGIN_Y;
        g.setColor(0x001100);
        g.fillRect(0, inputY - 2, screenW, fontH + 4);
        g.setColor(0x003300);
        g.drawLine(0, inputY - 2, screenW, inputY - 2);

        // Prompt in input line - shorten if screen too narrow
        String prompt = shell.getPrompt();
        g.setColor(COLOR_PROMPT);
        g.setFont(font);
        int promptW = font.stringWidth(prompt);
        if (promptW >= screenW - fontW * 4) {
            prompt  = "$";
            promptW = font.stringWidth(prompt);
        }
        g.drawString(prompt, MARGIN_X, inputY, Graphics.TOP | Graphics.LEFT);

        // Input text
        String dispInput = currentInput;
        int inputX = MARGIN_X + promptW;
        int maxInputW = screenW - inputX - fontW - 4;
        // Scroll input if too long
        int inputDisplayStart = 0;
        if (dispInput.length() * fontW > maxInputW) {
            inputDisplayStart = cursorPos - (maxInputW / fontW) + 2;
            if (inputDisplayStart < 0) inputDisplayStart = 0;
        }
        String visInput = dispInput.substring(inputDisplayStart);
        if (visInput.length() * fontW > maxInputW) {
            visInput = visInput.substring(0, maxInputW / fontW);
        }

        g.setColor(COLOR_INPUT);
        g.drawString(visInput, inputX, inputY, Graphics.TOP | Graphics.LEFT);

        // Cursor
        if (cursorVisible) {
            int cursorX = inputX + (cursorPos - inputDisplayStart) * fontW;
            if (cursorX >= inputX && cursorX < screenW - fontW) {
                g.setColor(COLOR_CURSOR);
                g.fillRect(cursorX, inputY, fontW, fontH - 1);
                // Character under cursor in inverted color
                if (cursorPos < dispInput.length()) {
                    g.setColor(COLOR_BG);
                    g.drawChar(dispInput.charAt(cursorPos), cursorX, inputY, Graphics.TOP | Graphics.LEFT);
                }
            }
        }
    }

    // ======================== KEY HANDLING ========================

    protected void keyPressed(int keyCode) {
        int gameAction = -1;
        try { gameAction = getGameAction(keyCode); } catch (Exception e) {}

        // ---- Navigation ----
        if (gameAction == UP || keyCode == -1) {
            scrollOrHistory(true);
            return;
        }
        if (gameAction == DOWN || keyCode == -2) {
            scrollOrHistory(false);
            return;
        }
        if (gameAction == LEFT || keyCode == -3) {
            if (cursorPos > 0) cursorPos--;
            repaint(); return;
        }
        if (gameAction == RIGHT || keyCode == -4) {
            if (cursorPos < currentInput.length()) cursorPos++;
            repaint(); return;
        }

        // ---- 5 = open native TextBox (must be checked BEFORE FIRE) ----
        if (keyCode == Canvas.KEY_NUM5) {
            openTextInput();
            return;
        }

        // ---- FIRE / centre key = execute (NOT 5) ----
        if (gameAction == FIRE || keyCode == 10 || keyCode == 13) {
            executeCommand();
            return;
        }

        // ---- * = backspace ----
        if (keyCode == Canvas.KEY_STAR || keyCode == 42) {
            if (cursorPos > 0) {
                currentInput = currentInput.substring(0, cursorPos - 1)
                             + currentInput.substring(cursorPos);
                cursorPos--;
            }
            repaint(); return;
        }

        // ---- # = Enter / submit ----
        if (keyCode == Canvas.KEY_POUND || keyCode == 35) {
            executeCommand();
            return;
        }

        // ---- Soft keys = shortcuts ----
        if (keyCode == -6 || keyCode == -7) {
            showShortcutsMenu();
            return;
        }

        // ---- Direct printable ASCII (keyboard / emulator) ----
        if (keyCode >= 32 && keyCode <= 126) {
            char c = (char) keyCode;
            currentInput = currentInput.substring(0, cursorPos) + c
                         + currentInput.substring(cursorPos);
            cursorPos++;
            repaint();
        }
    }

    /**
     * Opens the device native text-input screen (TextBox).
     * The phone uses whatever input method it supports (multitap, T9,
     * QWERTY swipe, etc.).  When the user presses OK the typed text
     * replaces / becomes currentInput and focus returns to the terminal.
     * Pressing Cancel discards the edit and returns unchanged.
     */
    private void openTextInput() {
        inputBox = new javax.microedition.lcdui.TextBox(
            "Enter command",          // title
            currentInput,             // pre-fill with what is already typed
            512,                      // max characters
            javax.microedition.lcdui.TextField.ANY
        );
        final javax.microedition.lcdui.Command okCmd =
            new javax.microedition.lcdui.Command(
                "OK", javax.microedition.lcdui.Command.OK, 1);
        final javax.microedition.lcdui.Command cancelCmd =
            new javax.microedition.lcdui.Command(
                "Cancel", javax.microedition.lcdui.Command.CANCEL, 2);

        inputBox.addCommand(okCmd);
        inputBox.addCommand(cancelCmd);
        inputBox.setCommandListener(new javax.microedition.lcdui.CommandListener() {
            public void commandAction(
                    javax.microedition.lcdui.Command cmd,
                    javax.microedition.lcdui.Displayable d) {
                if (cmd.getCommandType() == javax.microedition.lcdui.Command.OK) {
                    String typed = inputBox.getString();
                    if (typed == null) typed = "";
                    currentInput = typed;
                    cursorPos    = currentInput.length();
                }
                // Cancel: currentInput unchanged
                inputBox = null;
                midlet.getDisplay().setCurrent(TerminalCanvas.this);
                repaint();
            }
        });

        midlet.getDisplay().setCurrent(inputBox);
    }

    private void scrollOrHistory(boolean up) {
        if (up) {
            // Check if at bottom - if so, navigate history
            if (scrollOffset == 0 && historyIdx < shell.getHistory().size() - 1) {
                if (historyIdx == -1) savedInput = currentInput;
                historyIdx++;
                int idx = shell.getHistory().size() - 1 - historyIdx;
                currentInput = (String) shell.getHistory().elementAt(idx);
                cursorPos = currentInput.length();
                repaint();
            } else {
                // Scroll up
                int maxScroll = Math.max(0, screenBuffer.size() - rows);
                if (scrollOffset < maxScroll) {
                    scrollOffset = Math.min(scrollOffset + 3, maxScroll);
                    repaint();
                }
            }
        } else {
            if (scrollOffset > 0) {
                scrollOffset = Math.max(0, scrollOffset - 3);
                repaint();
            } else if (historyIdx >= 0) {
                historyIdx--;
                if (historyIdx < 0) {
                    currentInput = savedInput;
                } else {
                    int idx = shell.getHistory().size() - 1 - historyIdx;
                    currentInput = (String) shell.getHistory().elementAt(idx);
                }
                cursorPos = currentInput.length();
                repaint();
            }
        }
    }

    private void showShortcutsMenu() {
        // Insert common commands quickly via soft key
        String[] shortcuts = {
            "ls -la", "cd ..", "pwd", "cat ", "grep ", "ps aux",
            "df -h", "free -h", "uname -a", "ifconfig", "help", "clear"
        };
        // Cycle through shortcuts
        int idx = (int)(System.currentTimeMillis() % shortcuts.length);
        currentInput = shortcuts[idx];
        cursorPos = currentInput.length();
        repaint();
    }

    // ======================== UTILITIES ========================

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
        String[] arr = new String[v.size()];
        v.copyInto(arr);
        return arr;
    }

    public void shutdown() {
        running = false;
        if (blinkThread != null) blinkThread.interrupt();
    }
}