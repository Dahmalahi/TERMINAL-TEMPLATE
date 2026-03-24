# DashCMD or Terminal v1.1.1

**A full-featured terminal emulator for J2ME / MIDP 2.0 devices**

![DashCMD Banner](https://github.com/Dahmalahi/TERMINAL-TEMPLATE/releases/download/V1.1.1/banner.png)

> Classic green-on-black terminal experience with real scripting, persistent storage, and modern Linux-like commands — running on old feature phones and J2ME emulators.

---

## ✨ Features

### Core
- **400+ Linux commands** simulated (ls, cat, grep, ps, apt, docker, kubectl, git, etc.)
- **Real scripting engine**: `.sh`, `.lua`, and `.bsh` (BeanShell) scripts
- **Persistent filesystem** using RMS (survives app restart)
- **JSR-75 support** — access real device storage (`file:///`)
- **Multi-session support** (up to 3 independent terminals)
- **Desktop UI mode** (`desktop` command)
- **Real device time** and uptime
- **Real HTTP networking** (`curl`, `wget`, `ping`)

### New in v1.1.1
- First-run **Install Wizard** with storage detection
- Real `/proc/` files (`cpuinfo`, `meminfo`, `uptime`)
- Improved boot logging
- Better JSR-75 integration with free space display
- Enhanced ScriptEngine with better compatibility

---

## 📱 Screenshots

![Terminal](https://github.com/Dahmalahi/TERMINAL-TEMPLATE/releases/download/V1.1.1/terminal.png)
![Install Wizard](https://github.com/Dahmalahi/TERMINAL-TEMPLATE/releases/download/V1.1.1/install-wizard.png)
![Desktop UI](https://github.com/Dahmalahi/TERMINAL-TEMPLATE/releases/download/V1.1.1/desktop.png)

---

## 🚀 Quick Start

1. Download the latest `.jar` from [Releases](https://github.com/Dahmalahi/TERMINAL-TEMPLATE/releases/tag/V1.1.1)
2. Install on your J2ME phone or emulator (e.g. KEmulator, MicroEmulator, or real old Nokia/Sony Ericsson)
3. First launch will show the **Install Wizard**
4. Choose storage (RMS or JSR-75 if available)
5. Set username/password (default: `user` / `1234`)
6. Enjoy!

**Default credentials:**
- Normal user: `user` / `1234`
- Root: `root` / `toor`

---

## 📋 Supported Commands

### Everyday
`ls`, `cd`, `cat`, `echo`, `mkdir`, `rm`, `cp`, `mv`, `touch`, `nano`, `grep`, `head`, `tail`, `wc`, `sort`

### System
`ps`, `top`, `free`, `df`, `uptime`, `uname`, `neofetch`, `lshw`

### Networking
`ping`, `curl`, `wget`, `ifconfig`, `netstat`, `ssh`, `nmap`

### Scripting
`sh`, `lua`, `bsh`, `run`

### Advanced
`apt`, `docker`, `kubectl`, `git`, `python`, `node`, `java`, `gcc`

---

## 📁 Project Structure
Terminal/
├── src/
│   ├── TerminalMIDlet.java
│   ├── TerminalCanvas.java
│   ├── Shell.java
│   ├── ScriptEngine.java
│   ├── VirtualFS.java
│   ├── JSR75Storage.java
│   ├── InstallWizard.java
│   ├── DesktopUI.java
│   ├── AppStorage.java
│   ├── NetworkTask.java
│   └── ...
text---

## 🛠️ Building

Requires **Java ME SDK 3.0** or **WTK 2.5.2**

```bash
# Clean & Build using WTK
# Just open the project in Wireless Toolkit and click Build

📜 Changelog
v1.1.1 (Current)

Added full Install Wizard with JSR-75 detection
Real device time in all commands
Persistent RMS filesystem
Desktop UI (desktop command)
Multi-session support
Improved scripting engine

v1.1.0

Initial public release with 300+ commands


📄 License
This project is released under the MIT License.
Feel free to modify, improve, or port it.

⭐ Credits & Thanks

Built with love for the J2ME community
Inspired by old Nokia terminals and modern Linux experiences
Special thanks to everyone still keeping J2ME alive


Made for fun and nostalgia.
Enjoy your retro terminal on modern emulators or real old phones!
Star the repo if you like it ❤️

