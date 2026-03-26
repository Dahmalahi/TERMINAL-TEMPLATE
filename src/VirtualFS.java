import java.util.*;

/**
 * VirtualFS v1.1.1 - Virtual Filesystem for DashCMD
 * CLDC 1.1 / MIDP 2.0
 *
 * v1.1.1 additions:
 *  - Real device timestamps (System.currentTimeMillis()) on all file ops
 *  - RMS persistence: loadFromRMS() / saveToRMS()
 *  - Real /proc/cpuinfo & /proc/meminfo from device System properties
 *  - /boot/bootlog reads from AppStorage boot log
 *  - /dev/ contains runnable .sh scripts
 *  - Full directory tree matching spec
 */
public class VirtualFS {

    public static final int TYPE_DIR  = 0;
    public static final int TYPE_FILE = 1;

    private static final int N_TYPE    = 0;
    private static final int N_OWNER   = 1;
    private static final int N_PERMS   = 2;
    private static final int N_CONTENT = 3;
    private static final int N_SIZE    = 4;
    private static final int N_MTIME   = 5;
    private static final int N_GROUP   = 6;

    private Hashtable nodes;
    private Hashtable credentials; // user -> String[]{hash,uid,gid,home,shell,realname}
    private Hashtable symlinks;    // path -> target

    private String  currentPath;
    private String  homeDir;
    private String  username;
    private String  hostname;
    private boolean isRoot;

    public VirtualFS(String user, String host) {
        this.username    = user;
        this.hostname    = host;
        this.homeDir     = "/home/" + user;
        this.isRoot      = false;
        this.nodes       = new Hashtable();
        this.credentials = new Hashtable();
        this.symlinks    = new Hashtable();

        // Try to load persisted FS from RMS first
        Hashtable saved   = AppStorage.loadFS();
        Hashtable savedCreds = AppStorage.loadCredentials();

        if (saved.size() > 5) {
            // Existing install - restore from RMS
            nodes       = saved;
            credentials = savedCreds.size() > 0 ? savedCreds : credentials;
            if (credentials.size() == 0) buildCredentials();
            AppStorage.logBoot("INFO", "VirtualFS loaded from RMS (" + nodes.size() + " nodes)");
        } else {
            // Fresh install - build default FS
            buildInitialFS();
            buildCredentials();
            AppStorage.logBoot("INFO", "VirtualFS initialized (" + nodes.size() + " nodes)");
        }
        currentPath = homeDir;
    }

    /** Persist current FS state to RMS. Call after significant changes. */
    public void saveToRMS() {
        AppStorage.saveFS(nodes);
        AppStorage.saveCredentials(credentials);
        AppStorage.logBoot("INFO", "VirtualFS saved to RMS");
    }

    // ==================== CREDENTIALS ====================

    private void buildCredentials() {
        addCred("root",     "toor",   "0",    "0",    "/root",        "/bin/bash", "root");
        addCred(username,   "1234",   "1000", "1000", homeDir,        "/bin/bash", username);
        addCred("daemon",   "*",      "1",    "1",    "/usr/sbin",    "/usr/sbin/nologin", "Daemon");
        addCred("www-data", "*",      "33",   "33",   "/var/www",     "/usr/sbin/nologin", "www-data");
        addCred("guest",    "guest",  "1001", "1001", "/home/guest",  "/bin/sh",   "Guest");
    }

    private void addCred(String u, String p, String uid, String gid,
                          String home, String shell, String name) {
        credentials.put(u, new String[]{djb2(p), uid, gid, home, shell, name});
    }

    /** DJB2 hash, returns "$1$<hex>" or "*" for locked accounts. */
    private String djb2(String s) {
        if ("*".equals(s)) return "*";
        int h = 5381;
        for (int i = 0; i < s.length(); i++) h = ((h << 5) + h) + s.charAt(i);
        return "$1$" + Integer.toHexString(h & 0x7fffffff);
    }

    /** Attempt login. Returns null on success, error string on failure. */
    public String login(String user, String pass) {
        if (!credentials.containsKey(user)) return "Login failed: unknown user";
        String[] c = (String[]) credentials.get(user);
        if ("*".equals(c[0])) return "Login failed: account locked";
        if (!c[0].equals(djb2(pass))) return "Login failed: wrong password";
        username    = user;
        isRoot      = "0".equals(c[1]);
        homeDir     = c[3];
        currentPath = homeDir;
        return null;
    }

    /** Change password. Returns null on success, error string on failure. */
    public String changePassword(String user, String oldPass, String newPass) {
        if (!credentials.containsKey(user)) return "passwd: unknown user";
        String[] c = (String[]) credentials.get(user);
        if (!isRoot && !c[0].equals(djb2(oldPass))) return "passwd: authentication failure";
        if (newPass == null || newPass.length() < 4) return "passwd: password too short (min 4)";
        c[0] = djb2(newPass);
        rebuildShadow();
        return null;
    }

    /** Add new user. Returns null on success, error on failure. */
    public String addUser(String user, String pass) {
        if (!isRoot) return "useradd: permission denied";
        if (credentials.containsKey(user)) return "useradd: user '" + user + "' already exists";
        String uid  = String.valueOf(1002 + credentials.size());
        String home = "/home/" + user;
        addCred(user, pass, uid, uid, home, "/bin/bash", user);
        mkdirInternal(home);
        mkdirInternal(home + "/Documents");
        mkdirInternal(home + "/.ssh");
        mf(home + "/.bashrc",
            "# .bashrc for " + user + "\nPS1='\\u@\\h:\\w\\$ '\n", user, user, "-rw-r--r--");
        rebuildPasswd();
        rebuildShadow();
        return null;
    }

