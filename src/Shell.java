import java.util.*;

/**
 * Shell - parses and executes terminal commands.
 * Supports: ls, cd, cat, mkdir, rm, cp, mv, pwd, echo, grep, find,
 *           ps, kill, top, df, du, uname, whoami, id, chmod, chown,
 *           date, cal, history, env, export, alias, which, whereis,
 *           head, tail, wc, sort, uniq, cut, tr, sed, awk (basic),
 *           ping, ifconfig, ip, netstat, ss, nmap, curl, wget,
 *           tar, gzip, zip, unzip, base64, md5sum, sha256sum,
 *           file, stat, free, uptime, w, who, last, lsof,
 *           crontab, service, systemctl, dmesg, lsblk, fdisk,
 *           apt/apt-get, git, python, java, and more.
 */
public class Shell {

    private VirtualFS fs;
    private Hashtable env;           // environment variables
    private Hashtable aliases;       // user aliases
    private Vector history;          // command history
    private Vector processes;        // fake process list
    private boolean isRoot;
    private int lastExitCode;

    // Pipe/redirect simulation
    private String pipeBuffer;

    public Shell(VirtualFS fs) {
        this.fs = fs;
        this.env = new Hashtable();
        this.aliases = new Hashtable();
        this.history = new Vector();
        this.processes = new Vector();
        this.lastExitCode = 0;
        initEnv();
        initProcesses();
    }

    private void initEnv() {
        env.put("HOME",    fs.getHomeDir());
        env.put("USER",    fs.getUsername());
        env.put("LOGNAME", fs.getUsername());
        env.put("SHELL",   "/bin/bash");
        env.put("TERM",    "xterm-256color");
        env.put("LANG",    "en_US.UTF-8");
        env.put("PATH",    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("PWD",     fs.getCurrentPath());
        env.put("OLDPWD",  fs.getCurrentPath());
        env.put("HOSTNAME",fs.getHostname());
        env.put("EDITOR",  "nano");
        env.put("PAGER",   "less");
        env.put("DEBIAN_FRONTEND", "noninteractive");
        // Default aliases
        aliases.put("ll",    "ls -alF");
        aliases.put("la",    "ls -A");
        aliases.put("l",     "ls -CF");
        aliases.put("...",   "cd ../..");
        aliases.put("..",    "cd ..");
        aliases.put("cls",   "clear");
        aliases.put("dir",   "ls");
        aliases.put("md",    "mkdir");
        aliases.put("rd",    "rmdir");
        aliases.put("copy",  "cp");
        aliases.put("move",  "mv");
        aliases.put("del",   "rm");
        aliases.put("type",  "cat");
        aliases.put("grep",  "grep --color=auto");
    }

    private void initProcesses() {
        processes.addElement(new String[]{"1",   "root", "S",  "0:00", "/sbin/init"});
        processes.addElement(new String[]{"2",   "root", "S",  "0:00", "[kthreadd]"});
        processes.addElement(new String[]{"123", "root", "S",  "0:01", "/usr/sbin/sshd -D"});
        processes.addElement(new String[]{"456", "root", "S",  "0:00", "/usr/sbin/cron -f"});
        processes.addElement(new String[]{"789", "root", "Ss", "0:00", "/lib/systemd/systemd-journald"});
        processes.addElement(new String[]{"901", fs.getUsername(), "S", "0:00", "-bash"});
        processes.addElement(new String[]{"999", "root", "S",  "0:00", "/usr/sbin/apache2 -k start"});
        processes.addElement(new String[]{"1001",fs.getUsername(), "R", "0:00", "ps aux"});
    }

    /** Main entry: execute a raw command line. Returns output string. */
    public String execute(String cmdLine) {
        if (cmdLine == null) return "";
        cmdLine = cmdLine.trim();
        if (cmdLine.length() == 0) return "";

        // Add to history
        history.addElement(cmdLine);
        if (history.size() > 500) history.removeElementAt(0);

        // Expand aliases
        cmdLine = expandAlias(cmdLine);

        // Handle pipe: cmd1 | cmd2
        int pipeIdx = findPipe(cmdLine);
        if (pipeIdx >= 0) {
            String left  = cmdLine.substring(0, pipeIdx).trim();
            String right = cmdLine.substring(pipeIdx + 1).trim();
            String leftOut = execute(left);
            // Feed left output as stdin to right command
            return executePiped(right, leftOut);
        }

        // Handle output redirect: cmd > file  or  cmd >> file
        int redirAppend = cmdLine.indexOf(">>");
        int redirOver   = (redirAppend < 0) ? cmdLine.indexOf(">") : -1;
        if (redirAppend >= 0) {
            String cmd  = cmdLine.substring(0, redirAppend).trim();
            String file = cmdLine.substring(redirAppend + 2).trim();
            String out  = execute(cmd);
            fs.appendFile(fs.resolvePath(file), out + "\n");
            return "";
        }
        if (redirOver >= 0) {
            String cmd  = cmdLine.substring(0, redirOver).trim();
            String file = cmdLine.substring(redirOver + 1).trim();
            String out  = execute(cmd);
            fs.writeFile(fs.resolvePath(file), out + "\n");
            return "";
        }

        // Parse tokens
        String[] tokens = parseTokens(cmdLine);
        if (tokens.length == 0) return "";

        String cmd  = tokens[0];
        String[] args = new String[tokens.length - 1];
        for (int i = 0; i < args.length; i++) args[i] = tokens[i + 1];

        // Expand $VAR in args
        for (int i = 0; i < args.length; i++) args[i] = expandVars(args[i]);

        // Dispatch
        return dispatch(cmd, args, cmdLine);
    }

    private String executePiped(String cmdLine, String stdinData) {
        String[] tokens = parseTokens(cmdLine);
        if (tokens.length == 0) return stdinData;
        String cmd = tokens[0];
        String[] args = new String[tokens.length - 1];
        for (int i = 0; i < args.length; i++) args[i] = tokens[i + 1];
        // Commands that read stdin via pipe
        if (cmd.equals("grep"))   return cmdGrep(args, stdinData);
        if (cmd.equals("head"))   return cmdHead(args, stdinData);
        if (cmd.equals("tail"))   return cmdTail(args, stdinData);
        if (cmd.equals("wc"))     return cmdWc(args, stdinData);
        if (cmd.equals("sort"))   return cmdSort(args, stdinData);
        if (cmd.equals("uniq"))   return cmdUniq(args, stdinData);
        if (cmd.equals("cut"))    return cmdCut(args, stdinData);
        if (cmd.equals("tr"))     return cmdTr(args, stdinData);
        if (cmd.equals("sed"))    return cmdSed(args, stdinData);
        if (cmd.equals("awk"))    return cmdAwk(args, stdinData);
        if (cmd.equals("tee"))    return cmdTee(args, stdinData);
        if (cmd.equals("less") || cmd.equals("more")) return stdinData;
        if (cmd.equals("cat"))    return stdinData;
        if (cmd.equals("xargs"))  return cmdXargs(args, stdinData);
        return dispatch(cmd, args, cmdLine);
    }

    private String dispatch(String cmd, String[] args, String full) {
        env.put("PWD", fs.getCurrentPath());
        // Built-ins
        if (cmd.equals("cd"))         return cmdCd(args);
        if (cmd.equals("pwd"))        return fs.getCurrentPath();
        if (cmd.equals("ls"))         return cmdLs(args);
        if (cmd.equals("dir"))        return cmdLs(args);
        if (cmd.equals("cat"))        return cmdCat(args);
        if (cmd.equals("type"))       return cmdCat(args);
        if (cmd.equals("echo"))       return cmdEcho(args);
        if (cmd.equals("printf"))     return cmdPrintf(args);
        if (cmd.equals("mkdir"))      return cmdMkdir(args);
        if (cmd.equals("rmdir"))      return cmdRmdir(args);
        if (cmd.equals("rm"))         return cmdRm(args);
        if (cmd.equals("del"))        return cmdRm(args);
        if (cmd.equals("cp"))         return cmdCp(args);
        if (cmd.equals("copy"))       return cmdCp(args);
        if (cmd.equals("mv"))         return cmdMv(args);
        if (cmd.equals("move"))       return cmdMv(args);
        if (cmd.equals("touch"))      return cmdTouch(args);
        if (cmd.equals("ln"))         return cmdLn(args);
        if (cmd.equals("find"))       return cmdFind(args);
        if (cmd.equals("locate"))     return cmdFind(args);
        if (cmd.equals("grep"))       return cmdGrep(args, null);
        if (cmd.equals("egrep"))      return cmdGrep(args, null);
        if (cmd.equals("fgrep"))      return cmdGrep(args, null);
        if (cmd.equals("head"))       return cmdHead(args, null);
        if (cmd.equals("tail"))       return cmdTail(args, null);
        if (cmd.equals("wc"))         return cmdWc(args, null);
        if (cmd.equals("sort"))       return cmdSort(args, null);
        if (cmd.equals("uniq"))       return cmdUniq(args, null);
        if (cmd.equals("cut"))        return cmdCut(args, null);
        if (cmd.equals("tr"))         return cmdTr(args, null);
        if (cmd.equals("sed"))        return cmdSed(args, null);
        if (cmd.equals("awk"))        return cmdAwk(args, null);
        if (cmd.equals("tee"))        return cmdTee(args, null);
        if (cmd.equals("xargs"))      return cmdXargs(args, null);
        if (cmd.equals("less") || cmd.equals("more")) return cmdLess(args);
        if (cmd.equals("stat"))       return cmdStat(args);
        if (cmd.equals("file"))       return cmdFile(args);
        if (cmd.equals("readlink"))   return cmdReadlink(args);
        if (cmd.equals("basename"))   return cmdBasename(args);
        if (cmd.equals("dirname"))    return cmdDirname(args);
        if (cmd.equals("which"))      return cmdWhich(args);
        if (cmd.equals("whereis"))    return cmdWhereis(args);
        if (cmd.equals("whatis"))     return cmdWhatis(args);
        if (cmd.equals("man"))        return cmdMan(args);
        if (cmd.equals("help"))       return cmdHelp(args);
        if (cmd.equals("chmod"))      return cmdChmod(args);
        if (cmd.equals("chown"))      return cmdChown(args);
        if (cmd.equals("chgrp"))      return cmdChown(args);
        if (cmd.equals("umask"))      return "0022";
        if (cmd.equals("whoami"))     return fs.getUsername();
        if (cmd.equals("id"))         return cmdId(args);
        if (cmd.equals("groups"))     return fs.getUsername() + " adm cdrom sudo dip plugdev lpadmin sambashare";
        if (cmd.equals("users"))      return fs.getUsername();
        if (cmd.equals("who"))        return cmdWho();
        if (cmd.equals("w"))          return cmdW();
        if (cmd.equals("last"))       return cmdLast();
        if (cmd.equals("lastlog"))    return cmdLastlog();
        if (cmd.equals("su"))         return cmdSu(args);
        if (cmd.equals("sudo"))       return cmdSudo(args, full);
        if (cmd.equals("useradd"))    return "[sudo] user added: " + (args.length>0?args[args.length-1]:"");
        if (cmd.equals("userdel"))    return "[sudo] user deleted";
        if (cmd.equals("passwd"))     return cmdPasswd(args);
        if (cmd.equals("uname"))      return cmdUname(args);
        if (cmd.equals("hostname"))   return args.length==0 ? fs.getHostname() : "hostname: "+args[0]+" set";
        if (cmd.equals("date"))       return cmdDate(args);
        if (cmd.equals("cal"))        return cmdCal(args);
        if (cmd.equals("uptime"))     return cmdUptime();
        if (cmd.equals("free"))       return cmdFree(args);
        if (cmd.equals("df"))         return cmdDf(args);
        if (cmd.equals("du"))         return cmdDu(args);
        if (cmd.equals("lsblk"))      return cmdLsblk();
        if (cmd.equals("blkid"))      return cmdBlkid();
        if (cmd.equals("fdisk"))      return cmdFdisk(args);
        if (cmd.equals("mount"))      return cmdMount(args);
        if (cmd.equals("umount"))     return "umount: " + (args.length>0?args[0]:"") + " successfully unmounted";
        if (cmd.equals("ps"))         return cmdPs(args);
        if (cmd.equals("kill"))       return cmdKill(args);
        if (cmd.equals("killall"))    return cmdKillall(args);
        if (cmd.equals("pkill"))      return cmdKillall(args);
        if (cmd.equals("top"))        return cmdTop();
        if (cmd.equals("htop"))       return cmdTop();
        if (cmd.equals("lsof"))       return cmdLsof();
        if (cmd.equals("strace"))     return "strace: attach to pid " + (args.length>0?args[0]:"?") + " ... (requires root)";
        if (cmd.equals("nice"))       return "nice: executing with niceness 10";
        if (cmd.equals("nohup"))      return "nohup: appending output to 'nohup.out'";
        if (cmd.equals("bg"))         return "[1]+ Running";
        if (cmd.equals("fg"))         return "[1]+ Stopped";
        if (cmd.equals("jobs"))       return "[1]+ Stopped   bash";
        if (cmd.equals("env"))        return cmdEnv(args);
        if (cmd.equals("printenv"))   return cmdPrintenv(args);
        if (cmd.equals("export"))     return cmdExport(args);
        if (cmd.equals("unset"))      return cmdUnset(args);
        if (cmd.equals("set"))        return cmdEnv(args);
        if (cmd.equals("alias"))      return cmdAlias(args);
        if (cmd.equals("unalias"))    return cmdUnalias(args);
        if (cmd.equals("history"))    return cmdHistory(args);
        if (cmd.equals("source") || cmd.equals(".")) return cmdSource(args);
        if (cmd.equals("clear") || cmd.equals("cls") || cmd.equals("reset")) return "\033[CLEAR]";
        if (cmd.equals("exit") || cmd.equals("logout") || cmd.equals("quit")) return "\033[EXIT]";
        // Network
        if (cmd.equals("ping"))       return cmdPing(args);
        if (cmd.equals("ifconfig"))   return cmdIfconfig(args);
        if (cmd.equals("ip"))         return cmdIp(args);
        if (cmd.equals("netstat"))    return cmdNetstat(args);
        if (cmd.equals("ss"))         return cmdSs(args);
        if (cmd.equals("arp"))        return cmdArp();
        if (cmd.equals("route"))      return cmdRoute();
        if (cmd.equals("traceroute") || cmd.equals("tracert")) return cmdTraceroute(args);
        if (cmd.equals("dig"))        return cmdDig(args);
        if (cmd.equals("nslookup"))   return cmdNslookup(args);
        if (cmd.equals("host"))       return cmdHostCmd(args);
        if (cmd.equals("curl"))       return cmdCurl(args);
        if (cmd.equals("wget"))       return cmdWget(args);
        if (cmd.equals("nmap"))       return cmdNmap(args);
        if (cmd.equals("nc") || cmd.equals("netcat")) return cmdNc(args);
        if (cmd.equals("ssh"))        return cmdSsh(args);
        if (cmd.equals("scp"))        return cmdScp(args);
        if (cmd.equals("iptables"))   return cmdIptables(args);
        if (cmd.equals("ufw"))        return cmdUfw(args);
        if (cmd.equals("tcpdump"))    return cmdTcpdump(args);
        // Archive
        if (cmd.equals("tar"))        return cmdTar(args);
        if (cmd.equals("gzip"))       return cmdGzip(args);
        if (cmd.equals("gunzip"))     return cmdGunzip(args);
        if (cmd.equals("zip"))        return cmdZip(args);
        if (cmd.equals("unzip"))      return cmdUnzip(args);
        if (cmd.equals("bzip2"))      return cmdBzip2(args);
        // Crypto/hash
        if (cmd.equals("md5sum"))     return cmdMd5sum(args);
        if (cmd.equals("sha256sum"))  return cmdSha256sum(args);
        if (cmd.equals("sha1sum"))    return cmdSha1sum(args);
        if (cmd.equals("base64"))     return cmdBase64(args);
        if (cmd.equals("hexdump") || cmd.equals("xxd")) return cmdHexdump(args);
        if (cmd.equals("strings"))    return cmdStrings(args);
        // Package managers
        if (cmd.equals("apt") || cmd.equals("apt-get")) return cmdApt(args);
        if (cmd.equals("dpkg"))       return cmdDpkg(args);
        if (cmd.equals("snap"))       return cmdSnap(args);
        if (cmd.equals("pip") || cmd.equals("pip3")) return cmdPip(args);
        // Dev tools
        if (cmd.equals("git"))        return cmdGit(args);
        if (cmd.equals("python") || cmd.equals("python3")) return cmdPython(args);
        if (cmd.equals("node"))       return cmdNode(args);
        if (cmd.equals("npm"))        return cmdNpm(args);
        if (cmd.equals("java"))       return cmdJava(args);
        if (cmd.equals("javac"))      return cmdJavac(args);
        if (cmd.equals("gcc") || cmd.equals("g++") || cmd.equals("cc")) return cmdGcc(args);
        if (cmd.equals("make"))       return cmdMake(args);
        // System mgmt
        if (cmd.equals("systemctl"))  return cmdSystemctl(args);
        if (cmd.equals("service"))    return cmdService(args);
        if (cmd.equals("journalctl")) return cmdJournalctl(args);
        if (cmd.equals("dmesg"))      return cmdDmesg();
        if (cmd.equals("crontab"))    return cmdCrontab(args);
        if (cmd.equals("at"))         return "at: job scheduled";
        if (cmd.equals("shutdown"))   return cmdShutdown(args);
        if (cmd.equals("reboot"))     return "Broadcast message from root: The system is going down for reboot NOW!";
        if (cmd.equals("poweroff"))   return "Broadcast message from root: The system is going down for power off NOW!";
        // Windows CMD compatibility
        if (cmd.equals("ipconfig"))   return cmdIfconfig(args);
        if (cmd.equals("cls"))        return "\033[CLEAR]";
        if (cmd.equals("ver"))        return cmdUname(new String[]{"-a"});
        if (cmd.equals("tasklist"))   return cmdPs(new String[]{"aux"});
        if (cmd.equals("taskkill"))   return cmdKill(args);
        if (cmd.equals("systeminfo")) return cmdUname(new String[]{"-a"});
        if (cmd.equals("attrib"))     return cmdStat(args);
        if (cmd.equals("tree"))       return cmdTree(args);
        if (cmd.equals("xcopy"))      return cmdCp(args);
        if (cmd.equals("robocopy"))   return cmdCp(args);
        if (cmd.equals("findstr"))    return cmdGrep(args, null);
        if (cmd.equals("assoc"))      return "No file associations.";
        if (cmd.equals("chdir"))      return cmdCd(args);
        if (cmd.equals("ren") || cmd.equals("rename")) return cmdMv(args);
        if (cmd.equals("erase"))      return cmdRm(args);
        if (cmd.equals("format"))     return "format: This command requires elevated privileges.";
        if (cmd.equals("diskpart"))   return cmdFdisk(args);
        if (cmd.equals("net"))        return cmdNetCmd(args);
        if (cmd.equals("netsh"))      return cmdNetsh(args);
        if (cmd.equals("reg"))        return "reg: Windows Registry not available on Linux.";
        if (cmd.equals("sfc"))        return "sfc: System File Checker not available on Linux.";
        if (cmd.equals("msconfig"))   return "msconfig: use systemctl on Linux.";
        if (cmd.equals("regedit"))    return "regedit: Windows Registry not available on Linux.";
        if (cmd.equals("notepad"))    return "notepad: use nano or vi on Linux.";
        if (cmd.equals("calc"))       return cmdCalc(args);
        if (cmd.equals("mspaint"))    return "mspaint: use GIMP or ImageMagick on Linux.";
        if (cmd.equals("iexplore") || cmd.equals("chrome") || cmd.equals("firefox")) {
            return (args.length > 0 ? args[0] : "about:blank") + " (browser not available in terminal mode)";
        }
        // macOS style
        if (cmd.equals("open"))       return "open: " + (args.length>0?args[0]:"") + " (use xdg-open on Linux)";
        if (cmd.equals("pbcopy"))     return "(copied to clipboard)";
        if (cmd.equals("pbpaste"))    return "(clipboard content)";
        if (cmd.equals("sw_vers"))    return "ProductName: macOS\nProductVersion: 13.0\nBuildVersion: 22A380";
        if (cmd.equals("brew"))       return cmdBrew(args);
        if (cmd.equals("say"))        return "(text-to-speech: " + join(args, " ") + ")";
        if (cmd.equals("defaults"))   return "defaults: use gsettings on Linux.";
        if (cmd.equals("diskutil"))   return cmdLsblk();
        // misc
        if (cmd.equals("banner"))     return cmdBanner(args);
        if (cmd.equals("figlet"))     return cmdBanner(args);
        if (cmd.equals("cowsay"))     return cmdCowsay(args);
        if (cmd.equals("fortune"))    return cmdFortune();
        if (cmd.equals("sl"))         return "SL (Steam Locomotive) - you typed 'sl' not 'ls'! Choo choo!";
        if (cmd.equals("yes"))        return args.length>0?args[0]:"y\ny\ny\ny\ny\n...";
        if (cmd.equals("seq"))        return cmdSeq(args);
        if (cmd.equals("tput"))       return "";
        if (cmd.equals("stty"))       return "speed 38400 baud; rows 24; columns 80;";
        if (cmd.equals("tty"))        return "/dev/pts/0";
        if (cmd.equals("script"))     return "Script started, output log file is 'typescript'.";
        if (cmd.equals("screen"))     return "[screen: session started]";
        if (cmd.equals("tmux"))       return "[tmux: new session started]";
        if (cmd.equals("watch"))      return cmdWatch(args);
        if (cmd.equals("time"))       return cmdTime(args);
        if (cmd.equals("timeout"))    return cmdTime(args);
        if (cmd.equals("true"))       return "";
        if (cmd.equals("false"))      { lastExitCode=1; return ""; }
        if (cmd.equals("test") || cmd.equals("[")) return cmdTest(args);
        if (cmd.equals("expr"))       return cmdExpr(args);
        if (cmd.equals("bc"))         return cmdBc(args);
        if (cmd.equals("calc") || cmd.equals("qalc")) return cmdCalc(args);
        if (cmd.equals("factor"))     return cmdFactor(args);
        if (cmd.equals("shuf"))       return cmdShuf(args);
        if (cmd.equals("numfmt"))     return args.length>0?args[0]:"";
        if (cmd.equals("column"))     return args.length>0?args[0]:"";
        if (cmd.equals("paste"))      return cmdPaste(args);
        if (cmd.equals("comm"))       return "(comm output)";
        if (cmd.equals("diff"))       return cmdDiff(args);
        if (cmd.equals("patch"))      return "patching file";
        if (cmd.equals("nano") || cmd.equals("vi") || cmd.equals("vim") || cmd.equals("pico") || cmd.equals("emacs")) {
            return "[" + cmd + ": text editor cannot run in this terminal mode]";
        }
        if (cmd.equals("write") || cmd.equals("wall")) return "(message sent to all users)";
        if (cmd.equals("mail") || cmd.equals("mutt") || cmd.equals("mailx")) return "(mail client not available)";
        if (cmd.equals("ftp") || cmd.equals("sftp") || cmd.equals("telnet")) return cmdTelnet(cmd, args);
        if (cmd.equals("rsync"))      return cmdRsync(args);
        if (cmd.equals("rcp"))        return cmdScp(args);
        if (cmd.equals("lspci"))      return cmdLspci();
        if (cmd.equals("lsusb"))      return cmdLsusb();
        if (cmd.equals("lshw"))       return cmdLshw();
        if (cmd.equals("inxi"))       return cmdInxi();
        if (cmd.equals("neofetch") || cmd.equals("screenfetch")) return cmdNeofetch();
        if (cmd.equals("hwinfo"))     return cmdLshw();
        if (cmd.equals("sensors"))    return cmdSensors();
        if (cmd.equals("iostat"))     return cmdIostat();
        if (cmd.equals("vmstat"))     return cmdVmstat();
        if (cmd.equals("mpstat"))     return cmdMpstat();
        if (cmd.equals("sar"))        return "(sar: system activity reporter)";
        if (cmd.equals("ulimit"))     return "unlimited";
        if (cmd.equals("sysctl"))     return cmdSysctl(args);
        if (cmd.equals("modprobe"))   return "(modprobe: loading kernel module)";
        if (cmd.equals("lsmod"))      return cmdLsmod();
        if (cmd.equals("insmod"))     return "(insmod: inserting module)";
        if (cmd.equals("rmmod"))      return "(rmmod: removing module)";
        if (cmd.equals("depmod"))     return "(depmod: module dependencies updated)";
        if (cmd.equals("update-grub")) return "Generating grub configuration file ...done";
        if (cmd.equals("grub-install")) return "Installing for i386-pc platform. Installation finished. No error reported.";
        if (cmd.equals("update-initramfs")) return "update-initramfs: Generating /boot/initrd.img-5.15.0-88-generic";
        // ?? Kali Linux / Security Tools ??????????????????????????????????????
        if (cmd.equals("nmap"))         return cmdNmap(args);
        if (cmd.equals("masscan"))      return cmdMasscan(args);
        if (cmd.equals("nikto"))        return cmdNikto(args);
        if (cmd.equals("sqlmap"))       return cmdSqlmap(args);
        if (cmd.equals("metasploit") || cmd.equals("msfconsole")) return cmdMsf(args);
        if (cmd.equals("msfvenom"))     return cmdMsfvenom(args);
        if (cmd.equals("hydra"))        return cmdHydra(args);
        if (cmd.equals("medusa"))       return cmdMedusa(args);
        if (cmd.equals("john"))         return cmdJohn(args);
        if (cmd.equals("hashcat"))      return cmdHashcat(args);
        if (cmd.equals("aircrack-ng"))  return cmdAircrack(args);
        if (cmd.equals("airodump-ng"))  return cmdAirodump(args);
        if (cmd.equals("aireplay-ng"))  return cmdAireplay(args);
        if (cmd.equals("airmon-ng"))    return cmdAirmon(args);
        if (cmd.equals("wifite"))       return cmdWifite(args);
        if (cmd.equals("reaver"))       return cmdReaver(args);
        if (cmd.equals("bully"))        return cmdBully(args);
        if (cmd.equals("wireshark") || cmd.equals("tshark")) return cmdTshark(args);
        if (cmd.equals("ettercap"))     return cmdEttercap(args);
        if (cmd.equals("bettercap"))    return cmdBettercap(args);
        if (cmd.equals("dsniff"))       return "(dsniff: sniffing mode active)";
        if (cmd.equals("arpspoof"))     return cmdArpspoof(args);
        if (cmd.equals("sslstrip"))     return "(sslstrip: stripping HTTPS)";
        if (cmd.equals("burpsuite"))    return "(BurpSuite: use browser proxy 127.0.0.1:8080)";
        if (cmd.equals("zap") || cmd.equals("zaproxy")) return "(OWASP ZAP: listening on 8080)";
        if (cmd.equals("gobuster"))     return cmdGobuster(args);
        if (cmd.equals("dirb"))         return cmdDirb(args);
        if (cmd.equals("dirbuster"))    return cmdDirb(args);
        if (cmd.equals("ffuf"))         return cmdFfuf(args);
        if (cmd.equals("wfuzz"))        return cmdWfuzz(args);
        if (cmd.equals("sublist3r"))    return cmdSublist3r(args);
        if (cmd.equals("amass"))        return cmdAmass(args);
        if (cmd.equals("theHarvester") || cmd.equals("theharvester")) return cmdTheHarvester(args);
        if (cmd.equals("recon-ng"))     return "(recon-ng: open-source intelligence framework)";
        if (cmd.equals("maltego"))      return "(Maltego: graphical link analysis - GUI required)";
        if (cmd.equals("shodan"))       return cmdShodan(args);
        if (cmd.equals("dnsenum"))      return cmdDnsenum(args);
        if (cmd.equals("dnsrecon"))     return cmdDnsrecon(args);
        if (cmd.equals("fierce"))       return cmdFierce(args);
        if (cmd.equals("enum4linux"))   return cmdEnum4linux(args);
        if (cmd.equals("smbclient"))    return cmdSmbclient(args);
        if (cmd.equals("smbmap"))       return cmdSmbmap(args);
        if (cmd.equals("rpcclient"))    return "(rpcclient: connecting to SMB RPC)";
        if (cmd.equals("impacket") || cmd.equals("psexec.py")) return "(Impacket: remote execution tool)";
        if (cmd.equals("crackmapexec") || cmd.equals("cme")) return cmdCme(args);
        if (cmd.equals("evil-winrm"))   return cmdEvilWinrm(args);
        if (cmd.equals("bloodhound"))   return "(BloodHound: AD attack path analysis - GUI required)";
        if (cmd.equals("snort"))        return cmdSnort(args);
        if (cmd.equals("suricata"))     return cmdSuricata(args);
        if (cmd.equals("lynis"))        return cmdLynis(args);
        if (cmd.equals("rkhunter"))     return cmdRkhunter(args);
        if (cmd.equals("chkrootkit"))   return cmdChkrootkit();
        if (cmd.equals("clamav") || cmd.equals("clamscan")) return cmdClamscan(args);
        if (cmd.equals("fail2ban-client")) return cmdFail2ban(args);
        if (cmd.equals("openssl"))      return cmdOpenssl(args);
        if (cmd.equals("gpg"))          return cmdGpg(args);
        if (cmd.equals("ssh-keygen"))   return cmdSshKeygen(args);
        if (cmd.equals("ssh-copy-id"))  return "ssh-copy-id: key copied to " + (args.length>0?args[args.length-1]:"host");
        if (cmd.equals("netdiscover"))  return cmdNetdiscover(args);
        if (cmd.equals("arp-scan"))     return cmdArpScan(args);
        if (cmd.equals("fping"))        return cmdFping(args);
        if (cmd.equals("hping3"))       return cmdHping3(args);
        if (cmd.equals("scapy"))        return "(Scapy: interactive packet manipulation - Python required)";
        if (cmd.equals("volatility"))   return cmdVolatility(args);
        if (cmd.equals("binwalk"))      return cmdBinwalk(args);
        if (cmd.equals("foremost"))     return cmdForemost(args);
        if (cmd.equals("photorec"))     return "(PhotoRec: file recovery tool - interactive mode)";
        if (cmd.equals("testdisk"))     return "(TestDisk: disk recovery tool - interactive mode)";
        if (cmd.equals("dd"))           return cmdDd(args);
        if (cmd.equals("dcfldd"))       return cmdDd(args);
        if (cmd.equals("steghide"))     return cmdSteghide(args);
        if (cmd.equals("stegsolve"))    return "(StegSolve: image steganography - GUI required)";
        if (cmd.equals("exiftool"))     return cmdExiftool(args);
        if (cmd.equals("exiv2"))        return cmdExiftool(args);
        if (cmd.equals("objdump"))      return cmdObjdump(args);
        if (cmd.equals("radare2") || cmd.equals("r2")) return cmdRadare2(args);
        if (cmd.equals("gdb"))          return cmdGdb(args);
        if (cmd.equals("ltrace"))       return "(ltrace: tracing library calls)";
        if (cmd.equals("nm"))           return cmdNm(args);
        if (cmd.equals("ldd"))          return cmdLdd(args);
        if (cmd.equals("readelf"))      return cmdReadelf(args);
        if (cmd.equals("pwn") || cmd.equals("pwntools")) return "(pwntools: CTF exploit framework - Python)";
        if (cmd.equals("gef") || cmd.equals("pwndbg")) return "(GDB enhanced features plugin)";
        // ?? Docker & Containers ???????????????????????????????????????????????
        if (cmd.equals("docker"))       return cmdDocker(args);
        if (cmd.equals("docker-compose")) return cmdDockerCompose(args);
        if (cmd.equals("podman"))       return cmdDocker(args);
        if (cmd.equals("kubectl") || cmd.equals("k8s") || cmd.equals("k")) return cmdKubectl(args);
        if (cmd.equals("helm"))         return cmdHelm(args);
        if (cmd.equals("minikube"))     return cmdMinikube(args);
        if (cmd.equals("kind"))         return cmdKind(args);
        if (cmd.equals("skaffold"))     return "(skaffold: continuous development for K8s)";
        if (cmd.equals("kustomize"))    return "(kustomize: K8s configuration management)";
        if (cmd.equals("istioctl"))     return cmdIstioctl(args);
        if (cmd.equals("terraform"))    return cmdTerraform(args);
        if (cmd.equals("ansible"))      return cmdAnsible(args);
        if (cmd.equals("ansible-playbook")) return cmdAnsiblePlaybook(args);
        if (cmd.equals("vagrant"))      return cmdVagrant(args);
        if (cmd.equals("packer"))       return cmdPacker(args);
        // ?? Cloud CLI ?????????????????????????????????????????????????????????
        if (cmd.equals("aws"))          return cmdAws(args);
        if (cmd.equals("gcloud"))       return cmdGcloud(args);
        if (cmd.equals("az") || cmd.equals("azure")) return cmdAzure(args);
        if (cmd.equals("doctl"))        return cmdDoctl(args);
        if (cmd.equals("heroku"))       return cmdHeroku(args);
        if (cmd.equals("vercel"))       return cmdVercel(args);
        if (cmd.equals("netlify"))      return cmdNetlify(args);
        // ?? Databases ?????????????????????????????????????????????????????????
        if (cmd.equals("mysql"))        return cmdMysql(args);
        if (cmd.equals("mysqldump"))    return cmdMysqldump(args);
        if (cmd.equals("psql"))         return cmdPsql(args);
        if (cmd.equals("pg_dump"))      return cmdPgdump(args);
        if (cmd.equals("pg_restore"))   return "(pg_restore: restoring PostgreSQL database)";
        if (cmd.equals("sqlite3"))      return cmdSqlite3(args);
        if (cmd.equals("redis-cli"))    return cmdRedisCli(args);
        if (cmd.equals("mongo") || cmd.equals("mongosh")) return cmdMongo(args);
        if (cmd.equals("mongodump"))    return "(mongodump: creating archive mongodump.gz)";
        if (cmd.equals("mongorestore")) return "(mongorestore: restoring from archive)";
        if (cmd.equals("influx"))       return cmdInflux(args);
        if (cmd.equals("cassandra-cli") || cmd.equals("cqlsh")) return cmdCqlsh(args);
        if (cmd.equals("neo4j"))        return cmdNeo4j(args);
        // ?? More Dev Tools ????????????????????????????????????????????????????
        if (cmd.equals("cargo"))        return cmdCargo(args);
        if (cmd.equals("rustc"))        return cmdRustc(args);
        if (cmd.equals("go"))           return cmdGo(args);
        if (cmd.equals("ruby"))         return cmdRuby(args);
        if (cmd.equals("gem"))          return cmdGem(args);
        if (cmd.equals("bundle"))       return cmdBundle(args);
        if (cmd.equals("rails"))        return cmdRails(args);
        if (cmd.equals("php"))          return cmdPhp(args);
        if (cmd.equals("composer"))     return cmdComposer(args);
        if (cmd.equals("perl"))         return cmdPerl(args);
        if (cmd.equals("lua"))          return cmdLua(args);
        if (cmd.equals("swift"))        return cmdSwift(args);
        if (cmd.equals("kotlin"))       return cmdKotlin(args);
        if (cmd.equals("scala"))        return cmdScala(args);
        if (cmd.equals("mvn"))          return cmdMvn(args);
        if (cmd.equals("gradle"))       return cmdGradle(args);
        if (cmd.equals("ant"))          return cmdAnt(args);
        if (cmd.equals("yarn"))         return cmdYarn(args);
        if (cmd.equals("pnpm"))         return cmdPnpm(args);
        if (cmd.equals("npx"))          return cmdNpx(args);
        if (cmd.equals("nvm"))          return cmdNvm(args);
        if (cmd.equals("pyenv"))        return cmdPyenv(args);
        if (cmd.equals("virtualenv") || cmd.equals("venv")) return cmdVenv(args);
        if (cmd.equals("conda"))        return cmdConda(args);
        if (cmd.equals("jupyter"))      return cmdJupyter(args);
        if (cmd.equals("ipython"))      return "(IPython: enhanced Python shell - not available in J2ME mode)";
        if (cmd.equals("pytest"))       return cmdPytest(args);
        if (cmd.equals("unittest"))     return "(python unittest: running tests)";
        if (cmd.equals("mypy"))         return cmdMypy(args);
        if (cmd.equals("pylint"))       return cmdPylint(args);
        if (cmd.equals("black"))        return cmdBlack(args);
        if (cmd.equals("flake8"))       return cmdFlake8(args);
        if (cmd.equals("eslint"))       return cmdEslint(args);
        if (cmd.equals("tsc"))          return cmdTsc(args);
        if (cmd.equals("webpack"))      return cmdWebpack(args);
        if (cmd.equals("vite"))         return cmdVite(args);
        if (cmd.equals("rollup"))       return "(rollup: bundling modules...)";
        if (cmd.equals("parcel"))       return "(parcel: bundling application...)";
        if (cmd.equals("babel"))        return "(babel: transpiling JavaScript...)";
        if (cmd.equals("prettier"))     return "(prettier: code formatted)";
        if (cmd.equals("jq"))           return cmdJq(args);
        if (cmd.equals("yq"))           return cmdYq(args);
        if (cmd.equals("toml"))         return "(toml: TOML parser)";
        if (cmd.equals("xmllint"))      return cmdXmllint(args);
        if (cmd.equals("csvkit") || cmd.equals("csvstat")) return cmdCsvkit(args);
        if (cmd.equals("curl") || cmd.equals("curlie") || cmd.equals("httpie") || cmd.equals("http") || cmd.equals("xh")) return cmdCurl(args);
        if (cmd.equals("grpc") || cmd.equals("grpcurl")) return cmdGrpcurl(args);
        if (cmd.equals("protoc"))       return cmdProtoc(args);
        // ?? Version Control ???????????????????????????????????????????????????
        if (cmd.equals("svn"))          return cmdSvn(args);
        if (cmd.equals("hg") || cmd.equals("mercurial")) return cmdHg(args);
        if (cmd.equals("cvs"))          return cmdCvs(args);
        if (cmd.equals("gh"))           return cmdGh(args);
        if (cmd.equals("lab") || cmd.equals("glab")) return cmdGlab(args);
        if (cmd.equals("hub"))          return cmdHub(args);
        // ?? System Monitoring ?????????????????????????????????????????????????
        if (cmd.equals("glances"))      return cmdGlances(args);
        if (cmd.equals("nmon"))         return cmdNmon(args);
        if (cmd.equals("atop"))         return cmdTop();
        if (cmd.equals("iftop"))        return cmdIftop(args);
        if (cmd.equals("nethogs"))      return cmdNethogs(args);
        if (cmd.equals("iotop"))        return cmdIotop(args);
        if (cmd.equals("powertop"))     return cmdPowertop(args);
        if (cmd.equals("perf"))         return cmdPerf(args);
        if (cmd.equals("systemd-analyze")) return cmdSystemdAnalyze(args);
        if (cmd.equals("cgroups") || cmd.equals("cgget")) return "(cgroups: control groups management)";
        if (cmd.equals("namespaces") || cmd.equals("nsenter")) return "(nsenter: entering namespaces)";
        if (cmd.equals("unshare"))      return "(unshare: creating new namespace)";
        if (cmd.equals("strace"))       return cmdStrace(args);
        if (cmd.equals("perf-trace"))   return "(perf-trace: system call tracer)";
        if (cmd.equals("bpftrace"))     return "(bpftrace: eBPF tracing language)";
        // ?? Text & File Utilities ?????????????????????????????????????????????
        if (cmd.equals("ack") || cmd.equals("ag") || cmd.equals("rg") || cmd.equals("ripgrep")) return cmdRipgrep(args);
        if (cmd.equals("fd") || cmd.equals("fdfind")) return cmdFd(args);
        if (cmd.equals("fzf"))          return cmdFzf(args);
        if (cmd.equals("bat"))          return cmdBat(args);
        if (cmd.equals("exa"))          return cmdLs(args);
        if (cmd.equals("lsd"))          return cmdLs(args);
        if (cmd.equals("delta"))        return cmdDiff(args);
        if (cmd.equals("iconv"))        return cmdIconv(args);
        if (cmd.equals("dos2unix"))     return cmdDos2unix(args);
        if (cmd.equals("unix2dos"))     return cmdUnix2dos(args);
        if (cmd.equals("expand"))       return cmdExpand(args);
        if (cmd.equals("unexpand"))     return "(unexpand: converting spaces to tabs)";
        if (cmd.equals("fold"))         return cmdFold(args);
        if (cmd.equals("fmt"))          return cmdFmt(args);
        if (cmd.equals("pr"))           return "(pr: preparing file for printing)";
        if (cmd.equals("nl"))           return cmdNl(args);
        if (cmd.equals("rev"))          return cmdRev(args);
        if (cmd.equals("tac"))          return cmdTac(args);
        if (cmd.equals("od"))           return cmdOd(args);
        if (cmd.equals("split"))        return cmdSplit(args);
        if (cmd.equals("csplit"))       return "(csplit: splitting file by context)";
        if (cmd.equals("truncate"))     return cmdTruncate(args);
        if (cmd.equals("shred"))        return cmdShred(args);
        if (cmd.equals("wipe"))         return cmdShred(args);
        if (cmd.equals("srm"))          return cmdShred(args);
        if (cmd.equals("lzma") || cmd.equals("xz")) return cmdXz(args);
        if (cmd.equals("zstd"))         return cmdZstd(args);
        if (cmd.equals("7z") || cmd.equals("7za")) return cmd7z(args);
        if (cmd.equals("rar"))          return cmdRar(args);
        if (cmd.equals("unrar"))        return cmdUnrar(args);
        if (cmd.equals("cpio"))         return "(cpio: archiving files)";
        if (cmd.equals("ar"))           return cmdAr(args);
        // ?? Networking Plus ???????????????????????????????????????????????????
        if (cmd.equals("whois"))        return cmdWhois(args);
        if (cmd.equals("mtr"))          return cmdMtr(args);
        if (cmd.equals("nping"))        return cmdNping(args);
        if (cmd.equals("socat"))        return cmdSocat(args);
        if (cmd.equals("stunnel"))      return "(stunnel: SSL/TLS tunnel daemon)";
        if (cmd.equals("openvpn"))      return cmdOpenvpn(args);
        if (cmd.equals("wireguard") || cmd.equals("wg") || cmd.equals("wg-quick")) return cmdWireguard(args);
        if (cmd.equals("strongswan") || cmd.equals("ipsec")) return "(strongSwan: IPsec VPN)";
        if (cmd.equals("proxychains") || cmd.equals("proxychains4")) return cmdProxychains(args);
        if (cmd.equals("tor"))          return cmdTor(args);
        if (cmd.equals("torsocks"))     return "(torsocks: route traffic through Tor)";
        if (cmd.equals("onionshare"))   return "(OnionShare: file sharing over Tor)";
        if (cmd.equals("ncat"))         return cmdNc(args);
        if (cmd.equals("cryptcat"))     return cmdNc(args);
        if (cmd.equals("xinetd"))       return "(xinetd: extended internet daemon)";
        if (cmd.equals("inetd"))        return "(inetd: internet daemon)";
        if (cmd.equals("openbsd-inetd")) return "(openbsd-inetd: internet daemon)";
        if (cmd.equals("nginx"))        return cmdNginx(args);
        if (cmd.equals("apache2") || cmd.equals("httpd") || cmd.equals("apachectl")) return cmdApache(args);
        if (cmd.equals("certbot"))      return cmdCertbot(args);
        if (cmd.equals("acme.sh"))      return cmdAcme(args);
        if (cmd.equals("haproxy"))      return cmdHaproxy(args);
        if (cmd.equals("caddy"))        return cmdCaddy(args);
        if (cmd.equals("traefik"))      return "(traefik: modern reverse proxy started)";
        if (cmd.equals("bind9") || cmd.equals("named")) return "(BIND9: DNS server daemon)";
        if (cmd.equals("dnsmasq"))      return "(dnsmasq: DNS/DHCP server started)";
        if (cmd.equals("postfix") || cmd.equals("sendmail")) return "(Postfix: MTA started)";
        if (cmd.equals("dovecot"))      return "(Dovecot: IMAP/POP3 server started)";
        if (cmd.equals("mosquitto"))    return "(Mosquitto: MQTT broker started)";
        if (cmd.equals("nftables") || cmd.equals("nft")) return cmdNft(args);
        if (cmd.equals("ebtables"))     return cmdEbtables(args);
        if (cmd.equals("ipset"))        return cmdIpset(args);
        if (cmd.equals("tc"))           return cmdTc(args);
        // ?? Filesystems & Storage ?????????????????????????????????????????????
        if (cmd.equals("mkfs") || cmd.equals("mkfs.ext4") || cmd.equals("mkfs.xfs")) return cmdMkfs(args);
        if (cmd.equals("fsck") || cmd.equals("e2fsck")) return cmdFsck(args);
        if (cmd.equals("tune2fs"))      return cmdTune2fs(args);
        if (cmd.equals("dumpe2fs"))     return "(dumpe2fs: filesystem debug info)";
        if (cmd.equals("debugfs"))      return "(debugfs: ext2/3/4 filesystem debugger)";
        if (cmd.equals("hdparm"))       return cmdHdparm(args);
        if (cmd.equals("sdparm"))       return "(sdparm: SCSI device parameters)";
        if (cmd.equals("nvme"))         return cmdNvme(args);
        if (cmd.equals("smartctl"))     return cmdSmartctl(args);
        if (cmd.equals("badblocks"))    return "(badblocks: searching for bad blocks...)";
        if (cmd.equals("mdadm"))        return cmdMdadm(args);
        if (cmd.equals("lvm") || cmd.equals("pvs") || cmd.equals("vgs") || cmd.equals("lvs")) { String[] lvmArgs = new String[args.length+1]; lvmArgs[0]=cmd; for(int i=0;i<args.length;i++) lvmArgs[i+1]=args[i]; return cmdLvm(lvmArgs); }
        if (cmd.equals("cryptsetup"))   return cmdCryptsetup(args);
        if (cmd.equals("veracrypt"))    return "(VeraCrypt: encrypted volume management)";
        if (cmd.equals("encfs"))        return "(EncFS: encrypted filesystem)";
        if (cmd.equals("sshfs"))        return "(sshfs: mounting remote filesystem)";
        if (cmd.equals("curlftpfs"))    return "(curlftpfs: mounting FTP as filesystem)";
        if (cmd.equals("davfs2") || cmd.equals("mount.davfs")) return "(davfs2: mounting WebDAV)";
        if (cmd.equals("nfs") || cmd.equals("showmount")) return cmdShowmount(args);
        if (cmd.equals("autofs"))       return "(autofs: automounting daemon)";
        if (cmd.equals("inotifywait") || cmd.equals("inotifywatch")) return cmdInotify(args);
        if (cmd.equals("auditd") || cmd.equals("auditctl")) return cmdAudit(args);
        // ?? X11 / Display ?????????????????????????????????????????????????????
        if (cmd.equals("xrandr"))       return cmdXrandr(args);
        if (cmd.equals("xdpyinfo"))     return cmdXdpyinfo(args);
        if (cmd.equals("xset"))         return "(xset: user preferences for X)";
        if (cmd.equals("xdg-open"))     return "(xdg-open: opening " + (args.length>0?args[0]:"file") + " with default app)";
        if (cmd.equals("wmctrl"))       return "(wmctrl: window manager control)";
        if (cmd.equals("xdotool"))      return "(xdotool: X11 automation)";
        if (cmd.equals("scrot") || cmd.equals("gnome-screenshot") || cmd.equals("import")) return "(screenshot saved to ~/screenshot.png)";
        if (cmd.equals("xclip") || cmd.equals("xsel")) return "(clipboard: " + (args.length>0?args[0]:"content") + ")";
        // ?? Media & Image ?????????????????????????????????????????????????????
        if (cmd.equals("ffmpeg"))       return cmdFfmpeg(args);
        if (cmd.equals("ffprobe"))      return cmdFfprobe(args);
        if (cmd.equals("convert") || cmd.equals("magick")) return cmdConvert(args);
        if (cmd.equals("identify"))     return cmdIdentify(args);
        if (cmd.equals("gimp"))         return "(GIMP: GNU Image Manipulation Program - GUI required)";
        if (cmd.equals("inkscape"))     return "(Inkscape: SVG editor - GUI required)";
        if (cmd.equals("vlc") || cmd.equals("mpv") || cmd.equals("mplayer")) return "(media player: GUI required)";
        if (cmd.equals("sox"))          return cmdSox(args);
        if (cmd.equals("aplay") || cmd.equals("arecord")) return cmdAplay(args);
        if (cmd.equals("espeak") || cmd.equals("festival")) return "(TTS: " + join(args," ") + ")";
        if (cmd.equals("yt-dlp") || cmd.equals("youtube-dl")) return cmdYtDlp(args);
        // ?? PowerShell ????????????????????????????????????????????????????????
        if (cmd.equals("powershell") || cmd.equals("pwsh")) return cmdPowershell(args);
        if (cmd.equals("Get-Help"))     return cmdGetHelp(args);
        if (cmd.equals("Get-Command"))  return cmdGetCommand(args);
        if (cmd.equals("Get-Process"))  return cmdPs(new String[]{"aux"});
        if (cmd.equals("Get-Service"))  return cmdGetService(args);
        if (cmd.equals("Get-Item"))     return cmdStat(args);
        if (cmd.equals("Get-Content"))  return cmdCat(args);
        if (cmd.equals("Set-Content"))  return "(Set-Content: writing to file)";
        if (cmd.equals("Get-Location")) return fs.getCurrentPath();
        if (cmd.equals("Set-Location")) return cmdCd(args);
        if (cmd.equals("Get-ChildItem")) return cmdLs(args);
        if (cmd.equals("New-Item"))     return cmdTouch(args);
        if (cmd.equals("Remove-Item"))  return cmdRm(args);
        if (cmd.equals("Copy-Item"))    return cmdCp(args);
        if (cmd.equals("Move-Item"))    return cmdMv(args);
        if (cmd.equals("Invoke-WebRequest") || cmd.equals("iwr")) return cmdCurl(args);
        if (cmd.equals("Invoke-Expression") || cmd.equals("iex")) return dispatch(args.length>0?args[0]:"", new String[0], join(args," "));
        if (cmd.equals("Write-Host"))   return join(args," ");
        if (cmd.equals("Write-Output")) return join(args," ");
        if (cmd.equals("Write-Error"))  return "Error: " + join(args," ");
        if (cmd.equals("Select-String")) return cmdGrep(args, null);
        if (cmd.equals("Where-Object")) return "(Where-Object: filtering pipeline)";
        if (cmd.equals("ForEach-Object")) return "(ForEach-Object: iterating pipeline)";
        if (cmd.equals("Sort-Object"))  return cmdSort(args, null);
        if (cmd.equals("Measure-Object")) return cmdWc(args, null);
        if (cmd.equals("Test-Path"))    return fs.exists(args.length>0?args[0]:"") ? "True" : "False";
        if (cmd.equals("Test-Connection")) return cmdPing(args);
        if (cmd.equals("Get-Date"))     return cmdDate(new String[0]);
        if (cmd.equals("Get-Host"))     return "PowerShell " + (isRoot?"7.4.0":"7.4.0 (Linux)");
        if (cmd.equals("Clear-Host"))   return "\033[CLEAR]";
        if (cmd.equals("Exit-PSSession") || cmd.equals("Exit-PSHostProcess")) return "\033[EXIT]";
        // ?? Shell Scripting / Misc ????????????????????????????????????????????
        if (cmd.equals("bash") || cmd.equals("sh") || cmd.equals("dash") || cmd.equals("zsh") || cmd.equals("fish") || cmd.equals("ksh") || cmd.equals("tcsh") || cmd.equals("csh")) {
            if (args.length > 0 && !args[0].startsWith("-")) return "[running script: " + args[0] + "]";
            return "[" + cmd + " subshell]";
        }
        if (cmd.equals("xargs"))        return cmdXargs(args, null);
        if (cmd.equals("parallel"))     return "(GNU parallel: running jobs in parallel)";
        if (cmd.equals("timeout"))      return cmdTime(args);
        if (cmd.equals("retry"))        return "(retry: retrying command)";
        if (cmd.equals("sleep"))        return cmdSleep(args);
        if (cmd.equals("wait"))         return "";
        if (cmd.equals("read"))         return "";
        if (cmd.equals("declare") || cmd.equals("typeset") || cmd.equals("local")) return "";
        if (cmd.equals("readonly"))     return "";
        if (cmd.equals("shift"))        return "";
        if (cmd.equals("getopts"))      return "";
        if (cmd.equals("trap"))         return "";
        if (cmd.equals("return"))       return "";
        if (cmd.equals("break"))        return "";
        if (cmd.equals("continue"))     return "";
        if (cmd.equals("hash"))         return "(hash: hashing command locations)";
        if (cmd.equals("type"))         return cmd + " is a shell builtin";
        if (cmd.equals("builtin"))      return "(builtin: executing shell builtin)";
        if (cmd.equals("command"))      return dispatch(args.length>0?args[0]:"", args.length>1?copyArgs(args,1):new String[0], join(args," "));
        if (cmd.equals("complete"))     return "(complete: bash completion)";
        if (cmd.equals("compgen"))      return "(compgen: bash completion generator)";
        if (cmd.equals("enable"))       return "(enable: enabling shell builtin)";
        if (cmd.equals("exec"))         return "(exec: replacing shell process)";
        if (cmd.equals("eval"))         return dispatch(args.length>0?args[0]:"", args.length>1?copyArgs(args,1):new String[0], join(args," "));
        // ?? Fun & Easter eggs ?????????????????????????????????????????????????
        if (cmd.equals("matrix"))       return cmdMatrix(args);
        if (cmd.equals("hack"))         return cmdHack(args);
        if (cmd.equals("lolcat"))       return join(args," ");
        if (cmd.equals("toilet"))       return cmdBanner(args);
        if (cmd.equals("cmatrix"))      return cmdMatrix(args);
        if (cmd.equals("asciiquarium")) return cmdAsciiAquarium(args);
        if (cmd.equals("aafire"))       return "(aafire: ASCII fire animation)";
        if (cmd.equals("bb"))           return "(bb: aalib demo not available)";
        if (cmd.equals("steam-locomotive") || cmd.equals("sl")) return cmdSteamLocomotive(args);
        if (cmd.equals("telnet") && args.length > 0 && args[0].equals("towel.blinkenlights.nl")) return cmdStarWars(args);
        if (cmd.equals("rickroll"))     return "Never gonna give you up, never gonna let you down...";
        if (cmd.equals("sudo") && args.length > 0 && args[0].equals("make") && args.length > 1 && args[1].equals("me") && args.length > 2 && args[2].equals("a") && args.length > 3 && args[3].equals("sandwich")) return "Okay.";
        if (cmd.equals(":(){ :|:& };:")) return "bash: fork bomb detected and blocked!";
        if (cmd.equals("rm") && args.length > 1 && args[0].equals("-rf") && (args[1].equals("/") || args[1].equals("/*"))) return "Nice try. Operation not permitted.";
        // ?? Variable assignment ???????????????????????????????????????????????
        if (cmd.indexOf("=") >= 0 && cmd.indexOf(" ") < 0) {
            int eq = cmd.indexOf('=');
            String key = cmd.substring(0, eq);
            String val = cmd.substring(eq + 1);
            env.put(key, expandVars(val));
            return "";
        }
        // Unknown
        return "bash: " + cmd + ": command not found";
    }

    // ======================== COMMAND IMPLEMENTATIONS ========================

    private String cmdCd(String[] args) {
        if (args.length == 0) {
            return nullToEmpty(fs.cd(fs.getHomeDir()));
        }
        String path = args[args.length - 1];
        if (path.equals("-")) {
            String old = (String) env.get("OLDPWD");
            if (old == null) old = fs.getCurrentPath();
            env.put("OLDPWD", fs.getCurrentPath());
            return nullToEmpty(fs.cd(old));
        }
        env.put("OLDPWD", fs.getCurrentPath());
        return nullToEmpty(fs.cd(path));
    }

    private String cmdLs(String[] args) {
        boolean longFmt = false, all = false, human = false,
                reverse = false, sortTime = false, recursive = false,
                inode = false, colorize = false, classify = false;
        String targetPath = fs.getCurrentPath();

        Vector paths = new Vector();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-")) {
                if (a.indexOf('l') >= 0) longFmt = true;
                if (a.indexOf('a') >= 0) all = true;
                if (a.indexOf('A') >= 0) all = true;
                if (a.indexOf('h') >= 0) human = true;
                if (a.indexOf('r') >= 0) reverse = true;
                if (a.indexOf('t') >= 0) sortTime = true;
                if (a.indexOf('R') >= 0) recursive = true;
                if (a.indexOf('i') >= 0) inode = true;
                if (a.indexOf('F') >= 0) classify = true;
                if (a.indexOf("color") >= 0) colorize = true;
            } else {
                paths.addElement(a);
            }
        }

