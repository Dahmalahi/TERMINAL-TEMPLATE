import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;

/**
 * J2ME Terminal MIDlet - Kali/Ubuntu/CMD style terminal for CLDC 1.1 / MIDP 2.0
 */
public class TerminalMIDlet extends MIDlet implements CommandListener {

    private Display display;
    private TerminalCanvas terminal;
    private Command exitCmd;

    public void startApp() {
        display = Display.getDisplay(this);
        terminal = new TerminalCanvas(this);
        exitCmd = new Command("Exit", Command.EXIT, 1);
        terminal.addCommand(exitCmd);
        terminal.setCommandListener(this);
        display.setCurrent(terminal);
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {
        terminal.shutdown();
        notifyDestroyed();
    }

    public void commandAction(Command c, Displayable d) {
        if (c.getCommandType() == Command.EXIT) {
            destroyApp(true);
        }
    }

    public Display getDisplay() {
        return display;
    }
}
