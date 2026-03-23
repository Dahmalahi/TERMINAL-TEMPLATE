import java.util.*;

/**
 * Virtual Filesystem - simulates a Unix/Linux directory tree in memory.
 * Compatible with CLDC 1.1 (no java.util.HashMap with generics, uses Hashtable).
 */
public class VirtualFS {

    // Node types
    public static final int TYPE_DIR  = 0;
    public static final int TYPE_FILE = 1;

    private Hashtable nodes;     // path -> String[] {type, owner, perms, content, size, mtime}
    private String currentPath;
    private String homeDir;
    private String username;
    private String hostname;

    // Index constants for node data array
    private static final int N_TYPE    = 0;
    private static final int N_OWNER   = 1;
    private static final int N_PERMS   = 2;
    private static final int N_CONTENT = 3;
    private static final int N_SIZE    = 4;
    private static final int N_MTIME   = 5;

    public VirtualFS(String user, String host) {
        this.username = user;
        this.hostname = host;
        this.homeDir  = "/home/" + user;
        nodes = new Hashtable();
        buildInitialFS();
        currentPath = homeDir;
    }

    private void buildInitialFS() {
        // Core directory structure
        mkdirInternal("/");
        mkdirInternal("/bin");
        mkdirInternal("/boot");
        mkdirInternal("/dev");
        mkdirInternal("/etc");
        mkdirInternal("/etc/network");
        mkdirInternal("/home");
        mkdirInternal("/home/" + username);
        mkdirInternal("/home/" + username + "/Desktop");
        mkdirInternal("/home/" + username + "/Documents");
        mkdirInternal("/home/" + username + "/Downloads");
        mkdirInternal("/home/" + username + "/Pictures");
        mkdirInternal("/lib");
        mkdirInternal("/media");
        mkdirInternal("/mnt");
        mkdirInternal("/opt");
        mkdirInternal("/proc");
        mkdirInternal("/root");
        mkdirInternal("/sbin");
        mkdirInternal("/sys");
        mkdirInternal("/tmp");
        mkdirInternal("/usr");
        mkdirInternal("/usr/bin");
        mkdirInternal("/usr/lib");
        mkdirInternal("/usr/local");
        mkdirInternal("/usr/share");
        mkdirInternal("/var");
        mkdirInternal("/var/log");
        mkdirInternal("/var/www");
        mkdirInternal("/var/www/html");

        // /etc files
        mkfileInternal("/etc/hostname",   hostname + "\n",                             "root", "-rw-r--r--");
        mkfileInternal("/etc/issue",      "Ubuntu 22.04.3 LTS \\n \\l\n",             "root", "-rw-r--r--");
        mkfileInternal("/etc/os-release",
            "NAME=\"Ubuntu\"\nVERSION=\"22.04.3 LTS (Jammy Jellyfish)\"\nID=ubuntu\n" +
            "ID_LIKE=debian\nPRETTY_NAME=\"Ubuntu 22.04.3 LTS\"\n" +
            "VERSION_ID=\"22.04\"\nHOME_URL=\"https://www.ubuntu.com/\"\n",
            "root", "-rw-r--r--");
        mkfileInternal("/etc/passwd",
            "root:x:0:0:root:/root:/bin/bash\n" +
            "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n" +
            username + ":x:1000:1000:" + username + ",,,:/home/" + username + ":/bin/bash\n",
            "root", "-rw-r--r--");
        mkfileInternal("/etc/shadow",
            "root:!:19600:0:99999:7:::\n" + username + ":$6$xyz:19600:0:99999:7:::\n",
            "root", "-rw-r-----");
        mkfileInternal("/etc/hosts",
            "127.0.0.1\tlocalhost\n127.0.1.1\t" + hostname + "\n::1\tlocalhost ip6-localhost\n",
            "root", "-rw-r--r--");
        mkfileInternal("/etc/fstab",
            "# <file system> <mount point> <type> <options> <dump> <pass>\n" +
            "UUID=abc123 /               ext4    errors=remount-ro 0 1\n" +
            "UUID=def456 /boot           ext4    defaults          0 2\n" +
            "/dev/sda2   none            swap    sw                0 0\n",
            "root", "-rw-r--r--");
        mkfileInternal("/etc/network/interfaces",
            "auto lo\niface lo inet loopback\nauto eth0\niface eth0 inet dhcp\n",
            "root", "-rw-r--r--");
        mkfileInternal("/etc/crontab",
            "# m h dom mon dow user  command\n17 * * * * root  cd / && run-parts --report /etc/cron.hourly\n",
            "root", "-rw-r--r--");

        // /proc virtual files
        mkfileInternal("/proc/version",
            "Linux version 5.15.0-88-generic (buildd@lcy02-amd64-020) (gcc (Ubuntu 11.4.0-1ubuntu1~22.04) 11.4.0, GNU ld (GNU Binutils for Ubuntu) 2.38) #98-Ubuntu SMP Mon Oct 2 15:18:56 UTC 2023\n",
            "root", "-r--r--r--");
        mkfileInternal("/proc/cpuinfo",
            "processor\t: 0\nvendor_id\t: GenuineIntel\ncpu family\t: 6\nmodel\t\t: 142\n" +
            "model name\t: Intel(R) Core(TM) i5-8250U CPU @ 1.60GHz\ncpu MHz\t\t: 1800.000\n" +
            "cache size\t: 6144 KB\nbogomips\t: 3600.00\n",
            "root", "-r--r--r--");
        mkfileInternal("/proc/meminfo",
            "MemTotal:        8051524 kB\nMemFree:         1234567 kB\nMemAvailable:    3456789 kB\n" +
            "Buffers:          123456 kB\nCached:          2345678 kB\nSwapTotal:       2097148 kB\nSwapFree:        2097148 kB\n",
            "root", "-r--r--r--");
        mkfileInternal("/proc/uptime", "3721.45 14234.67\n", "root", "-r--r--r--");
        mkfileInternal("/proc/loadavg", "0.15 0.10 0.08 1/423 12345\n", "root", "-r--r--r--");

        // /var/log files
        mkfileInternal("/var/log/syslog",
            "Mar 22 10:01:01 " + hostname + " kernel: [    0.000000] Initializing cgroup subsys cpuset\n" +
            "Mar 22 10:01:01 " + hostname + " kernel: [    0.000000] Linux version 5.15.0-88-generic\n" +
            "Mar 22 10:01:03 " + hostname + " systemd[1]: Started OpenSSH Server Daemon.\n" +
            "Mar 22 10:01:04 " + hostname + " sshd[1234]: Server listening on 0.0.0.0 port 22.\n",
            "root", "-rw-r-----");
        mkfileInternal("/var/log/auth.log",
            "Mar 22 10:05:12 " + hostname + " sshd[2345]: Accepted publickey for " + username + " from 192.168.1.5\n" +
            "Mar 22 10:05:12 " + hostname + " sshd[2345]: pam_unix(sshd:session): session opened for user " + username + "\n",
            "root", "-rw-r-----");

        // /var/www/html
        mkfileInternal("/var/www/html/index.html",
            "<!DOCTYPE html>\n<html>\n<head><title>Welcome</title></head>\n" +
            "<body><h1>It works!</h1></body>\n</html>\n",
            "root", "-rw-r--r--");

        // Home directory files
        mkfileInternal(homeDir + "/.bashrc",
            "# ~/.bashrc: executed by bash(1) for non-login shells.\n" +
            "PS1='${debian_chroot:+($debian_chroot)}\\u@\\h:\\w\\$ '\n" +
            "alias ll='ls -alF'\nalias la='ls -A'\nalias l='ls -CF'\n" +
            "alias grep='grep --color=auto'\nexport PATH=\"$HOME/.local/bin:$PATH\"\n",
            username, "-rw-r--r--");
        mkfileInternal(homeDir + "/.bash_history",
            "ls -la\npwd\ncd /etc\ncat /etc/passwd\nifconfig\nping 8.8.8.8\nnmap -sV localhost\nsudo su\n",
            username, "-rw-------");
        mkfileInternal(homeDir + "/.profile",
            "# ~/.profile: executed by the command interpreter for login shells.\n" +
            "if [ -n \"$BASH_VERSION\" ]; then\n  if [ -f \"$HOME/.bashrc\" ]; then\n    . \"$HOME/.bashrc\"\n  fi\nfi\n",
            username, "-rw-r--r--");
        mkfileInternal(homeDir + "/readme.txt",
            "Welcome to J2ME Terminal!\n" +
            "========================\n" +
            "A full Unix/Linux terminal emulator for J2ME devices.\n\n" +
            "Type 'help' for a list of available commands.\n" +
            "Supports: ls, cd, cat, mkdir, rm, cp, mv, pwd, echo,\n" +
            "          grep, find, ps, kill, top, df, du, uname,\n" +
            "          whoami, id, chmod, chown, date, cal, history,\n" +
            "          env, export, alias, which, whereis, and more!\n",
            username, "-rw-r--r--");
        mkfileInternal(homeDir + "/Documents/notes.txt",
            "Personal Notes\n--------------\nRemember to update /etc/hosts\nCheck crontab entries\nReview firewall rules\n",
            username, "-rw-r--r--");
        mkfileInternal(homeDir + "/Documents/todo.txt",
            "TODO List:\n[ ] Configure SSH keys\n[ ] Set up backup cron job\n[ ] Update system packages\n[x] Install vim\n",
            username, "-rw-r--r--");

        // bin executables (empty content, just for ls/which)
        String[] bins = {"bash","sh","ls","cat","grep","find","cp","mv","rm","mkdir","rmdir",
                         "chmod","chown","chgrp","ln","touch","echo","pwd","env","export",
                         "ps","kill","top","df","du","uname","whoami","id","date","cal",
                         "history","alias","which","whereis","man","nano","vi","vim",
                         "ping","ifconfig","ip","netstat","ss","curl","wget","ssh","scp",
                         "nmap","traceroute","arp","route","dig","nslookup","host","nc",
                         "iptables","ufw","tcpdump","wireshark",
                         "apt","apt-get","dpkg","yum","pacman","pip","python3","python",
                         "java","javac","node","npm","gcc","g++","make","git",
                         "tar","gzip","gunzip","zip","unzip","bzip2",
                         "head","tail","wc","sort","uniq","cut","awk","sed","tr","tee",
                         "clear","reset","exit","sudo","su","useradd","userdel","passwd",
                         "mount","umount","fdisk","lsblk","blkid","free","uptime","w","who",
                         "last","lastlog","lsof","strace","ltrace","file","stat","readlink",
                         "xargs","base64","md5sum","sha256sum","hexdump","strings",
                         "crontab","service","systemctl","journalctl","dmesg"};
        for (int i = 0; i < bins.length; i++) {
            mkfileInternal("/bin/" + bins[i], "", "root", "-rwxr-xr-x");
            mkfileInternal("/usr/bin/" + bins[i], "", "root", "-rwxr-xr-x");
        }
    }