        if (paths.size() == 0) paths.addElement(fs.getCurrentPath());

        StringBuffer sb = new StringBuffer();
        for (int pi = 0; pi < paths.size(); pi++) {
            String p = fs.resolvePath((String) paths.elementAt(pi));
            if (!fs.exists(p)) {
                sb.append("ls: cannot access '").append((String)paths.elementAt(pi)).append("': No such file or directory\n");
                continue;
            }
            if (paths.size() > 1) sb.append(p).append(":\n");
            if (fs.isFile(p)) {
                sb.append(lsLine(p, longFmt, all, human, inode, classify));
            } else {
                String[] children = fs.listChildren(p);
                // Sort
                children = sortStrings(children, reverse);
                if (longFmt) {
                    sb.append("total ").append(children.length * 8).append("\n");
                }
                // Dot entries
                if (all) {
                    if (longFmt) {
                        sb.append("drwxr-xr-x 2 ").append(fs.getUsername()).append(" ").append(fs.getUsername())
                          .append("  4096 Mar 22 10:00 .\n");
                        sb.append("drwxr-xr-x 3 root root  4096 Mar 22 10:00 ..\n");
                    } else {
                        sb.append(".  ..  ");
                    }
                }
                for (int i = 0; i < children.length; i++) {
                    String child = children[i];
                    String name = fs.nameOf(child);
                    if (!all && name.startsWith(".")) continue;
                    sb.append(lsLine(child, longFmt, all, human, inode, classify));
                    if (!longFmt) sb.append("  ");
                }
                if (!longFmt) sb.append("\n");
            }
            if (recursive) {
                // Recurse into subdirs
                String[] children = fs.listChildren(p);
                for (int i = 0; i < children.length; i++) {
                    if (fs.isDir(children[i])) {
                        sb.append("\n").append(children[i]).append(":\n");
                        String[] subchildren = fs.listChildren(children[i]);
                        for (int j = 0; j < subchildren.length; j++) {
                            String name = fs.nameOf(subchildren[j]);
                            if (!all && name.startsWith(".")) continue;
                            sb.append(lsLine(subchildren[j], longFmt, all, human, inode, classify));
                            if (!longFmt) sb.append("  ");
                        }
                        if (!longFmt) sb.append("\n");
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private String lsLine(String absPath, boolean longFmt, boolean all, boolean human, boolean inode, boolean classify) {
        String name = fs.nameOf(absPath);
        boolean isDir = fs.isDir(absPath);
        String suffix = "";
        if (classify) suffix = isDir ? "/" : (fs.getPerms(absPath).indexOf('x') >= 1 ? "*" : "");
        if (!longFmt) return name + suffix;
        String perms = fs.getPerms(absPath);
        String owner = fs.getOwner(absPath);
        String size  = fs.getSize(absPath);
        String mtime = fs.getMtime(absPath);
        if (human) size = humanSize(size);
        String inodeStr = inode ? (Math.abs(name.hashCode()) % 900000 + 100000) + " " : "";
        return inodeStr + padRight(perms, 10) + " 1 " + padRight(owner, 8) + " " + padRight(owner, 8) +
               " " + padLeft(size, 8) + " " + mtime + " " + name + suffix + "\n";
    }

    private String cmdCat(String[] args) {
        if (args.length == 0) return "(reading from stdin - not supported in this mode)";
        boolean number = false, squeeze = false;
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-n")) { number = true; continue; }
            if (args[i].equals("-s")) { squeeze = true; continue; }
            if (args[i].equals("-A") || args[i].equals("-e")) continue;
            String p = fs.resolvePath(args[i]);
            if (!fs.exists(p)) { sb.append("cat: ").append(args[i]).append(": No such file or directory\n"); continue; }
            if (fs.isDir(p))   { sb.append("cat: ").append(args[i]).append(": Is a directory\n"); continue; }
            String content = fs.readFile(p);
            if (number) {
                String[] lines = splitLines(content);
                for (int j = 0; j < lines.length; j++) {
                    sb.append(padLeft(String.valueOf(j+1), 6)).append("\t").append(lines[j]).append("\n");
                }
            } else {
                sb.append(content);
            }
        }
        return sb.toString().trim();
    }

    private String cmdEcho(String[] args) {
        boolean noNewline = false, interpret = false;
        StringBuffer sb = new StringBuffer();
        int start = 0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-n")) { noNewline = true; start = i+1; }
            else if (args[i].equals("-e")) { interpret = true; start = i+1; }
            else break;
        }
        for (int i = start; i < args.length; i++) {
            if (i > start) sb.append(" ");
            String s = args[i];
            if (interpret) s = interpretEscapes(s);
            sb.append(s);
        }
        if (!noNewline) sb.append("\n");
        return sb.toString();
    }

    private String cmdPrintf(String[] args) {
        if (args.length == 0) return "";
        String fmt = args[0];
        fmt = interpretEscapes(fmt);
        // Very basic %s, %d substitution
        StringBuffer sb = new StringBuffer();
        int ai = 1;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c == '%' && i+1 < fmt.length()) {
                char spec = fmt.charAt(i+1);
                String val = ai < args.length ? args[ai++] : "";
                if (spec == 's') sb.append(val);
                else if (spec == 'd') { try { sb.append(Integer.parseInt(val)); } catch(Exception e){ sb.append("0"); } }
                else if (spec == 'f') { try { sb.append(Float.parseFloat(val)); } catch(Exception e){ sb.append("0.0"); } }
                else sb.append(spec);
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String cmdMkdir(String[] args) {
        if (args.length == 0) return "mkdir: missing operand";
        boolean parents = false;
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-p")) { parents = true; continue; }
            String p = fs.resolvePath(args[i]);
            if (parents) {
                // Create all intermediate dirs
                String[] parts = splitPathParts(p);
                StringBuffer cur = new StringBuffer();
                for (int j = 0; j < parts.length; j++) {
                    if (j == 0) cur.append("/");
                    else cur.append(parts[j]).append("/");
                    String seg = normSlash(cur.toString());
                    if (!fs.exists(seg)) fs.createDir(seg);
                }
            } else {
                if (!fs.createDir(p)) {
                    if (fs.exists(p)) sb.append("mkdir: cannot create directory '").append(args[i]).append("': File exists\n");
                    else sb.append("mkdir: cannot create directory '").append(args[i]).append("': No such file or directory\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private String cmdRmdir(String[] args) {
        if (args.length == 0) return "rmdir: missing operand";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) continue;
            String p = fs.resolvePath(args[i]);
            if (!fs.exists(p)) { sb.append("rmdir: failed to remove '").append(args[i]).append("': No such file or directory\n"); continue; }
            if (!fs.isDir(p))  { sb.append("rmdir: failed to remove '").append(args[i]).append("': Not a directory\n"); continue; }
            if (!fs.deleteNode(p)) sb.append("rmdir: failed to remove '").append(args[i]).append("': Directory not empty\n");
        }
        return sb.toString().trim();
    }

    private String cmdRm(String[] args) {
        if (args.length == 0) return "rm: missing operand";
        boolean force = false, recursive = false;
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-")) {
                if (a.indexOf('f') >= 0) force = true;
                if (a.indexOf('r') >= 0 || a.indexOf('R') >= 0) recursive = true;
                continue;
            }
            String p = fs.resolvePath(a);
            if (!fs.exists(p)) {
                if (!force) sb.append("rm: cannot remove '").append(a).append("': No such file or directory\n");
                continue;
            }
            if (fs.isDir(p) && !recursive) { sb.append("rm: cannot remove '").append(a).append("': Is a directory\n"); continue; }
            if (!fs.deleteRecursive(p)) sb.append("rm: cannot remove '").append(a).append("'\n");
        }
        return sb.toString().trim();
    }

    private String cmdCp(String[] args) {
        if (args.length < 2) return "cp: missing file operand";
        boolean recursive = false, force = false, verbose = false;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                if (args[i].indexOf('r') >= 0 || args[i].indexOf('R') >= 0) recursive = true;
                if (args[i].indexOf('f') >= 0) force = true;
                if (args[i].indexOf('v') >= 0) verbose = true;
            } else files.addElement(args[i]);
        }
        if (files.size() < 2) return "cp: missing destination";
        String dst = (String) files.lastElement();
        String dstAbs = fs.resolvePath(dst);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < files.size() - 1; i++) {
            String src = fs.resolvePath((String) files.elementAt(i));
            if (!fs.exists(src)) { sb.append("cp: cannot stat '").append(files.elementAt(i)).append("': No such file or directory\n"); continue; }
            String target = dstAbs;
            if (fs.isDir(dstAbs)) target = dstAbs + "/" + fs.nameOf(src);
            if (!fs.copyNode(src, target)) sb.append("cp: cannot copy '").append(files.elementAt(i)).append("'\n");
            else if (verbose) sb.append("'").append(src).append("' -> '").append(target).append("'\n");
        }
        return sb.toString().trim();
    }

    private String cmdMv(String[] args) {
        if (args.length < 2) return "mv: missing file operand";
        Vector files = new Vector();
        boolean verbose = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) { if(args[i].indexOf('v')>=0) verbose=true; }
            else files.addElement(args[i]);
        }
        if (files.size() < 2) return "mv: missing destination";
        String dst = (String) files.lastElement();
        String dstAbs = fs.resolvePath(dst);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < files.size() - 1; i++) {
            String src = fs.resolvePath((String) files.elementAt(i));
            if (!fs.exists(src)) { sb.append("mv: cannot stat '").append(files.elementAt(i)).append("': No such file or directory\n"); continue; }
            String target = dstAbs;
            if (fs.isDir(dstAbs)) target = dstAbs + "/" + fs.nameOf(src);
            fs.moveNode(src, target);
            if (verbose) sb.append("'").append(src).append("' -> '").append(target).append("'\n");
        }
        return sb.toString().trim();
    }

    private String cmdTouch(String[] args) {
        if (args.length == 0) return "touch: missing file operand";
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) continue;
            String p = fs.resolvePath(args[i]);
            if (!fs.exists(p)) fs.writeFile(p, "");
        }
        return "";
    }

    private String cmdLn(String[] args) {
        boolean symbolic = false;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) { if(args[i].indexOf('s')>=0) symbolic=true; }
            else files.addElement(args[i]);
        }
        if (files.size() < 2) return "ln: missing operand";
        String src = fs.resolvePath((String)files.elementAt(0));
        String dst = fs.resolvePath((String)files.elementAt(1));
        if (!fs.exists(src)) return "ln: failed to access '" + files.elementAt(0) + "': No such file or directory";
        fs.copyNode(src, dst);
        return "";
    }

    private String cmdFind(String[] args) {
        String startPath = fs.getCurrentPath();
        StringBuffer sb = new StringBuffer();
        String namePattern = null;
        String typeFilter  = null;
        String[] printArgs = null;
        boolean maxdepth1  = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                if (args[i].equals("-name") && i+1 < args.length) { namePattern = args[++i]; }
                else if (args[i].equals("-type") && i+1 < args.length) { typeFilter = args[++i]; }
                else if (args[i].equals("-maxdepth") && i+1 < args.length) { if(args[++i].equals("1")) maxdepth1=true; }
                else if (args[i].equals("-iname") && i+1 < args.length) { namePattern = args[++i].toLowerCase(); }
            } else {
                startPath = fs.resolvePath(args[i]);
            }
        }

        // Walk all nodes
        String absStart = startPath;
        Enumeration keys = null;
        // We need to iterate nodes - use VirtualFS helper
        // Collect matches
        String[] children = getAllUnder(absStart);
        if (!fs.exists(absStart)) return "find: '" + startPath + "': No such file or directory";
        sb.append(absStart).append("\n");
        for (int i = 0; i < children.length; i++) {
            String path = children[i];
            String name = fs.nameOf(path);
            // Type filter
            if (typeFilter != null) {
                if (typeFilter.equals("f") && !fs.isFile(path)) continue;
                if (typeFilter.equals("d") && !fs.isDir(path))  continue;
            }
            // Name pattern
            if (namePattern != null) {
                if (!simpleGlob(namePattern, name)) continue;
            }
            sb.append(path).append("\n");
        }
        return sb.toString().trim();
    }

    private String[] getAllUnder(String absDir) {
        // Use fs.listChildren recursively
        Vector v = new Vector();
        collectRecursive(absDir, v);
        String[] arr = new String[v.size()];
        v.copyInto(arr);
        return arr;
    }

    private void collectRecursive(String dir, Vector v) {
        String[] children = fs.listChildren(dir);
        for (int i = 0; i < children.length; i++) {
            v.addElement(children[i]);
            if (fs.isDir(children[i])) collectRecursive(children[i], v);
        }
    }

    private String cmdGrep(String[] args, String stdin) {
        boolean ignoreCase = false, invert = false, lineNumber = false,
                count = false, recursive = false, onlyMatch = false, quiet = false;
        String pattern = null;
        Vector files = new Vector();

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                if (args[i].indexOf('i') >= 0) ignoreCase = true;
                if (args[i].indexOf('v') >= 0) invert = true;
                if (args[i].indexOf('n') >= 0) lineNumber = true;
                if (args[i].indexOf('c') >= 0) count = true;
                if (args[i].indexOf('r') >= 0 || args[i].indexOf('R') >= 0) recursive = true;
                if (args[i].indexOf('o') >= 0) onlyMatch = true;
                if (args[i].indexOf('q') >= 0) quiet = true;
                if (args[i].equals("-e") && i+1 < args.length) pattern = args[++i];
                if (args[i].equals("--color=auto") || args[i].equals("--color")) continue;
            } else {
                if (pattern == null) pattern = args[i];
                else files.addElement(args[i]);
            }
        }
        if (pattern == null) return "grep: no pattern specified";

        StringBuffer sb = new StringBuffer();
        final String pat = ignoreCase ? pattern.toLowerCase() : pattern;

        if (stdin != null && files.size() == 0) {
            grepText(sb, stdin, pat, ignoreCase, invert, lineNumber, count, onlyMatch, null);
        } else {
            for (int i = 0; i < files.size(); i++) {
                String p = fs.resolvePath((String)files.elementAt(i));
                if (!fs.exists(p)) { sb.append("grep: ").append(files.elementAt(i)).append(": No such file or directory\n"); continue; }
                String content = fs.readFile(p);
                if (content == null) continue;
                String prefix = files.size() > 1 ? (String)files.elementAt(i) + ":" : null;
                grepText(sb, content, pat, ignoreCase, invert, lineNumber, count, onlyMatch, prefix);
            }
        }
        return sb.toString().trim();
    }

    private void grepText(StringBuffer sb, String text, String pat, boolean ignoreCase,
                          boolean invert, boolean lineNumber, boolean count,
                          boolean onlyMatch, String prefix) {
        String[] lines = splitLines(text);
        int matchCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String cmpLine = ignoreCase ? line.toLowerCase() : line;
            boolean found = cmpLine.indexOf(pat) >= 0;
            if (invert) found = !found;
            if (found) {
                matchCount++;
                if (!count && !onlyMatch) {
                    if (prefix != null) sb.append(prefix);
                    if (lineNumber) sb.append((i+1)).append(":");
                    sb.append(line).append("\n");
                }
            }
        }
        if (count) {
            if (prefix != null) sb.append(prefix);
            sb.append(matchCount).append("\n");
        }
    }

    private String cmdHead(String[] args, String stdin) {
        int n = 10;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-n") && i+1 < args.length) { try { n = Integer.parseInt(args[++i]); } catch(Exception e){} }
            else if (args[i].startsWith("-") && args[i].length() > 1) { try { n = Integer.parseInt(args[i].substring(1)); } catch(Exception e){} }
            else files.addElement(args[i]);
        }
        String text = stdin;
        if (files.size() > 0) text = fs.readFile(fs.resolvePath((String)files.elementAt(0)));
        if (text == null) return "";
        String[] lines = splitLines(text);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < Math.min(n, lines.length); i++) sb.append(lines[i]).append("\n");
        return sb.toString().trim();
    }

    private String cmdTail(String[] args, String stdin) {
        int n = 10;
        boolean follow = false;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-n") && i+1 < args.length) { try { n = Integer.parseInt(args[++i]); } catch(Exception e){} }
            else if (args[i].equals("-f")) follow = true;
            else if (args[i].startsWith("-") && args[i].length() > 1) { try { n = Integer.parseInt(args[i].substring(1)); } catch(Exception e){} }
            else files.addElement(args[i]);
        }
        String text = stdin;
        if (files.size() > 0) {
            String p = fs.resolvePath((String)files.elementAt(0));
            if (!fs.exists(p)) return "tail: cannot open '" + files.elementAt(0) + "' for reading: No such file or directory";
            text = fs.readFile(p);
        }
        if (text == null) return "";
        String[] lines = splitLines(text);
        int start = Math.max(0, lines.length - n);
        StringBuffer sb = new StringBuffer();
        for (int i = start; i < lines.length; i++) sb.append(lines[i]).append("\n");
        if (follow) sb.append("(tail -f: streaming mode not supported in this terminal)");
        return sb.toString().trim();
    }

    private String cmdWc(String[] args, String stdin) {
        boolean lines = false, words = false, chars = false, bytes = false;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-l")) lines = true;
            else if (args[i].equals("-w")) words = true;
            else if (args[i].equals("-c") || args[i].equals("-m")) { chars = true; bytes = true; }
            else if (!args[i].startsWith("-")) files.addElement(args[i]);
        }
        if (!lines && !words && !chars) { lines = true; words = true; chars = true; }

        String text = stdin;
        if (files.size() > 0) text = nullToEmpty(fs.readFile(fs.resolvePath((String)files.elementAt(0))));
        if (text == null) text = "";

        String[] lineArr = splitLines(text);
        int lc = lineArr.length, wc = 0, cc = text.length();
        for (int i = 0; i < lineArr.length; i++) {
            String[] wds = splitWS(lineArr[i].trim());
            if (lineArr[i].trim().length() > 0) wc += wds.length;
        }
        StringBuffer sb = new StringBuffer();
        if (lines) sb.append(padLeft(String.valueOf(lc), 7)).append(" ");
        if (words) sb.append(padLeft(String.valueOf(wc), 7)).append(" ");
        if (chars) sb.append(padLeft(String.valueOf(cc), 7));
        if (files.size() > 0) sb.append(" ").append(files.elementAt(0));
        return sb.toString().trim();
    }

    private String cmdSort(String[] args, String stdin) {
        boolean reverse = false, numeric = false, unique = false, ignoreCase = false;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                if (args[i].indexOf('r') >= 0) reverse = true;
                if (args[i].indexOf('n') >= 0) numeric = true;
                if (args[i].indexOf('u') >= 0) unique = true;
                if (args[i].indexOf('f') >= 0) ignoreCase = true;
            } else files.addElement(args[i]);
        }
        String text = stdin;
        if (files.size() > 0) text = nullToEmpty(fs.readFile(fs.resolvePath((String)files.elementAt(0))));
        if (text == null || text.length() == 0) return "";
        String[] lines = splitLines(text);
        lines = sortStrings(lines, false);
        if (reverse) lines = reverseArray(lines);
        if (unique) lines = uniqueLines(lines);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < lines.length; i++) sb.append(lines[i]).append("\n");
        return sb.toString().trim();
    }

    private String cmdUniq(String[] args, String stdin) {
        boolean count = false, dup = false, unique = false;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-c")) count = true;
            else if (args[i].equals("-d")) dup = true;
            else if (args[i].equals("-u")) unique = true;
            else if (!args[i].startsWith("-")) files.addElement(args[i]);
        }
        String text = stdin;
        if (files.size() > 0) text = nullToEmpty(fs.readFile(fs.resolvePath((String)files.elementAt(0))));
        if (text == null || text.length() == 0) return "";
        String[] lines = splitLines(text);
        StringBuffer sb = new StringBuffer();
        int i = 0;
        while (i < lines.length) {
            int cnt = 1;
            while (i+cnt < lines.length && lines[i+cnt].equals(lines[i])) cnt++;
            if (!dup && !unique) {
                if (count) sb.append(padLeft(String.valueOf(cnt), 4)).append(" ");
                sb.append(lines[i]).append("\n");
            } else if (dup && cnt > 1) {
                if (count) sb.append(padLeft(String.valueOf(cnt), 4)).append(" ");
                sb.append(lines[i]).append("\n");
            } else if (unique && cnt == 1) {
                sb.append(lines[i]).append("\n");
            }
            i += cnt;
        }
        return sb.toString().trim();
    }

    private String cmdCut(String[] args, String stdin) {
        String delim = "\t";
        String fields = null;
        String chars = null;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-d") && i+1 < args.length) delim = args[++i];
            else if (args[i].startsWith("-d")) delim = args[i].substring(2);
            else if (args[i].equals("-f") && i+1 < args.length) fields = args[++i];
            else if (args[i].startsWith("-f")) fields = args[i].substring(2);
            else if (args[i].equals("-c") && i+1 < args.length) chars = args[++i];
            else if (!args[i].startsWith("-")) files.addElement(args[i]);
        }
        String text = stdin;
        if (files.size() > 0) text = nullToEmpty(fs.readFile(fs.resolvePath((String)files.elementAt(0))));
        if (text == null) return "";
        String[] lines = splitLines(text);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < lines.length; i++) {
            if (fields != null) {
                String[] parts = splitOn(lines[i], delim);
                int fieldNum = 1;
                try { fieldNum = Integer.parseInt(fields); } catch(Exception e){}
                if (fieldNum > 0 && fieldNum <= parts.length) sb.append(parts[fieldNum-1]);
            } else if (chars != null) {
                try {
                    int c = Integer.parseInt(chars) - 1;
                    if (c >= 0 && c < lines[i].length()) sb.append(lines[i].charAt(c));
                } catch(Exception e){}
            } else {
                sb.append(lines[i]);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdTr(String[] args, String stdin) {
        if (stdin == null || args.length < 2) return stdin != null ? stdin : "";
        boolean delete = false, squeeze = false;
        String set1 = "", set2 = "";
        Vector sets = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-d")) delete = true;
            else if (args[i].equals("-s")) squeeze = true;
            else sets.addElement(args[i]);
        }
        if (sets.size() > 0) set1 = (String)sets.elementAt(0);
        if (sets.size() > 1) set2 = (String)sets.elementAt(1);

        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < stdin.length(); i++) {
            char c = stdin.charAt(i);
            int idx = set1.indexOf(c);
            if (delete) {
                if (idx < 0) sb.append(c);
            } else if (idx >= 0 && idx < set2.length()) {
                sb.append(set2.charAt(idx));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String cmdSed(String[] args, String stdin) {
        String expr = null;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-e") && i+1 < args.length) expr = args[++i];
            else if (args[i].startsWith("-e")) expr = args[i].substring(2);
            else if (!args[i].startsWith("-")) {
                if (expr == null) expr = args[i];
                else files.addElement(args[i]);
            }
        }
        String text = stdin;
        if (files.size() > 0) text = nullToEmpty(fs.readFile(fs.resolvePath((String)files.elementAt(0))));
        if (text == null) text = "";
        if (expr == null) return text;
        // Support s/pattern/replacement/g
        if (expr.startsWith("s")) {
            char sep = expr.length() > 1 ? expr.charAt(1) : '/';
            String[] parts = splitSed(expr.substring(2), sep);
            if (parts.length >= 2) {
                String from = parts[0], to = parts[1];
                boolean global = parts.length > 2 && parts[2].indexOf('g') >= 0;
                if (global) text = replaceAll(text, from, to);
                else text = replaceFirst(text, from, to);
            }
        }
        return text;
    }

    private String cmdAwk(String[] args, String stdin) {
        // Basic awk: print fields
        String prog = null;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-F") && i+1 < args.length) { i++; continue; } // ignore FS for now
            else if (!args[i].startsWith("-")) {
                if (prog == null) prog = args[i];
                else files.addElement(args[i]);
            }
        }
        String text = stdin;
        if (files.size() > 0) text = nullToEmpty(fs.readFile(fs.resolvePath((String)files.elementAt(0))));
        if (text == null) return "";
        if (prog == null) return text;
        // Very basic: {print $N} or {print $NF} or {print}
        StringBuffer sb = new StringBuffer();
        String[] lines = splitLines(text);
        for (int i = 0; i < lines.length; i++) {
            String[] fields = splitWS(lines[i].trim());
            // Replace {print ...} pattern
            if (prog.indexOf("print") >= 0) {
                if (prog.indexOf("$NF") >= 0) {
                    sb.append(fields.length > 0 ? fields[fields.length-1] : "").append("\n");
                } else if (prog.indexOf("$0") >= 0 || prog.indexOf("print}") >= 0 || prog.equals("{print}")) {
                    sb.append(lines[i]).append("\n");
                } else {
                    // Try to extract field number
                    int dollarIdx = prog.indexOf('$');
                    if (dollarIdx >= 0) {
                        String rest = prog.substring(dollarIdx + 1);
                        int fnum = 0;
                        for (int j = 0; j < rest.length(); j++) {
                            char c = rest.charAt(j);
                            if (c >= '0' && c <= '9') fnum = fnum * 10 + (c - '0');
                            else break;
                        }
                        if (fnum > 0 && fnum <= fields.length) sb.append(fields[fnum-1]).append("\n");
                    } else {
                        sb.append(lines[i]).append("\n");
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private String cmdTee(String[] args, String stdin) {
        if (stdin == null) return "";
        boolean append = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-a")) { append = true; continue; }
            String p = fs.resolvePath(args[i]);
            if (append) fs.appendFile(p, stdin);
            else fs.writeFile(p, stdin);
        }
        return stdin;
    }

    private String cmdXargs(String[] args, String stdin) {
        if (stdin == null) return "";
        String cmd = args.length > 0 ? args[0] : "echo";
        String[] lines = splitLines(stdin);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() > 0) sb.append(execute(cmd + " " + line)).append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdLess(String[] args) {
        if (args.length == 0) return "(less: reading from stdin)";
        String p = fs.resolvePath(args[args.length - 1]);
        if (!fs.exists(p)) return "less: " + args[args.length-1] + ": No such file or directory";
        return nullToEmpty(fs.readFile(p));
    }

    private String cmdStat(String[] args) {
        if (args.length == 0) return "stat: missing operand";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) continue;
            String p = fs.resolvePath(args[i]);
            if (!fs.exists(p)) { sb.append("stat: cannot stat '").append(args[i]).append("': No such file or directory\n"); continue; }
            String type = fs.isDir(p) ? "directory" : "regular file";
            sb.append("  File: ").append(p).append("\n");
            sb.append("  Size: ").append(fs.getSize(p)).append("\tBlocks: 8\tIO Block: 4096\t").append(type).append("\n");
            sb.append("Device: fd00h/64768d\tInode: ").append(Math.abs(p.hashCode()) % 900000 + 100000).append("\tLinks: 1\n");
            sb.append("Access: ").append(fs.getPerms(p)).append(" Uid: (1000/ ").append(fs.getOwner(p)).append(")\tGid: (1000/ ").append(fs.getOwner(p)).append(")\n");
            sb.append("Access: 2024-03-22 10:00:00.000000000 +0000\n");
            sb.append("Modify: ").append(fs.getMtime(p)).append(":00.000000000 +0000\n");
            sb.append("Change: 2024-03-22 10:00:00.000000000 +0000\n");
            sb.append(" Birth: -\n");
        }
        return sb.toString().trim();
    }

    private String cmdFile(String[] args) {
        if (args.length == 0) return "file: missing operand";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) continue;
            String p = fs.resolvePath(args[i]);
            if (!fs.exists(p)) { sb.append(args[i]).append(": cannot open (No such file or directory)\n"); continue; }
            String type;
            if (fs.isDir(p)) type = "directory";
            else {
                String name = fs.nameOf(p).toLowerCase();
                if (name.endsWith(".sh")) type = "Bourne-Again shell script, ASCII text executable";
                else if (name.endsWith(".py")) type = "Python script, ASCII text executable";
                else if (name.endsWith(".java")) type = "Java source, ASCII text";
                else if (name.endsWith(".class")) type = "compiled Java class data";
                else if (name.endsWith(".html") || name.endsWith(".htm")) type = "HTML document, ASCII text";
                else if (name.endsWith(".gz")) type = "gzip compressed data";
                else if (name.endsWith(".tar")) type = "POSIX tar archive";
                else if (name.endsWith(".zip")) type = "Zip archive data";
                else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) type = "JPEG image data";
                else if (name.endsWith(".png")) type = "PNG image data";
                else if (name.endsWith(".pdf")) type = "PDF document";
                else type = "ASCII text";
            }
            sb.append(args[i]).append(": ").append(type).append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdReadlink(String[] args) {
        if (args.length == 0) return "";
        String p = fs.resolvePath(args[args.length-1]);
        return p;
    }

    private String cmdBasename(String[] args) {
        if (args.length == 0) return "";
        String name = fs.nameOf(args[0]);
        if (args.length > 1) {
            String suf = args[1];
            if (name.endsWith(suf)) name = name.substring(0, name.length() - suf.length());
        }
        return name;
    }

    private String cmdDirname(String[] args) {
        if (args.length == 0) return ".";
        String p = args[0];
        int idx = p.lastIndexOf('/');
        if (idx < 0) return ".";
        if (idx == 0) return "/";
        return p.substring(0, idx);
    }

    private String cmdWhich(String[] args) {
        if (args.length == 0) return "";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            String binPath = "/usr/bin/" + args[i];
            if (fs.exists(binPath)) sb.append(binPath).append("\n");
            else {
                binPath = "/bin/" + args[i];
                if (fs.exists(binPath)) sb.append(binPath).append("\n");
                else sb.append("which: no ").append(args[i]).append(" in (PATH)\n");
            }
        }
        return sb.toString().trim();
    }

    private String cmdWhereis(String[] args) {
        if (args.length == 0) return "";
        String name = args[args.length-1];
        StringBuffer sb = new StringBuffer();
        sb.append(name).append(": /usr/bin/").append(name);
        sb.append(" /bin/").append(name);
        sb.append(" /usr/share/man/man1/").append(name).append(".1.gz");
        return sb.toString();
    }

    private String cmdWhatis(String[] args) {
        if (args.length == 0) return "";
        return args[0] + " (1)     - command description (man page not available in this terminal)";
    }

    private String cmdMan(String[] args) {
        if (args.length == 0) return "What manual page do you want?";
        String cmd = args[args.length-1];
        // Built-in man pages for common commands
        Hashtable manPages = new Hashtable();
        manPages.put("ls",   "LS(1) - list directory contents\n\nSYNOPSIS: ls [OPTION]... [FILE]...\n\nOPTIONS:\n  -a  do not ignore entries starting with .\n  -l  use a long listing format\n  -h  human-readable sizes\n  -r  reverse order\n  -R  list subdirectories recursively");
        manPages.put("cd",   "CD(1) - change the shell working directory\n\nSYNOPSIS: cd [DIR]\n\n  cd -   go to previous directory\n  cd ~   go to home directory");
        manPages.put("grep", "GREP(1) - print lines matching a pattern\n\nSYNOPSIS: grep [OPTIONS] PATTERN [FILE...]\n\nOPTIONS:\n  -i  ignore case\n  -v  invert match\n  -n  show line numbers\n  -r  recursive\n  -c  print count only");
        manPages.put("cat",  "CAT(1) - concatenate files and print on the standard output\n\nSYNOPSIS: cat [OPTION]... [FILE]...\n\nOPTIONS:\n  -n  number all output lines\n  -s  suppress repeated empty output lines");
        manPages.put("find", "FIND(1) - search for files in a directory hierarchy\n\nSYNOPSIS: find [path] [expression]\n\nEXPRESSIONS:\n  -name PATTERN   file name matches PATTERN\n  -type f         regular files only\n  -type d         directories only");
        if (manPages.containsKey(cmd)) return "Manual page " + cmd + "(1)\n\n" + manPages.get(cmd) + "\n\n(END)";
        return "No manual entry for " + cmd;
    }

    private String cmdHelp(String[] args) {
        return "J2ME Terminal - Unix/Linux/Windows CMD/macOS command emulator\n" +
               "================================================================\n\n" +
               "FILE SYSTEM:\n" +
               "  ls [-la]   cd [dir]   pwd        mkdir [-p]  rm [-rf]\n" +
               "  cp [-r]    mv         touch       ln [-s]     find\n" +
               "  cat [-n]   head [-n]  tail [-nf]  stat        file\n" +
               "  less/more  which      whereis     chmod       chown\n\n" +
               "TEXT PROCESSING:\n" +
               "  grep [-ivnrc]  wc [-lwc]  sort [-rnu]  uniq [-cd]\n" +
               "  cut [-df]      tr         sed s/x/y/g  awk {print}\n" +
               "  tee            xargs      diff         paste\n\n" +
               "SYSTEM INFO:\n" +
               "  uname [-a]  uptime   free [-h]  df [-h]   du [-sh]\n" +
               "  ps [aux]    top      lsof       lsblk     fdisk\n" +
               "  dmesg       lspci    lsusb      sensors   neofetch\n" +
               "  vmstat      iostat   lsmod      sysctl\n\n" +
               "USERS:\n" +
               "  whoami  id  who  w  last  su  sudo  passwd\n" +
               "  useradd  userdel  groups  users\n\n" +
               "NETWORK:\n" +
               "  ping       ifconfig   ip         netstat    ss\n" +
               "  nmap       curl       wget       ssh        scp\n" +
               "  dig        nslookup   traceroute arp        route\n" +
               "  iptables   ufw        tcpdump    nc\n\n" +
               "ARCHIVE:\n" +
               "  tar [-czxf]  gzip  gunzip  zip  unzip  bzip2\n\n" +
               "CRYPTO:\n" +
               "  md5sum  sha256sum  sha1sum  base64  hexdump  strings\n\n" +
               "PACKAGE:\n" +
               "  apt/apt-get  dpkg  snap  pip/pip3\n\n" +
               "DEV TOOLS:\n" +
               "  git  python/python3  node  npm  java  javac  gcc  make\n\n" +
               "SYSTEM MGMT:\n" +
               "  systemctl  service  journalctl  crontab  shutdown  reboot\n\n" +
               "WINDOWS CMD:\n" +
               "  dir  type  copy  move  del  md  rd  ren  cls  ver\n" +
               "  ipconfig  tasklist  taskkill  net  netsh  tree  xcopy\n\n" +
               "macOS:\n" +
               "  open  pbcopy  pbpaste  sw_vers  brew  say  diskutil\n\n" +
               "PIPE & REDIRECT:\n" +
               "  cmd1 | cmd2    cmd > file    cmd >> file\n\n" +
               "MISC:\n" +
               "  echo  date  cal  env  export  alias  history  man\n" +
               "  bc    expr  seq  yes  cowsay  fortune  neofetch  clear\n" +
               "Type 'man COMMAND' for more info. Type 'exit' to quit.\n";
    }

    private String cmdChmod(String[] args) {
        if (args.length < 2) return "chmod: missing operand";
        return ""; // silent success
    }

    private String cmdChown(String[] args) {
        if (args.length < 2) return "chown: missing operand";
        return "";
    }

    private String cmdId(String[] args) {
        String user = fs.getUsername();
        return "uid=1000(" + user + ") gid=1000(" + user + ") groups=1000(" + user + "),4(adm),24(cdrom),27(sudo),30(dip),46(plugdev)";
    }

    private String cmdWho() {
        String user = fs.getUsername();
        return user + "   pts/0        2024-03-22 10:00 (:0)";
    }

    private String cmdW() {
        String user = fs.getUsername();
        return " 10:00:00 up 1:01,  1 user,  load average: 0.15, 0.10, 0.08\n" +
               "USER     TTY      FROM             LOGIN@   IDLE JCPU   PCPU WHAT\n" +
               user + " pts/0    :0               10:00    0.00s 0.05s  0.02s w";
    }

    private String cmdLast() {
        String user = fs.getUsername();
        return user + " pts/0        :0               Fri Mar 22 10:00   still logged in\n" +
               user + " pts/0        :0               Thu Mar 21 09:00 - 18:00  (09:00)\n" +
               "reboot   system boot  5.15.0-88-generi Fri Mar 22 09:58   still running\n\n" +
               "wtmp begins Mon Mar 18 08:00:00 2024";
    }

    private String cmdLastlog() {
        String user = fs.getUsername();
        return "Username         Port     From             Latest\n" +
               "root             pts/0    192.168.1.1      Fri Mar 22 08:00:00 +0000 2024\n" +
               user + "          pts/0    :0               Fri Mar 22 10:00:00 +0000 2024";
    }

    private String cmdSu(String[] args) {
        if (args.length > 0 && args[0].equals("-")) return "[switching to root shell - password required]\nbash: su: Authentication failure";
        return "[su: password required for " + (args.length>0?args[0]:"root") + "]";
    }

    private String cmdSudo(String[] args, String full) {
        if (args.length == 0) return "usage: sudo [-AbEHnPS] [-g group] [-u user] command";
        if (args[0].equals("-l") || args[0].equals("--list")) {
            return "Matching Defaults entries for " + fs.getUsername() + " on " + fs.getHostname() + ":\n    env_reset, mail_badpass\n\nUser " + fs.getUsername() + " may run the following commands:\n    (" + fs.getUsername() + ") ALL : ALL";
        }
        // Execute the rest as if root
        String subcmd = join(args, " ");
        return "[sudo] running as root: " + subcmd + "\n" + execute(subcmd);
    }

    private String cmdPasswd(String[] args) {
        return "Changing password for " + fs.getUsername() + ".\n(passwd: password updated successfully)";
    }

    private String cmdUname(String[] args) {
        boolean all = false, kernel = false, node = false,
                release = false, version = false, machine = false, os = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].indexOf("a") >= 0) all = true;
            if (args[i].indexOf("s") >= 0) kernel = true;
            if (args[i].indexOf("n") >= 0) node = true;
            if (args[i].indexOf("r") >= 0) release = true;
            if (args[i].indexOf("v") >= 0) version = true;
            if (args[i].indexOf("m") >= 0) machine = true;
            if (args[i].indexOf("o") >= 0) os = true;
        }
        if (args.length == 0) return "Linux";
        if (all) return "Linux " + fs.getHostname() + " 5.15.0-88-generic #98-Ubuntu SMP Mon Oct 2 15:18:56 UTC 2023 x86_64 x86_64 x86_64 GNU/Linux";
        StringBuffer sb = new StringBuffer();
        if (kernel)  sb.append("Linux ");
        if (node)    sb.append(fs.getHostname()).append(" ");
        if (release) sb.append("5.15.0-88-generic ");
        if (version) sb.append("#98-Ubuntu SMP Mon Oct 2 15:18:56 UTC 2023 ");
        if (machine) sb.append("x86_64 ");
        if (os)      sb.append("GNU/Linux");
        return sb.toString().trim();
    }

    private String cmdDate(String[] args) {
        // Return a fixed but realistic looking date
        String[] weekdays = {"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};
        String[] months   = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
        // Use current simulated time
        String dt = "Sat Mar 22 10:00:00 UTC 2025";
        if (args.length > 0 && args[0].startsWith("+")) {
            String fmt = args[0].substring(1);
            fmt = replaceAll(fmt, "%Y", "2025");
            fmt = replaceAll(fmt, "%m", "03");
            fmt = replaceAll(fmt, "%d", "22");
            fmt = replaceAll(fmt, "%H", "10");
            fmt = replaceAll(fmt, "%M", "00");
            fmt = replaceAll(fmt, "%S", "00");
            fmt = replaceAll(fmt, "%A", "Saturday");
            fmt = replaceAll(fmt, "%B", "March");
            fmt = replaceAll(fmt, "%a", "Sat");
            fmt = replaceAll(fmt, "%b", "Mar");
            return fmt;
        }
        return dt;
    }

    private String cmdCal(String[] args) {
        return "   March 2025\nSu Mo Tu We Th Fr Sa\n" +
               "                   1\n" +
               " 2  3  4  5  6  7  8\n" +
               " 9 10 11 12 13 14 15\n" +
               "16 17 18 19 20 21 22\n" +
               "23 24 25 26 27 28 29\n" +
               "30 31";
    }

    private String cmdUptime() {
        return " 10:00:00 up 1:01,  1 user,  load average: 0.15, 0.10, 0.08";
    }

    private String cmdFree(String[] args) {
        boolean human = false;
        for (int i = 0; i < args.length; i++) if (args[i].indexOf('h') >= 0) human = true;
        if (human) {
            return "              total        used        free      shared  buff/cache   available\n" +
                   "Mem:           7.7G        2.1G        1.2G       234M        4.4G        5.1G\n" +
                   "Swap:          2.0G          0B        2.0G";
        }
        return "              total        used        free      shared  buff/cache   available\n" +
               "Mem:        8051524     2195672     1234567      239876     4621285     5356789\n" +
               "Swap:       2097148           0     2097148";
    }

    private String cmdDf(String[] args) {
        boolean human = false;
        for (int i = 0; i < args.length; i++) if (args[i].indexOf('h') >= 0) human = true;
        if (human) {
            return "Filesystem      Size  Used Avail Use% Mounted on\n" +
                   "/dev/sda1        50G   12G   36G  25% /\n" +
                   "tmpfs           3.9G  2.4M  3.9G   1% /dev/shm\n" +
                   "/dev/sda2       200G   45G  145G  24% /home\n" +
                   "tmpfs           785M  100K  785M   1% /run";
        }
        return "Filesystem     1K-blocks    Used Available Use% Mounted on\n" +
               "/dev/sda1       52428800 12345678  37583122  25% /\n" +
               "tmpfs            4025760     2456   4023304   1% /dev/shm\n" +
               "/dev/sda2      209715200 47185920 152529280  24% /home\n" +
               "tmpfs             803152      100    803052   1% /run";
    }

    private String cmdDu(String[] args) {
        boolean human = false, summary = false;
        String path = fs.getCurrentPath();
        for (int i = 0; i < args.length; i++) {
            if (args[i].indexOf('h') >= 0) human = true;
            if (args[i].indexOf('s') >= 0) summary = true;
            if (!args[i].startsWith("-")) path = fs.resolvePath(args[i]);
        }
        if (!fs.exists(path)) return "du: cannot access '" + path + "': No such file or directory";
        if (summary) return (human ? "4.0K" : "4096") + "\t" + path;
        StringBuffer sb = new StringBuffer();
        sb.append(human ? "4.0K" : "4096").append("\t").append(path).append("\n");
        String[] children = fs.listChildren(path);
        for (int i = 0; i < children.length; i++) {
            sb.append(human ? "4.0K" : "4096").append("\t").append(children[i]).append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdLsblk() {
        return "NAME   MAJ:MIN RM  SIZE RO TYPE MOUNTPOINT\n" +
               "sda      8:0    0   60G  0 disk\n" +
               "\u251C\u2500sda1   8:1    0   50G  0 part /\n" +
               "\u251C\u2500sda2   8:2    0    8G  0 part /home\n" +
               "\u2514\u2500sda3   8:3    0    2G  0 part [SWAP]\n" +
               "sr0     11:0    1 1024M  0 rom";
    }

    private String cmdBlkid() {
        return "/dev/sda1: UUID=\"abc-123-def\" TYPE=\"ext4\" PARTUUID=\"aabbcc\"\n" +
               "/dev/sda2: UUID=\"def-456-ghi\" TYPE=\"ext4\" PARTUUID=\"ddeeff\"\n" +
               "/dev/sda3: UUID=\"ghi-789-jkl\" TYPE=\"swap\" PARTUUID=\"112233\"";
    }

    private String cmdFdisk(String[] args) {
        return "Disk /dev/sda: 60 GiB, 64424509440 bytes, 125829120 sectors\nDisk model: VBOX HARDDISK\n" +
               "Units: sectors of 1 * 512 = 512 bytes\n" +
               "Sector size (logical/physical): 512 bytes / 512 bytes\n\n" +
               "Device     Boot   Start       End   Sectors  Size Id Type\n" +
               "/dev/sda1  *       2048  104859647 104857600   50G 83 Linux\n" +
               "/dev/sda2     104859648  121636863  16777216    8G 83 Linux\n" +
               "/dev/sda3     121636864  125827071   4190208    2G 82 Linux swap";
    }

    private String cmdMount(String[] args) {
        if (args.length == 0) {
            return "sysfs on /sys type sysfs (rw,nosuid,nodev,noexec,relatime)\n" +
                   "proc on /proc type proc (rw,nosuid,nodev,noexec,relatime)\n" +
                   "/dev/sda1 on / type ext4 (rw,relatime)\n" +
                   "/dev/sda2 on /home type ext4 (rw,relatime)\n" +
                   "tmpfs on /tmp type tmpfs (rw,nosuid,nodev)\n" +
                   "tmpfs on /dev/shm type tmpfs (rw,nosuid,nodev)";
        }
        return "mount: " + join(args, " ") + " mounted successfully";
    }

    private String cmdPs(String[] args) {
        boolean aux = false, wide = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].indexOf('a') >= 0 || args[i].indexOf('x') >= 0) aux = true;
        }
        StringBuffer sb = new StringBuffer();
        sb.append("USER         PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND\n");
        for (int i = 0; i < processes.size(); i++) {
            String[] p = (String[]) processes.elementAt(i);
            sb.append(padRight(p[1], 12)).append(" ").append(padLeft(p[0], 5)).append("  0.0  0.1  ");
            sb.append(" 1234  5678 pts/0    ").append(padRight(p[2], 6)).append(" 10:00   ").append(p[3]).append(" ").append(p[4]).append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdKill(String[] args) {
        if (args.length == 0) return "kill: usage: kill [-s sigspec | -n signum | -sigspec] pid | jobspec ... or kill -l [sigspec]";
        int sig = 15; // SIGTERM
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                String s = args[i].substring(1);
                if (s.equals("9")) sig = 9;
                else if (s.equals("l") || s.equals("L")) {
                    return " 1) SIGHUP\t 2) SIGINT\t 3) SIGQUIT\t 4) SIGILL\t 5) SIGTRAP\n" +
                           " 6) SIGABRT\t 7) SIGBUS\t 8) SIGFPE\t 9) SIGKILL\t10) SIGUSR1\n" +
                           "11) SIGSEGV\t12) SIGUSR2\t13) SIGPIPE\t14) SIGALRM\t15) SIGTERM";
                }
            } else {
                // Remove process
                String pid = args[i];
                for (int j = 0; j < processes.size(); j++) {
                    String[] p = (String[]) processes.elementAt(j);
                    if (p[0].equals(pid)) { processes.removeElementAt(j); return ""; }
                }
                return "bash: kill: (" + pid + ") - No such process";
            }
        }
        return "";
    }

    private String cmdKillall(String[] args) {
        if (args.length == 0) return "killall: no process name specified";
        return ""; // silent
    }

    private String cmdTop() {
        return "top - 10:00:00 up  1:01,  1 user,  load average: 0.15, 0.10, 0.08\n" +
               "Tasks: " + processes.size() + " total,   1 running,  " + (processes.size()-1) + " sleeping,   0 stopped,   0 zombie\n" +
               "%Cpu(s):  2.0 us,  1.0 sy,  0.0 ni, 96.0 id,  0.5 wa,  0.0 hi,  0.5 si\n" +
               "MiB Mem :   7863.8 total,   1205.6 free,   2143.2 used,   4515.0 buff/cache\n" +
               "MiB Swap:   2048.0 total,   2048.0 free,      0.0 used.   5231.4 avail Mem\n\n" +
               "  PID USER      PR  NI    VIRT    RES    SHR S  %CPU  %MEM     TIME+ COMMAND\n" +
               "    1 root      20   0  167672   9712   7792 S   0.0   0.1   0:01.23 systemd\n" +
               "  123 root      20   0   13876   3200   2584 S   0.0   0.0   0:00.15 sshd\n" +
               "  901 " + fs.getUsername() + "      20   0   23552   5176   3456 S   0.0   0.1   0:00.45 bash\n" +
               " 1001 " + fs.getUsername() + "      20   0   22876   4012   3200 R   0.0   0.0   0:00.01 top";
    }

    private String cmdLsof() {
        String user = fs.getUsername();
        return "COMMAND  PID      USER   FD   TYPE DEVICE SIZE/OFF NODE NAME\n" +
               "systemd    1      root  cwd    DIR    8,1     4096    2 /\n" +
               "sshd     123      root    4u  IPv4  12345      0t0  TCP *:ssh (LISTEN)\n" +
               "bash     901      " + user + "  cwd    DIR    8,1     4096 1234 " + fs.getCurrentPath() + "\n" +
               "bash     901      " + user + "    0u   CHR  136,0      0t0    3 /dev/pts/0\n" +
               "apache2  999      root    4u  IPv6  23456      0t0  TCP *:http (LISTEN)";
    }

    private String cmdEnv(String[] args) {
        StringBuffer sb = new StringBuffer();
        Enumeration keys = env.keys();
        Vector pairs = new Vector();
        while (keys.hasMoreElements()) {
            String k = (String) keys.nextElement();
            pairs.addElement(k + "=" + env.get(k));
        }
        String[] sorted = new String[pairs.size()];
        pairs.copyInto(sorted);
        sorted = sortStrings(sorted, false);
        for (int i = 0; i < sorted.length; i++) sb.append(sorted[i]).append("\n");
        return sb.toString().trim();
    }

    private String cmdPrintenv(String[] args) {
        if (args.length == 0) return cmdEnv(args);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            String val = (String) env.get(args[i]);
            if (val != null) sb.append(val).append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdExport(String[] args) {
        if (args.length == 0) {
            StringBuffer sb = new StringBuffer();
            Enumeration keys = env.keys();
            while (keys.hasMoreElements()) {
                String k = (String) keys.nextElement();
                sb.append("declare -x ").append(k).append("=\"").append(env.get(k)).append("\"\n");
            }
            return sb.toString().trim();
        }
        for (int i = 0; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq >= 0) {
                env.put(args[i].substring(0, eq), expandVars(args[i].substring(eq + 1)));
            }
        }
        return "";
    }

    private String cmdUnset(String[] args) {
        for (int i = 0; i < args.length; i++) env.remove(args[i]);
        return "";
    }

    private String cmdAlias(String[] args) {
        if (args.length == 0) {
            StringBuffer sb = new StringBuffer();
            Enumeration keys = aliases.keys();
            while (keys.hasMoreElements()) {
                String k = (String) keys.nextElement();
                sb.append("alias ").append(k).append("='").append(aliases.get(k)).append("'\n");
            }
            return sb.toString().trim();
        }
        for (int i = 0; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq >= 0) {
                String name = args[i].substring(0, eq);
                String val  = args[i].substring(eq + 1);
                if (val.startsWith("'") && val.endsWith("'")) val = val.substring(1, val.length()-1);
                if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length()-1);
                aliases.put(name, val);
            } else {
                // Show specific alias
                if (aliases.containsKey(args[i])) return "alias " + args[i] + "='" + aliases.get(args[i]) + "'";
            }
        }
        return "";
    }

    private String cmdUnalias(String[] args) {
        for (int i = 0; i < args.length; i++) aliases.remove(args[i]);
        return "";
    }

    private String cmdHistory(String[] args) {
        int n = history.size();
        for (int i = 0; i < args.length; i++) {
            try { n = Integer.parseInt(args[i]); } catch(Exception e){}
        }
        StringBuffer sb = new StringBuffer();
        int start = Math.max(0, history.size() - n);
        for (int i = start; i < history.size(); i++) {
            sb.append(padLeft(String.valueOf(i + 1), 5)).append("  ").append(history.elementAt(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdSource(String[] args) {
        if (args.length == 0) return "source: filename argument required";
        String p = fs.resolvePath(args[0]);
        if (!fs.exists(p)) return "bash: source: " + args[0] + ": No such file or directory";
        String content = fs.readFile(p);
        if (content == null) return "";
        StringBuffer sb = new StringBuffer();
        String[] lines = splitLines(content);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() > 0 && !line.startsWith("#")) {
                String out = execute(line);
                if (out.length() > 0) sb.append(out).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // =================== NETWORK COMMANDS ===================

    private String cmdPing(String[] args) {
        if (args.length == 0) return "ping: missing host operand";
        String host = args[args.length - 1];
        int count = 4;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("-c") && i+1 < args.length-1) { try { count = Integer.parseInt(args[++i]); } catch(Exception e){} }
        }
        StringBuffer sb = new StringBuffer();
        sb.append("PING ").append(host).append(" (93.184.216.34) 56(84) bytes of data.\n");
        for (int i = 0; i < Math.min(count, 5); i++) {
            sb.append("64 bytes from ").append(host).append(" (93.184.216.34): icmp_seq=").append(i+1)
              .append(" ttl=55 time=").append(12 + i * 3).append(".").append(i * 7).append(" ms\n");
        }
        sb.append("\n--- ").append(host).append(" ping statistics ---\n");
        sb.append(count).append(" packets transmitted, ").append(count).append(" received, 0% packet loss, time ").append(count * 1003).append("ms\n");
        sb.append("rtt min/avg/max/mdev = 12.0/18.5/24.0/2.5 ms");
        return sb.toString();
    }

    private String cmdIfconfig(String[] args) {
        boolean all = args.length > 0 && (args[0].equals("-a") || args[0].equals("all"));
        return "eth0: flags=4163<UP,BROADCAST,RUNNING,MULTICAST>  mtu 1500\n" +
               "        inet 192.168.1.100  netmask 255.255.255.0  broadcast 192.168.1.255\n" +
               "        inet6 fe80::a00:27ff:fe4e:66a1  prefixlen 64  scopeid 0x20<link>\n" +
               "        ether 08:00:27:4e:66:a1  txqueuelen 1000  (Ethernet)\n" +
               "        RX packets 12345  bytes 9876543 (9.8 MB)\n" +
               "        RX errors 0  dropped 0  overruns 0  frame 0\n" +
               "        TX packets 8765  bytes 1234567 (1.2 MB)\n" +
               "        TX errors 0  dropped 0 overruns 0  carrier 0  collisions 0\n\n" +
               "lo: flags=73<UP,LOOPBACK,RUNNING>  mtu 65536\n" +
               "        inet 127.0.0.1  netmask 255.0.0.0\n" +
               "        inet6 ::1  prefixlen 128  scopeid 0x10<host>\n" +
               "        loop  txqueuelen 1000  (Local Loopback)\n" +
               "        RX packets 1234  bytes 123456 (123.4 KB)\n" +
               "        TX packets 1234  bytes 123456 (123.4 KB)";
    }

    private String cmdIp(String[] args) {
        if (args.length == 0) return "Usage: ip [ OPTIONS ] OBJECT { COMMAND | help }";
        if (args[0].equals("addr") || args[0].equals("a")) return cmdIfconfig(new String[0]);
        if (args[0].equals("link") || args[0].equals("l")) {
            return "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN\n    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00\n" +
                   "2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc fq_codel state UP\n    link/ether 08:00:27:4e:66:a1 brd ff:ff:ff:ff:ff:ff";
        }
        if (args[0].equals("route") || args[0].equals("r")) return cmdRoute();
        if (args[0].equals("neigh") || args[0].equals("n")) return cmdArp();
        return "ip: unknown command: " + args[0];
    }

    private String cmdNetstat(String[] args) {
        return "Active Internet connections (servers and established)\n" +
               "Proto Recv-Q Send-Q Local Address           Foreign Address         State      PID/Program\n" +
               "tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN      123/sshd\n" +
               "tcp        0      0 0.0.0.0:80              0.0.0.0:*               LISTEN      999/apache2\n" +
               "tcp        0      0 192.168.1.100:22        192.168.1.5:54321       ESTABLISHED 901/bash\n" +
               "tcp6       0      0 :::22                   :::*                    LISTEN      123/sshd\n" +
               "udp        0      0 0.0.0.0:68              0.0.0.0:*                           456/dhclient";
    }

    private String cmdSs(String[] args) {
        return "Netid  State   Recv-Q  Send-Q   Local Address:Port     Peer Address:Port   Process\n" +
               "tcp    LISTEN  0       128            0.0.0.0:22            0.0.0.0:*       users:((\"sshd\",pid=123,fd=3))\n" +
               "tcp    LISTEN  0       511            0.0.0.0:80            0.0.0.0:*       users:((\"apache2\",pid=999,fd=4))\n" +
               "tcp    ESTAB   0       0        192.168.1.100:22      192.168.1.5:54321     users:((\"sshd\",pid=901,fd=3))";
    }

    private String cmdArp() {
        return "Address                  HWtype  HWaddress           Flags Mask            Iface\n" +
               "192.168.1.1              ether   aa:bb:cc:dd:ee:01   C                     eth0\n" +
               "192.168.1.5              ether   aa:bb:cc:dd:ee:02   C                     eth0\n" +
               "192.168.1.254            ether   aa:bb:cc:dd:ee:ff   C                     eth0";
    }

    private String cmdRoute() {
        return "Kernel IP routing table\n" +
               "Destination     Gateway         Genmask         Flags Metric Ref    Use Iface\n" +
               "0.0.0.0         192.168.1.1     0.0.0.0         UG    100    0        0 eth0\n" +
               "192.168.1.0     0.0.0.0         255.255.255.0   U     100    0        0 eth0\n" +
               "192.168.1.1     0.0.0.0         255.255.255.255 UH    100    0        0 eth0";
    }

    private String cmdTraceroute(String[] args) {
        if (args.length == 0) return "traceroute: missing host operand";
        String host = args[args.length-1];
        StringBuffer sb = new StringBuffer();
        sb.append("traceroute to ").append(host).append(" (93.184.216.34), 30 hops max, 60 byte packets\n");
        String[] hops = {"192.168.1.1","10.0.0.1","72.14.201.209","108.170.246.66","93.184.216.34"};
        for (int i = 0; i < hops.length; i++) {
            sb.append(" ").append(i+1).append("  ").append(hops[i]).append("  ").append((i+1)*8 + 2).append(".").append(i*3).append(" ms  ").append((i+1)*8 + 3).append(".0 ms  ").append((i+1)*8 + 4).append(".1 ms\n");
        }
        return sb.toString().trim();
    }

    private String cmdDig(String[] args) {
        if (args.length == 0) return "dig: missing host operand";
        String host = args[args.length-1];
        return "; <<>> DiG 9.18.1-1ubuntu1.3-Ubuntu <<>> " + host + "\n" +
               ";; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 12345\n" +
               ";; flags: qr rd ra; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 1\n\n" +
               ";; QUESTION SECTION:\n;" + host + ".\t\t\tIN\tA\n\n" +
               ";; ANSWER SECTION:\n" + host + ".\t\t300\tIN\tA\t93.184.216.34\n\n" +
               ";; Query time: 23 msec\n;; SERVER: 8.8.8.8#53(8.8.8.8)\n;; WHEN: Sat Mar 22 10:00:00 UTC 2025\n;; MSG SIZE  rcvd: 55";
    }

    private String cmdNslookup(String[] args) {
        if (args.length == 0) return "nslookup: missing host operand";
        String host = args[0];
        return "Server:\t\t8.8.8.8\nAddress:\t8.8.8.8#53\n\nNon-authoritative answer:\nName:\t" + host + "\nAddress: 93.184.216.34";
    }

    private String cmdHostCmd(String[] args) {
        if (args.length == 0) return "host: missing host operand";
        return args[0] + " has address 93.184.216.34\n" + args[0] + " mail is handled by 0 .";
    }

    private String cmdCurl(String[] args) {
        if (args.length == 0) return "curl: try 'curl --help' for more information";
        String url = args[args.length-1];
        boolean silent = false, output = false, head = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-s") || args[i].equals("--silent")) silent = true;
            if (args[i].equals("-I") || args[i].equals("--head")) head = true;
            if (args[i].equals("-o") || args[i].equals("--output")) output = true;
        }
        if (head) return "HTTP/1.1 200 OK\nContent-Type: text/html; charset=UTF-8\nContent-Length: 1256\nServer: Apache/2.4.57 (Ubuntu)\nDate: Sat, 22 Mar 2025 10:00:00 GMT";
        if (!silent) return "  % Total    % Received % Xferd  Average Speed   Time\n" +
                                 "100  1256  100  1256    0     0   5432      0 --:--:-- --:--:-- --:--:--  5432\n" +
                                 "<!DOCTYPE html><html><head><title>Response from " + url + "</title></head>\n<body><p>Simulated HTTP response.</p></body></html>";
        return "<!DOCTYPE html><html><head><title>Response</title></head><body><p>OK</p></body></html>";
    }

    private String cmdWget(String[] args) {
        if (args.length == 0) return "wget: missing URL";
        String url = args[args.length-1];
        String fname = url.indexOf('/') >= 0 ? url.substring(url.lastIndexOf('/') + 1) : "index.html";
        if (fname.length() == 0) fname = "index.html";
        fs.writeFile(fs.resolvePath(fname), "<!-- Downloaded from " + url + " -->\n<html><body>OK</body></html>");
        return "--2025-03-22 10:00:00-- " + url + "\nResolving " + url + "... 93.184.216.34\nConnecting... connected.\nHTTP request sent, awaiting response... 200 OK\nLength: 1256 (1.2K) [text/html]\nSaving to: '" + fname + "'\n\n" + fname + "     100%[=================>]   1.21K  --.-KB/s    in 0.001s\n\n2025-03-22 10:00:00 (1.21 MB/s) - '" + fname + "' saved [1256/1256]";
    }

    private String cmdNmap(String[] args) {
        if (args.length == 0) return "Nmap 7.94 ( https://nmap.org )\nUsage: nmap [Scan Type(s)] [Options] {target}";
        String host = args[args.length-1];
        return "Starting Nmap 7.94 ( https://nmap.org ) at 2025-03-22 10:00 UTC\n" +
               "Nmap scan report for " + host + " (192.168.1.100)\n" +
               "Host is up (0.00025s latency).\n" +
               "Not shown: 996 closed tcp ports (reset)\n" +
               "PORT     STATE SERVICE     VERSION\n" +
               "22/tcp   open  ssh         OpenSSH 8.9p1 Ubuntu 3ubuntu0.4\n" +
               "80/tcp   open  http        Apache httpd 2.4.57 ((Ubuntu))\n" +
               "443/tcp  open  ssl/https   Apache httpd 2.4.57\n" +
               "3306/tcp open  mysql       MySQL 8.0.35-0ubuntu0.22.04.1\n\n" +
               "Service detection performed. Please report any incorrect results at https://nmap.org/submit/ .\n" +
               "Nmap done: 1 IP address (1 host up) scanned in 2.47 seconds";
    }

    private String cmdNc(String[] args) {
        if (args.length == 0) return "nc: usage: nc [-46CDdFhklNnrStUuvZz] [-I length] [-i interval] [-M ttl] host port";
        return "nc: connection to " + join(args, " ") + " (simulated - no real network in J2ME mode)";
    }

    private String cmdSsh(String[] args) {
        if (args.length == 0) return "usage: ssh [-46AaCfGgKkMNnqsTtVvXxYy] destination [command]";
        return "ssh: connect to host " + args[args.length-1] + " port 22: Connection refused (simulated)";
    }

    private String cmdScp(String[] args) {
        return "scp: " + join(args, " ") + " (simulated - no real network)";
    }

    private String cmdIptables(String[] args) {
        if (args.length > 0 && args[0].equals("-L")) {
            return "Chain INPUT (policy ACCEPT)\ntarget     prot opt source               destination\nACCEPT     all  --  anywhere             anywhere\n\n" +
                   "Chain FORWARD (policy ACCEPT)\ntarget     prot opt source               destination\n\n" +
                   "Chain OUTPUT (policy ACCEPT)\ntarget     prot opt source               destination\nACCEPT     all  --  anywhere             anywhere";
        }
        return "(iptables: rule applied)";
    }

    private String cmdUfw(String[] args) {
        if (args.length == 0) return "Status: inactive";
        if (args[0].equals("status")) return "Status: active\n\nTo                         Action      From\n--                         ------      ----\n22/tcp                     ALLOW       Anywhere\n80/tcp                     ALLOW       Anywhere";
        if (args[0].equals("enable")) return "Firewall is active and enabled on system startup";
        if (args[0].equals("disable")) return "Firewall stopped and disabled on system startup";
        if (args[0].equals("allow")) return "Rule added";
        if (args[0].equals("deny"))  return "Rule added";
        return "ufw: " + join(args, " ");
    }

    private String cmdTcpdump(String[] args) {
        return "tcpdump: verbose output suppressed, use -v[v]... for full protocol decode\nlistening on eth0, link-type EN10MB (Ethernet), snapshot length 262144 bytes\n10:00:01.123456 IP 192.168.1.100.54321 > 8.8.8.8.53: UDP, length 28\n10:00:01.156789 IP 8.8.8.8.53 > 192.168.1.100.54321: UDP, length 44\n^C\n2 packets captured\n2 packets received by filter\n0 packets dropped by kernel";
    }

    // =================== ARCHIVE COMMANDS ===================

    private String cmdTar(String[] args) {
        if (args.length == 0) return "tar: You must specify one of the '-Acdtrux', '--delete' or '--test-label' options";
        boolean create = false, extract = false, list = false, verbose = false, gzip = false;
        String file = null;
        Vector files = new Vector();
        for (int i = 0; i < args.length; i++) {
            if (args[i].indexOf('c') >= 0) create = true;
            if (args[i].indexOf('x') >= 0) extract = true;
            if (args[i].indexOf('t') >= 0) list = true;
            if (args[i].indexOf('v') >= 0) verbose = true;
            if (args[i].indexOf('z') >= 0 || args[i].indexOf('j') >= 0) gzip = true;
            if ((args[i].equals("-f") || args[i].indexOf('f') >= 0) && i+1 < args.length && !args[i+1].startsWith("-")) {
                if (!args[i].startsWith("-")) { i++; }
                else { file = args[++i]; }
            }
            if (file == null && !args[i].startsWith("-")) file = args[i];
        }
        if (file == null) file = "archive.tar";
        if (create) return "tar: " + file + " created" + (gzip ? " (compressed)" : "");
        if (extract) return "tar: extracting " + file + (verbose ? "\nfile1\nfile2\nfile3" : "");
        if (list) return file + ":\nfile1\nfile2\nfile3\ndir1/\ndir1/file4";
        return "tar: " + join(args, " ");
    }

    private String cmdGzip(String[] args) {
        if (args.length == 0) return "gzip: invalid usage";
        return args[0] + " compressed to " + args[0] + ".gz";
    }

    private String cmdGunzip(String[] args) {
        if (args.length == 0) return "gunzip: invalid usage";
        String f = args[0];
        if (f.endsWith(".gz")) f = f.substring(0, f.length()-3);
        return f + ".gz decompressed to " + f;
    }

    private String cmdZip(String[] args) {
        if (args.length < 2) return "zip: missing operand";
        return "  adding: " + args[1] + " (deflated 45%)\narchive " + args[0] + " created";
    }

    private String cmdUnzip(String[] args) {
        if (args.length == 0) return "unzip: missing archive operand";
        return "Archive:  " + args[0] + "\n  inflating: file1.txt\n  inflating: file2.txt\nfinished";
    }

    private String cmdBzip2(String[] args) {
        if (args.length == 0) return "bzip2: invalid usage";
        return args[args.length-1] + " compressed";
    }

    // =================== CRYPTO/HASH ===================

    private String cmdMd5sum(String[] args) {
        if (args.length == 0) return "md5sum: missing file";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) continue;
            String hash = fakeHash(args[i], 32);
            sb.append(hash).append("  ").append(args[i]).append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdSha256sum(String[] args) {
        if (args.length == 0) return "sha256sum: missing file";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) continue;
            String hash = fakeHash(args[i], 64);
            sb.append(hash).append("  ").append(args[i]).append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdSha1sum(String[] args) {
        if (args.length == 0) return "sha1sum: missing file";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) continue;
            String hash = fakeHash(args[i], 40);
            sb.append(hash).append("  ").append(args[i]).append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdBase64(String[] args) {
        if (args.length == 0) return "(base64: reading stdin)";
        boolean decode = false;
        String input = "";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-d") || args[i].equals("--decode")) decode = true;
            else {
                String p = fs.resolvePath(args[i]);
                if (fs.exists(p)) input = nullToEmpty(fs.readFile(p));
                else input = args[i];
            }
        }
        if (decode) return "(decoded: " + input + ")";
        return base64Encode(input);
    }

    private String cmdHexdump(String[] args) {
        if (args.length == 0) return "hexdump: missing file";
        String p = fs.resolvePath(args[args.length-1]);
        String content = fs.exists(p) ? nullToEmpty(fs.readFile(p)) : args[args.length-1];
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < Math.min(content.length(), 64); i += 16) {
            sb.append(padHex(i, 8)).append("  ");
            for (int j = i; j < Math.min(i+16, content.length()); j++) {
                sb.append(padHex(content.charAt(j), 2)).append(" ");
                if (j - i == 7) sb.append(" ");
            }
            sb.append("  |");
            for (int j = i; j < Math.min(i+16, content.length()); j++) {
                char c = content.charAt(j);
                sb.append((c >= 32 && c < 127) ? c : '.');
            }
            sb.append("|\n");
        }
        return sb.toString().trim();
    }

    private String cmdStrings(String[] args) {
        if (args.length == 0) return "strings: missing file";
        String p = fs.resolvePath(args[args.length-1]);
        if (!fs.exists(p)) return "strings: " + args[args.length-1] + ": No such file";
        return nullToEmpty(fs.readFile(p));
    }

    // =================== PACKAGE MANAGERS ===================

    private String cmdApt(String[] args) {
        if (args.length == 0) return "apt: command required\nUsage: apt {install|remove|update|upgrade|search|show|list} [options]";
        String sub = args[0];
        if (sub.equals("update")) return "Hit:1 http://archive.ubuntu.com/ubuntu jammy InRelease\nGet:2 http://security.ubuntu.com/ubuntu jammy-security InRelease [110 kB]\nFetched 3,241 kB in 2s (1,620 kB/s)\nReading package lists... Done";
        if (sub.equals("upgrade")) return "Reading package lists... Done\nBuilding dependency tree... Done\n0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.";
        if (sub.equals("install") && args.length > 1) {
            String pkg = args[1];
            return "Reading package lists... Done\nBuilding dependency tree... Done\nThe following NEW packages will be installed:\n  " + pkg + "\n0 upgraded, 1 newly installed, 0 to remove.\nGet:1 http://archive.ubuntu.com/ubuntu " + pkg + " [100 kB]\nFetched 100 kB in 1s\nSelecting " + pkg + "\nSetting up " + pkg + " ... done";
        }
        if (sub.equals("remove") && args.length > 1) return "Removing " + args[1] + " ... done";
        if (sub.equals("search") && args.length > 1) return args[1] + " - " + args[1] + " package\nlib" + args[1] + "-dev - development files for " + args[1];
        if (sub.equals("list")) return "Listing... Done\nbash/jammy,now 5.1-6ubuntu1 amd64 [installed]\nopenssh-server/jammy-security 1:8.9p1-3ubuntu0.4 amd64";
        if (sub.equals("show") && args.length > 1) return "Package: " + args[1] + "\nVersion: 1.0.0\nMaintainer: Ubuntu\nDescription: " + args[1] + " package";
        return "apt: unknown command: " + sub;
    }

    private String cmdDpkg(String[] args) {
        if (args.length == 0) return "dpkg: error: need an action option";
        if (args[0].equals("-l") || args[0].equals("--list")) {
            return "Desired=Unknown/Install/Remove/Purge/Hold\n||/ Name           Version  Architecture Description\n+++-==============-========-============-============\nii  bash           5.1-6    amd64        GNU Bourne Again shell\nii  openssh-server 8.9p1-3  amd64        secure shell (SSH) server";
        }
        return "dpkg: " + join(args, " ");
    }

    private String cmdSnap(String[] args) {
        if (args.length == 0) return "The snap command lets you install, configure, refresh and remove snaps.";
        if (args[0].equals("list")) return "Name        Version  Rev    Tracking       Publisher   Notes\ncore20      20231123 2105   latest/stable  canonical   base\nsnapd       2.61.3   20671  latest/stable  canonical   snapd";
        if (args[0].equals("install")) return "snap \"" + (args.length>1?args[1]:"") + "\" installed";
        return "snap: " + join(args, " ");
    }

    private String cmdPip(String[] args) {
        if (args.length == 0) return "pip: Please provide a command.";
        if (args[0].equals("install") && args.length > 1) return "Collecting " + args[1] + "\nDownloading " + args[1] + "-1.0.0-py3-none-any.whl\nInstalling collected packages: " + args[1] + "\nSuccessfully installed " + args[1] + "-1.0.0";
        if (args[0].equals("list")) return "Package      Version\n------------ -------\npip          23.3.1\nsetuptools   68.0.0\nwheel        0.41.2\nrequests     2.31.0\nnumpy        1.24.3";
        if (args[0].equals("show") && args.length > 1) return "Name: " + args[1] + "\nVersion: 1.0.0\nLocation: /usr/lib/python3/dist-packages";
        return "pip: " + join(args, " ");
    }

    // =================== DEV TOOLS ===================

    private String cmdGit(String[] args) {
        if (args.length == 0) return "usage: git [-v | --version] [-h | --help] [-C <path>] [-c <name>=<value>]\n           [--exec-path[=<path>]] [--html-path] [--man-path] [--info-path]\n           [-p | --paginate | -P | --no-pager] [--no-replace-objects] [--bare]\n           [--git-dir=<path>] [--work-tree=<path>] [--namespace=<name>]\n           [--super-prefix=<path>] [--config-env=<name>=<envvar>]\n           <command> [<args>]";
        String sub = args[0];
        if (sub.equals("init")) return "Initialized empty Git repository in " + fs.getCurrentPath() + "/.git/";
        if (sub.equals("status")) return "On branch main\nYour branch is up to date with 'origin/main'.\n\nnothing to commit, working tree clean";
        if (sub.equals("log")) return "commit abc123def456 (HEAD -> main, origin/main)\nAuthor: " + fs.getUsername() + " <user@example.com>\nDate:   Sat Mar 22 10:00:00 2025 +0000\n\n    Initial commit";
        if (sub.equals("clone")) return "Cloning into '" + (args.length>1 ? fs.nameOf(args[1]) : "repo") + "'...\nremote: Counting objects: 42\nReceiving objects: 100% (42/42), 12.34 KiB\nResolving deltas: 100% (5/5), done.";
        if (sub.equals("pull")) return "Already up to date.";
        if (sub.equals("push")) return "Everything up-to-date";
        if (sub.equals("add")) return "";
        if (sub.equals("commit")) return "[main abc1234] " + (args.length>2 ? args[2] : "commit") + "\n 1 file changed, 1 insertion(+)";
        if (sub.equals("branch")) return "* main\n  develop\n  feature/new-feature";
        if (sub.equals("checkout")) return "Switched to branch '" + (args.length>1?args[args.length-1]:"main") + "'";
        if (sub.equals("diff")) return "(no differences)";
        if (sub.equals("stash")) return "Saved working directory and index state WIP on main: abc1234 message";
        if (sub.equals("remote")) return "origin";
        if (sub.equals("fetch")) return "From https://github.com/user/repo\n   abc1234..def5678  main -> origin/main";
        if (sub.equals("merge")) return "Updating abc1234..def5678\nFast-forward\n 1 file changed, 1 insertion(+)";
        if (sub.equals("rebase")) return "Successfully rebased and updated refs/heads/main.";
        if (sub.equals("tag")) return "v1.0\nv1.1\nv2.0";
        if (sub.equals("config")) return args.length > 1 ? (args.length>2 ? "" : "value") : "";
        if (sub.equals("show")) return "commit abc123def\nAuthor: user\nDate: Sat Mar 22 10:00:00 2025\n\n    commit message\n\ndiff --git a/file.txt b/file.txt\n+new line";
        return "git: '" + sub + "' is not a git command.";
    }

    private String cmdPython(String[] args) {
        if (args.length == 0) return "Python 3.11.0 (default, Oct 24 2022)\n[GCC 11.2.0] on linux\nType \"help\", \"copyright\", \"credits\" or \"license\" for more information.\n>>> (interactive mode not supported)";
        String p = fs.resolvePath(args[0]);
        if (fs.exists(p)) return "(executing " + args[0] + " ... output not captured in this terminal)";
        if (args[0].equals("-c") && args.length > 1) {
            String code = args[1];
            if (code.indexOf("print") >= 0) {
                int s = code.indexOf("print(");
                if (s >= 0) {
                    int e = code.indexOf(")", s);
                    String val = code.substring(s+6, e).trim();
                    val = replaceAll(replaceAll(val, "\"", ""), "'", "");
                    return val;
                }
            }
            return "(executing inline python)";
        }
        if (args[0].equals("--version") || args[0].equals("-V")) return "Python 3.11.0";
        return "python3: can't open file '" + args[0] + "': No such file or directory";
    }

    private String cmdNode(String[] args) {
        if (args.length == 0) return "Welcome to Node.js v20.11.0.\nType \".help\" for more information.\n> (interactive mode not supported)";
        if (args[0].equals("--version") || args[0].equals("-v")) return "v20.11.0";
        return "node: " + join(args, " ");
    }

    private String cmdNpm(String[] args) {
        if (args.length == 0) return "npm <command>\n\nUsage:\nnpm install\nnpm start\nnpm test";
        if (args[0].equals("install")) return (args.length > 1 ? "\nadded 1 package" : "\nadded 42 packages") + " in 2.345s";
        if (args[0].equals("start")) return "> project@1.0.0 start\n> node index.js\nServer started on port 3000";
        if (args[0].equals("-v") || args[0].equals("--version")) return "10.2.4";
        if (args[0].equals("list")) return "project@1.0.0\n\u251C\u2500\u2500 express@4.18.2\n\u2514\u2500\u2500 lodash@4.17.21";
        return "npm: " + join(args, " ");
    }

    private String cmdJava(String[] args) {
        if (args.length == 0) return "Usage: java [-options] class [args...]\n   or  java [-options] -jar jarfile [args...]";
        if (args[0].equals("-version") || args[0].equals("--version")) return "java version \"17.0.8\" 2023-07-18 LTS\nJava(TM) SE Runtime Environment (build 17.0.8+9-LTS-211)\nJava HotSpot(TM) 64-Bit Server VM (build 17.0.8+9-LTS-211, mixed mode, sharing)";
        return "(executing " + join(args, " ") + ")";
    }

    private String cmdJavac(String[] args) {
        if (args.length == 0) return "Usage: javac <options> <source files>";
        String f = args[args.length-1];
        if (!f.endsWith(".java")) return "javac: file not found: " + f;
        return f + " compiled successfully";
    }

    private String cmdGcc(String[] args) {
        if (args.length == 0) return "gcc: no input files";
        String out = "a.out";
        for (int i = 0; i < args.length; i++) if (args[i].equals("-o") && i+1 < args.length) { out = args[++i]; }
        return "(compiled to " + out + ")";
    }

    private String cmdMake(String[] args) {
        if (!fs.exists(fs.resolvePath("Makefile")) && !fs.exists(fs.resolvePath("makefile"))) {
            return "make: *** No rule to make target 'all'.  Stop.";
        }
        return "gcc -o program main.c\nmake: 'all' is up to date.";
    }

    // =================== SYSTEM MANAGEMENT ===================

    private String cmdSystemctl(String[] args) {
        if (args.length == 0) return "systemctl: no command specified";
        String sub = args[0];
        String svc  = args.length > 1 ? args[1] : "";
        if (sub.equals("start"))   return "(systemctl: starting " + svc + ")";
        if (sub.equals("stop"))    return "(systemctl: stopping " + svc + ")";
        if (sub.equals("restart")) return "(systemctl: restarting " + svc + ")";
        if (sub.equals("enable"))  return "Created symlink /etc/systemd/system/multi-user.target.wants/" + svc + ".service";
        if (sub.equals("disable")) return "Removed /etc/systemd/system/multi-user.target.wants/" + svc + ".service";
        if (sub.equals("status"))  return "\u25CF " + svc + ".service - " + svc + "\n   Loaded: loaded (/lib/systemd/system/" + svc + ".service; enabled)\n   Active: active (running) since Sat 2025-03-22 10:00:00 UTC; 1h 1min ago\n Main PID: 123 (" + svc + ")\n    Tasks: 1 (limit: 2310)\n   Memory: 5.2M\n   CGroup: /system.slice/" + svc + ".service\n           \u2514\u2500123 /usr/sbin/" + svc;
        if (sub.equals("list-units")) return "UNIT                        LOAD   ACTIVE SUB     DESCRIPTION\nsshd.service                loaded active running OpenSSH Server Daemon\napache2.service             loaded active running The Apache HTTP Server\ncron.service                loaded active running Regular background jobs";
        return "systemctl: " + join(args, " ");
    }

    private String cmdService(String[] args) {
        if (args.length < 2) return "Usage: service <service> <command>";
        return "[ ok ] " + args[0] + ": " + args[1];
    }

    private String cmdJournalctl(String[] args) {
        return "-- Journal begins at Sat 2025-03-22 09:58:01 UTC --\n" +
               "Mar 22 09:58:01 " + fs.getHostname() + " kernel: Linux version 5.15.0-88-generic\n" +
               "Mar 22 09:58:01 " + fs.getHostname() + " kernel: BIOS-provided physical RAM map\n" +
               "Mar 22 10:00:01 " + fs.getHostname() + " systemd[1]: Started OpenSSH Server Daemon.\n" +
               "Mar 22 10:00:01 " + fs.getHostname() + " sshd[123]: Server listening on 0.0.0.0 port 22.";
    }

    private String cmdDmesg() {
        return "[    0.000000] Initializing cgroup subsys cpuset\n" +
               "[    0.000000] Linux version 5.15.0-88-generic\n" +
               "[    0.000000] Command line: BOOT_IMAGE=/boot/vmlinuz-5.15.0-88-generic\n" +
               "[    1.234567] ACPI: IRQ0 used by override.\n" +
               "[    2.345678] NET: Registered PF_INET protocol family\n" +
               "[    3.456789] eth0: renamed from veth01b2c3d\n" +
               "[    4.567890] EXT4-fs (sda1): mounted filesystem with ordered data mode";
    }

    private String cmdCrontab(String[] args) {
        boolean edit = false, list = false, remove = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-e")) edit = true;
            if (args[i].equals("-l")) list = true;
            if (args[i].equals("-r")) remove = true;
        }
        if (edit) return "(crontab -e: cannot open text editor in this terminal)";
        if (remove) return "(crontab: removed)";
        String cronFile = fs.getHomeDir() + "/.crontab";
        if (fs.exists(cronFile)) return fs.readFile(cronFile);
        return "# no crontab for " + fs.getUsername();
    }

    private String cmdShutdown(String[] args) {
        return "Broadcast message from root@" + fs.getHostname() + ":\nThe system is going down for " +
               (args.length > 0 && args[0].equals("-r") ? "reboot" : "maintenance") + " NOW!";
    }

    // =================== MISC & FUN ===================

    private String cmdTree(String[] args) {
        String path = args.length > 0 && !args[0].startsWith("-") ? fs.resolvePath(args[0]) : fs.getCurrentPath();
        if (!fs.exists(path)) return "tree: " + path + ": No such file or directory";
        StringBuffer sb = new StringBuffer();
        sb.append(path).append("\n");
        appendTree(sb, path, "");
        return sb.toString().trim();
    }

    private void appendTree(StringBuffer sb, String dir, String prefix) {
        String[] children = fs.listChildren(dir);
        children = sortStrings(children, false);
        for (int i = 0; i < children.length; i++) {
            boolean last = (i == children.length - 1);
            String name = fs.nameOf(children[i]);
            if (name.startsWith(".")) continue;
            sb.append(prefix).append(last ? "\u2514\u2500\u2500 " : "\u251C\u2500\u2500 ").append(name);
            if (fs.isDir(children[i])) sb.append("/");
            sb.append("\n");
            if (fs.isDir(children[i])) {
                appendTree(sb, children[i], prefix + (last ? "    " : "\u2502   "));
            }
        }
    }

    private String cmdNetCmd(String[] args) {
        if (args.length == 0) return "The syntax of this command is: NET [ACCOUNTS|COMPUTER|CONFIG|CONTINUE|FILE|GROUP|HELP|HELPMSG|LOCALGROUP|PAUSE|PRINT|SESSION|SHARE|START|STATISTICS|STOP|TIME|USE|USER|VIEW]";
        String sub = args[0].toLowerCase();
        if (sub.equals("user")) return fs.getUsername() + "\nGuest\nAdministrator\nThe command completed successfully.";
        if (sub.equals("start")) return "(net start: service management not available)";
        if (sub.equals("stop"))  return "(net stop: service management not available)";
        if (sub.equals("view")) return "Server Name    Remark\n\\\\WORKSTATION\n\\\\SERVER\nThe command completed successfully.";
        return "net " + sub + ": executed";
    }

    private String cmdNetsh(String[] args) {
        return "netsh> (use 'ip' or 'ifconfig' on Linux instead)";
    }

    private String cmdBrew(String[] args) {
        if (args.length == 0) return "Example usage:\n  brew search TEXT|/REGEX/\n  brew info [FORMULA|CASK...]\n  brew install FORMULA|CASK...\n  brew update";
        if (args[0].equals("install") && args.length > 1) return "==> Downloading " + args[1] + "\n==> Installing " + args[1] + "\n  /usr/local/Cellar/" + args[1] + "/1.0: 42 files, 1.2MB";
        if (args[0].equals("update")) return "Updated 3 taps (homebrew/core, homebrew/cask).\n==> Updated Formulae: curl git openssl";
        if (args[0].equals("list")) return "curl\ngit\nnode\npython@3.11\nopenssh\nwget";
        if (args[0].equals("--version")) return "Homebrew 4.1.9";
        return "brew: " + join(args, " ");
    }

    private String cmdBanner(String[] args) {
        if (args.length == 0) return "";
        String text = join(args, " ").toUpperCase();
        // Simple ASCII art letters
        StringBuffer sb = new StringBuffer();
        sb.append("\n");
        for (int row = 0; row < 5; row++) {
            for (int ci = 0; ci < text.length(); ci++) {
                char c = text.charAt(ci);
                sb.append(bigChar(c, row)).append("  ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String bigChar(char c, int row) {
        // 5-row ASCII art for A-Z, 0-9
        String[][] alpha = {
            {"  #  "," # # ","#####","#   #","#   #"},// A
            {"#### ","#   #","#### ","#   #","#### "},// B
            {" ### ","#    ","#    ","#    "," ### "},// C
            {"#### ","#   #","#   #","#   #","#### "},// D
            {"#####","#    ","#### ","#    ","#####"},// E
            {"#####","#    ","#### ","#    ","#    "},// F
            {" ####","#    ","# ###","#   #"," ####"},// G
            {"#   #","#   #","#####","#   #","#   #"},// H
            {" ### ","  #  ","  #  ","  #  "," ### "},// I
            {"  ###","    #","    #","#   #"," ### "},// J
            {"#   #","#  # ","###  ","#  # ","#   #"},// K
            {"#    ","#    ","#    ","#    ","#####"},// L
            {"#   #","## ##","# # #","#   #","#   #"},// M
            {"#   #","##  #","# # #","#  ##","#   #"},// N
            {" ### ","#   #","#   #","#   #"," ### "},// O
            {"#### ","#   #","#### ","#    ","#    "},// P
            {" ### ","#   #","# # #","#  # "," ## #"},// Q
            {"#### ","#   #","#### ","#  # ","#   #"},// R
            {" ####","#    "," ### ","    #","#### "},// S
            {"#####","  #  ","  #  ","  #  ","  #  "},// T
            {"#   #","#   #","#   #","#   #"," ### "},// U
            {"#   #","#   #"," # # "," # # ","  #  "},// V
            {"#   #","#   #","# # #","## ##","#   #"},// W
            {"#   #"," # # ","  #  "," # # ","#   #"},// X
            {"#   #"," # # ","  #  ","  #  ","  #  "},// Y
            {"#####","   # ","  #  "," #   ","#####"},// Z
        };
        if (c >= 'A' && c <= 'Z') return alpha[c - 'A'][row];
        if (c == ' ') return "     ";
        return "     ";
    }

    private String cmdCowsay(String[] args) {
        String msg = args.length > 0 ? join(args, " ") : "Moo!";
        int len = msg.length() + 2;
        StringBuffer border = new StringBuffer();
        for (int i = 0; i < len; i++) border.append("-");
        return " " + border + "\n< " + msg + " >\n " + border + "\n        \\   ^__^\n         \\  (oo)\\_______\n            (__)\\       )\\/\\\n                ||----w |\n                ||     ||";
    }

    private String cmdFortune() {
        String[] fortunes = {
            "The best way to predict the future is to invent it. - Alan Kay",
            "Talk is cheap. Show me the code. - Linus Torvalds",
            "Any sufficiently advanced technology is indistinguishable from magic. - Arthur C. Clarke",
            "Unix is user-friendly; it's just picky about who its friends are.",
            "There are only 10 types of people: those who understand binary and those who don't.",
            "The computer was born to solve problems that did not exist before. - Bill Gates",
            "Software is eating the world. - Marc Andreessen",
            "rm -rf /: Because sometimes you just need a fresh start.",
            "In the beginning was the command line. - Neal Stephenson",
            "sudo make me a sandwich"
        };
        int idx = (int)(System.currentTimeMillis() % fortunes.length);
        return fortunes[idx];
    }

    private String cmdNeofetch() {
        String user = fs.getUsername();
        String host = fs.getHostname();
        return "       _\n" +
               "      ( )\n" +
               "     /| |\\      " + user + "@" + host + "\n" +
               "    / | | \\     -------------------------\n" +
               "   /  | |  \\    OS: Ubuntu 22.04.3 LTS x86_64\n" +
               "  |   | |   |   Host: VirtualBox\n" +
               "  |   | |   |   Kernel: 5.15.0-88-generic\n" +
               "  |   \\_/   |   Uptime: 1 hour, 1 min\n" +
               "   \\_______/    Shell: bash 5.1.16\n" +
               "                Terminal: xterm-256color\n" +
               "                CPU: Intel i5-8250U (4) @ 1.800GHz\n" +
               "                GPU: VirtualBox Graphics Adapter\n" +
               "                Memory: 2143MiB / 7863MiB";
    }

    private String cmdSeq(String[] args) {
        int start = 1, end = 1, step = 1;
        if (args.length == 1) { try { end = Integer.parseInt(args[0]); } catch(Exception e){} }
        if (args.length == 2) { try { start = Integer.parseInt(args[0]); end = Integer.parseInt(args[1]); } catch(Exception e){} }
        if (args.length == 3) { try { start = Integer.parseInt(args[0]); step = Integer.parseInt(args[1]); end = Integer.parseInt(args[2]); } catch(Exception e){} }
        StringBuffer sb = new StringBuffer();
        for (int i = start; step > 0 ? i <= end : i >= end; i += step) sb.append(i).append("\n");
        return sb.toString().trim();
    }

    private String cmdWatch(String[] args) {
        if (args.length == 0) return "watch: missing command";
        return "(watch: executing '" + join(args, " ") + "' every 2s - not supported in this terminal)";
    }

    private String cmdTime(String[] args) {
        if (args.length == 0) return "";
        long t = System.currentTimeMillis();
        String out = execute(join(args, " "));
        long elapsed = System.currentTimeMillis() - t;
        return out + "\nreal\t0m" + (elapsed/1000) + "." + padLeft(String.valueOf(elapsed%1000), 3).replace(' ', '0') + "s\nuser\t0m0.001s\nsys\t0m0.000s";
    }

    private String cmdTest(String[] args) {
        // Basic test expressions
        if (args.length == 0) { lastExitCode = 1; return ""; }
        // -f file, -d dir, -e exists, -z str, -n str, str = str
        if (args.length >= 2 && args[0].equals("-f")) { lastExitCode = fs.isFile(fs.resolvePath(args[1])) ? 0 : 1; return ""; }
        if (args.length >= 2 && args[0].equals("-d")) { lastExitCode = fs.isDir(fs.resolvePath(args[1])) ? 0 : 1; return ""; }
        if (args.length >= 2 && args[0].equals("-e")) { lastExitCode = fs.exists(fs.resolvePath(args[1])) ? 0 : 1; return ""; }
        if (args.length >= 2 && args[0].equals("-z")) { lastExitCode = args[1].length() == 0 ? 0 : 1; return ""; }
        if (args.length >= 2 && args[0].equals("-n")) { lastExitCode = args[1].length() > 0 ? 0 : 1; return ""; }
        if (args.length >= 3 && args[1].equals("="))  { lastExitCode = args[0].equals(args[2]) ? 0 : 1; return ""; }
        if (args.length >= 3 && args[1].equals("!=")) { lastExitCode = !args[0].equals(args[2]) ? 0 : 1; return ""; }
        lastExitCode = 0;
        return "";
    }

    private String cmdExpr(String[] args) {
        if (args.length < 3) return args.length > 0 ? args[0] : "";
        try {
            long a = Long.parseLong(args[0]);
            String op = args[1];
            long b = Long.parseLong(args[2]);
            if (op.equals("+")) return String.valueOf(a + b);
            if (op.equals("-")) return String.valueOf(a - b);
            if (op.equals("*") || op.equals("\\*")) return String.valueOf(a * b);
            if (op.equals("/")) return String.valueOf(a / b);
            if (op.equals("%")) return String.valueOf(a % b);
            if (op.equals("=")) return a == b ? "1" : "0";
            if (op.equals("!=")) return a != b ? "1" : "0";
            if (op.equals("<")) return a < b ? "1" : "0";
            if (op.equals(">")) return a > b ? "1" : "0";
        } catch(Exception e) {}
        return "0";
    }

    private String cmdBc(String[] args) {
        return "(bc: interactive calculator - type expressions)";
    }

    private String cmdCalc(String[] args) {
        if (args.length == 0) return "(calculator: provide an expression)";
        // Simple eval for basic math
        try {
            String expr = replaceAll(replaceAll(join(args, ""), "x", "*"), "X", "*");
            // Very basic: single operation
            if (expr.indexOf("+") >= 0 || expr.indexOf("-") >= 0 || expr.indexOf("*") >= 0 || expr.indexOf("/") >= 0) {
                return cmdExpr(new String[]{ /* simplified */ "0", "+", "0" });
            }
        } catch(Exception e) {}
        return "(calc: " + join(args, " ") + ")";
    }

    private String cmdFactor(String[] args) {
        if (args.length == 0) return "factor: missing operand";
        try {
            long n = Long.parseLong(args[0]);
            StringBuffer sb = new StringBuffer();
            sb.append(n).append(":");
            for (long d = 2; d * d <= n; d++) {
                while (n % d == 0) { sb.append(" ").append(d); n /= d; }
            }
            if (n > 1) sb.append(" ").append(n);
            return sb.toString();
        } catch(Exception e) { return "factor: invalid argument"; }
    }

    private String cmdShuf(String[] args) {
        if (args.length == 0) return "(shuf: reading from stdin)";
        String p = fs.resolvePath(args[args.length-1]);
        String text = fs.exists(p) ? nullToEmpty(fs.readFile(p)) : args[args.length-1];
        String[] lines = splitLines(text);
        // Fisher-Yates shuffle (deterministic seed for repeatability)
        long seed = System.currentTimeMillis();
        for (int i = lines.length - 1; i > 0; i--) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            int j = (int) Math.abs(seed % (i + 1));
            String tmp = lines[i]; lines[i] = lines[j]; lines[j] = tmp;
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < lines.length; i++) sb.append(lines[i]).append("\n");
        return sb.toString().trim();
    }

    private String cmdPaste(String[] args) {
        if (args.length == 0) return "";
        StringBuffer sb = new StringBuffer();
        Vector fileContents = new Vector();
        for (int i = 0; i < args.length; i++) {
            String p = fs.resolvePath(args[i]);
            fileContents.addElement(fs.exists(p) ? splitLines(nullToEmpty(fs.readFile(p))) : new String[]{});
        }
        int maxLines = 0;
        for (int i = 0; i < fileContents.size(); i++) maxLines = Math.max(maxLines, ((String[])fileContents.elementAt(i)).length);
        for (int row = 0; row < maxLines; row++) {
            for (int col = 0; col < fileContents.size(); col++) {
                String[] lines = (String[]) fileContents.elementAt(col);
                if (col > 0) sb.append("\t");
                if (row < lines.length) sb.append(lines[row]);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String cmdDiff(String[] args) {
        if (args.length < 2) return "diff: missing operand";
        String f1 = fs.resolvePath(args[0]), f2 = fs.resolvePath(args[1]);
        if (!fs.exists(f1)) return "diff: " + args[0] + ": No such file or directory";
        if (!fs.exists(f2)) return "diff: " + args[1] + ": No such file or directory";
        String c1 = nullToEmpty(fs.readFile(f1));
        String c2 = nullToEmpty(fs.readFile(f2));
        if (c1.equals(c2)) return "(files are identical)";
        return "--- " + args[0] + "\n+++ " + args[1] + "\n@@ -1 +1 @@\n-" + c1.substring(0, Math.min(40, c1.length())) + "\n+" + c2.substring(0, Math.min(40, c2.length()));
    }

    private String cmdTelnet(String cmd, String[] args) {
        if (args.length == 0) return cmd + ": Trying connection...";
        return cmd + " " + args[0] + " (port " + (args.length>1?args[1]:"23") + "): Connection refused";
    }

    private String cmdRsync(String[] args) {
        if (args.length < 2) return "rsync: missing source/dest";
        return "sending incremental file list\n./\nfile1.txt\n\nsent 1,234 bytes  received 34 bytes  2,536.00 bytes/sec\ntotal size is 1,200  speedup is 0.94";
    }

    private String cmdLspci() {
        return "00:00.0 Host bridge: Intel Corporation 8th Gen Core Processor Host Bridge/DRAM Registers\n" +
               "00:02.0 VGA compatible controller: VMware SVGA II Adapter\n" +
               "00:03.0 Ethernet controller: Intel Corporation 82540EM Gigabit Ethernet Controller\n" +
               "00:1f.2 SATA controller: Intel Corporation 8 Series/C220 Series Chipset SATA Controller";
    }

    private String cmdLsusb() {
        return "Bus 001 Device 001: ID 1d6b:0002 Linux Foundation 2.0 root hub\n" +
               "Bus 002 Device 001: ID 1d6b:0001 Linux Foundation 1.1 root hub\n" +
               "Bus 002 Device 002: ID 80ee:0021 VirtualBox USB Tablet";
    }

    private String cmdLshw() {
        return "*-computer\n  description: Computer\n  *-core\n    *-cpu\n      product: Intel Core i5-8250U\n      capacity: 1800MHz\n    *-memory\n      size: 8GiB\n    *-disk\n      product: VBOX HARDDISK\n      size: 60GiB";
    }

    private String cmdInxi() {
        return "System:    Host: " + fs.getHostname() + " Kernel: 5.15.0-88-generic x86_64 Console: tty0\n" +
               "Machine:   VirtualBox v: 1.2\n" +
               "CPU:       Quad Core Intel Core i5-8250U (-MT MCP-) speed: 1800 MHz\n" +
               "Graphics:  VMware SVGA II Adapter\n" +
               "Network:   Intel 82540EM Gigabit Ethernet  IF: eth0 state: up speed: 1000 Mbps\n" +
               "Drives:    HDD Total Size: 64.4GB\n" +
               "Info:      Processes: 42 Uptime: 1:01 Memory: 2143.2/7863.8MB";
    }

    private String cmdSensors() {
        return "coretemp-isa-0000\nAdapter: ISA adapter\nPackage id 0:  +45.0\u00B0C  (high = +100.0\u00B0C, crit = +100.0\u00B0C)\nCore 0:        +43.0\u00B0C  (high = +100.0\u00B0C, crit = +100.0\u00B0C)\nCore 1:        +44.0\u00B0C  (high = +100.0\u00B0C, crit = +100.0\u00B0C)\n\nacpitz-virtual-0\nAdapter: Virtual device\ntemp1:        +27.8\u00B0C";
    }

    private String cmdIostat() {
        return "Linux 5.15.0-88-generic (" + fs.getHostname() + ")\n\navg-cpu:  %user   %nice %system %iowait  %steal   %idle\n           2.00    0.00    1.00    0.50    0.00   96.50\n\nDevice             tps    kB_read/s    kB_wrtn/s    kB_read    kB_wrtn\nsda               1.23        12.34        45.67     123456     456789";
    }

    private String cmdVmstat() {
        return "procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----\n r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st\n 1  0      0 1205620 123456 4515000    0    0     5    10  100  200  2  1 96  1  0";
    }

    private String cmdMpstat() {
        return "Linux 5.15.0-88-generic (" + fs.getHostname() + ")\n\n10:00:00 AM  CPU    %usr   %nice    %sys %iowait    %irq   %soft  %steal  %guest   %idle\n10:00:00 AM  all    2.00    0.00    1.00    0.50    0.00    0.50    0.00    0.00   96.00";
    }

    private String cmdSysctl(String[] args) {
        Hashtable sysctls = new Hashtable();
        sysctls.put("kernel.hostname",        fs.getHostname());
        sysctls.put("kernel.ostype",          "Linux");
        sysctls.put("kernel.osrelease",       "5.15.0-88-generic");
        sysctls.put("vm.swappiness",          "10");
        sysctls.put("net.ipv4.ip_forward",    "0");
        sysctls.put("net.ipv4.tcp_fin_timeout","60");
        sysctls.put("fs.file-max",            "9223372036854775807");
        if (args.length == 0 || args[0].equals("-a")) {
            StringBuffer sb = new StringBuffer();
            Enumeration keys = sysctls.keys();
            while (keys.hasMoreElements()) {
                String k = (String) keys.nextElement();
                sb.append(k).append(" = ").append(sysctls.get(k)).append("\n");
            }
            return sb.toString().trim();
        }
        if (args[0].equals("-w") && args.length > 1) {
            int eq = args[1].indexOf('=');
            if (eq >= 0) { sysctls.put(args[1].substring(0,eq).trim(), args[1].substring(eq+1).trim()); return ""; }
        }
        String key = args[0];
        if (sysctls.containsKey(key)) return key + " = " + sysctls.get(key);
        return "sysctl: cannot stat /proc/sys/" + key.replace('.', '/') + ": No such file or directory";
    }

    private String cmdLsmod() {
        return "Module                  Size  Used by\nnfsd                  397312  2\ncifs                  393216  0\nvboxsf                 90112  1\nvboxguest             327680  2 vboxsf\next4                  868352  1\nmbcache                16384  1 ext4\njbd2                  131072  1 ext4";
    }

    // =================== HELPER UTILITIES ===================

    private String expandAlias(String cmdLine) {
        String first = cmdLine;
        int spaceIdx = cmdLine.indexOf(' ');
        if (spaceIdx >= 0) first = cmdLine.substring(0, spaceIdx);
        if (aliases.containsKey(first)) {
            String expansion = (String) aliases.get(first);
            if (spaceIdx >= 0) return expansion + cmdLine.substring(spaceIdx);
            return expansion;
        }
        return cmdLine;
    }

    private String expandVars(String s) {
        if (s == null) return "";
        StringBuffer sb = new StringBuffer();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '$') {
                i++;
                if (i < s.length() && s.charAt(i) == '{') {
                    int end = s.indexOf('}', i);
                    if (end > i) {
                        String key = s.substring(i+1, end);
                        String val = (String) env.get(key);
                        sb.append(val != null ? val : "");
                        i = end + 1;
                        continue;
                    }
                }
                StringBuffer key = new StringBuffer();
                while (i < s.length() && isVarChar(s.charAt(i))) { key.append(s.charAt(i)); i++; }
                if (key.length() > 0) {
                    String val = (String) env.get(key.toString());
                    sb.append(val != null ? val : "");
                } else {
                    sb.append('$');
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private boolean isVarChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private int findPipe(String cmdLine) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < cmdLine.length(); i++) {
            char c = cmdLine.charAt(i);
            if (!inQuote && (c == '\'' || c == '"')) { inQuote = true; quoteChar = c; continue; }
            if (inQuote && c == quoteChar) { inQuote = false; continue; }
            if (!inQuote && c == '|') return i;
        }
        return -1;
    }

    /** Tokenize respecting quotes */
    private String[] parseTokens(String cmdLine) {
        Vector tokens = new Vector();
        StringBuffer cur = new StringBuffer();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < cmdLine.length(); i++) {
            char c = cmdLine.charAt(i);
            if (!inSingle && !inDouble && c == '\'') { inSingle = true; continue; }
            if (inSingle && c == '\'') { inSingle = false; continue; }
            if (!inSingle && !inDouble && c == '"') { inDouble = true; continue; }
            if (inDouble && c == '"') { inDouble = false; continue; }
            if (!inSingle && !inDouble && c == ' ') {
                if (cur.length() > 0) { tokens.addElement(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) tokens.addElement(cur.toString());
        String[] arr = new String[tokens.size()];
        tokens.copyInto(arr);
        return arr;
    }

    // =================== STRING UTILITIES ===================

    private String join(String[] arr, String sep) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private String[] copyArgs(String[] arr, int fromIndex) {
        if (fromIndex >= arr.length) return new String[0];
        String[] result = new String[arr.length - fromIndex];
        for (int i = 0; i < result.length; i++) result[i] = arr[fromIndex + i];
        return result;
    }

    private String[] splitLines(String s) {
        if (s == null) return new String[0];
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

    private String[] splitOn(String s, String delim) {
        Vector v = new Vector();
        int start = 0;
        int idx;
        while ((idx = s.indexOf(delim, start)) >= 0) {
            v.addElement(s.substring(start, idx));
            start = idx + delim.length();
        }
        v.addElement(s.substring(start));
        String[] arr = new String[v.size()];
        v.copyInto(arr);
        return arr;
    }

    private String[] sortStrings(String[] arr, boolean reverse) {
        // Bubble sort (CLDC safe)
        String[] copy = new String[arr.length];
        for (int i = 0; i < arr.length; i++) copy[i] = arr[i];
        for (int i = 0; i < copy.length - 1; i++) {
            for (int j = 0; j < copy.length - 1 - i; j++) {
                boolean swap = copy[j].compareTo(copy[j+1]) > 0;
                if (reverse) swap = !swap;
                if (swap) { String t = copy[j]; copy[j] = copy[j+1]; copy[j+1] = t; }
            }
        }
        return copy;
    }

    private String[] reverseArray(String[] arr) {
        String[] copy = new String[arr.length];
        for (int i = 0; i < arr.length; i++) copy[i] = arr[arr.length - 1 - i];
        return copy;
    }

    private String[] uniqueLines(String[] arr) {
        Vector v = new Vector();
        for (int i = 0; i < arr.length; i++) {
            boolean found = false;
            for (int j = 0; j < v.size(); j++) if (v.elementAt(j).equals(arr[i])) { found = true; break; }
            if (!found) v.addElement(arr[i]);
        }
        String[] result = new String[v.size()];
        v.copyInto(result);
        return result;
    }

    private String padRight(String s, int width) {
        while (s.length() < width) s = s + " ";
        return s.length() > width ? s.substring(0, width) : s;
    }

    private String padLeft(String s, int width) {
        while (s.length() < width) s = " " + s;
        return s;
    }

    private String humanSize(String sizeStr) {
        long size = 0;
        try { size = Long.parseLong(sizeStr); } catch(Exception e) { return sizeStr; }
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return (size / 1024) + "K";
        if (size < 1024 * 1024 * 1024) return (size / (1024*1024)) + "M";
        return (size / (1024*1024*1024)) + "G";
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }

    /** Split string on whitespace ? CLDC-safe replacement for String.split("\\s+") */
    private String[] splitWS(String s) {
        if (s == null || s.length() == 0) return new String[0];
        java.util.Vector v = new java.util.Vector();
        int i = 0, len = s.length();
        while (i < len) {
            while (i < len && s.charAt(i) <= ' ') i++;
            if (i >= len) break;
            int start = i;
            while (i < len && s.charAt(i) > ' ') i++;
            v.addElement(s.substring(start, i));
        }
        String[] arr = new String[v.size()];
        for (int j = 0; j < v.size(); j++) arr[j] = (String) v.elementAt(j);
        return arr;
    }

    private String normSlash(String s) {
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length()-1);
        while (s.indexOf("//") >= 0) s = replaceAll(s, "//", "/");
        return s;
    }

    private String interpretEscapes(String s) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i+1 < s.length()) {
                char next = s.charAt(i+1);
                if (next == 'n') { sb.append('\n'); i++; }
                else if (next == 't') { sb.append('\t'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else if (next == 'r') { sb.append('\r'); i++; }
                else sb.append(s.charAt(i));
            } else sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    private String replaceAll(String s, String from, String to) {
        if (from.length() == 0) return s;
        StringBuffer sb = new StringBuffer();
        int idx, start = 0;
        while ((idx = s.indexOf(from, start)) >= 0) {
            sb.append(s.substring(start, idx)).append(to);
            start = idx + from.length();
        }
        sb.append(s.substring(start));
        return sb.toString();
    }

    private String replaceFirst(String s, String from, String to) {
        int idx = s.indexOf(from);
        if (idx < 0) return s;
        return s.substring(0, idx) + to + s.substring(idx + from.length());
    }

    private String[] splitSed(String s, char sep) {
        Vector v = new Vector();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) {
                v.addElement(s.substring(start, i));
                start = i + 1;
            }
        }
        v.addElement(s.substring(start));
        String[] arr = new String[v.size()];
        v.copyInto(arr);
        return arr;
    }

    private String[] splitPathParts(String path) {
        return splitOn(path, "/");
    }

    private boolean simpleGlob(String pattern, String str) {
        if (pattern.equals("*")) return true;
        if (pattern.startsWith("*") && pattern.endsWith("*")) {
            return str.indexOf(pattern.substring(1, pattern.length()-1)) >= 0;
        }
        if (pattern.startsWith("*")) return str.endsWith(pattern.substring(1));
        if (pattern.endsWith("*")) return str.startsWith(pattern.substring(0, pattern.length()-1));
        if (pattern.indexOf('?') >= 0) {
            if (pattern.length() != str.length()) return false;
            for (int i = 0; i < pattern.length(); i++) {
                if (pattern.charAt(i) != '?' && pattern.charAt(i) != str.charAt(i)) return false;
            }
            return true;
        }
        return pattern.equals(str);
    }

    private String fakeHash(String input, int length) {
        int seed = input.hashCode();
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuffer sb = new StringBuffer();
        long v = seed;
        for (int i = 0; i < length; i++) {
            v = v * 6364136223846793005L + 1442695040888963407L;
            sb.append(hex[(int)((v >> 28) & 0xf)]);
        }
        return sb.toString();
    }

    private String base64Encode(String s) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuffer sb = new StringBuffer();
        byte[] bytes = s.getBytes();
        for (int i = 0; i < bytes.length; i += 3) {
            int b0 = bytes[i] & 0xff;
            int b1 = i+1 < bytes.length ? bytes[i+1] & 0xff : 0;
            int b2 = i+2 < bytes.length ? bytes[i+2] & 0xff : 0;
            sb.append(chars.charAt(b0 >> 2));
            sb.append(chars.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i+1 < bytes.length ? chars.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i+2 < bytes.length ? chars.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    private String padHex(int n, int width) {
        String h = Integer.toHexString(n);
        while (h.length() < width) h = "0" + h;
        return h;
    }

    public String getPrompt() { return fs.getPrompt(); }

    public Vector getHistory() { return history; }


    // ?? Security / Pentest tools ??????????????????????????????????????????????
    private String cmdMasscan(String[] args) {
        if (args.length == 0) return "Usage: masscan <ip/range> -p<ports>";
        return "Starting masscan 1.3.2...\nScanning " + args[0] + "\nDiscovered open port 80/tcp on 192.168.1.1\nDiscovered open port 22/tcp on 192.168.1.1\nRate: 100.00-kpps, 0.00% done\nmasscan done.";
    }
    private String cmdNikto(String[] args) {
        String host = args.length > 0 ? args[args.length-1] : "target";
        return "- Nikto v2.1.6\n---------------------------------------------------------------------------\n+ Target IP: 192.168.1.1\n+ Target Hostname: " + host + "\n+ Target Port: 80\n+ Start Time: 2025-03-22 10:00:00\n---------------------------------------------------------------------------\n+ Server: Apache/2.4.52\n+ /: The X-XSS-Protection header is not defined.\n+ /: The X-Content-Type-Options header is not set.\n+ /index.html: Apache default file found.\n+ 8783 requests: 0 error(s) and 3 item(s) reported on remote host\n+ End Time: 2025-03-22 10:02:14 (134 seconds)\n---------------------------------------------------------------------------";
    }
    private String cmdSqlmap(String[] args) {
        if (args.length == 0) return "Usage: sqlmap -u <url> [options]";
        return "        ___\n       __H__\n ___ ___[.]_____ ___ ___  {1.7.8#stable}\n|_ -| . [,]     | .'| . |\n|___|_  [(]_|_|_|__,|  _|\n      |_|V...       |_|\n\n[*] starting @ 10:00:00\n[INFO] testing connection to the target URL\n[INFO] testing if the target URL content is stable\n[INFO] target URL content is stable\n[INFO] heuristic (basic) test shows that GET parameter 'id' might be injectable\n[INFO] testing for SQL injection on GET parameter 'id'\n[INFO] GET parameter 'id' appears to be 'AND boolean-based blind' injectable\n[INFO] sqlmap identified the following injection point(s):\nParameter: id (GET)\n    Type: boolean-based blind\n    Payload: id=1 AND 1=1\n[*] ending @ 10:00:05";
    }
    private String cmdMsf(String[] args) {
        return "                                                  \n      .:okOOOkdc'           'cdkOOOko:.              \n   .xOOOOOOOOOOOOk,       ,kOOOOOOOOOOOx.           \n  oOOOOOOOOOOOOOOOk.     .kOOOOOOOOOOOOOOo          \n\n       =[ metasploit v6.3.44-dev                          ]\n+ -- --=[ 2376 exploits - 1232 auxiliary - 416 post       ]\n+ -- --=[ 1079 payloads - 45 encoders - 11 nops           ]\n+ -- --=[ 9 evasion                                       ]\n\nmsf6 > ";
    }
    private String cmdMsfvenom(String[] args) {
        if (args.length == 0) return "Usage: msfvenom -p <payload> [options]\nExample: msfvenom -p linux/x86/shell_reverse_tcp LHOST=10.0.0.1 LPORT=4444 -f elf";
        return "[-] No platform was selected, choosing Msf::Module::Platform::Linux from the payload\n[-] No arch selected, selecting arch: x86 from the payload\nNo encoder specified, outputting raw payload\nPayload size: 68 bytes\nFinal size of elf file: 152 bytes";
    }
    private String cmdHydra(String[] args) {
        if (args.length == 0) return "Usage: hydra [-l login|-L file] [-p pass|-P file] <target> <service>";
        return "Hydra v9.4 (c) 2022 by van Hauser/THC\n[DATA] max 16 tasks per 1 server, overall 16 tasks, 14344398 login tries\n[DATA] attacking ssh://target:22/\n[22][ssh] host: target   login: admin   password: password123\n1 of 1 target successfully completed, 1 valid password found\nHydra finished.";
    }
    private String cmdMedusa(String[] args) {
        if (args.length == 0) return "Usage: medusa -h host -u user -P passlist -M service";
        return "Medusa v2.2 - by JoMo-Kun\nMEDUSA-INFO [hostinfo.c:293] h(1) u(1) P(1) C(1) T(1)\nACCOUNT FOUND: [ssh] Host: target User: admin Password: admin123 [SUCCESS]";
    }
    private String cmdJohn(String[] args) {
        if (args.length == 0) return "Usage: john [options] <hashfile>";
        return "Using default input encoding: UTF-8\nLoaded 1 password hash (sha512crypt, crypt(3) $6$ [SHA512 256/256 AVX2 4x])\nWill run 4 OpenMP threads\nProceeding with single, rules:Single\nPress 'q' or Ctrl-C to abort, almost any other key for status\npassword123      (user)\n1g 0:00:00:02 DONE 0.4830g/s 6185p/s\nUse the \"--show\" option to display all of the cracked passwords reliably";
    }
    private String cmdHashcat(String[] args) {
        if (args.length == 0) return "Usage: hashcat -m <mode> <hashfile> <wordlist>";
        return "hashcat (v6.2.6) starting...\nOpenCL API (OpenCL 3.0): Platform #1 [NVIDIA]\nMinimum password length supported by kernel: 0\nMaximum password length supported by kernel: 256\nHashes: 1 digests; 1 unique digests, 1 unique salts\nBitmaps: 16 bits, 65536 entries\nApproaching final keyspace - workload adjusted.\nhash:password123\nSession..........: hashcat\nStatus...........: Cracked\nRecovered........: 1/1 (100.00%)";
    }
    private String cmdAircrack(String[] args) {
        return "Aircrack-ng 1.7\n[00:00:01] 512/512 keys tested (487.23 k/s)\nTime left: 0 seconds                                     100.00%\n                         KEY FOUND! [ password123 ]\nMaster Key     : CD D7 9A 5A CF B0 70 C7 E9 D1 02 3B 87 02 85 D6\nTransient Key  : 0D 87 D4 E8 73 43 55 4D EF 1A 27 6E A3 6E 47 6B";
    }
    private String cmdAirodump(String[] args) {
        return " CH 11 ][ Elapsed: 12 s ][ 2025-03-22 10:00 \n\n BSSID              PWR  Beacons    #Data  #/s  CH   MB   ENC CIPHER  AUTH ESSID\n\n AA:BB:CC:DD:EE:FF  -45       42        8    0   6  130   WPA2 CCMP   PSK  HomeNetwork\n 11:22:33:44:55:66  -62       31        2    0  11   54   WEP  WEP         OfficeWifi\n\n BSSID              STATION            PWR    Lost  Frames  Notes\n\n AA:BB:CC:DD:EE:FF  FF:EE:DD:CC:BB:AA  -35       0      22";
    }
    private String cmdAireplay(String[] args) {
        return "The interface MAC (wlan0) doesn't match the specified MAC.\n\n11:06:41  Waiting for beacon frame (BSSID: AA:BB:CC:DD:EE:FF) on channel 6\n11:06:41  Sending 64 directed DeAuth (code 7). STMAC: [FF:EE:DD:CC:BB:AA] [ 0|63 ACKs]\n11:06:42  Sending 64 directed DeAuth (code 7). STMAC: [FF:EE:DD:CC:BB:AA] [62|63 ACKs]";
    }
    private String cmdAirmon(String[] args) {
        if (args.length > 0 && args[0].equals("start")) return "PHY     Interface       Driver          Chipset\nphy0    wlan0           ath9k           Qualcomm Atheros AR9485\n\n(mac80211 monitor mode vif enabled for [phy0]wlan0 on [phy0]wlan0mon)\n(mac80211 station mode vif disabled for [phy0]wlan0)";
        return "PHY     Interface       Driver          Chipset\nphy0    wlan0           ath9k           Qualcomm Atheros AR9485";
    }
    private String cmdWifite(String[] args) {
        return " .;'                                                           ';.\n.;'  ,;'                                                     ';,  ';.\n.;'  ,;'  ,;'                                             ';,  ';,  ';.\n                      wifite 2.7.0\n\n [+] scanning for wireless devices...\n [+] enabling monitor mode on wlan0... enabled\n [+] scanning for targets (CTRL+C when ready)\n\n  NUM  ESSID              CH  ENCR  POWER  WPS  CLIENT\n ----  -----------------  --  ----  -----  ---  ------\n    1  HomeNetwork         6  WPA2   -45db  YES       1\n\n [+] select target (1-%d): 1";
    }
    private String cmdReaver(String[] args) {
        if (args.length == 0) return "Usage: reaver -i <iface> -b <bssid> [options]";
        return "Reaver v1.6.5 WiFi Protected Setup Attack Tool\n[+] Waiting for beacon from AA:BB:CC:DD:EE:FF\n[+] Switching wlan0mon to channel 6\n[+] Received beacon from AA:BB:CC:DD:EE:FF\n[+] Trying pin \"12345670\"\n[+] 10.52% complete @ 2025-03-22 10:01:30 (3 seconds/pin)\n[+] WPS PIN: '12345670'\n[+] WPA PSK: 'password123'\n[+] AP SSID: 'HomeNetwork'";
    }
    private String cmdBully(String[] args) {
        if (args.length == 0) return "Usage: bully <interface> -b <bssid> [options]";
        return "[+] Bully v1.4.00 - WPS brute force utility\n[+] Running with pid: 1337\n[+] Switching interface to monitor mode done\n[+] Trying pin 12345670...\n[+] Got M1 message\n[+] Got M3 message\n[+] Cracked WPS pin: 12345670\n[+] WPA Key: password123";
    }
    private String cmdTshark(String[] args) {
        if (args.length > 0 && args[0].equals("-v")) return "TShark (Wireshark) 4.0.3";
        return "Running as user \"root\".\nCapturing on 'eth0'\n    1 0.000000 192.168.1.100 -> 192.168.1.1  TCP 74 54321 > 80 [SYN]\n    2 0.001234 192.168.1.1   -> 192.168.1.100 TCP 74 80 > 54321 [SYN, ACK]\n    3 0.001456 192.168.1.100 -> 192.168.1.1  TCP 66 54321 > 80 [ACK]\n    4 0.002000 192.168.1.100 -> 192.168.1.1  HTTP 482 GET / HTTP/1.1\n4 packets captured";
    }
    private String cmdEttercap(String[] args) {
        return "ettercap 0.8.3\nUsing mitm: arp\nListening on:\n eth0 -> 08:00:27:AB:CD:EF  192.168.1.100/255.255.255.0\nSSL dissection needs a valid 'redir_command_on' script in the etter.conf file\nStarting Unified sniffing...\nText only Interface activated...\nHit 'h' for inline help\nActivating arp poisoning...";
    }
    private String cmdBettercap(String[] args) {
        if (args.length == 0) return "Usage: bettercap [options]";
        return "bettercap v2.32.0 (built for linux amd64 with go1.19.4)\n[10:00:00] [sys.log] [inf] starting bettercap\n[10:00:00] [sys.log] [inf] loading 19 modules ...\n192.168.1.0/24 > 192.168.1.100 \u00BB help\n\nAvailable commands:\n  help MODULE   - show module help\n  net.recon     - passive network reconnaissance\n  net.probe     - active network probing\n  arp.spoof     - ARP spoofer";
    }
    private String cmdArpspoof(String[] args) {
        if (args.length == 0) return "Usage: arpspoof [-i interface] [-t target] host";
        return "0:c:29:ab:cd:ef 0:50:56:c0:0:8 0806 42: arp reply 192.168.1.1 is-at 0:c:29:ab:cd:ef\n0:c:29:ab:cd:ef 0:50:56:c0:0:8 0806 42: arp reply 192.168.1.1 is-at 0:c:29:ab:cd:ef";
    }
    private String cmdGobuster(String[] args) {
        if (args.length == 0) return "Usage: gobuster dir -u <url> -w <wordlist>";
        return "===============================================================\nGobuster v3.6\nby OJ Reeves (@TheColonial) & Christian Mehlmauer (@firefart)\n===============================================================\n[+] Url: http://target\n[+] Threads: 10\n[+] Wordlist: /usr/share/wordlists/dirb/common.txt\n[+] Status codes: 200,204,301,302,307,401,403\n===============================================================\n/admin                (Status: 301) [Size: 317]\n/login                (Status: 200) [Size: 1156]\n/uploads              (Status: 403) [Size: 293]\n/robots.txt           (Status: 200) [Size: 26]\nProgress: 4614/4614 (100.00%)\n===============================================================";
    }
    private String cmdDirb(String[] args) {
        if (args.length == 0) return "Usage: dirb <url> [wordlist]";
        return "-----------------\nDIRB v2.22\nBy The Dark Raver\n-----------------\nSTART_TIME: Sat Mar 22 10:00:00 2025\nURL_BASE: http://target/\nWORDLIST_FILES: /usr/share/dirb/wordlists/common.txt\n-----------------\nGENERATED WORDS: 4612\n---- Scanning URL: http://target/ ----\n+ http://target/index.html (CODE:200|SIZE:1024)\n==> DIRECTORY: http://target/admin/\n+ http://target/robots.txt (CODE:200|SIZE:26)\n-----------------\nEND_TIME: Sat Mar 22 10:00:14 2025\nDOWNLOADED: 4612 - FOUND: 3";
    }
    private String cmdFfuf(String[] args) {
        if (args.length == 0) return "Usage: ffuf -w <wordlist> -u <url>/FUZZ";
        return "\n        /'___\\  /'___\\           /'___\\\n       /\\ \\__/ /\\ \\__/  __  __  /\\ \\__/\n       \\ \\ ,__\\\\ \\ ,__\\/\\ \\/\\ \\ \\ \\ ,__\\\n        \\ \\ \\_/ \\ \\ \\_/\\ \\ \\_\\ \\ \\ \\ \\_/\n         \\ \\_\\   \\ \\_\\  \\ \\____/  \\ \\_\\\n          \\/_/    \\/_/   \\/___/    \\/_/\n\n________________________________________________\n\n :: Method           : GET\n :: URL              : http://target/FUZZ\n :: Wordlist         : /usr/share/wordlists/dirb/common.txt\n________________________________________________\n\nadmin                   [Status: 301, Size: 312]\nlogin                   [Status: 200, Size: 1156]\n\n:: Progress: [4614/4614] :: Job [1/1] :: 182 req/sec :: Duration: [0:00:25] ::";
    }
    private String cmdWfuzz(String[] args) {
        if (args.length == 0) return "Usage: wfuzz -c -w <wordlist> <url>/FUZZ";
        return "********************************************************\n* Wfuzz 3.1.0 - The Web Fuzzer                         *\n********************************************************\nTarget: http://target/FUZZ\nTotal requests: 4614\n=====================================================================\nID           Response   Lines    Word       Chars       Payload\n=====================================================================\n000000001:   200        9 L      22 W       236 Ch      \"index\"\n000000023:   301        9 L      28 W       316 Ch      \"admin\"\n000001073:   200        25 L     65 W       1156 Ch     \"login\"\nTotal time: 25.321\nProcessed Requests: 4614";
    }
    private String cmdSublist3r(String[] args) {
        if (args.length == 0) return "Usage: sublist3r -d <domain>";
        String domain = args.length > 1 ? args[1] : "target.com";
        return "\n                 ____  __    _ __\n                / ___// /_  (_) /\n                \\__ \\/ __ \\/ / /\n               ___/ / /_/ / / /\n              /____/_.___/_/_/ v1.1\n\n[-] Enumerating subdomains now for " + domain + "\n[-] Searching now in Baidu...\n[-] Searching now in Google...\n[-] Total Enumerations:  5\nwww." + domain + "\nmail." + domain + "\nftp." + domain + "\ndev." + domain + "\napi." + domain;
    }
    private String cmdAmass(String[] args) {
        if (args.length == 0) return "Usage: amass enum -d <domain>";
        String domain = args.length > 1 ? args[1] : "target.com";
        return "www." + domain + "\nmail." + domain + "\napi." + domain + "\ndev." + domain + "\nstaging." + domain + "\n\nASN: 15169 - GOOGLE\nASN: 8075  - MICROSOFT\n\n5 names discovered - dns: 5";
    }
    private String cmdTheHarvester(String[] args) {
        if (args.length == 0) return "Usage: theHarvester -d <domain> -b <source>";
        return "*******************************************************************\n*  _   _                                            _             *\n* | |_| |__   ___    /\\  /\\__ _ _ ____   _____  ___| |_ ___ _ __  *\n*                   theHarvester v4.4.0                            *\n*******************************************************************\n\n[*] Target: target.com \n\n[*] Searching Bing.\n\n[*] No IPs found.\n\n[*] Emails found: 2\n------------------\nadmin@target.com\ninfo@target.com\n\n[*] Hosts found: 3\n---------------------\nwww.target.com:93.184.216.34\nmail.target.com:93.184.216.35\napi.target.com:93.184.216.36";
    }
    private String cmdShodan(String[] args) {
        if (args.length == 0) return "Usage: shodan <command> [args]\nCommands: search, host, count, stats, scan";
        if (args[0].equals("host") && args.length > 1) return "IP: " + args[1] + "\nCountry: United States\nCity: San Jose\nOrganization: Cloudflare\nISP: Cloudflare\nLast update: 2025-03-20T12:00:00.000Z\n\nPorts:\n80/tcp - HTTP\n443/tcp - HTTPS\n22/tcp - SSH OpenSSH 8.2";
        return "Shodan CLI 1.10\nUsage: shodan [OPTIONS] COMMAND [ARGS]...";
    }
    private String cmdDnsenum(String[] args) {
        if (args.length == 0) return "Usage: dnsenum <domain>";
        String domain = args[0];
        return "dnsenum.pl VERSION:1.2.6\n\n-----   " + domain + "   -----\n\nHost's addresses:\n__________________\n" + domain + " 300 IN A 93.184.216.34\n\nName Servers:\n______________\nns1." + domain + " 86400 IN A 205.251.196.1\nns2." + domain + " 86400 IN A 205.251.198.1\n\nMail servers:\n_____________\nmail." + domain + " 3600 IN A 93.184.216.35\n\nBrute forcing with /usr/share/dnsenum/dns.txt:\n____________________________________________\nwww." + domain + " 300 IN A 93.184.216.34\nftp." + domain + " 300 IN A 93.184.216.34";
    }
    private String cmdDnsrecon(String[] args) {
        if (args.length == 0) return "Usage: dnsrecon -d <domain> [options]";
        return "[*] Performing General Enumeration of Domain: target.com\n[*] DNSSEC is not configured for target.com\n[+] A target.com 93.184.216.34\n[+] MX mail.target.com 93.184.216.35\n[+] NS ns1.target.com 205.251.196.1\n[+] NS ns2.target.com 205.251.198.1\n[+] SOA ns1.target.com 205.251.196.1\n[*] Enumerating SRV Records\n[+] 0 Records Found";
    }
    private String cmdFierce(String[] args) {
        if (args.length == 0) return "Usage: fierce --domain <domain>";
        return "NS: ns1.target.com.\nNS: ns2.target.com.\nSOA: ns1.target.com. (205.251.196.1)\nZone: failure\nTrying zone transfer first...\nUnable to perform zone transfer\nResolving SOA Record...\nFound: ns1.target.com -> 205.251.196.1\nScanning for subdomains:\nFound: www.target.com -> 93.184.216.34\nFound: mail.target.com -> 93.184.216.35\nFound: dev.target.com -> 93.184.216.36\nDone with Fierce scan: http://ha.ckers.org/fierce/";
    }
    private String cmdEnum4linux(String[] args) {
        if (args.length == 0) return "Usage: enum4linux [-a] <target>";
        return "Starting enum4linux v0.9.1 against target\n\n========================== Target Info ==========================\nTarget ........... target\nRID Range ........ 500-550,1000-1050\nUsername ......... ''\nPassword ......... ''\nKnown Usernames .. administrator, guest, krbtgt\n\n========================== Workgroup/Domain ==========================\n[+] Got domain/workgroup name: WORKGROUP\n\n========================== Users ==========================\nuser:[administrator] rid:[0x1f4]\nuser:[guest] rid:[0x1f5]\nuser:[user] rid:[0x3e8]\n\nenum4linux complete.";
    }
    private String cmdSmbclient(String[] args) {
        if (args.length == 0) return "Usage: smbclient //server/share [password] [options]";
        return "Try \"help\" to get a list of possible commands.\nsmb: \\> ls\n  .                                   D        0  Mon Mar 22 10:00:00 2025\n  ..                                  D        0  Mon Mar 22 10:00:00 2025\n  documents                           D        0  Mon Mar 22 09:00:00 2025\n  secret.txt                          A     1024  Mon Mar 22 08:00:00 2025\n\n\t\t9803772 blocks of size 1024. 4512180 blocks available";
    }
    private String cmdSmbmap(String[] args) {
        if (args.length == 0) return "Usage: smbmap [-H host] [-u user] [-p pass]";
        return "[+] Guest session   \tIP: 192.168.1.1:445\tName: target\n\tDisk                                                  \tPermissions\t------\n\tIPC$                                                  \tNO ACCESS\n\tC$                                                    \tNO ACCESS\n\tADMIN$                                                \tNO ACCESS\n\tshared                                                \tREAD, WRITE";
    }
    private String cmdCme(String[] args) {
        if (args.length == 0) return "Usage: crackmapexec <protocol> <target> [options]";
        return "SMB         192.168.1.1  445    DC01    [*] Windows 10.0 Build 19041 x64 (name:DC01) (domain:corp.local) (signing:True) (SMBv1:False)\nSMB         192.168.1.1  445    DC01    [+] corp.local\\administrator:password123 (Pwn3d!)";
    }
    private String cmdEvilWinrm(String[] args) {
        if (args.length == 0) return "Usage: evil-winrm -i <ip> -u <user> -p <pass>";
        return "Evil-WinRM shell v3.5\nInfo: Establishing connection to remote endpoint\n*Evil-WinRM* PS C:\\Users\\Administrator\\Documents>";
    }
    private String cmdSnort(String[] args) {
        if (args.length == 0) return "Usage: snort [-options] <filter options>";
        return "Running in packet dump mode\n\n        --== Initializing Snort ==--\nInitializing Output Plugins!\nSnort BPF option: (null)\nReading network traffic from \"eth0\" interface.\n\nPcap DAQ configured to passive.\nAcquiring network traffic from \"eth0\".\n\n        --== Initialization Complete ==--\n\n   ,,_     -*> Snort! <*-\n  o\"  )~   Version 2.9.20";
    }
    private String cmdSuricata(String[] args) {
        return "This is Suricata version 7.0.0 RELEASE running in LIVE mode\n[Info] -- CPUs/cores online: 2\n[Info] -- Using 2 threads\n[Info] -- Running in live mode, activating AF_PACKET IDS mode\n[Info] -- eth0: threads=2, IDS mode\n[Info] -- Engine started.";
    }
    private String cmdLynis(String[] args) {
        return "\n[ Lynis 3.0.8 ]\n\n################################################################################\n  Lynis comes with ABSOLUTELY NO WARRANTY. This is free software, and you are\n  welcome to redistribute it under the terms of the GNU General Public License.\n  See LICENSE file for details about using this software.\n################################################################################\n\n[+] Initializing program\n------------------------------------\n  - Detecting OS...                                           [ DONE ]\n  - Checking profiles...                                      [ DONE ]\n\n[+] System tools\n------------------------------------\n  - Scanning available tools...\n  - Checking system binaries...                               [ DONE ]\n\n[+] Hardening\n------------------------------------\n  - Kernel hardening index: 45 [WARNING]\n\n================================================================================\n  Lynis security scan details:\n  Hardening index : 65 [############        ]\n  Tests performed : 241\n  Plugins enabled : 2\n================================================================================";
    }
    private String cmdRkhunter(String[] args) {
        return "[ Rootkit Hunter version 1.4.6 ]\n\nChecking system commands...\n  Performing 'strings' command checks\n    Checking 'strings' command                               [ OK ]\n  Performing 'shared libraries' checks\n    Checking for preloading variables                         [ None found ]\n    Checking for preloaded libraries                         [ None found ]\n    Checking LD_LIBRARY_PATH variable                        [ Not found ]\nChecking for rootkits...\n  Performing check of known rootkit files and directories\n    55808 Trojan - Variant A                                 [ Not found ]\n    ADM Worm                                                 [ Not found ]\n    Suckit Rootkit                                           [ Not found ]\nSystem checks summary\n=====================\nFile properties checks...\n  Required commands check failed\n    Files checked: 143\n    Suspect files: 0\nRootkit checks...\n  Rootkits checked : 506\n  Possible rootkits: 0\nChecks: 652\nSuspect files: 0";
    }
    private String cmdChkrootkit() {
        return "ROOTDIR is `/'\nChecking `amd'...                                            not found\nChecking `basename'...                                       not infected\nChecking `biff'...                                           not found\nChecking `chfn'...                                           not infected\nChecking `chsh'...                                           not infected\nChecking `cron'...                                           not infected\nChecking `date'...                                           not infected\nChecking `du'...                                             not infected\nChecking `find'...                                           not infected\nChecking `grep'...                                           not infected\nChecking `ifconfig'...                                       not infected\nChecking `inetd'...                                          not tested\nChecking `login'...                                          not infected\nChecking `ls'...                                             not infected\nChecking `lsof'...                                           not infected\nChecking `passwd'...                                         not infected\nChecking `ps'...                                             not infected\nChecking `sshd'...                                           not infected\nChecking `top'...                                            not infected\nChecking for rootkits...\nNot infected";
    }
    private String cmdClamscan(String[] args) {
        if (args.length == 0) return "Usage: clamscan [options] [file/directory]";
        return "----------- SCAN SUMMARY -----------\nKnown viruses: 8603318\nEngine version: 1.0.1\nScanned directories: 1\nScanned files: 42\nInfected files: 0\nData scanned: 2.50 MB\nData read: 2.48 MB (ratio 1.01:1)\nTime: 4.215 sec (0 m 4 s)\nStart Date: 2025:03:22 10:00:00\nEnd Date:   2025:03:22 10:00:04";
    }
    private String cmdFail2ban(String[] args) {
        if (args.length == 0) return "Usage: fail2ban-client <command>";
        if (args[0].equals("status")) return "Status\n|- Number of jail:      2\n`- Jail list:   sshd, apache-auth";
        if (args[0].equals("ping")) return "pong";
        return "fail2ban-client v1.0.2";
    }
    private String cmdOpenssl(String[] args) {
        if (args.length == 0) return "usage: openssl command [ options... ]\nCommands: genrsa, rsa, req, x509, s_client, enc, dgst, rand, version";
        if (args[0].equals("version")) return "OpenSSL 3.0.2 15 Mar 2022";
        if (args[0].equals("genrsa")) return "Generating RSA private key, 2048 bit long modulus (2 primes)\n.........+++++\n...................+++++\ne is 65537 (0x010001)";
        return "(openssl " + join(args, " ") + " simulated)";
    }
    private String cmdGpg(String[] args) {
        if (args.length == 0) return "Usage: gpg [options] [files]\ngpg: no valid OpenPGP data found.";
        if (args[0].equals("--version")) return "gpg (GnuPG) 2.2.27\nlibgcrypt 1.9.4";
        if (args[0].equals("--list-keys") || args[0].equals("-k")) return "/home/user/.gnupg/pubring.kbx\n------------------------------\npub   rsa4096 2023-01-01 [SC]\n      ABCDEF1234567890ABCDEF1234567890ABCDEF12\nuid           [ultimate] User <user@example.com>\nsub   rsa4096 2023-01-01 [E]";
        return "(gpg " + join(args, " ") + " simulated)";
    }
    private String cmdSshKeygen(String[] args) {
        return "Generating public/private rsa key pair.\nEnter file in which to save the key (/home/user/.ssh/id_rsa): \nEnter passphrase (empty for no passphrase): \nEnter same passphrase again: \nYour identification has been saved in /home/user/.ssh/id_rsa\nYour public key has been saved in /home/user/.ssh/id_rsa.pub\nThe key fingerprint is:\nSHA256:Abc123XYZDefGhi456JklMno789PqrStuVwx012Yz3= user@localhost\nThe key's randomart image is:\n+---[RSA 3072]----+\n|        .o+o.    |\n|       o =+...   |\n|      . + +o  .  |\n|       . +  ..   |\n|        S .o     |\n+----[SHA256]-----+";
    }
    private String cmdNetdiscover(String[] args) {
        return " Currently scanning: 192.168.1.0/24   |   Screen View: Unique Hosts\n\n 4 Captured ARP Req/Rep packets, from 4 hosts.   Total size: 240\n _____________________________________________________________________________\n   IP            At MAC Address     Count     Len  MAC Vendor / Hostname      \n -----------------------------------------------------------------------------\n 192.168.1.1     aa:bb:cc:dd:ee:ff      1      60  Netgear Inc.\n 192.168.1.100   11:22:33:44:55:66      1      60  Dell Inc.\n 192.168.1.101   aa:11:bb:22:cc:33      1      60  Apple Inc.\n 192.168.1.102   dd:44:ee:55:ff:66      1      60  Unknown vendor";
    }
    private String cmdArpScan(String[] args) {
        return "Interface: eth0, type: EN10MB, MAC: 08:00:27:ab:cd:ef, IPv4: 192.168.1.100\nStarting arp-scan 1.10.0\n192.168.1.1\taa:bb:cc:dd:ee:ff\t(Unknown)\n192.168.1.101\t11:22:33:44:55:66\tDell Inc.\n192.168.1.102\taa:11:bb:22:cc:33\tApple Inc.\n\n3 packets received by filter, 0 packets dropped by kernel\nEnding arp-scan 1.10.0: 256 hosts scanned in 1.425 seconds (179.65 hosts/sec). 3 responded";
    }
    private String cmdFping(String[] args) {
        if (args.length == 0) return "Usage: fping [options] [targets...]";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("-")) sb.append(args[i]).append(" is alive\n");
        }
        return sb.length() > 0 ? sb.toString().trim() : "fping: no targets specified";
    }
    private String cmdHping3(String[] args) {
        if (args.length == 0) return "Usage: hping3 [options] host";
        return "HPING " + (args.length > 0 ? args[args.length-1] : "target") + " (eth0): NO FLAGS are set, 40 headers + 0 data bytes\nlen=40 ip=" + args[args.length-1] + " ttl=64 DF id=0 sport=0 flags=RA seq=0 win=0 rtt=0.4 ms\nlen=40 ip=" + args[args.length-1] + " ttl=64 DF id=0 sport=0 flags=RA seq=1 win=0 rtt=0.3 ms\n--- hping statistic ---\n2 packets transmitted, 2 packets received, 0% packet loss";
    }

    // ?? Forensics / Reverse Engineering ??????????????????????????????????????
    private String cmdVolatility(String[] args) {
        if (args.length == 0) return "Usage: volatility -f <image> [plugin]";
        return "Volatility Foundation Volatility Framework 2.6.1\nNamespace(cache_dtbs=False, debug=False, dir_json=None, filename='/tmp/memdump.mem', ...)\nProfiles: LinuxUbuntu_5_15_0_x64, Win10x64_19041";
    }
    private String cmdBinwalk(String[] args) {
        if (args.length == 0) return "Usage: binwalk [options] <file>";
        return "DECIMAL       HEXADECIMAL     DESCRIPTION\n--------------------------------------------------------------------------------\n0             0x0             ELF, 64-bit LSB executable, AMD x86-64\n1024          0x400           gzip compressed data, from Unix\n65536         0x10000         JPEG image data, JFIF standard 1.01";
    }
    private String cmdForemost(String[] args) {
        if (args.length == 0) return "Usage: foremost [-t type] [-i file]";
        return "Processing: input.img\n|*|*|*|*|*|*|*|*|*|*|*|*|*|*|*|*|*|*|*|*|*|\nProcessed: 1 GB in 45 Seconds\nFinished.\n\nINFO: Audit File Exists. Be sure to look at the file audit.txt.\nFound:\n\nFile: image\n\tSegment: 1\n\nFile: doc\n\tSegment: 1";
    }
    private String cmdDd(String[] args) {
        return "0+1 records in\n0+1 records out\n512 bytes copied, 0.000123 s, 4.2 MB/s";
    }
    private String cmdSteghide(String[] args) {
        if (args.length == 0) return "Usage: steghide <command> [args]\nCommands: embed, extract, info";
        if (args[0].equals("info") && args.length > 1) return "\"" + args[args.length-1] + "\":\n  format: jpeg\n  capacity: 4.7 KB\nTry to get information about embedded data ?\nPassphrase: \n  embedded file \"secret.txt\":\n    size: 68.0 Byte\n    encrypted: rijndael-128, cbc\n    compressed: yes";
        if (args[0].equals("extract")) return "Enter passphrase: \nwrote extracted data to \"secret.txt\".";
        return "(steghide " + join(args, " ") + ")";
    }
    private String cmdExiftool(String[] args) {
        if (args.length == 0) return "Usage: exiftool [options] file";
        String f = args[args.length-1];
        return "ExifTool Version Number         : 12.60\nFile Name                       : " + f + "\nDirectory                       : .\nFile Size                       : 2.4 MB\nFile Modification Date/Time     : 2025:03:22 10:00:00+00:00\nFile Type                       : JPEG\nImage Width                     : 4032\nImage Height                    : 3024\nMake                            : Apple\nModel                           : iPhone 14 Pro\nGPS Latitude                    : 5 deg 21' 23.40\" N\nGPS Longitude                   : 4 deg 1' 36.00\" W";
    }
    private String cmdObjdump(String[] args) {
        if (args.length == 0) return "Usage: objdump <option(s)> <file(s)>";
        return args[args.length-1] + ":     file format elf64-x86-64\n\nDisassembly of section .text:\n\n0000000000001149 <main>:\n    1149:\t55                   \tpush   %rbp\n    114a:\t48 89 e5             \tmov    %rsp,%rbp\n    114d:\t48 8d 05 b0 0e 00 00 \tlea    0xeb0(%rip),%rax\n    1154:\t48 89 c7             \tmov    %rax,%rdi\n    1157:\te8 f4 fe ff ff       \tcallq  1050 <puts@plt>\n    115c:\tb8 00 00 00 00       \tmov    $0x0,%eax\n    1161:\t5d                   \tpop    %rbp\n    1162:\tc3                   \tretq";
    }
    private String cmdRadare2(String[] args) {
        if (args.length == 0) return "Usage: r2 [options] <file>";
        return " -- Run r2 and use q!! to quit without saving the project\n[0x00001149]> aaa\n[x] Analyze all flags starting with sym. and entry0\n[x] Analyze function calls\n[x] Analyze len bytes of instructions for references\n[x] Check for objc references\n[x] Analyze value pointers\n[0x00001149]> pdf\n/ (fcn) sym.main 25\n|   int main (int argc, char **argv);\n|           0x00001149      55             push rbp\n|           0x0000114a      4889e5         mov rbp, rsp\n|           0x0000114d      488d05b00e00   lea rax, [0x00001004]\n|           0x00001157      e8f4feffff     call sym.imp.puts\n|           0x0000115c      b800000000     mov eax, 0\n\\           0x00001161      5d             pop rbp";
    }
    private String cmdGdb(String[] args) {
        if (args.length == 0) return "GNU gdb (Ubuntu 12.1-0ubuntu1~22.04) 12.1\nCopyright (C) 2022 Free Software Foundation\nType \"help\" for help.\n(gdb) ";
        return "GNU gdb (Ubuntu 12.1-0ubuntu1~22.04) 12.1\nReading symbols from " + args[0] + "...\n(gdb) ";
    }
    private String cmdNm(String[] args) {
        if (args.length == 0) return "Usage: nm [options] <file>";
        return "0000000000004020 B __bss_start\n0000000000004020 b completed.0\n                 U __cxa_finalize@GLIBC_2.2.5\n0000000000004010 D __data_start\n0000000000001100 t deregister_tm_clones\n0000000000001050 T main\n                 U puts@GLIBC_2.2.5\n                 U __stack_chk_fail@GLIBC_2.4";
    }
    private String cmdLdd(String[] args) {
        if (args.length == 0) return "Usage: ldd [options] <file>";
        return "\tlinux-vdso.so.1 (0x00007ffd5d9f1000)\n\tlibc.so.6 => /lib/x86_64-linux-gnu/libc.so.6 (0x00007f8b7ec00000)\n\t/lib64/ld-linux-x86-64.so.2 (0x00007f8b7efd8000)";
    }
    private String cmdReadelf(String[] args) {
        if (args.length == 0) return "Usage: readelf <option(s)> elf-file(s)";
        return "ELF Header:\n  Magic:   7f 45 4c 46 02 01 01 00 00 00 00 00 00 00 00 00 \n  Class:                             ELF64\n  Data:                              2's complement, little endian\n  Version:                           1 (current)\n  OS/ABI:                            UNIX - System V\n  Type:                              DYN (Position-Independent Executable file)\n  Machine:                           Advanced Micro Devices X86-64\n  Entry point address:               0x1080\n  Start of program headers:          64 (bytes into file)\n  Start of section headers:          14208 (bytes into file)";
    }

    // ?? Docker / Kubernetes / DevOps ?????????????????????????????????????????
    private String cmdDocker(String[] args) {
        if (args.length == 0) return "Usage:  docker [OPTIONS] COMMAND\n\nManagement Commands:\n  container   Manage containers\n  image       Manage images\n  network     Manage networks\n  volume      Manage volumes\n\nCommands:\n  build       Build an image\n  exec        Run a command in a container\n  images      List images\n  ps          List containers\n  pull        Pull an image\n  push        Push an image\n  run         Run a container\n  start       Start containers\n  stop        Stop containers";
        String sub = args[0];
        if (sub.equals("ps")) return "CONTAINER ID   IMAGE         COMMAND              CREATED         STATUS         PORTS                  NAMES\na1b2c3d4e5f6   nginx:latest  \"/docker-entryp...\"  2 hours ago     Up 2 hours     0.0.0.0:80->80/tcp     webserver\nb2c3d4e5f6a7   mysql:8.0     \"docker-entryp...\"   3 hours ago     Up 3 hours     3306/tcp               database";
        if (sub.equals("images")) return "REPOSITORY    TAG       IMAGE ID       CREATED        SIZE\nnginx         latest    a99a39d070bf   2 weeks ago    142MB\nmysql         8.0       96d0bbb1e3be   3 weeks ago    516MB\nubuntu        22.04     08d22c0ceb15   5 weeks ago    77.8MB\npython        3.11      a3e2c7e62985   6 weeks ago    921MB";
        if (sub.equals("run")) return "(Starting container...)";
        if (sub.equals("build")) return "Sending build context to Docker daemon  2.048kB\nStep 1/3 : FROM ubuntu:22.04\n ---> 08d22c0ceb15\nStep 2/3 : RUN apt-get update\n ---> Running in c8d9e2f1a4b3\nStep 3/3 : CMD [\"/bin/bash\"]\n ---> b1c2d3e4f5a6\nSuccessfully built b1c2d3e4f5a6";
        if (sub.equals("pull")) return "Using default tag: latest\nlatest: Pulling from library/" + (args.length > 1 ? args[1] : "image") + "\nStatus: Image is up to date for " + (args.length > 1 ? args[1] : "image") + ":latest";
        if (sub.equals("stop") || sub.equals("start") || sub.equals("rm") || sub.equals("restart")) return (args.length > 1 ? args[1] : "container");
        return "(docker " + join(args, " ") + ")";
    }
    private String cmdDockerCompose(String[] args) {
        if (args.length == 0) return "Usage: docker-compose [OPTIONS] COMMAND\nCommands: up, down, build, ps, logs, pull, restart, exec";
        String sub = args[0];
        if (sub.equals("up")) return "Creating network \"app_default\" with the default driver\nCreating app_db_1    ... done\nCreating app_web_1   ... done";
        if (sub.equals("down")) return "Stopping app_web_1    ... done\nStopping app_db_1     ... done\nRemoving app_web_1    ... done\nRemoving app_db_1     ... done\nRemoving network app_default";
        if (sub.equals("ps")) return "     Name                    Command               State           Ports\n--------------------------------------------------------------------------\napp_db_1    docker-entrypoint.sh mysqld   Up      3306/tcp\napp_web_1   /docker-entrypoint.sh ngin    Up      0.0.0.0:80->80/tcp";
        return "(docker-compose " + join(args, " ") + ")";
    }
    private String cmdKubectl(String[] args) {
        if (args.length == 0) return "kubectl controls the Kubernetes cluster manager.\nUsage: kubectl [command] [TYPE] [NAME] [flags]\nCommands: get, describe, create, apply, delete, logs, exec, port-forward";
        String sub = args[0];
        if (sub.equals("get") && args.length > 1) {
            if (args[1].equals("pods")) return "NAME                          READY   STATUS    RESTARTS   AGE\nnginx-deployment-abc12-xyz98  1/1     Running   0          2d\nmysql-statefulset-0           1/1     Running   0          5d\nredis-cache-def34-uvw56       1/1     Running   2          1d";
            if (args[1].equals("nodes")) return "NAME       STATUS   ROLES           AGE   VERSION\nnode-01    Ready    control-plane   30d   v1.28.0\nnode-02    Ready    <none>          30d   v1.28.0\nnode-03    Ready    <none>          30d   v1.28.0";
            if (args[1].equals("svc") || args[1].equals("services")) return "NAME         TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)        AGE\nkubernetes   ClusterIP   10.96.0.1       <none>        443/TCP        30d\nnginx-svc    NodePort    10.100.0.5      <none>        80:30080/TCP   2d\nmysql-svc    ClusterIP   10.100.0.8      <none>        3306/TCP       5d";
            if (args[1].equals("namespaces") || args[1].equals("ns")) return "NAME              STATUS   AGE\ndefault           Active   30d\nkube-system       Active   30d\nkube-public       Active   30d\nkube-node-lease   Active   30d\nmonitoring        Active   10d";
        }
        if (sub.equals("apply")) return "deployment.apps/nginx configured\nservice/nginx-svc unchanged";
        if (sub.equals("delete")) return (args.length > 2 ? args[2] : "resource") + " deleted";
        if (sub.equals("logs")) return "[2025-03-22 10:00:00] INFO  Starting...\n[2025-03-22 10:00:01] INFO  Server listening on port 8080\n[2025-03-22 10:00:05] INFO  Health check OK";
        if (sub.equals("version")) return "Client Version: version.Info{Major:\"1\", Minor:\"28\", GitVersion:\"v1.28.0\"}\nKustomize Version: v5.0.4-0.20230601165947-6ce0bf390ce3\nServer Version: version.Info{Major:\"1\", Minor:\"28\", GitVersion:\"v1.28.0\"}";
        return "(kubectl " + join(args, " ") + ")";
    }
    private String cmdHelm(String[] args) {
        if (args.length == 0) return "The Kubernetes Package Manager\nUsage: helm [command]\nCommands: install, upgrade, uninstall, list, repo, search, status, rollback";
        String sub = args[0];
        if (sub.equals("list")) return "NAME       \tNAMESPACE\tREVISION\tSTATUS  \tCHART            \tAPP VERSION\nnginx-ingress\tdefault  \t3       \tdeployed\tingress-nginx-4.7\t1.8.1\nmonitoring   \tdefault  \t1       \tdeployed\tkube-prom-45.7.1 \t0.66.0";
        if (sub.equals("install") || sub.equals("upgrade")) return "Release \"" + (args.length > 1 ? args[1] : "release") + "\" has been upgraded. Happy Helming!\nNAME: " + (args.length > 1 ? args[1] : "release") + "\nNAMESPACE: default\nSTATUS: deployed\nREVISION: 1";
        if (sub.equals("version")) return "version.BuildInfo{Version:\"v3.12.0\", GitCommit:\"abc123\", GoVersion:\"go1.20.4\"}";
        return "(helm " + join(args, " ") + ")";
    }
    private String cmdMinikube(String[] args) {
        if (args.length == 0) return "minikube provisions and manages local Kubernetes clusters.\nUsage: minikube [command]\nCommands: start, stop, status, delete, dashboard, addons";
        String sub = args[0];
        if (sub.equals("start")) return "* minikube v1.31.2\n* Automatically selected the docker driver\n* Starting control plane node minikube in cluster minikube\n* Pulling base image ...\n* Creating docker container (CPUs=2, Memory=4096MB) ...\n* Preparing Kubernetes v1.28.0 on Docker 24.0.4 ...\n* Done! kubectl is now configured to use \"minikube\" cluster and \"default\" namespace by default";
        if (sub.equals("status")) return "minikube\ntype: Control Plane\nhost: Running\nkubelet: Running\napiserver: Running\nkubeconfig: Configured";
        if (sub.equals("stop")) return "* Stopping node \"minikube\"  ...\n* Powering off \"minikube\" via SSH ...";
        return "(minikube " + join(args, " ") + ")";
    }
    private String cmdKind(String[] args) {
        if (args.length == 0) return "kind creates and manages local Kubernetes clusters using Docker.\nUsage: kind [command]\nCommands: create, delete, get, load, export";
        if (args[0].equals("create") && args.length > 1 && args[1].equals("cluster")) return "Creating cluster \"kind\" ...\n * Ensuring node image (kindest/node:v1.28.0) ...\n * Preparing nodes ...\n * Writing configuration ...\n * Starting control-plane ...\nSet kubectl context to \"kind-kind\"\nYou can now use your cluster with: kubectl cluster-info --context kind-kind";
        if (args[0].equals("get") && args.length > 1 && args[1].equals("clusters")) return "kind";
        return "(kind " + join(args, " ") + ")";
    }
    private String cmdIstioctl(String[] args) {
        if (args.length == 0) return "Istio configuration command line utility.\nUsage: istioctl [command]\nCommands: install, analyze, proxy-status, dashboard";
        if (args[0].equals("version")) return "client version: 1.18.0\ncontrol plane version: 1.18.0\ndata plane version: 1.18.0";
        return "(istioctl " + join(args, " ") + ")";
    }
    private String cmdTerraform(String[] args) {
        if (args.length == 0) return "Usage: terraform [global options] <subcommand> [args]\nSubcommands: init, validate, plan, apply, destroy, state, output, fmt";
        String sub = args[0];
        if (sub.equals("init")) return "\nInitializing the backend...\nInitializing provider plugins...\n- Finding hashicorp/aws versions matching \"~> 5.0\"...\n- Installing hashicorp/aws v5.20.0...\nTerraform has been successfully initialized!";
        if (sub.equals("plan")) return "\nTerraform used the selected providers to generate the following execution plan.\n\nPlan: 3 to add, 0 to change, 0 to destroy.\n\nChanges to Outputs:\n  + instance_id = (known after apply)\n  + public_ip   = (known after apply)";
        if (sub.equals("apply")) return "\nTerraform will perform the following actions:\n  + aws_instance.web\n  + aws_security_group.allow_ssh\n  + aws_key_pair.deployer\n\nPlan: 3 to add, 0 to change, 0 to destroy.\n\nApply complete! Resources: 3 added, 0 changed, 0 destroyed.";
        if (sub.equals("destroy")) return "Destroy complete! Resources: 3 destroyed.";
        if (sub.equals("version")) return "Terraform v1.5.7\non linux_amd64";
        return "(terraform " + join(args, " ") + ")";
    }
    private String cmdAnsible(String[] args) {
        if (args.length == 0) return "Usage: ansible <host-pattern> [options]\nOptions: -m module, -a args, -i inventory, --list-hosts";
        return "192.168.1.1 | SUCCESS => {\n    \"changed\": false,\n    \"ping\": \"pong\"\n}";
    }
    private String cmdAnsiblePlaybook(String[] args) {
        if (args.length == 0) return "Usage: ansible-playbook <playbook.yml> [options]";
        return "\nPLAY [all] *****\n\nTASK [Gathering Facts] *\nok: [192.168.1.1]\n\nTASK [Install nginx] ***\nchanged: [192.168.1.1]\n\nPLAY RECAP ****\n192.168.1.1 : ok=2  changed=1  unreachable=0  failed=0  skipped=0";
    }
    private String cmdVagrant(String[] args) {
        if (args.length == 0) return "Usage: vagrant [options] <command> [<args>]\nCommands: box, destroy, halt, init, provision, reload, resume, ssh, status, suspend, up";
        String sub = args[0];
        if (sub.equals("up")) return "Bringing machine 'default' up with 'virtualbox' provider...\n==> default: Importing base box 'ubuntu/jammy64'...\n==> default: Forwarding ports...\n==> default: Running provisioner: shell...\n==> default: Machine booted and ready!";
        if (sub.equals("status")) return "Current machine states:\ndefault                   running (virtualbox)";
        if (sub.equals("halt")) return "==> default: Attempting graceful shutdown of VM...";
        if (sub.equals("ssh")) return "(connecting to VM via SSH...)";
        return "(vagrant " + join(args, " ") + ")";
    }
    private String cmdPacker(String[] args) {
        if (args.length == 0) return "Usage: packer [--version] [--help] <command> [<args>]\nCommands: build, validate, init, inspect, fmt";
        if (args[0].equals("version")) return "Packer v1.9.4";
        if (args[0].equals("build")) return "==> amazon-ebs: Prevalidating AMI Name: my-ami-2025-03-22\n==> amazon-ebs: Waiting for spot request ...\n==> amazon-ebs: Waiting for AMI to become ready...\n==> amazon-ebs: AMI: ami-0abcdef1234567890\nBuild 'amazon-ebs' finished after 5 minutes.\n==> Builds finished. The artifacts of successful builds are:\n--> amazon-ebs: AMIs were created:\nus-east-1: ami-0abcdef1234567890";
        return "(packer " + join(args, " ") + ")";
    }

    // ?? Cloud CLIs ????????????????????????????????????????????????????????????
    private String cmdAws(String[] args) {
        if (args.length == 0) return "usage: aws [options] <command> <subcommand> [parameters]\nServices: s3, ec2, iam, lambda, rds, ecs, eks, cloudformation";
        String svc = args[0];
        if (svc.equals("s3")) {
            if (args.length > 1 && args[1].equals("ls")) return "2025-01-01 00:00:00 my-backup-bucket\n2025-02-01 00:00:00 my-static-site\n2025-03-01 00:00:00 my-logs-bucket";
            return "(aws s3 " + (args.length > 1 ? args[1] : "") + ")";
        }
        if (svc.equals("ec2")) {
            if (args.length > 1 && args[1].equals("describe-instances")) return "{\n  \"Reservations\": [{\n    \"Instances\": [{\n      \"InstanceId\": \"i-0abcdef1234567890\",\n      \"InstanceType\": \"t3.micro\",\n      \"State\": { \"Name\": \"running\" },\n      \"PublicIpAddress\": \"54.123.456.789\"\n    }]\n  }]\n}";
        }
        if (svc.equals("--version")) return "aws-cli/2.13.0 Python/3.11.4 Linux/5.15.0 botocore/2.0.0";
        return "(aws " + join(args, " ") + ")";
    }
    private String cmdGcloud(String[] args) {
        if (args.length == 0) return "Usage: gcloud [--version] [--help] <group|command> [...]\nGroups: compute, container, iam, sql, storage, functions";
        if (args[0].equals("--version")) return "Google Cloud SDK 447.0.0\nbq 2.0.98\ncore 2023.10.20";
        if (args[0].equals("compute") && args.length > 1 && args[1].equals("instances") && args.length > 2 && args[2].equals("list")) return "NAME          ZONE           MACHINE_TYPE  STATUS\nweb-server-1  us-central1-a  n1-standard-1 RUNNING\ndb-server-1   us-central1-a  n1-standard-2 RUNNING";
        return "(gcloud " + join(args, " ") + ")";
    }
    private String cmdAzure(String[] args) {
        if (args.length == 0) return "Welcome to Azure CLI!\nUsage: az [command]\nGroups: vm, storage, network, sql, aks, acr, keyvault";
        if (args[0].equals("--version")) return "azure-cli                         2.53.0";
        if (args[0].equals("vm") && args.length > 1 && args[1].equals("list")) return "[\n  {\n    \"name\": \"myVM\",\n    \"location\": \"eastus\",\n    \"powerState\": \"VM running\",\n    \"resourceGroup\": \"myResourceGroup\"\n  }\n]";
        return "(az " + join(args, " ") + ")";
    }
    private String cmdDoctl(String[] args) {
        if (args.length == 0) return "doctl is the official DigitalOcean CLI.\nUsage: doctl [command]\nCommands: compute, databases, kubernetes, registry";
        if (args[0].equals("version")) return "doctl version 1.98.1";
        return "(doctl " + join(args, " ") + ")";
    }
    private String cmdHeroku(String[] args) {
        if (args.length == 0) return "CLI to interact with Heroku\nUsage: heroku COMMAND\nCommands: apps, addons, config, logs, ps, run, domains, login, create, deploy";
        String sub = args[0];
        if (sub.equals("apps")) return "=== user@example.com Apps\nmy-app (us)\nmy-api (eu)";
        if (sub.equals("logs")) return "2025-03-22T10:00:00+00:00 heroku[web.1]: Starting process with command `node index.js`\n2025-03-22T10:00:01+00:00 app[web.1]: Listening on port 5000\n2025-03-22T10:00:02+00:00 heroku[web.1]: State changed from starting to up";
        if (sub.equals("version")) return "heroku/8.7.1 linux-x64 node-v18.17.1";
        return "(heroku " + join(args, " ") + ")";
    }
    private String cmdVercel(String[] args) {
        if (args.length == 0) return "Vercel CLI\nUsage: vercel [options] [command]\nCommands: deploy, dev, env, domains, dns, logs, projects";
        if (args[0].equals("--version")) return "Vercel CLI 32.5.0";
        if (args[0].equals("deploy")) return "Vercel CLI 32.5.0\n> Deploying ~/project\n> Using project my-project\n> Linked to team-name/my-project\ni Building...\n> Build completed\ni Assigning domains...\n> Production: https://my-project.vercel.app [copied to clipboard] [2s]";
        if (args[0].equals("dev")) return "Vercel CLI 32.5.0\n> Ready! Available at http://localhost:3000";
        return "(vercel " + join(args, " ") + ")";
    }
    private String cmdNetlify(String[] args) {
        if (args.length == 0) return "Netlify CLI\nUsage: netlify [command]\nCommands: deploy, dev, env, link, login, open, sites, status";
        if (args[0].equals("deploy")) return "Deploy path: ./dist\nConfiguration path: netlify.toml\nDeploying to main site URL...\n\u2714 Finished hashing 42 files\n\u2714 CDN requesting 8 files\n\u2714 Finishing deployment\n\u2554\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2557\n\u2551        Netlify Build Complete          \u2551\n\u255A\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u255D\nhttps://my-site.netlify.app";
        if (args[0].equals("dev")) return "\u25C8 Netlify Dev \u25C8\n\u25C8 Server listening to 3000";
        return "(netlify " + join(args, " ") + ")";
    }

    // ?? Databases ?????????????????????????????????????????????????????????????
    private String cmdMysql(String[] args) {
        if (args.length == 0) return "Usage: mysql [OPTIONS] [database]\nOptions: -u user, -p, -h host, -e \"query\"";
        return "Welcome to the MySQL monitor.  Commands end with ; or \\g.\nYour MySQL connection id is 8\nServer version: 8.0.35 MySQL Community Server - GPL\n\nmysql> ";
    }
    private String cmdMysqldump(String[] args) {
        if (args.length == 0) return "Usage: mysqldump [OPTIONS] database [tables]";
        return "-- MySQL dump 10.13  Distrib 8.0.35\n-- Host: localhost    Database: " + (args.length > 0 ? args[0] : "mydb") + "\n-- Server version\t8.0.35\n\n/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;\n\n-- Table structure for table `users`\nCREATE TABLE `users` (\n  `id` int NOT NULL AUTO_INCREMENT,\n  `name` varchar(100) DEFAULT NULL,\n  PRIMARY KEY (`id`)\n);\n\n-- Dump completed on 2025-03-22 10:00:00";
    }
    private String cmdPsql(String[] args) {
        if (args.length == 0) return "psql (15.4)\nType \"help\" for help.\n\npostgres=# ";
        return "psql (15.4)\nSSL connection\nType \"help\" for help.\n\npostgres=# ";
    }
    private String cmdPgdump(String[] args) {
        if (args.length == 0) return "Usage: pg_dump [OPTION]... [DBNAME]";
        return "-- PostgreSQL database dump\n-- Dumped from database version 15.4\n-- Dumped by pg_dump version 15.4\n\nSET statement_timeout = 0;\nSET lock_timeout = 0;\nSET client_encoding = 'UTF8';\n\n-- PostgreSQL database dump complete";
    }
    private String cmdSqlite3(String[] args) {
        if (args.length == 0) return "SQLite version 3.42.0 2023-05-16\nEnter \".help\" for usage hints.\nConnected to a transient in-memory database.\nsqlite> ";
        return "SQLite version 3.42.0\nsqlite> ";
    }
    private String cmdRedisCli(String[] args) {
        if (args.length == 0) return "127.0.0.1:6379> ";
        if (args[0].equals("ping")) return "PONG";
        if (args[0].equals("-v") || args[0].equals("--version")) return "Redis CLI 7.2.1";
        if (args.length > 0 && args[0].equals("get") && args.length > 1) return "(nil)";
        if (args.length > 0 && args[0].equals("set") && args.length > 2) return "OK";
        if (args[0].equals("info")) return "# Server\nredis_version:7.2.1\nredis_mode:standalone\nos:Linux 5.15.0\narch_bits:64\n# Clients\nconnected_clients:1\n# Memory\nused_memory:870312\nused_memory_human:849.91K\n# Stats\ntotal_commands_processed:12345";
        return "(redis-cli " + join(args, " ") + ")";
    }
    private String cmdMongo(String[] args) {
        if (args.length == 0) return "Current Mongosh Log ID:\tABCDEF1234567890\nConnecting to:\t\tmongodb://127.0.0.1:27017/\nMongoSH Beta 2.0.2\n\ntest> ";
        return "(mongo " + join(args, " ") + ")";
    }
    private String cmdInflux(String[] args) {
        if (args.length == 0) return "InfluxDB shell version: 2.7.1\nConnected to http://localhost:8086 version 2.7.1\n> ";
        return "(influx " + join(args, " ") + ")";
    }
    private String cmdCqlsh(String[] args) {
        return "Connected to Test Cluster at 127.0.0.1:9042.\n[cqlsh 6.1.0 | Cassandra 4.1.0 | CQL spec 3.4.6]\nUse HELP for help.\ncqlsh> ";
    }
    private String cmdNeo4j(String[] args) {
        if (args.length == 0) return "Usage: neo4j <command>\nCommands: start, stop, restart, status, console";
        String sub = args[0];
        if (sub.equals("start")) return "Directories in use:\n  home:         /var/lib/neo4j\n  config:       /etc/neo4j\nStarting Neo4j.....started at pid 1234";
        if (sub.equals("status")) return "Neo4j is running at pid 1234";
        if (sub.equals("version")) return "neo4j 5.12.0";
        return "(neo4j " + join(args, " ") + ")";
    }

    // ?? Languages / Build Tools ???????????????????????????????????????????????
    private String cmdCargo(String[] args) {
        if (args.length == 0) return "Rust's package manager\nUsage: cargo [options] <command> [<args>...]\nCommands: new, build, run, test, check, add, update, publish, install";
        String sub = args[0];
        if (sub.equals("new")) return "     Created binary (application) `" + (args.length > 1 ? args[1] : "hello") + "` package";
        if (sub.equals("build")) return "   Compiling hello v0.1.0\n    Finished dev [unoptimized + debuginfo] target(s) in 0.52s";
        if (sub.equals("run")) return "   Compiling hello v0.1.0\n    Finished dev [unoptimized + debuginfo] target(s) in 0.30s\n     Running `target/debug/hello`\nHello, world!";
        if (sub.equals("test")) return "   Compiling hello v0.1.0\n    Finished test [unoptimized + debuginfo] target(s) in 0.44s\n     Running unittests src/main.rs\nrunning 2 tests\ntest tests::it_works ... ok\ntest tests::test_add ... ok\ntest result: ok. 2 passed; 0 failed; 0 ignored;";
        if (sub.equals("--version") || sub.equals("version")) return "cargo 1.73.0 (9c99c39d5 2023-08-15)";
        return "(cargo " + join(args, " ") + ")";
    }
    private String cmdRustc(String[] args) {
        if (args.length == 0) return "Usage: rustc [OPTIONS] INPUT";
        if (args[0].equals("--version")) return "rustc 1.73.0 (cc66ad468 2023-10-03)";
        return "(rustc " + join(args, " ") + ")";
    }
    private String cmdGo(String[] args) {
        if (args.length == 0) return "Go is a tool for managing Go source code.\nUsage: go <command> [arguments]\nCommands: build, test, run, get, install, mod, fmt, vet";
        String sub = args[0];
        if (sub.equals("version")) return "go version go1.21.3 linux/amd64";
        if (sub.equals("build")) return "(building...)";
        if (sub.equals("run") && args.length > 1) return "(running " + args[1] + "...)";
        if (sub.equals("test")) return "ok  \t" + (args.length > 1 ? args[1] : "./...") + "\t0.123s";
        if (sub.equals("mod") && args.length > 1 && args[1].equals("init")) return "go: creating new go.mod: module " + (args.length > 2 ? args[2] : "example.com/hello");
        if (sub.equals("fmt")) return "";
        return "(go " + join(args, " ") + ")";
    }
    private String cmdRuby(String[] args) {
        if (args.length == 0) return "Usage: ruby [options] [--] [programfile] [arguments]";
        if (args[0].equals("-v") || args[0].equals("--version")) return "ruby 3.2.2 (2023-03-30 revision e51014f9d0) [x86_64-linux]";
        if (args[0].equals("-e") && args.length > 1) return "(executing: " + args[1] + ")";
        return "(ruby " + join(args, " ") + ")";
    }
    private String cmdGem(String[] args) {
        if (args.length == 0) return "RubyGems is a package manager for Ruby.\nUsage: gem command [arguments...] [options]\nCommands: install, uninstall, list, search, update, push, build, help";
        String sub = args[0];
        if (sub.equals("install") && args.length > 1) return "Fetching " + args[1] + "-1.0.0.gem\nSuccessfully installed " + args[1] + "-1.0.0\n1 gem installed";
        if (sub.equals("list")) return "*** LOCAL GEMS ***\nbundler (2.4.22)\npuma (6.3.1)\nrake (13.0.6)\nrails (7.1.1)";
        if (sub.equals("--version")) return "3.4.22";
        return "(gem " + join(args, " ") + ")";
    }
    private String cmdMvn(String[] args) {
        if (args.length == 0) return "Usage: mvn [options] [<goal(s)>] [<phase(s)>]\nPhases: clean, compile, test, package, install, deploy";
        String goal = args[0];
        if (goal.equals("--version") || goal.equals("-v")) return "Apache Maven 3.9.4\nMaven home: /opt/maven\nJava version: 17.0.8, vendor: Eclipse Adoptium";
        return "[INFO] Scanning for projects...\n[INFO]\n[INFO] ------------------< com.example:my-project >------------------\n[INFO] Building my-project 1.0-SNAPSHOT\n[INFO] --------------------------------[ jar ]---------------------------------\n[INFO]\n[INFO] BUILD SUCCESS\n[INFO] Total time:  2.534 s";
    }
    private String cmdGradle(String[] args) {
        if (args.length == 0) return "Usage: gradle [option...] [task...]\nTasks: build, test, clean, assemble, check, dependencies";
        if (args[0].equals("--version")) return "\n------------------------------------------------------------\nGradle 8.4\n------------------------------------------------------------\nBuild time:   2023-10-04\nKotlin:       1.9.10\nJVM:          17.0.8";
        return "\n> Task :compileJava\n> Task :processResources\n> Task :classes\n> Task :jar\n> Task :" + args[0] + "\n\nBUILD SUCCESSFUL in 3s\n4 actionable tasks: 4 executed";
    }
    private String cmdKotlin(String[] args) {
        if (args.length == 0 || args[0].equals("-version")) return "kotlinc-jvm 1.9.10 (JRE 17.0.8)";
        return "(kotlinc " + join(args, " ") + ")";
    }
    private String cmdScala(String[] args) {
        if (args.length == 0 || args[0].equals("-version")) return "Scala code runner version 3.3.1 -- Copyright 2002-2023, LAMP/EPFL";
        return "(scala " + join(args, " ") + ")";
    }
    private String cmdSwift(String[] args) {
        if (args.length == 0 || args[0].equals("--version")) return "swift-driver version: 1.87.1 Apple Swift version 5.9 (swiftlang-5.9.0.128.106 clang-1500.0.40.1)";
        return "(swift " + join(args, " ") + ")";
    }
    private String cmdPerl(String[] args) {
        if (args.length == 0) return "Usage: perl [switches] [--] [programfile] [arguments]";
        if (args[0].equals("-v")) return "\nThis is perl 5, version 36, subversion 0 (v5.36.0)";
        if (args[0].equals("-e") && args.length > 1) return "(executing: " + args[1] + ")";
        return "(perl " + join(args, " ") + ")";
    }
    private String cmdPhp(String[] args) {
        if (args.length == 0) return "Usage: php [options] [-f] <file> [--] [args...]";
        if (args[0].equals("-v") || args[0].equals("--version")) return "PHP 8.2.10 (cli) (built: Sep  1 2023 07:35:14) (NTS)\nCopyright (c) The PHP Group";
        if (args[0].equals("-r") && args.length > 1) return "(executing: " + args[1] + ")";
        return "(php " + join(args, " ") + ")";
    }
    private String cmdLua(String[] args) {
        if (args.length == 0) return "Lua 5.4.6  Copyright (C) 1994-2023 Lua.org, PUC-Rio";
        if (args[0].equals("-v")) return "Lua 5.4.6  Copyright (C) 1994-2023 Lua.org, PUC-Rio";
        return "(lua " + join(args, " ") + ")";
    }
    private String cmdComposer(String[] args) {
        if (args.length == 0) return "Composer version 2.6.5\nUsage: composer [command]\nCommands: install, update, require, remove, show, init, create-project";
        String sub = args[0];
        if (sub.equals("--version")) return "Composer version 2.6.5 2023-10-06";
        if (sub.equals("install")) return "Installing dependencies from lock file (including require-dev)\nPackage operations: 24 installs, 0 updates, 0 removals\n  - Installing symfony/console (v6.3.4): Extracting archive\nGenerating autoload files\n24 packages you are using are looking for funding.";
        return "(composer " + join(args, " ") + ")";
    }
    private String cmdBundle(String[] args) {
        if (args.length == 0) return "Bundler version 2.4.22\nUsage: bundle [command]\nCommands: install, update, exec, init, show, list";
        if (args[0].equals("install")) return "Using bundler 2.4.22\nUsing rake 13.0.6\nUsing rails 7.1.1\nBundle complete! 12 Gemfile dependencies, 89 gems now installed.";
        return "(bundle " + join(args, " ") + ")";
    }
    private String cmdNpx(String[] args) {
        if (args.length == 0) return "Usage: npx [options] <command>[@version] [command-arg]...";
        return "(running: " + join(args, " ") + ")";
    }
    private String cmdNvm(String[] args) {
        if (args.length == 0) return "Node Version Manager\nUsage: nvm [command]\nCommands: install, use, ls, alias, current, uninstall";
        if (args[0].equals("ls")) return "->    v18.17.1\n      v20.9.0\n         system";
        if (args[0].equals("current")) return "v18.17.1";
        if (args[0].equals("use") && args.length > 1) return "Now using node v" + args[1] + " (npm v9.8.1)";
        if (args[0].equals("install") && args.length > 1) return "Downloading and installing node v" + args[1] + "...\nnvm is not compatible with the npm config \"prefix\" option\nCreating default alias: default -> " + args[1];
        return "(nvm " + join(args, " ") + ")";
    }
    private String cmdYarn(String[] args) {
        if (args.length == 0) return "Usage: yarn [command]\nCommands: add, remove, install, run, start, build, test, upgrade, global";
        String sub = args[0];
        if (sub.equals("--version")) return "1.22.21";
        if (sub.equals("install")) return "yarn install v1.22.21\n[1/4] Resolving packages...\n[2/4] Fetching packages...\n[3/4] Linking dependencies...\n[4/4] Building fresh packages...\nDone in 12.34s.";
        if (sub.equals("add") && args.length > 1) return "yarn add v1.22.21\n[1/4] Resolving packages...\nsuccess Saved 1 new dependency.\ndone in 3.45s.";
        return "(yarn " + join(args, " ") + ")";
    }
    private String cmdPnpm(String[] args) {
        if (args.length == 0) return "Usage: pnpm [command]\nCommands: install, add, remove, run, start, build, test, update, dlx";
        if (args[0].equals("--version")) return "8.9.0";
        if (args[0].equals("install")) return "Lockfile is up to date, resolution step is skipped\nPackages: +423\n++++++++++++++++++++++++++++++++++++++++++++\nProgress: resolved 423, reused 423, downloaded 0, added 423, done\nDone in 5.2s";
        return "(pnpm " + join(args, " ") + ")";
    }
    private String cmdTsc(String[] args) {
        if (args.length == 0) return "Version 5.2.2\nSyntax: tsc [options] [file...]\nExamples: tsc --init, tsc --build, tsc hello.ts";
        if (args[0].equals("--version")) return "Version 5.2.2";
        if (args[0].equals("--init")) return "message TS6071: Successfully created a tsconfig.json file.";
        return "(tsc: compiling...)";
    }
    private String cmdVite(String[] args) {
        if (args.length == 0) return "Vite v5.0.0\nUsage: vite [command] [options]\nCommands: dev, build, preview, optimize";
        if (args[0].equals("--version")) return "vite/5.0.0 linux-x64 node-v18.17.1";
        if (args[0].equals("build")) return "vite v5.0.0 building for production...\n\u2713 42 modules transformed.\ndist/index.html                  0.45 kB\ndist/assets/index-DiwrgTda.css   1.25 kB\ndist/assets/index-BN4bVlHf.js    142.36 kB\n\u2713 built in 1.24s";
        if (args[0].equals("dev")) return "  VITE v5.0.0  ready in 523 ms\n  \u279C  Local:   http://localhost:5173/\n  \u279C  Network: use --host to expose";
        return "(vite " + join(args, " ") + ")";
    }
    private String cmdWebpack(String[] args) {
        if (args.length == 0 || args[0].equals("--version")) return "webpack 5.88.2\nwebpack-cli 5.1.4";
        return "asset main.js 148 KiB [emitted] [minimized] (name: main)\nruntime modules 663 bytes 3 modules\ncacheable modules 394 KiB\n  modules by path ./node_modules/ 394 KiB\nwebpack 5.88.2 compiled successfully in 2341 ms";
    }
    private String cmdEslint(String[] args) {
        if (args.length == 0) return "Usage: eslint [options] file.js [file.js] [dir]\nOptions: --fix, --format, --rule, --config, --ignore-path";
        return "(eslinting " + join(args, " ") + " ... no problems found)";
    }
    private String cmdPylint(String[] args) {
        if (args.length == 0) return "Usage: pylint [options] <file or directory>";
        return "Your code has been rated at 9.50/10 (previous run: 9.00/10, +0.50)";
    }
    private String cmdFlake8(String[] args) {
        if (args.length == 0) return "Usage: flake8 [options] file [files]";
        return "(no style issues found)";
    }
    private String cmdMypy(String[] args) {
        if (args.length == 0) return "Usage: mypy [options] <source files>";
        return "Success: no issues found in 1 source file";
    }
    private String cmdBlack(String[] args) {
        if (args.length == 0) return "Usage: black [options] src [src ...]";
        return "All done! \u2728 \uD83C\uDF70 \u2728\n1 file left unchanged.";
    }
    private String cmdPytest(String[] args) {
        return "============================= test session starts ==============================\nplatform linux -- Python 3.11.0, pytest-7.4.3, pluggy-1.3.0\ncollected 5 items\n\ntest_app.py .....                                                        [100%]\n\n============================== 5 passed in 0.12s ===============================";
    }
    private String cmdJupyter(String[] args) {
        if (args.length == 0) return "Usage: jupyter <subcommand> [options]\nSubcommands: notebook, lab, kernelspec, nbconvert, console";
        if (args[0].equals("notebook") || args[0].equals("lab")) return "[I 2025-03-22 10:00:00.000 ServerApp] Jupyter Server 2.7.3 is running at:\n[I 2025-03-22 10:00:00.001 ServerApp] http://localhost:8888/lab?token=abc123\n[I 2025-03-22 10:00:00.001 ServerApp]     http://127.0.0.1:8888/lab?token=abc123";
        return "(jupyter " + join(args, " ") + ")";
    }
    private String cmdConda(String[] args) {
        if (args.length == 0) return "usage: conda [-h] [-V] command\ncommands: create, activate, deactivate, install, update, remove, list, env";
        String sub = args[0];
        if (sub.equals("--version")) return "conda 23.7.4";
        if (sub.equals("list")) return "# packages in environment at /opt/conda:\nName                    Version\nnumpy                   1.26.0\npandas                  2.1.1\nscikit-learn            1.3.1\nmatplotlib              3.8.0";
        if (sub.equals("activate")) return "(activating environment: " + (args.length > 1 ? args[1] : "base") + ")";
        return "(conda " + join(args, " ") + ")";
    }
    private String cmdVenv(String[] args) {
        if (args.length == 0) return "usage: venv [-h] [--system-site-packages] [--clear] dir";
        return "(virtual environment created at: " + args[0] + ")";
    }
    private String cmdPyenv(String[] args) {
        if (args.length == 0) return "Usage: pyenv <command> [<args>]\nCommands: install, uninstall, versions, version, global, local, shell";
        if (args[0].equals("versions")) return "  system\n  3.9.18\n  3.10.13\n* 3.11.6 (set by /home/user/.python-version)";
        if (args[0].equals("version")) return "3.11.6 (set by /home/user/.python-version)";
        return "(pyenv " + join(args, " ") + ")";
    }
    private String cmdRails(String[] args) {
        if (args.length == 0) return "Usage: rails COMMAND [ARGS]\nCommands: new, server, generate, db, routes, console, test, assets, credentials";
        String sub = args[0];
        if (sub.equals("--version")) return "Rails 7.1.1";
        if (sub.equals("new")) return "      create  " + (args.length > 1 ? args[1] : "app") + "\n      create  " + (args.length > 1 ? args[1] : "app") + "/README.md\n      create  " + (args.length > 1 ? args[1] : "app") + "/Gemfile\nRun `bundle install` to install dependencies.";
        if (sub.equals("server") || sub.equals("s")) return "=> Booting Puma\n=> Rails 7.1.1 application starting in development\n=> Run `bin/rails server --help` for more startup options\nPuma starting...\n* Listening on http://127.0.0.1:3000";
        return "(rails " + join(args, " ") + ")";
    }
    private String cmdGh(String[] args) {
        if (args.length == 0) return "Work seamlessly with GitHub from the command line.\nUsage: gh <command> <subcommand> [flags]\nCommands: repo, pr, issue, gist, workflow, release, auth, config";
        String sub = args[0];
        if (sub.equals("repo") && args.length > 1 && args[1].equals("list")) return "user/my-project\tPublic\n0 stars\n2 forks\nUpdated about now\n\nuser/dotfiles\tPrivate\n0 stars\n0 forks\nUpdated 2 weeks ago";
        if (sub.equals("pr") && args.length > 1 && args[1].equals("list")) return "Showing 2 of 2 pull requests in user/my-project\n\n#3  Fix typo in README    feature/fix-typo  about 1 hour ago\n#2  Add dark mode          feature/dark-mode  about 2 days ago";
        if (sub.equals("--version")) return "gh version 2.37.0 (2023-10-10)";
        return "(gh " + join(args, " ") + ")";
    }
    private String cmdGlab(String[] args) {
        if (args.length == 0) return "A GitLab CLI Tool.\nUsage: glab [command]\nCommands: repo, mr, issue, ci, release, auth";
        if (args[0].equals("--version")) return "glab version 1.36.0 (2023-10-01)";
        return "(glab " + join(args, " ") + ")";
    }
    private String cmdHub(String[] args) {
        if (args.length == 0) return "hub is a command-line wrapper for git.\nUsage: hub [--noop] COMMAND [OPTIONS]\nCommands: clone, fork, pull-request, issue, browse, compare, ci-status";
        return "(hub " + join(args, " ") + ")";
    }

    // ?? System / Misc Tools ???????????????????????????????????????????????????
    private String cmdWhois(String[] args) {
        if (args.length == 0) return "Usage: whois <domain or IP>";
        String target = args[0];
        return "Domain Name: " + target.toUpperCase() + "\nRegistry Domain ID: D12345678-LROR\nRegistrar: GoDaddy.com, LLC\nUpdated Date: 2024-01-01T00:00:00Z\nCreation Date: 2010-06-15T04:00:00Z\nRegistry Expiry Date: 2025-06-15T04:00:00Z\nRegistrant Country: US\nName Server: NS1." + target.toUpperCase() + "\nName Server: NS2." + target.toUpperCase() + "\nDNSSEC: unsigned";
    }
    private String cmdStrace(String[] args) {
        if (args.length == 0) return "Usage: strace [-options] command [args]";
        return "execve(\"/bin/ls\", [\"ls\"], 0x7ffd5...) = 0\nbrk(NULL)                               = 0x5629a1234000\nmmap(NULL, 8192, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0) = 0x7f8b7ef00000\nopen(\"/etc/ld.so.cache\", O_RDONLY|O_CLOEXEC) = 3\nfstat(3, {st_mode=S_IFREG|0644, st_size=25432, ...}) = 0\nmmap(NULL, 25432, PROT_READ, MAP_PRIVATE, 3, 0) = 0x7f8b7ee00000\nclose(3)                                = 0\n+++ exited with 0 +++";
    }
    private String cmdSplit(String[] args) {
        if (args.length == 0) return "Usage: split [options] [input [prefix]]\nOptions: -l lines, -b bytes, -n chunks";
        return "(splitting " + args[args.length-1] + " ...)";
    }
    private String cmdNl(String[] args) {
        if (args.length == 0) return "Usage: nl [options] [file]";
        return "     1\tLine one\n     2\tLine two\n     3\tLine three";
    }
    private String cmdOd(String[] args) {
        if (args.length == 0) return "Usage: od [options] [file]";
        return "0000000 062550 066554 020157 067567 066162 020144 067524 071163\n0000020 020164 066550 064540 071547 030411 067124 066545 066163\n0000040\n";
    }
    private String cmdRev(String[] args) {
        if (args.length == 0) return "(rev: reads from stdin)";
        return new StringBuffer(join(args, " ")).reverse().toString();
    }
    private String cmdTac(String[] args) {
        if (args.length == 0) return "Usage: tac [file]";
        return "(tac: concatenating files in reverse)";
    }
    private String cmdFmt(String[] args) {
        return "(fmt: reformatting text)";
    }
    private String cmdFold(String[] args) {
        return "(fold: wrapping lines)";
    }
    private String cmdExpand(String[] args) {
        return "(expand: converting tabs to spaces)";
    }
    private String cmdDos2unix(String[] args) {
        if (args.length == 0) return "Usage: dos2unix <file>";
        return "dos2unix: converting file " + args[0] + " to Unix format...";
    }
    private String cmdUnix2dos(String[] args) {
        if (args.length == 0) return "Usage: unix2dos <file>";
        return "unix2dos: converting file " + args[0] + " to DOS format...";
    }
    private String cmdIconv(String[] args) {
        if (args.length == 0) return "Usage: iconv [options] [-f encoding] [-t encoding] [input]";
        return "(iconv: converting encoding)";
    }
    private String cmdTruncate(String[] args) {
        if (args.length == 0) return "Usage: truncate [options] file";
        return "(truncated)";
    }
    private String cmdShred(String[] args) {
        if (args.length == 0) return "Usage: shred [options] file";
        return "shred: " + (args.length > 0 ? args[args.length-1] : "file") + ": pass 1/3 (random)...\nshred: pass 2/3 (0x00)...\nshred: pass 3/3 (random)...";
    }
    private String cmdSleep(String[] args) {
        if (args.length == 0) return "Usage: sleep NUMBER[SUFFIX]";
        return "";
    }
    private String cmdXz(String[] args) {
        if (args.length == 0) return "Usage: xz [options] [files]";
        return "(xz: compressing " + (args.length > 0 ? args[args.length-1] : "file") + ")";
    }
    private String cmdZstd(String[] args) {
        if (args.length == 0) return "Usage: zstd [args] [FILE(s)]\nCompression: zstd file\nDecompression: zstd -d file.zst";
        return "(zstd: processing " + args[args.length-1] + ")";
    }
    private String cmdRar(String[] args) {
        if (args.length == 0) return "Usage: rar <command> [options] <archive> [files]";
        if (args[0].equals("a") && args.length > 1) return "\nRAR 6.23\nCreating archive " + args[1] + "\nAdding files...\nDone";
        return "(rar " + join(args, " ") + ")";
    }
    private String cmdUnrar(String[] args) {
        if (args.length == 0) return "Usage: unrar <command> [-<switches>] <archive> [files...]";
        return "UNRAR 6.23\nExtracting from " + (args.length > 1 ? args[1] : "archive.rar") + "\nAll OK";
    }
    private String cmd7z(String[] args) {
        if (args.length == 0) return "7-Zip 23.01 (x64)\nUsage: 7z <command> [<switches>...] <archive_name> [<file_names>...]\nCommands: a=add, d=delete, e=extract, l=list, t=test, u=update, x=extract with paths";
        String sub = args[0];
        if (sub.equals("l") && args.length > 1) return "7-Zip 23.01\n\nScanning the drive for archives:\n1 file, 1024 bytes\n\nListing archive: " + args[1] + "\n\nDate      Time    Attr         Size   Compressed  Name\n2025-03-22 10:00:00 ....A         1234          567  file.txt\n1 files, 1234 bytes";
        if (sub.equals("a") && args.length > 1) return "7-Zip 23.01\nCreating archive: " + args[1] + "\nItems to compress: " + (args.length-2) + "\nEverything is Ok";
        if (sub.equals("x") || sub.equals("e")) return "7-Zip 23.01\nEverything is Ok\nFiles: 1\nSize: 1234";
        if (sub.equals("t") && args.length > 1) return "7-Zip 23.01\nTesting archive: " + args[1] + "\nEverything is Ok";
        return "(7z " + join(args, " ") + ")";
    }
    private String cmdJq(String[] args) {
        if (args.length == 0) return "Usage: jq [options] <jq filter> [file...]";
        return "{\n  \"result\": \"ok\",\n  \"data\": null\n}";
    }
    private String cmdYq(String[] args) {
        if (args.length == 0) return "Usage: yq [flags] <expression> [file.yaml]";
        if (args[0].equals("--version")) return "yq (https://github.com/mikefarah/yq/) version v4.35.1";
        return "(yq: processing YAML)";
    }
    private String cmdXmllint(String[] args) {
        if (args.length == 0) return "Usage: xmllint [options] xmlfile";
        return "file validates";
    }
    private String cmdRipgrep(String[] args) {
        if (args.length == 0) return "Usage: rg [OPTIONS] PATTERN [PATH]";
        return "(searching with ripgrep...)";
    }
    private String cmdFd(String[] args) {
        if (args.length == 0) return "Usage: fd [OPTIONS] [PATTERN] [PATH]";
        return "(searching with fd...)";
    }
    private String cmdFzf(String[] args) {
        return "(fzf: interactive fuzzy finder - requires terminal input)";
    }
    private String cmdBat(String[] args) {
        if (args.length == 0) return "Usage: bat [options] [file]...";
        return cmdCat(args);
    }
    private String cmdGlances(String[] args) {
        return "Glances v3.4.0\nCPU   12.5%  | Load  0.45 0.52 0.61\nMEM   45.2%  | Swap  12.3%\nNET   eth0    Rx:  1.2 KB/s  Tx: 0.8 KB/s\nDISK  sda     R:   0 B/s    W: 2.1 KB/s\n\nPROCESSES (sorted by CPU%)\n PID   USER  CPU%  MEM%  NAME\n1234  root   5.2   2.1  python3\n 789  user   1.3   0.8  node";
    }
    private String cmdNmon(String[] args) {
        return "nmon version 16m for Linux\nCPU Utilization ========================%Busy\n  0%  10%  20%  30%  40%  50%  60%  70%  80%  90% 100%\nUser%   | ##                        |  12.5%\nSys%    | #                         |   5.2%\nWait%   |                           |   0.1%\nIdle%   | #####################     |  82.2%";
    }
    private String cmdIftop(String[] args) {
        return "interface: eth0\n                12.5Kb                 25.0Kb                  37.5Kb         50.0Kb\n 192.168.1.100           =>  192.168.1.1          1.23Kb  1.23Kb  1.23Kb\n                          <=                       456b    456b    456b\n--------------------------------------------------------------------------------\nTX:             cumulative  23.4KB   peak  1.23Kb   rates  1.23Kb  1.23Kb  1.23Kb\nRX:                          8.9KB            456b           456b    456b    456b\nTOTAL:                      32.3KB           1.66Kb         1.67Kb  1.67Kb  1.67Kb";
    }
    private String cmdNethogs(String[] args) {
        return "NetHogs version 0.8.7\n\n  PID USER     PROGRAM                     DEV        SENT      RECEIVED\n 1234 user     /usr/bin/node               eth0       1.234      0.456 KB/sec\n  789 www-data /usr/sbin/apache2           eth0       0.123      2.345 KB/sec\n\n  TOTAL                                              1.357      2.801 KB/sec";
    }
    private String cmdIotop(String[] args) {
        return "Total DISK READ:         0.00 B/s | Total DISK WRITE:        10.24 K/s\nCurrent DISK READ:       0.00 B/s | Current DISK WRITE:      40.96 K/s\n  TID  PRIO  USER     DISK READ  DISK WRITE  SWAPIN      IO    COMMAND\n  123  be/4  root      0.00 B/s   10.24 K/s  0.00 %  0.12 %  kworker/u4:1";
    }
    private String cmdPowertop(String[] args) {
        return "PowerTOP v2.14\n\nThe battery reports a discharge rate of 7.23 W\n\nEstimated power drain: 8.1W\n\nTop 5 power consumers:\n  Usage  Events/s  Category  Description\n  24.6%    150.0   Process   /usr/lib/firefox/firefox\n   5.2%     20.3   Process   /usr/bin/Xorg\n   1.1%      8.5   Process   /usr/bin/python3";
    }
    private String cmdSystemdAnalyze(String[] args) {
        if (args.length == 0) return "Startup finished in 1.423s (kernel) + 4.532s (userspace) = 5.955s\ngraphical.target reached after 4.489s in userspace";
        if (args[0].equals("blame")) return "  3.142s networkd-dispatcher.service\n  2.531s NetworkManager-wait-online.service\n  1.892s snapd.service\n  1.456s ModemManager.service\n   934ms apt-daily.service";
        return "(systemd-analyze " + join(args, " ") + ")";
    }
    private String cmdMtr(String[] args) {
        if (args.length == 0) return "Usage: mtr [options] <hostname>";
        String host = args[args.length-1];
        return "Start: " + host + "\nHOST: localhost                   Loss%   Snt   Last   Avg  Best  Wrst StDev\n  1.|-- _gateway                   0.0%    10    1.2   1.1   0.9   1.5   0.2\n  2.|-- 10.0.0.1                   0.0%    10    5.4   5.2   4.9   5.8   0.3\n  3.|-- " + host + "               0.0%    10   12.3  12.1  11.8  12.7   0.3";
    }
    private String cmdNping(String[] args) {
        if (args.length == 0) return "Usage: nping [options] <targets>";
        return "Starting Nping 0.7.94\nSENT (0.0301s) TCP 192.168.1.100:33000 > 192.168.1.1:80 S\nRCVD (0.0315s) TCP 192.168.1.1:80 > 192.168.1.100:33000 SA\nMax rtt: 1.4ms | Min rtt: 1.2ms | Avg rtt: 1.3ms";
    }
    private String cmdSocat(String[] args) {
        if (args.length == 0) return "Usage: socat [options] <address> <address>\nExample: socat TCP-LISTEN:4444,fork EXEC:/bin/bash";
        return "(socat: relay established)";
    }
    private String cmdOpenvpn(String[] args) {
        if (args.length == 0) return "Usage: openvpn [options]\nOptions: --config file, --dev tun, --remote host port, --daemon";
        return "OpenVPN 2.6.4 x86_64-pc-linux-gnu\nCurrent Parameter Settings:\n  config = 'client.ovpn'\nThu Mar 22 10:00:00 2025 NOTE: OpenVPN 2.1 requires '--script-security 2'\nThu Mar 22 10:00:01 2025 Initialization Sequence Completed";
    }
    private String cmdWireguard(String[] args) {
        if (args.length == 0) return "Usage: wg <cmd> [<args>]\nCommands: show, showconf, set, setconf, addconf, syncconf, genkey, genpsk, pubkey";
        if (args[0].equals("show")) return "interface: wg0\n  public key: AbCdEfGhIjKlMnOpQrStUvWxYz1234567890AbCdEfA=\n  private key: (hidden)\n  listening port: 51820\n\npeer: AbCdEfGhIjKlMnOpQrStUvWxYz1234567890AbCdEfB=\n  endpoint: 10.0.0.2:51820\n  allowed ips: 10.10.0.2/32\n  latest handshake: 1 minute, 23 seconds ago\n  transfer: 14.92 MiB received, 48.34 MiB sent";
        return "(wg " + join(args, " ") + ")";
    }
    private String cmdProxychains(String[] args) {
        if (args.length == 0) return "Usage: proxychains <command> [args]";
        return "ProxyChains-3.1 (http://proxychains.sf.net)\n|DNS-request| " + (args.length > 0 ? args[0] : "target") + "\n|S-chain|-<>-127.0.0.1:9050-<><>-" + (args.length > 0 ? args[0] : "target") + ":80-<><>-OK";
    }
    private String cmdTor(String[] args) {
        if (args.length == 0) return "Usage: tor [OPTION value]...";
        return "Mar 22 10:00:00.000 [notice] Tor 0.4.8.7 opening log file.\nMar 22 10:00:01.000 [notice] Bootstrapped 5% (conn): Connecting to a relay\nMar 22 10:00:02.000 [notice] Bootstrapped 10% (conn_done): Connected to a relay\nMar 22 10:00:05.000 [notice] Bootstrapped 100% (done): Done";
    }
    private String cmdCsvkit(String[] args) {
        if (args.length == 0) return "csvkit tools: csvcut, csvjoin, csvlook, csvmerge, csvsort, csvstack, csvstat";
        return "(csvkit: processing CSV data)";
    }
    private String cmdFfmpeg(String[] args) {
        if (args.length == 0) return "Usage: ffmpeg [options] [[infile options] -i infile]... {[outfile options] outfile}...";
        if (args[0].equals("-version")) return "ffmpeg version 6.0 Copyright (c) 2000-2023 the FFmpeg developers";
        return "ffmpeg version 6.0\nInput #0, mov,mp4,m4a, from '" + (args.length > 2 ? args[args.length-1] : "input.mp4") + "':\nOutput #0, mp4, to 'output.mp4':\nframe= 1234 fps= 30 q=28.0 Lsize=   12345kB time=00:00:41.13 speed=1.23x\nvideo:12000kB audio:300kB subtitle:0kB other streams:0kB";
    }
    private String cmdFfprobe(String[] args) {
        if (args.length == 0) return "Usage: ffprobe [options] input_file";
        return "ffprobe version 6.0\nInput #0, mov,mp4,m4a, from 'video.mp4':\n  Duration: 00:01:30.00, start: 0.000000, bitrate: 2048 kb/s\n    Stream #0:0(und): Video: h264 (Main), yuv420p, 1920x1080, 30 fps\n    Stream #0:1(und): Audio: aac, 44100 Hz, stereo, fltp, 128 kb/s";
    }
    private String cmdSox(String[] args) {
        if (args.length == 0) return "Usage: sox [global-options] [format-options] infile [format-options] outfile\nSoX version 14.4.2";
        return "(sox: processing audio)";
    }
    private String cmdAplay(String[] args) {
        if (args.length == 0) return "Usage: aplay [flags] [file...]";
        if (args[0].equals("-l")) return "**** List of PLAYBACK Hardware Devices ****\ncard 0: PCH [HDA Intel PCH], device 0: ALC3246 Analog [ALC3246 Analog]\n  Subdevices: 1/1\n  Subdevice #0: subdevice #0";
        return "(playing: " + args[0] + ")";
    }
    private String cmdConvert(String[] args) {
        if (args.length == 0) return "Usage: convert [options ...] file [ [options ...] file ...] [options ...] file";
        return "(ImageMagick: converting " + args[0] + " -> " + (args.length > 1 ? args[args.length-1] : "output") + ")";
    }
    private String cmdIdentify(String[] args) {
        if (args.length == 0) return "Usage: identify [options ...] input-file [input-file ...]";
        return (args.length > 0 ? args[0] : "image.png") + " PNG 1920x1080 1920x1080+0+0 8-bit sRGB 2.54MB 0.000u 0:00.000";
    }
    private String cmdExiftoolAlias(String[] args) { return cmdExiftool(args); }

    // ?? Misc fun / games ??????????????????????????????????????????????????????
    private String cmdMatrix(String[] args) {
        StringBuffer sb = new StringBuffer();
        String chars = "\uFF8A\uFF90\uFF8B\uFF70\uFF73\uFF7C\uFF85\uFF93\uFF86\uFF7B\uFF9C\uFF82\uFF75\uFF98\uFF71\uFF8E\uFF83\uFF8F\uFF79\uFF92\uFF74\uFF76\uFF77\uFF91\uFF95\uFF97\uFF7E\uFF88\uFF7D\uFF80\uFF87\uFF8D0123456789";
        java.util.Random rnd = new java.util.Random();
        for (int row = 0; row < 12; row++) {
            for (int col = 0; col < 40; col++) {
                sb.append(chars.charAt(rnd.nextInt(chars.length())));
            }
            sb.append("\n");
        }
        return sb.toString();
    }
    private String cmdStarWars(String[] args) {
        return "A long time ago in a galaxy far,\nfar away....\n\n\n          STAR WARS\n\n Episode IV - A NEW HOPE\n\nIt is a period of civil war. Rebel\nspaceships, striking from a hidden\nbase, have won their first victory\nagainst the evil Galactic Empire.\n\nDuring the battle, Rebel spies managed\nto steal secret plans to the Empire's\nultimate weapon, the DEATH STAR, an\narmored space station with enough\npower to destroy an entire planet.\n\nPursued by the Empire's sinister agents,\nPrincess Leia races home aboard her\nstarship, custodian of the stolen plans\nthat can save her people and restore\nfreedom to the galaxy....";
    }
    private String cmdSteamLocomotive(String[] args) {
        return "      ====        ________                ___________\n  _D _|  |_______/        \\__I_I_____===__|_______|\n   |(_)---  |   H\\________/ |   |        =|___ ___|      _________________\n   /     |  |   H  |  |     |   |         ||_| |_||     _|                \\_____A\n  |      |  |   H  |__--------------------| [___] |   =|                        |\n  | ________|___H__/__|_____/[][]~\\_______|       |   -|                        |\n  |/ |   |-----------I_____I [][] []  D   |=======|____|________________________|\n__/ =| o |=-~~\\  /~~\\  /~~\\  /~~\\ ____Y___________|__|__________________________|_\n |/-=|___|=    ||    ||    ||    |_____/~\\___/          |_D__D__D_|  |_D__D__D_|\n  \\_/      \\O=====O=====O=====O_/      \\_/               \\_/   \\_/    \\_/   \\_/";
    }
    private String cmdAsciiAquarium(String[] args) {
        return "  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~\n\n       ><(((\u00BA>                          <\u00BA)))><\n\n  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~\n\n                  ><>              <><\n\n  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~  ~";
    }
    private String cmdHack(String[] args) {
        return "Initializing hack sequence...\n[##########] 10%  Bypassing firewall...\n[##########] 25%  Cracking encryption...\n[##########] 50%  Accessing mainframe...\n[##########] 75%  Downloading files...\n[##########] 100% Access granted!\n\nWelcome to the mainframe.\n> _";
    }
    private String cmdLine(String[] args) {
        return "";
    }

    // ?? Misc system tools ??????????????????????????????????????????????????????
    private String cmdFsck(String[] args) {
        if (args.length == 0) return "Usage: fsck [options] [device]";
        return "fsck from util-linux 2.37.4\ne2fsck 1.46.5 (30-Dec-2021)\n" + (args.length > 0 ? args[0] : "/dev/sda1") + ": clean, 87432/3932160 files, 892341/15728640 blocks";
    }
    private String cmdMkfs(String[] args) {
        if (args.length == 0) return "Usage: mkfs [-t type] [options] device";
        return "mke2fs 1.46.5 (30-Dec-2021)\nCreating filesystem with 1048576 4k blocks and 262144 inodes\nFilesystem UUID: a1b2c3d4-e5f6-7890-abcd-ef1234567890\nAllocating group tables: done\nWriting inode tables: done\nCreating journal (16384 blocks): done\nWriting superblocks and filesystem accounting information: done";
    }
    private String cmdMdadm(String[] args) {
        if (args.length == 0) return "Usage: mdadm [mode] [options] <components>";
        return "(mdadm: managing software RAID)";
    }
    private String cmdLvm(String[] args) {
        if (args.length == 0) return "Usage: lvm <command> [options]\nCommands: pvcreate, vgcreate, lvcreate, pvdisplay, vgdisplay, lvdisplay";
        return "(lvm: " + join(args, " ") + ")";
    }
    private String cmdCryptsetup(String[] args) {
        if (args.length == 0) return "Usage: cryptsetup <action> [<options>] <action args>\nActions: luksFormat, luksOpen, luksClose, status, luksDump";
        return "(cryptsetup: " + join(args, " ") + ")";
    }
    private String cmdSmartctl(String[] args) {
        if (args.length == 0) return "Usage: smartctl [options] device";
        return "smartctl 7.3 2022-02-28 r5338\n=== START OF INFORMATION SECTION ===\nDevice Model:     Samsung SSD 870 EVO 500GB\nSerial Number:    S4EWNX0R123456\nFirmware Version: SVT21B6Q\nUser Capacity:    500,107,862,016 bytes [500 GB]\nSector Size:      512 bytes logical/physical\nRotation Rate:    Solid State Device\nSMART overall-health self-assessment test result: PASSED";
    }
    private String cmdHdparm(String[] args) {
        if (args.length == 0) return "Usage: hdparm [options] [device]";
        return "/dev/sda:\n Timing buffered disk reads: 1418 MB in  3.00 seconds = 472.43 MB/sec";
    }
    private String cmdNvme(String[] args) {
        if (args.length == 0) return "Usage: nvme <command> [<device>] [<args>]";
        if (args[0].equals("list")) return "Node                  SN                   Model                     Namespace Usage                      Format           FW Rev\n/dev/nvme0n1          S12345678901          Samsung SSD 980 PRO 1TB   1         125.00  GB / 1000.20  GB 512   B +  0 B   2B2QEXM7";
        return "(nvme " + join(args, " ") + ")";
    }
    private String cmdIpset(String[] args) {
        if (args.length == 0) return "Usage: ipset [options] COMMAND\nCommands: create, add, del, test, list, flush, destroy";
        return "(ipset: " + join(args, " ") + ")";
    }
    private String cmdNft(String[] args) {
        if (args.length == 0) return "Usage: nft [options] [command]\nCommands: list, add, delete, flush, ruleset";
        if (args[0].equals("list") && args.length > 1 && args[1].equals("ruleset")) return "table inet filter {\n  chain input {\n    type filter hook input priority 0;\n    iif lo accept\n    ct state established,related accept\n    tcp dport 22 accept\n    drop\n  }\n}";
        return "(nft: " + join(args, " ") + ")";
    }
    private String cmdEbtables(String[] args) {
        if (args.length == 0) return "Usage: ebtables [options] [chain] [rule]";
        return "(ebtables: Ethernet bridge frame table administration)";
    }
    private String cmdTc(String[] args) {
        if (args.length == 0) return "Usage: tc [options] object { command | help }";
        return "(tc: traffic control)";
    }
    private String cmdXdpyinfo(String[] args) {
        return "name of display:    :0\nversion number:    11.0\nvendor string:    The X.Org Foundation\nvendor release number:    12101007\nX.Org version: 1.21.1.7\nmaximum request size:  16777212 bytes\nmaximum screen dimensions:    65535x65535 pixels\nnumber of screens:    1\n\nscreen #0:\n  dimensions:    1920x1080 pixels (508x285 millimeters)\n  depth of root window:    24 planes";
    }
    private String cmdXrandr(String[] args) {
        if (args.length == 0 || args[0].equals("--query")) return "Screen 0: minimum 320 x 200, current 1920 x 1080, maximum 16384 x 16384\neDP-1 connected primary 1920x1080+0+0 (normal left inverted right x axis y axis) 309mm x 173mm\n   1920x1080     60.05*+  59.93\n   1280x720      60.00    59.94\nHDMI-1 disconnected (normal left inverted right x axis y axis)";
        return "(xrandr: " + join(args, " ") + ")";
    }
    private String cmdCertbot(String[] args) {
        if (args.length == 0) return "Usage: certbot [SUBCOMMAND] [options]\nSubcommands: run, certonly, renew, certificates, delete, revoke";
        String sub = args[0];
        if (sub.equals("certonly") || sub.equals("run")) return "Saving debug log to /var/log/letsencrypt/letsencrypt.log\nRequesting a certificate for example.com\nSuccessfully received certificate.\nCertificate is saved at: /etc/letsencrypt/live/example.com/fullchain.pem\nKey is saved at:         /etc/letsencrypt/live/example.com/privkey.pem\nThis certificate expires on 2025-06-22.";
        if (sub.equals("renew")) return "Processing /etc/letsencrypt/renewal/example.com.conf\nCertificate not yet due for renewal\n1 renew skipped, next renewal is 2025-06-22";
        if (sub.equals("certificates")) return "Found the following certs:\n  Certificate Name: example.com\n    Domains: example.com www.example.com\n    Expiry Date: 2025-06-22 (VALID: 90 days)\n    Certificate Path: /etc/letsencrypt/live/example.com/fullchain.pem";
        return "(certbot " + join(args, " ") + ")";
    }
    private String cmdNginx(String[] args) {
        if (args.length == 0) return "nginx version: nginx/1.24.0\nUsage: nginx [-?hvVtTq] [-s signal] [-p prefix] [-e filename] [-c filename] [-g directives]";
        if (args[0].equals("-v") || args[0].equals("-V")) return "nginx version: nginx/1.24.0\nbuilt with OpenSSL 3.0.2 15 Mar 2022";
        if (args[0].equals("-t")) return "nginx: the configuration file /etc/nginx/nginx.conf syntax is ok\nnginx: configuration file /etc/nginx/nginx.conf test is successful";
        if (args[0].equals("-s")) return "(nginx signal: " + (args.length > 1 ? args[1] : "?") + ")";
        return "(nginx: " + join(args, " ") + ")";
    }
    private String cmdHaproxy(String[] args) {
        if (args.length == 0) return "Usage: haproxy [-f cfgfile]* [ -vdVD ] [ -n maxconn ] [ -N maxpconn ] [ -p pidfile ]";
        if (args[0].equals("-v")) return "HA-Proxy version 2.8.3-1 2023/09/08";
        return "(haproxy: starting proxy)";
    }
    private String cmdCaddy(String[] args) {
        if (args.length == 0) return "Usage: caddy <command>\nCommands: adapt, build, environ, file-server, fmt, hash-password, help, list-modules, manpage, reload, respond, reverse-proxy, run, start, stop, trust, untrust, upgrade, validate, version";
        if (args[0].equals("version")) return "v2.7.5 h1:HuueNp7kCy/Qb3GhzKRhsD5r/GIRgtjCIqKnEDqKqw0=";
        if (args[0].equals("run") || args[0].equals("start")) return "2025/03/22 10:00:00.000 INFO    using config from file {\"filename\":\"Caddyfile\"}\n2025/03/22 10:00:00.001 INFO    adapted config to JSON {\"adapter\":\"caddyfile\"}\n2025/03/22 10:00:00.002 INFO    serving initial configuration";
        return "(caddy " + join(args, " ") + ")";
    }
    private String cmdApache(String[] args) {
        return "Apache/2.4.57 (Ubuntu)\nUsage: apache2 [-D name] [-d directory] [-f file]\nCommands: -k start|restart|graceful|graceful-stop|stop";
    }
    private String cmdShowmount(String[] args) {
        return "Export list for server:\n/export/data     192.168.1.0/24\n/export/public   *";
    }
    private String cmdGrpcurl(String[] args) {
        if (args.length == 0) return "Usage: grpcurl [flags] [address] [list|describe] [symbol]\nUsage: grpcurl [flags] [address] package.Service/Method";
        return "(grpcurl: invoking gRPC service)";
    }
    private String cmdProtoc(String[] args) {
        if (args.length == 0) return "Usage: protoc [OPTION] PROTO_FILES";
        if (args[0].equals("--version")) return "libprotoc 24.4";
        return "(protoc: compiling .proto files)";
    }
    private String cmdSvn(String[] args) {
        if (args.length == 0) return "Type 'svn help' for usage.";
        if (args[0].equals("--version")) return "svn, version 1.14.2 (r1899510)";
        if (args[0].equals("status")) return "M       src/main.c\nA       src/util.c\n?       build/";
        if (args[0].equals("log")) return "------------------------------------------------------------------------\nr42 | user | 2025-03-22 10:00:00 +0000 | 1 line\n\nFix bug in main.c\n------------------------------------------------------------------------";
        return "(svn " + join(args, " ") + ")";
    }
    private String cmdHg(String[] args) {
        if (args.length == 0) return "Mercurial Distributed SCM\nbasic commands: add, annotate, clone, commit, diff, log, pull, push, status, update";
        if (args[0].equals("version")) return "Mercurial Distributed SCM (version 6.5.2)";
        if (args[0].equals("log")) return "changeset:   42:abc123def456\ntag:         tip\nuser:        user <user@example.com>\ndate:        Fri Mar 22 10:00:00 2025 +0000\nsummary:     Fix bug";
        return "(hg " + join(args, " ") + ")";
    }
    private String cmdCvs(String[] args) {
        if (args.length == 0) return "Usage: cvs [cvs-options] command [command-options-and-arguments]";
        return "(cvs " + join(args, " ") + ")";
    }
    private String cmdAcme(String[] args) {
        return "(acme.sh: ACME protocol client for certificate issuance)";
    }
    private String cmdAnt(String[] args) {
        if (args.length == 0) return "Apache Ant(TM) version 1.10.14\nUsage: ant [options] [target [target2 [target3] ...]]";
        if (args[0].equals("-version")) return "Apache Ant(TM) version 1.10.14 compiled on August 16 2023";
        return "Buildfile: build.xml\n\ninit:\n\ncompile:\n    [javac] Compiling 5 source files\n\njar:\n    [jar] Building jar: build/app.jar\n\nBUILD SUCCESSFUL\nTotal time: 2 seconds";
    }
    private String cmdPerf(String[] args) {
        if (args.length == 0) return "Usage: perf [--version] [--help] [--exec-path] COMMAND [ARGS]\nCommands: stat, record, report, annotate, top, bench, test";
        return "Performance counter stats for 'ls':\n         0.623882      task-clock (msec)     #    0.565 CPUs utilized\n                0      context-switches      #    0.000 K/sec\n                0      cpu-migrations        #    0.000 K/sec\n               68      page-faults           #    0.109 M/sec\n        1,345,678      cycles                #    2.157 GHz\n        1,234,567      instructions          #    0.92  insn per cycle\n       0.001103785 seconds time elapsed";
    }
    private String cmdAudit(String[] args) {
        return "time->Fri Mar 22 10:00:00 2025\ntype=SYSCALL msg=audit(1711101600.123:456): arch=c000003e syscall=59 success=yes exit=0 a0=55d3a1b2c3d0 a1=55d3a1b2c440 a2=55d3a1b2c3e0 a3=8 items=3 ppid=1234 pid=5678 auid=1000 uid=1000 gid=1000 euid=1000 suid=1000 fsuid=1000 egid=1000 sgid=1000 fsgid=1000 tty=pts0 ses=1 comm=\"ls\" exe=\"/bin/ls\" key=\"execve\"";
    }
    private String cmdInotify(String[] args) {
        return "Setting up watches.\nWatches established.\n/home/user/Documents/ CREATE new_file.txt\n/home/user/Documents/ CLOSE_WRITE,CLOSE new_file.txt\n/home/user/Documents/ MODIFY existing_file.txt";
    }
    private String cmdAt(String[] args) {
        if (args.length == 0) return "Usage: at <time>\nExample: at now + 5 minutes";
        return "warning: commands will be executed using /bin/sh\njob 1 at Fri Mar 22 10:05:00 2025";
    }
    private String cmdGetCommand(String[] args) {
        if (args.length == 0) return "Usage: get-command <name>";
        return "CommandType  Name   Version  Source\n-----------  ----   -------  ------\nCmdlet       " + (args.length > 0 ? args[0] : "Get-Command") + "  7.3.0    Microsoft.PowerShell.Core";
    }
    private String cmdGetHelp(String[] args) {
        if (args.length == 0) return "Usage: get-help <cmdlet-name>";
        return "NAME\n    " + (args.length > 0 ? args[0] : "Get-Help") + "\n\nSYNOPSIS\n    Displays information about PowerShell commands and concepts.\n\nSYNTAX\n    Get-Help [[-Name] <String>] [-Category <String[]>]";
    }
    private String cmdGetService(String[] args) {
        return "Status   Name               DisplayName\n------   ----               -----------\nRunning  WinRM              Windows Remote Management (WS-Manag...\nStopped  XblAuthManager     Xbox Live Auth Manager";
    }
    private String cmdPowershell(String[] args) {
        if (args.length == 0) return "Windows PowerShell\nCopyright (C) Microsoft Corporation. All rights reserved.\n\nPS C:\\Users\\User>";
        if (args[0].equals("-version") || args[0].equals("--version")) return "Major  Minor  Build  Revision\n-----  -----  -----  --------\n7      3      0      -1";
        return "(powershell " + join(args, " ") + ")";
    }
    private String cmdYtDlp(String[] args) {
        if (args.length == 0) return "Usage: yt-dlp [OPTIONS] URL [URL...]";
        return "[youtube] Extracting URL: " + (args.length > 0 ? args[0] : "URL") + "\n[youtube] Video ID: dQw4w9WgXcQ: Downloading webpage\n[youtube] Video ID: dQw4w9WgXcQ: Downloading ios player API JSON\n[info] dQw4w9WgXcQ: Downloading 1 format(s): 22\n[download] Destination: Rick Astley - Never Gonna Give You Up [dQw4w9WgXcQ].mp4\n[download] 100% of  60.54MiB in 00:00:10 at 6.00MiB/s";
    }
    private String cmdXdpyinfoAlias(String[] args) { return cmdXdpyinfo(args); }

    private String cmdAr(String[] args) {
        if (args.length == 0) return "Usage: ar [options] archive [member...]\nOptions: r=replace, x=extract, t=list, d=delete, c=create";
        if (args[0].indexOf("t") >= 0 && args.length > 1) return "rw-r--r-- 0/0   1234 Jan  1 00:00 2025 main.o\nrw-r--r-- 0/0    567 Jan  1 00:00 2025 util.o";
        if (args[0].indexOf("x") >= 0) return "(extracted members from archive)";
        return "ar: creating " + (args.length > 1 ? args[1] : "archive.a");
    }

    private String cmdTune2fs(String[] args) {
        if (args.length == 0) return "Usage: tune2fs [-l] [-c max-mount-counts] [-i interval] device";
        if (args[0].equals("-l") && args.length > 1) return "tune2fs 1.46.5 (30-Dec-2021)\nFilesystem volume name:   <none>\nLast mounted on:          /\nFilesystem UUID:          a1b2c3d4-e5f6-7890-abcd-ef1234567890\nFilesystem magic number:  0xEF53\nFilesystem revision #:    1 (dynamic)\nFilesystem features:      has_journal ext_attr resize_inode dir_index\nFilesystem state:         clean\nErrors behavior:          Continue\nFilesystem OS type:       Linux\nInode count:              3932160\nBlock count:              15728640\nFree blocks:              8234521\nFree inodes:              3421876\nFirst block:              0\nBlock size:               4096\nFragment size:            4096\nMount count:              42\nMaximum mount count:      -1\nLast checked:             Mon Mar 22 10:00:00 2025";
        return "(tune2fs: " + join(args, " ") + ")";
    }
}