    /** Delete user. Returns null on success, error on failure. */
    public String delUser(String user) {
        if (!isRoot) return "userdel: permission denied";
        if (!credentials.containsKey(user)) return "userdel: user not found";
        if ("root".equals(user)) return "userdel: cannot delete root";
        credentials.remove(user);
        rebuildPasswd();
        rebuildShadow();
        return null;
    }

    public boolean userExists(String user) { return credentials.containsKey(user); }
    public String  getUid(String user) {
        return credentials.containsKey(user) ? ((String[])credentials.get(user))[1] : "1000";
    }
    public String  getGid(String user) {
        return credentials.containsKey(user) ? ((String[])credentials.get(user))[2] : "1000";
    }

    public void    setRoot(boolean r) { isRoot = r; }
    public boolean isRoot()           { return isRoot; }

    // ==================== FILESYSTEM BUILD ====================

    private void buildInitialFS() {
        // Full directory tree per v1.1.1 spec
        String[] dirs = {
            "/","/bin","/boot","/dev","/etc","/etc/network","/etc/ssh",
            "/etc/cron.d","/etc/apt","/etc/apt/sources.list.d","/etc/init.d",
            "/home","/home/"+username,
            "/home/"+username+"/Desktop","/home/"+username+"/Documents",
            "/home/"+username+"/Downloads","/home/"+username+"/Pictures",
            "/home/"+username+"/Music",
            "/home/"+username+"/.ssh","/home/"+username+"/.config",
            "/lib","/lib/systemd","/lib/systemd/system",
            "/media","/media/usb","/media/cdrom",
            "/mnt","/mnt/usb","/mnt/data",
            "/opt","/opt/dashcmd",
            "/proc","/proc/net",
            "/root","/root/.ssh","/sbin","/sys","/tmp",
            "/usr","/usr/bin","/usr/lib","/usr/local",
            "/usr/local/bin","/usr/share","/usr/share/man","/usr/share/doc",
            "/var","/var/log","/var/tmp","/var/cache","/var/run",
            "/var/www","/var/www/html",
            "/var/spool","/var/spool/cron","/var/spool/cron/crontabs",
            "/run","/srv"
        };
        for (int i = 0; i < dirs.length; i++) mkdirInternal(dirs[i]);

        // /etc
        mf("/etc/hostname",  hostname+"\n", "root","root","-rw-r--r--");
        mf("/etc/issue",     "DashCMD v1.1 / Ubuntu 22.04.3 LTS\n","root","root","-rw-r--r--");
        mf("/etc/os-release",
            "NAME=\"Ubuntu\"\nVERSION=\"22.04.3 LTS\"\nID=ubuntu\n"+
            "PRETTY_NAME=\"Ubuntu 22.04.3 LTS\"\nVERSION_ID=\"22.04\"\n"+
            "DASHCMD_VERSION=\"1.1\"\n","root","root","-rw-r--r--");
        mf("/etc/passwd",
            "root:x:0:0:root:/root:/bin/bash\n"+
            "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n"+
            "www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin\n"+
            username+":x:1000:1000:"+username+",,,:/home/"+username+":/bin/bash\n"+
            "guest:x:1001:1001:Guest:/home/guest:/bin/sh\n",
            "root","root","-rw-r--r--");
        mf("/etc/shadow",
            "root:$1$abc:19600:0:99999:7:::\n"+username+":$1$xyz:19600:0:99999:7:::\n",
            "root","shadow","-rw-r-----");
        mf("/etc/group",
            "root:x:0:\ndaemon:x:1:\nsudo:x:27:"+username+"\nadm:x:4:"+username+"\n"+
            username+":x:1000:\nwww-data:x:33:\nguest:x:1001:\n",
            "root","root","-rw-r--r--");
        mf("/etc/sudoers",
            "Defaults\tenv_reset\nroot\tALL=(ALL:ALL) ALL\n%sudo\tALL=(ALL:ALL) ALL\n"+
            username+"\tALL=(ALL) NOPASSWD:ALL\n",
            "root","root","-r--r-----");
        mf("/etc/hosts",
            "127.0.0.1\tlocalhost\n127.0.1.1\t"+hostname+"\n"+
            "::1\tlocalhost ip6-localhost ip6-loopback\n192.168.1.1\tgateway\n",
            "root","root","-rw-r--r--");
        mf("/etc/fstab",
            "# <fs> <mount> <type> <options> <dump> <pass>\n"+
            "UUID=abc123 /      ext4 errors=remount-ro 0 1\n"+
            "UUID=def456 /boot  ext4 defaults          0 2\n"+
            "/dev/sda2   none   swap sw                0 0\n",
            "root","root","-rw-r--r--");
        mf("/etc/crontab",
            "SHELL=/bin/sh\nPATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin\n"+
            "17 * * * * root  cd / && run-parts --report /etc/cron.hourly\n"+
            "25 6 * * * root  run-parts /etc/cron.daily\n",
            "root","root","-rw-r--r--");
        mf("/etc/cron.d/dashcmd",
            "0 2 * * * "+username+" /usr/local/bin/backup.sh\n",
            "root","root","-rw-r--r--");
        mf("/etc/ssh/sshd_config",
            "Port 22\nProtocol 2\nPermitRootLogin no\nPasswordAuthentication yes\n"+
            "PubkeyAuthentication yes\nAuthorizedKeysFile .ssh/authorized_keys\n"+
            "X11Forwarding yes\nSubsystem sftp /usr/lib/openssh/sftp-server\n",
            "root","root","-rw-r--r--");
        mf("/etc/motd",
            "Welcome to DashCMD v1.1 / Ubuntu 22.04.3 LTS\n"+
            "Type 'help' for commands.  Press 5 to open input.\n",
            "root","root","-rw-r--r--");
        mf("/etc/apt/sources.list",
            "deb http://archive.ubuntu.com/ubuntu jammy main restricted universe multiverse\n"+
            "deb http://archive.ubuntu.com/ubuntu jammy-updates main restricted\n"+
            "deb http://security.ubuntu.com/ubuntu jammy-security main restricted\n",
            "root","root","-rw-r--r--");
        mf("/etc/network/interfaces",
            "auto lo\niface lo inet loopback\nauto eth0\niface eth0 inet dhcp\n",
            "root","root","-rw-r--r--");

        // /proc
        mf("/proc/version",
            "Linux version 5.15.0-88-generic (gcc 11.4.0) #98-Ubuntu SMP Mon Oct 2 2023\n",
            "root","root","-r--r--r--");
        mf("/proc/cpuinfo",
            "processor\t: 0\nvendor_id\t: GenuineIntel\n"+
            "model name\t: Intel(R) Core(TM) i5-8250U CPU @ 1.60GHz\n"+
            "cpu MHz\t\t: 1800.000\ncache size\t: 6144 KB\ncpu cores\t: 4\n",
            "root","root","-r--r--r--");
        mf("/proc/meminfo",
            "MemTotal:        8051524 kB\nMemFree:         1234567 kB\n"+
            "MemAvailable:    3456789 kB\nCached:          2345678 kB\n"+
            "SwapTotal:       2097148 kB\nSwapFree:        2097148 kB\n",
            "root","root","-r--r--r--");
        mf("/proc/uptime",  "3721.45 14234.67\n","root","root","-r--r--r--");
        mf("/proc/loadavg", "0.15 0.10 0.08 1/423 12345\n","root","root","-r--r--r--");
        mf("/proc/partitions",
            "major minor  #blocks  name\n   8  0  488386584 sda\n   8  1  487374848 sda1\n",
            "root","root","-r--r--r--");
        mf("/proc/net/dev",
            "Inter-|  Receive               |  Transmit\n"+
            " face |bytes  packets errs drop|bytes  packets errs drop\n"+
            "    lo: 12345678 12345 0 0  12345678 12345 0 0\n"+
            "  eth0: 98765432 87654 0 0  23456789 23456 0 0\n",
            "root","root","-r--r--r--");

        // /var/log
        mf("/var/log/syslog",
            "Mar 22 10:01:01 "+hostname+" kernel: Initializing cgroup subsys cpuset\n"+
            "Mar 22 10:01:01 "+hostname+" kernel: Linux version 5.15.0-88-generic\n"+
            "Mar 22 10:01:03 "+hostname+" systemd[1]: Started OpenSSH Server Daemon.\n"+
            "Mar 22 10:05:00 "+hostname+" cron[456]: (CRON) daemon started\n",
            "root","adm","-rw-r-----");
        mf("/var/log/auth.log",
            "Mar 22 10:05:12 "+hostname+" sshd[2345]: Accepted publickey for "+username+"\n"+
            "Mar 22 10:10:00 "+hostname+" sudo: "+username+" : USER=root ; COMMAND=/bin/bash\n",
            "root","adm","-rw-r-----");
        mf("/var/log/dpkg.log",
            "2024-03-22 10:00:02 install vim:amd64 <none> 2:9.0.0749-1\n"+
            "2024-03-22 10:00:05 status installed vim:amd64 2:9.0.0749-1\n",
            "root","root","-rw-r--r--");
        mf("/var/log/kern.log",
            "Mar 22 10:00:01 "+hostname+" kernel: [ 0.000000] Booting Linux\n"+
            "Mar 22 10:00:01 "+hostname+" kernel: [ 0.000000] Linux version 5.15.0-88-generic\n",
            "root","adm","-rw-r-----");
        mf("/var/www/html/index.html",
            "<!DOCTYPE html>\n<html>\n<head><title>DashCMD Server</title></head>\n"+
            "<body><h1>DashCMD v1.1</h1><p>It works!</p></body>\n</html>\n",
            "www-data","www-data","-rw-r--r--");

        // systemd services
        mf("/lib/systemd/system/ssh.service",
            "[Unit]\nDescription=OpenBSD Secure Shell server\nAfter=network.target\n"+
            "[Service]\nExecStart=/usr/sbin/sshd -D\nRestart=on-failure\n"+
            "[Install]\nWantedBy=multi-user.target\n",
            "root","root","-rw-r--r--");
        mf("/lib/systemd/system/cron.service",
            "[Unit]\nDescription=Regular background program processing daemon\n"+
            "[Service]\nExecStart=/usr/sbin/cron -f\nRestart=on-failure\n"+
            "[Install]\nWantedBy=multi-user.target\n",
            "root","root","-rw-r--r--");
        mf("/lib/systemd/system/nginx.service",
            "[Unit]\nDescription=A high performance web server\n"+
            "[Service]\nExecStart=/usr/sbin/nginx\n"+
            "[Install]\nWantedBy=multi-user.target\n",
            "root","root","-rw-r--r--");

        // /var/run pids
        mf("/var/run/sshd.pid",  "1234\n","root","root","-rw-r--r--");
        mf("/var/run/cron.pid",  "456\n", "root","root","-rw-r--r--");
        mf("/var/run/nginx.pid", "999\n", "root","root","-rw-r--r--");

        // Crontab for user
        mf("/var/spool/cron/crontabs/"+username,
            "0 9 * * 1 /usr/local/bin/backup.sh\n",
            username,username,"-rw-------");

        // Home directory files
        mf(homeDir+"/.bashrc",
            "# DashCMD v1.1 .bashrc\nPS1='${debian_chroot:+($debian_chroot)}\\u@\\h:\\w\\$ '\n"+
            "alias ll='ls -alF'\nalias la='ls -A'\nalias l='ls -CF'\nalias ..='cd ..'\n"+
            "alias cls='clear'\nexport EDITOR=nano\nexport DASHCMD_VERSION=1.1\n",
            username,username,"-rw-r--r--");
        mf(homeDir+"/.bash_history",
            "ls -la\npwd\ncd /etc\ncat /etc/passwd\nifconfig\nping 8.8.8.8\n"+
            "nmap -sV localhost\nsudo su\npasswd\nsudo apt update\n",
            username,username,"-rw-------");
        mf(homeDir+"/.profile",
            "if [ -n \"$BASH_VERSION\" ]; then\n  [ -f \"$HOME/.bashrc\" ] && . \"$HOME/.bashrc\"\nfi\n",
            username,username,"-rw-r--r--");
        mf(homeDir+"/.ssh/authorized_keys",
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQC... "+username+"@"+hostname+"\n",
            username,username,"-rw-------");
        mf(homeDir+"/.ssh/known_hosts",
            "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI...\n",
            username,username,"-rw-r--r--");
        mf(homeDir+"/.ssh/id_rsa",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA... (simulated)\n-----END RSA PRIVATE KEY-----\n",
            username,username,"-rw-------");
        mf(homeDir+"/.ssh/id_rsa.pub",
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQC... "+username+"@"+hostname+"\n",
            username,username,"-rw-r--r--");
        mf(homeDir+"/readme.txt",
            "Welcome to DashCMD v1.1!\n"+
            "========================\n"+
            "New in v1.1:\n"+
            "  Multi-session: use 'New Session' command\n"+
            "  Login system: login, passwd, adduser, userdel\n"+
            "  Background tasks: bg, fg, jobs\n"+
            "  Real file I/O: cat, echo >, nano simulation\n"+
            "  Richer filesystem: /etc/ssh, cron.d, logs\n\n"+
            "Default credentials:\n"+
            "  "+username+" / 1234\n"+
            "  root     / toor\n"+
            "  guest    / guest\n\n"+
            "Type 'help' for all commands.\n",
            username,username,"-rw-r--r--");
        mf(homeDir+"/Documents/notes.txt",
            "Personal Notes\n--------------\nUpdate /etc/hosts after network change\n"+
            "SSH keys in ~/.ssh/\nCron jobs: crontab -e\n"+
            "Check logs: tail -f /var/log/syslog\n",
            username,username,"-rw-r--r--");
        mf(homeDir+"/Documents/todo.txt",
            "TODO:\n[x] Install DashCMD v1.1\n[ ] Configure SSH keys\n"+
            "[ ] Set up cron backup\n[ ] Update system packages\n[ ] Review firewall\n",
            username,username,"-rw-r--r--");
        mf(homeDir+"/Desktop/terminal.lnk",
            "[Desktop Entry]\nName=DashCMD\nExec=dashcmd\nType=Application\n",
            username,username,"-rw-r--r--");

        // Scripts
        mf("/usr/local/bin/backup.sh",
            "#!/bin/bash\nDATE=$(date +%Y%m%d)\n"+
            "tar czf /tmp/backup_$DATE.tar.gz /home/"+username+"/Documents\n"+
            "echo \"Backup done: /tmp/backup_$DATE.tar.gz\"\n",
            "root","root","-rwxr-xr-x");
        mf("/usr/local/bin/sysinfo.sh",
            "#!/bin/bash\necho '=== System ===' && uname -a\n"+
            "echo '=== Memory ===' && free -h\n"+
            "echo '=== Disk ===' && df -h\n"+
            "echo '=== Net ===' && ifconfig\n",
            "root","root","-rwxr-xr-x");
        mf("/usr/local/bin/dashcmd-update.sh",
            "#!/bin/bash\necho 'DashCMD v1.1 - checking for updates...'\n"+
            "echo 'You are up to date.'\n",
            "root","root","-rwxr-xr-x");

        // /bin and /usr/bin
        String[] bins = {
            "bash","sh","dash","ls","cat","grep","find","cp","mv","rm","mkdir","rmdir",
            "chmod","chown","chgrp","ln","touch","echo","printf","pwd","env","export",
            "ps","kill","killall","pkill","top","htop","df","du","uname","whoami","id",
            "date","cal","history","alias","which","whereis","man","nano","vi","vim",
            "ping","ifconfig","ip","netstat","ss","curl","wget","ssh","scp","rsync",
            "nmap","traceroute","arp","route","dig","nslookup","host","nc","ncat",
            "iptables","ip6tables","ufw","tcpdump",
            "apt","apt-get","apt-cache","dpkg","snap","pip","pip3","python3","python",
            "java","javac","node","npm","yarn","gcc","g++","make","cmake","git",
            "docker","kubectl","helm","terraform",
            "tar","gzip","gunzip","bzip2","xz","zip","unzip","7z",
            "head","tail","wc","sort","uniq","cut","awk","sed","tr","tee","xargs",
            "clear","reset","exit","sudo","su","useradd","userdel","usermod","passwd",
            "groupadd","groupdel","groups","login","newgrp",
            "mount","umount","fdisk","lsblk","blkid","free","uptime","w","who",
            "last","lastlog","lsof","strace","file","stat","readlink",
            "base64","md5sum","sha256sum","sha1sum","hexdump","xxd","strings",
            "crontab","at","service","systemctl","journalctl","dmesg","logger",
            "jobs","bg","fg","nohup","nice","sleep","wait",
            "diff","patch","dd","sync","fsck","mkfs",
            "openssl","gpg","ssh-keygen","ssh-copy-id","ssh-agent","ssh-add",
            "vmstat","iostat","sar","glances","htop"
        };
        for (int i = 0; i < bins.length; i++) {
            mf("/bin/"+bins[i],     "","root","root","-rwxr-xr-x");
            mf("/usr/bin/"+bins[i], "","root","root","-rwxr-xr-x");
        }

        // Symlinks
        symlinks.put("/bin/sh",          "/bin/bash");
        symlinks.put("/usr/bin/python",  "/usr/bin/python3");
        symlinks.put("/usr/bin/vi",      "/usr/bin/vim");
        symlinks.put("/etc/mtab",        "/proc/mounts");

        // ===== v1.1.1 NEW SECTIONS =====

        // /boot - boot logs
        mf("/boot/bootlog",
            AppStorage.readBootLog(),
            "root","root","-rw-r--r--");
        mf("/boot/config",
            "# DashCMD v1.1.1 boot config\nDEFAULT_USER="+username+"\nVERSION=1.1.1\nRMS=enabled\nNETWORK=enabled\n",
            "root","root","-rw-r--r--");
        mf("/boot/grub.cfg",
            "# GRUB Configuration\nmenuentry \"DashCMD v1.1.1\" {\n  linux /boot/vmlinuz root=/dev/sda1\n}\n",
            "root","root","-rw-r--r--");

        // /dev - device nodes and runnable scripts
        mf("/dev/null",    "","root","root","crw-rw-rw-");
        mf("/dev/zero",    "","root","root","crw-rw-rw-");
        mf("/dev/random",  "","root","root","crw-rw-rw-");
        mf("/dev/urandom", "","root","root","crw-rw-rw-");
        mf("/dev/tty",     "","root","root","crw-rw-rw-");
        mf("/dev/tty0",    "","root","root","crw--w----");
        mf("/dev/sda",     "","root","disk", "brw-rw----");
        mf("/dev/sda1",    "","root","disk", "brw-rw----");
        mf("/dev/mem",     "","root","kmem", "crw-r-----");
        // Runnable .sh scripts in /dev
        mf("/dev/hello.sh",
            "#!/bin/sh\n# Hello World script\necho 'Hello from DashCMD /dev/hello.sh!'\necho 'Running as: '$(whoami)\necho 'Date: '$(date)\n",
            "root","root","-rwxr-xr-x");
        mf("/dev/syscheck.sh",
            "#!/bin/sh\n# System check script\necho '=== DashCMD System Check ==='\necho 'Uptime:' $(uptime)\necho 'Memory:' $(free -h)\necho 'Disk:' $(df -h /)\necho 'Network:' $(ifconfig eth0)\necho 'Done.'\n",
            "root","root","-rwxr-xr-x");
        mf("/dev/nettest.sh",
            "#!/bin/sh\n# Network test\necho 'Testing network...'\nping -c 3 8.8.8.8\ncurl -I http://example.com\necho 'Network test complete.'\n",
            "root","root","-rwxr-xr-x");

        // Lua scripts in /dev
        mf("/dev/hello.lua",
            "-- DashCMD Lua 5.0 script\nprint(\"Hello from Lua!\")\nlocal x = 42\nprint(\"Answer: \" .. x)\nfor i=1,5 do\n  print(\"Line \" .. i)\nend\n",
            "root","root","-rwxr-xr-x");
        mf("/dev/calc.lua",
            "-- Lua calculator\nlocal a = 10\nlocal b = 32\nprint(a .. \" + \" .. b .. \" = \" .. (a+b))\nprint(a .. \" * \" .. b .. \" = \" .. (a*b))\nprint(\"sqrt(2) ~ 1.41421\")\n",
            "root","root","-rwxr-xr-x");
        mf("/dev/sysinfo.lua",
            "-- Lua system info\nprint(\"DashCMD v1.1.1\")\nprint(\"Lua 5.0 interpreter\")\nprint(\"Platform: J2ME MIDP 2.0\")\n",
            username,username,"-rwxr-xr-x");

        // BeanShell scripts in /dev
        mf("/dev/hello.bsh",
            "// BeanShell script\nprint(\"Hello from BeanShell!\");\nint x = 6 * 7;\nprint(\"6 * 7 = \" + x);\nString[] fruits = {\"apple\",\"banana\",\"cherry\"};\nfor (String f : fruits) print(f);\n",
            "root","root","-rwxr-xr-x");
        mf("/dev/sysinfo.bsh",
            "// BeanShell system info\nprint(\"DashCMD v1.1.1\");\nprint(\"J2ME CLDC 1.1 / MIDP 2.0\");\nlong t = System.currentTimeMillis();\nprint(\"Time (ms): \" + t);\n",
            "root","root","-rwxr-xr-x");

        // /mnt - mount points with info files
        mf("/mnt/usb/README",
            "USB Storage mount point.\nMount USB with: mount /dev/sdb1 /mnt/usb\n",
            "root","root","-rw-r--r--");
        mf("/mnt/data/README",
            "Data mount point.\nFor NFS: mount -t nfs server:/share /mnt/data\n",
            "root","root","-rw-r--r--");
        mf("/mnt/README",
            "Mount points directory.\nUSB:  /mnt/usb\nData: /mnt/data\n",
            "root","root","-rw-r--r--");

        // /opt - optional software
        mf("/opt/dashcmd/version",
            "DashCMD v1.1.1\nBuild: J2ME CLDC1.1/MIDP2.0\nFeatures: RMS,HTTP,JSR75,BeanShell,Lua\n",
            "root","root","-rw-r--r--");
        mf("/opt/dashcmd/install.log",
            AppStorage.formatTime(System.currentTimeMillis())+" Install completed\n",
            "root","root","-rw-r--r--");
        mf("/opt/README",
            "Optional software directory.\nDashCMD: /opt/dashcmd/\n",
            "root","root","-rw-r--r--");

        // /proc with real device system properties
        String jvmVendor  = getSystemProp("java.vendor",  "J2ME");
        String jvmVersion = getSystemProp("java.version", "1.3");
        String osName     = getSystemProp("os.name",      "MIDP");
        String microedition = getSystemProp("microedition.platform", "J2ME Device");
        mf("/proc/cpuinfo",
            "processor\t: 0\nvendor_id\t: " + jvmVendor + "\n"+
            "model name\t: " + microedition + "\n"+
            "platform\t: " + osName + "\n"+
            "java.version\t: " + jvmVersion + "\n"+
            "bogomips\t: 800.00\n",
            "root","root","-r--r--r--");
        // /proc/meminfo uses Runtime for real free memory
        long maxMem  = Runtime.getRuntime().totalMemory();
        long freeMem = Runtime.getRuntime().freeMemory();
        mf("/proc/meminfo",
            "MemTotal:\t" + (maxMem/1024)  + " kB\n"+
            "MemFree:\t" + (freeMem/1024) + " kB\n"+
            "MemUsed:\t" + ((maxMem-freeMem)/1024) + " kB\n"+
            "Platform:\t" + osName + "\n",
            "root","root","-r--r--r--");
        mf("/proc/version",
            "DashCMD v1.1.1 (J2ME "+jvmVersion+") on "+osName+" platform\n",
            "root","root","-r--r--r--");
        mf("/proc/uptime",
            "0.00 0.00\n","root","root","-r--r--r--"); // updated dynamically by Shell

        // /etc/profile for login scripts
        mf("/etc/profile",
            "# /etc/profile - system-wide profile\nexport PATH=/usr/local/bin:/usr/bin:/bin\n"+
            "export TERM=xterm-256color\nexport EDITOR=nano\n"+
            "echo \"Welcome to DashCMD v1.1.1\"\ncat /etc/motd\n",
            "root","root","-rw-r--r--");
        mf("/etc/init.d/rc",
            "#!/bin/sh\n# DashCMD init.d runlevel control\necho 'Starting DashCMD services...'\n"+
            "echo 'Started: sshd cron nginx'\n",
            "root","root","-rwxr-xr-x");

        // Music folder placeholder
        mf(homeDir+"/Music/README.txt",
            "Music directory.\nDownload audio with: wget <url> -O ~/Music/song.mp3\n"+
            "Play with: play ~/Music/song.mp3 (J2ME media API if supported)\n",
            username,username,"-rw-r--r--");

        // Pictures folder placeholder
        mf(homeDir+"/Pictures/README.txt",
            "Pictures directory.\nDownload images with: wget <url> -O ~/Pictures/image.png\n",
            username,username,"-rw-r--r--");

        // Updated readme for v1.1.1
        mf(homeDir+"/readme.txt",
            "Welcome to DashCMD v1.1.1!\n"+
            "===========================\n"+
            "Installed: "+AppStorage.formatTime(System.currentTimeMillis())+"\n\n"+
            "New in v1.1.1:\n"+
            "  Real time: all timestamps from device clock\n"+
            "  RMS storage: files persist between launches\n"+
            "  HTTP: curl/wget use real device network\n"+
            "  Install wizard: first-run setup\n"+
            "  Desktop UI: 'desktop' command\n"+
            "  Boot log: /boot/bootlog & /var/log/syslog\n"+
            "  Scripts: .sh, .lua, .bsh in /dev/\n"+
            "  JSR-75: file:/// access if device supports\n\n"+
            "Run scripts:\n"+
            "  sh /dev/hello.sh\n"+
            "  lua /dev/hello.lua\n"+
            "  bsh /dev/hello.bsh\n\n"+
            "Default credentials:\n"+
            "  "+username+" / 1234\n"+
            "  root     / toor\n\n"+
            "Type 'help' for all commands.\n",
            username,username,"-rw-r--r--");

        // Desktop icons
        mf(homeDir+"/Desktop/terminal.lnk",
            "[Desktop Entry]\nName=DashCMD Terminal\nExec=terminal\nIcon=terminal\nType=Application\n",
            username,username,"-rw-r--r--");
        mf(homeDir+"/Desktop/editor.lnk",
            "[Desktop Entry]\nName=Text Editor\nExec=editor\nIcon=editor\nType=Application\n",
            username,username,"-rw-r--r--");
        mf(homeDir+"/Desktop/network.lnk",
            "[Desktop Entry]\nName=Network\nExec=network\nIcon=network\nType=Application\n",
            username,username,"-rw-r--r--");
    }