    private void mkdirInternal(String path) {
        String[] node = new String[6];
        node[N_TYPE]    = "d";
        node[N_OWNER]   = "root";
        node[N_PERMS]   = "drwxr-xr-x";
        node[N_CONTENT] = "";
        node[N_SIZE]    = "4096";
        node[N_MTIME]   = "Mar 22 10:00";
        nodes.put(normalizePath(path), node);
    }

    private void mkfileInternal(String path, String content, String owner, String perms) {
        String[] node = new String[6];
        node[N_TYPE]    = "f";
        node[N_OWNER]   = owner;
        node[N_PERMS]   = perms;
        node[N_CONTENT] = content;
        node[N_SIZE]    = String.valueOf(content.length());
        node[N_MTIME]   = "Mar 22 10:00";
        nodes.put(normalizePath(path), node);
    }

    // -------  Public API -------

    public String getCurrentPath() { return currentPath; }
    public String getHomeDir()      { return homeDir; }
    public String getUsername()     { return username; }
    public String getHostname()     { return hostname; }

    public String getPrompt() {
        String display = currentPath;
        if (display.equals(homeDir)) display = "~";
        else if (display.startsWith(homeDir + "/")) display = "~" + display.substring(homeDir.length());
        return username + "@" + hostname + ":" + display + "$ ";
    }