    /** Safe System.getProperty() for CLDC 1.1. */
    private static String getSystemProp(String key, String def) {
        try {
            String v = System.getProperty(key);
            return (v != null && v.length() > 0) ? v : def;
        } catch (Exception e) { return def; }
    }

    /** Update /proc/uptime and /proc/meminfo with live values. Call before reading. */
    public void refreshProcFS(long bootTimeMillis) {
        long uptimeSecs = (System.currentTimeMillis() - bootTimeMillis) / 1000;
        mf("/proc/uptime", uptimeSecs + ".00 " + (uptimeSecs / 2) + ".00\n",
           "root","root","-r--r--r--");
        long maxMem  = Runtime.getRuntime().totalMemory();
        long freeMem = Runtime.getRuntime().freeMemory();
        mf("/proc/meminfo",
            "MemTotal:\t" + (maxMem/1024)  + " kB\n"+
            "MemFree:\t"  + (freeMem/1024) + " kB\n"+
            "MemUsed:\t"  + ((maxMem-freeMem)/1024) + " kB\n",
            "root","root","-r--r--r--");
        // Update boot log
        mf("/boot/bootlog", AppStorage.readBootLog(), "root","root","-rw-r--r--");
    }

    private void rebuildPasswd() {
        StringBuffer sb = new StringBuffer();
        Enumeration e = credentials.keys();
        while (e.hasMoreElements()) {
            String u = (String) e.nextElement();
            String[] c = (String[]) credentials.get(u);
            sb.append(u).append(":x:").append(c[1]).append(":").append(c[2])
              .append(":").append(c[5]).append(":").append(c[3]).append(":").append(c[4]).append("\n");
        }
        mf("/etc/passwd", sb.toString(), "root","root","-rw-r--r--");
    }

    private void rebuildShadow() {
        StringBuffer sb = new StringBuffer();
        Enumeration e = credentials.keys();
        while (e.hasMoreElements()) {
            String u = (String) e.nextElement();
            String[] c = (String[]) credentials.get(u);
            sb.append(u).append(":").append(c[0]).append(":19600:0:99999:7:::\n");
        }
        mf("/etc/shadow", sb.toString(), "root","shadow","-rw-r-----");
    }

    // ==================== INTERNAL HELPERS ====================

    private String nowMtime() {
        return AppStorage.formatTime(System.currentTimeMillis());
    }

    private void mkdirInternal(String path) {
        String[] n = new String[7];
        n[N_TYPE]="d"; n[N_OWNER]="root"; n[N_PERMS]="drwxr-xr-x";
        n[N_CONTENT]=""; n[N_SIZE]="4096"; n[N_MTIME]=nowMtime(); n[N_GROUP]="root";
        nodes.put(normalizePath(path), n);
    }

    private void mf(String path, String content, String owner, String group, String perms) {
        String[] n = new String[7];
        n[N_TYPE]="f"; n[N_OWNER]=owner; n[N_PERMS]=perms;
        n[N_CONTENT]=(content != null ? content : "");
        n[N_SIZE]=String.valueOf(content != null ? content.length() : 0);
        n[N_MTIME]=nowMtime(); n[N_GROUP]=group;
        nodes.put(normalizePath(path), n);
    }