    /** Resolve a path (absolute or relative) to absolute normalized form. */
    public String resolvePath(String path) {
        if (path == null || path.length() == 0) return currentPath;
        if (path.equals("~")) return homeDir;
        if (path.startsWith("~/")) return normalizePath(homeDir + "/" + path.substring(2));
        if (path.charAt(0) == '/') return normalizePath(path);
        return normalizePath(currentPath + "/" + path);
    }

    /** Normalize path: resolve .. and . and double slashes */
    public String normalizePath(String path) {
        if (path == null || path.length() == 0) return "/";
        // Split and rebuild
        Vector parts = new Vector();
        String[] segs = splitPath(path);
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i];
            if (s.equals("") || s.equals(".")) continue;
            if (s.equals("..")) {
                if (parts.size() > 0) parts.removeElementAt(parts.size() - 1);
            } else {
                parts.addElement(s);
            }
        }
        if (parts.size() == 0) return "/";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < parts.size(); i++) {
            sb.append("/");
            sb.append((String) parts.elementAt(i));
        }
        return sb.toString();
    }

    private String[] splitPath(String path) {
        Vector v = new Vector();
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == '/') {
                v.addElement(path.substring(start, i));
                start = i + 1;
            }
        }
        String[] arr = new String[v.size()];
        v.copyInto(arr);
        return arr;
    }

    public boolean exists(String absPath) {
        return nodes.containsKey(absPath);
    }

    public boolean isDir(String absPath) {
        if (!exists(absPath)) return false;
        String[] n = (String[]) nodes.get(absPath);
        return n[N_TYPE].equals("d");
    }

    public boolean isFile(String absPath) {
        if (!exists(absPath)) return false;
        String[] n = (String[]) nodes.get(absPath);
        return n[N_TYPE].equals("f");
    }

    public String readFile(String absPath) {
        if (!isFile(absPath)) return null;
        return ((String[]) nodes.get(absPath))[N_CONTENT];
    }

    public boolean writeFile(String absPath, String content) {
        if (isDir(absPath)) return false;
        if (!isFile(absPath)) {
            // create new
            String parent = parentOf(absPath);
            if (!isDir(parent)) return false;
        }
        mkfileInternal(absPath, content, username, "-rw-r--r--");
        return true;
    }

    public boolean appendFile(String absPath, String content) {
        if (isDir(absPath)) return false;
        if (isFile(absPath)) {
            String existing = readFile(absPath);
            return writeFile(absPath, existing + content);
        }
        return writeFile(absPath, content);
    }

    public boolean createDir(String absPath) {
        String parent = parentOf(absPath);
        if (!isDir(parent)) return false;
        if (exists(absPath)) return false;
        mkdirInternal(absPath);
        return true;
    }

    public boolean deleteNode(String absPath) {
        if (!exists(absPath)) return false;
        if (isDir(absPath)) {
            // Check empty
            if (listChildren(absPath).length > 0) return false;
        }
        nodes.remove(absPath);
        return true;
    }

    public boolean deleteRecursive(String absPath) {
        if (!exists(absPath)) return false;
        if (isDir(absPath)) {
            String[] children = listChildren(absPath);
            for (int i = 0; i < children.length; i++) {
                deleteRecursive(children[i]);
            }
        }
        nodes.remove(absPath);
        return true;
    }

    public boolean copyNode(String src, String dst) {
        if (!exists(src)) return false;
        if (isFile(src)) {
            String content = readFile(src);
            String[] srcNode = (String[]) nodes.get(src);
            mkfileInternal(dst, content, srcNode[N_OWNER], srcNode[N_PERMS]);
            return true;
        }
        return false;
    }

    public boolean moveNode(String src, String dst) {
        if (!exists(src)) return false;
        if (isDir(src)) {
            // Move directory - rebuild all children paths
            String[] children = listAllUnder(src);
            // Create dst dir
            mkdirInternal(dst);
            for (int i = 0; i < children.length; i++) {
                String child = children[i];
                String newChild = dst + child.substring(src.length());
                String[] node = (String[]) nodes.get(child);
                nodes.put(newChild, node);
                nodes.remove(child);
            }
            nodes.remove(src);
        } else {
            copyNode(src, dst);
            nodes.remove(src);
        }
        return true;
    }

    /** List direct children paths of a directory */
    public String[] listChildren(String absDir) {
        Vector v = new Vector();
        Enumeration keys = nodes.keys();
        while (keys.hasMoreElements()) {
            String key = (String) keys.nextElement();
            if (key.equals(absDir)) continue;
            String parent = parentOf(key);
            if (parent.equals(absDir)) {
                v.addElement(key);
            }
        }
        String[] arr = new String[v.size()];
        v.copyInto(arr);
        return arr;
    }

    /** List all descendants */
    private String[] listAllUnder(String absDir) {
        Vector v = new Vector();
        Enumeration keys = nodes.keys();
        while (keys.hasMoreElements()) {
            String key = (String) keys.nextElement();
            if (!key.equals(absDir) && key.startsWith(absDir + "/")) {
                v.addElement(key);
            }
        }
        String[] arr = new String[v.size()];
        v.copyInto(arr);
        return arr;
    }

    public String[] getNodeInfo(String absPath) {
        if (!exists(absPath)) return null;
        return (String[]) nodes.get(absPath);
    }

    public String getPerms(String absPath) {
        String[] n = getNodeInfo(absPath);
        return n != null ? n[N_PERMS] : "----------";
    }

    public String getOwner(String absPath) {
        String[] n = getNodeInfo(absPath);
        return n != null ? n[N_OWNER] : "?";
    }

    public String getSize(String absPath) {
        String[] n = getNodeInfo(absPath);
        return n != null ? n[N_SIZE] : "0";
    }

    public String getMtime(String absPath) {
        String[] n = getNodeInfo(absPath);
        return n != null ? n[N_MTIME] : "Jan  1 00:00";
    }

    /** Change current directory */
    public String cd(String path) {
        String target = resolvePath(path);
        if (!exists(target)) return "bash: cd: " + path + ": No such file or directory";
        if (!isDir(target)) return "bash: cd: " + path + ": Not a directory";
        currentPath = target;
        return null;
    }

    public String parentOf(String absPath) {
        if (absPath.equals("/")) return "/";
        int idx = absPath.lastIndexOf('/');
        if (idx == 0) return "/";
        return absPath.substring(0, idx);
    }

    public String nameOf(String absPath) {
        int idx = absPath.lastIndexOf('/');
        return absPath.substring(idx + 1);
    }

    /** Find matching paths by glob pattern */
    public String[] glob(String pattern) {
        String absPattern = resolvePath(pattern);
        // Simple wildcard support: * matches anything in segment
        Vector results = new Vector();
        Enumeration keys = nodes.keys();
        while (keys.hasMoreElements()) {
            String key = (String) keys.nextElement();
            if (matchGlob(absPattern, key)) results.addElement(key);
        }
        String[] arr = new String[results.size()];
        results.copyInto(arr);
        return arr;
    }

    private boolean matchGlob(String pattern, String str) {
        // Simple recursive glob: * = any chars in path segment (not /)
        return globMatch(pattern, 0, str, 0);
    }

    private boolean globMatch(String pat, int pi, String str, int si) {
        while (pi < pat.length() && si < str.length()) {
            char pc = pat.charAt(pi);
            char sc = str.charAt(si);
            if (pc == '*') {
                // Match zero or more non-slash chars
                while (si <= str.length()) {
                    if (globMatch(pat, pi + 1, str, si)) return true;
                    if (si < str.length() && str.charAt(si) == '/') break;
                    si++;
                }
                return false;
            } else if (pc == '?' ) {
                if (sc == '/') return false;
                pi++; si++;
            } else {
                if (pc != sc) return false;
                pi++; si++;
            }
        }
        while (pi < pat.length() && pat.charAt(pi) == '*') pi++;
        return pi == pat.length() && si == str.length();
    }
}