    // ==================== PUBLIC API ====================

    public String getCurrentPath() { return currentPath; }
    public String getHomeDir()     { return homeDir; }
    public String getUsername()    { return username; }
    public String getHostname()    { return hostname; }
    public void   setUsername(String u) { if (u != null && u.length() > 0) username = u; }
    public void   setHostname(String h) { if (h != null && h.length() > 0) hostname = h; }

    public String getPrompt() {
        String d = currentPath;
        if (d.equals(homeDir)) d = "~";
        else if (d.startsWith(homeDir+"/")) d = "~" + d.substring(homeDir.length());
        return username + "@" + hostname + ":" + d + (isRoot ? "# " : "$ ");
    }

    public String resolvePath(String path) {
        if (path == null || path.length() == 0) return currentPath;
        if (path.equals("~")) return homeDir;
        if (path.startsWith("~/")) return normalizePath(homeDir + "/" + path.substring(2));
        if (path.charAt(0) == '/') return normalizePath(path);
        return normalizePath(currentPath + "/" + path);
    }

    public String normalizePath(String path) {
        if (path == null || path.length() == 0) return "/";
        Vector parts = new Vector();
        String[] segs = splitPath(path);
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i];
            if (s.equals("") || s.equals(".")) continue;
            if (s.equals("..")) { if (parts.size() > 0) parts.removeElementAt(parts.size()-1); }
            else parts.addElement(s);
        }
        if (parts.size() == 0) return "/";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < parts.size(); i++) sb.append("/").append((String)parts.elementAt(i));
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
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    public boolean exists(String p) {
        return nodes.containsKey(p) || symlinks.containsKey(p);
    }
    public boolean isDir(String p) {
        String r = realPath(p);
        if (!nodes.containsKey(r)) return false;
        return "d".equals(((String[])nodes.get(r))[N_TYPE]);
    }
    public boolean isFile(String p) {
        String r = realPath(p);
        if (!nodes.containsKey(r)) return false;
        String t = ((String[])nodes.get(r))[N_TYPE];
        return "f".equals(t) || "-".equals(t);
    }
    public boolean isSymlink(String p) { return symlinks.containsKey(p); }
    public String  realPath(String p) {
        return symlinks.containsKey(p) ? (String)symlinks.get(p) : p;
    }

    public String readFile(String absPath) {
        String rp = realPath(absPath);
        if (!isFile(rp)) return null;
        String[] n = (String[]) nodes.get(rp);
        if (!isRoot) {
            String perms = n[N_PERMS];
            boolean ownerRead  = perms.length() > 1 && perms.charAt(1) == 'r';
            boolean othersRead = perms.length() > 7 && perms.charAt(7) == 'r';
            boolean isOwner    = n[N_OWNER].equals(username);
            if (!isOwner && !othersRead) return null;
        }
        return n[N_CONTENT];
    }

    public boolean writeFile(String absPath, String content) {
        if (isDir(absPath)) return false;
        if (!isFile(absPath)) {
            if (!isDir(parentOf(absPath))) return false;
        }
        mf(absPath, content, username, username, "-rw-r--r--");
        return true;
    }

    public boolean appendFile(String absPath, String content) {
        if (isDir(absPath)) return false;
        String existing = isFile(absPath) ? readFile(absPath) : "";
        if (existing == null) existing = "";
        return writeFile(absPath, existing + content);
    }

    public String cd(String path) {
        String target = realPath(resolvePath(path));
        if (!exists(target)) return "bash: cd: " + path + ": No such file or directory";
        if (!isDir(target))  return "bash: cd: " + path + ": Not a directory";
        if (!isRoot) {
            String[] n = (String[]) nodes.get(target);
            if (n != null) {
                boolean othersExec = n[N_PERMS].length() > 9 && n[N_PERMS].charAt(9) == 'x';
                if (!n[N_OWNER].equals(username) && !othersExec)
                    return "bash: cd: " + path + ": Permission denied";
            }
        }
        currentPath = target;
        return null;
    }

    public boolean createDir(String absPath) {
        if (!isDir(parentOf(absPath))) return false;
        if (exists(absPath)) return false;
        mkdirInternal(absPath);
        String[] n = (String[]) nodes.get(absPath);
        if (n != null) { n[N_OWNER] = username; n[N_GROUP] = username; }
        return true;
    }

    public boolean deleteNode(String absPath) {
        if (!exists(absPath)) return false;
        if (isDir(absPath) && listChildren(absPath).length > 0) return false;
        if (!isRoot) {
            String[] n = (String[]) nodes.get(absPath);
            if (n != null && !n[N_OWNER].equals(username)) return false;
        }
        nodes.remove(absPath);
        return true;
    }

    public boolean deleteRecursive(String absPath) {
        if (!exists(absPath)) return false;
        if (isDir(absPath)) {
            String[] ch = listChildren(absPath);
            for (int i = 0; i < ch.length; i++) deleteRecursive(ch[i]);
        }
        nodes.remove(absPath);
        return true;
    }

    public boolean copyNode(String src, String dst) {
        if (!exists(src)) return false;
        if (isFile(src)) {
            String content = readFile(src);
            if (content == null) content = "";
            String[] sn = (String[]) nodes.get(src);
            mf(dst, content, username, username, sn[N_PERMS]);
            return true;
        }
        return false;
    }

    public boolean moveNode(String src, String dst) {
        if (!exists(src)) return false;
        if (isDir(src)) {
            String[] ch = listAllUnder(src);
            mkdirInternal(dst);
            for (int i = 0; i < ch.length; i++) {
                String newChild = dst + ch[i].substring(src.length());
                nodes.put(newChild, nodes.get(ch[i]));
                nodes.remove(ch[i]);
            }
            nodes.remove(src);
        } else {
            copyNode(src, dst);
            nodes.remove(src);
        }
        return true;
    }

    public String[] listChildren(String absDir) {
        Vector v = new Vector();
        Enumeration keys = nodes.keys();
        while (keys.hasMoreElements()) {
            String key = (String) keys.nextElement();
            if (!key.equals(absDir) && parentOf(key).equals(absDir)) v.addElement(key);
        }
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    private String[] listAllUnder(String absDir) {
        Vector v = new Vector();
        Enumeration keys = nodes.keys();
        while (keys.hasMoreElements()) {
            String key = (String) keys.nextElement();
            if (!key.equals(absDir) && key.startsWith(absDir+"/")) v.addElement(key);
        }
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    public String[] getNodeInfo(String p) {
        return nodes.containsKey(p) ? (String[]) nodes.get(p) : null;
    }
    public String getPerms(String p)  { String[] n=getNodeInfo(p); return n!=null?n[N_PERMS]:"----------"; }
    public String getOwner(String p)  { String[] n=getNodeInfo(p); return n!=null?n[N_OWNER]:"?"; }
    public String getGroup(String p)  { String[] n=getNodeInfo(p); return (n!=null&&n.length>N_GROUP)?n[N_GROUP]:"root"; }
    public String getSize(String p)   { String[] n=getNodeInfo(p); return n!=null?n[N_SIZE]:"0"; }
    public String getMtime(String p)  { String[] n=getNodeInfo(p); return n!=null?n[N_MTIME]:"Jan  1 00:00"; }

    public String parentOf(String absPath) {
        if (absPath.equals("/")) return "/";
        int idx = absPath.lastIndexOf('/');
        return idx == 0 ? "/" : absPath.substring(0, idx);
    }
    public String nameOf(String absPath) {
        return absPath.substring(absPath.lastIndexOf('/')+1);
    }

    public String[] glob(String pattern) {
        String abs = resolvePath(pattern);
        Vector res = new Vector();
        Enumeration keys = nodes.keys();
        while (keys.hasMoreElements()) {
            String key = (String) keys.nextElement();
            if (globMatch(abs, 0, key, 0)) res.addElement(key);
        }
        String[] a = new String[res.size()];
        res.copyInto(a);
        return a;
    }

    private boolean globMatch(String pat, int pi, String str, int si) {
        while (pi < pat.length() && si < str.length()) {
            char pc = pat.charAt(pi), sc = str.charAt(si);
            if (pc == '*') {
                while (si <= str.length()) {
                    if (globMatch(pat, pi+1, str, si)) return true;
                    if (si < str.length() && str.charAt(si) == '/') break;
                    si++;
                }
                return false;
            } else if (pc == '?') {
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
