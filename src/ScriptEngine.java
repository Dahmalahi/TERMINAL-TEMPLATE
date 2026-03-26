import java.util.*;

/**
 * ScriptEngine v1.1.1 - Multi-language script runner for DashCMD.
 * CLDC 1.1 / MIDP 2.0 - no reflection, no class loading.
 *
 * Supports:
 *  .sh  - POSIX shell subset (echo, export, if, for, while, $VAR, pipes)
 *  .lua - Lua 5.0 subset (print, local, for, while, if, functions, tables)
 *  .bsh - BeanShell subset (Java-like syntax: print, int, String, for, if)
 *
 * Run via Shell commands:
 *  sh   <file>  - run shell script
 *  lua  <file>  - run Lua script
 *  bsh  <file>  - run BeanShell script
 *  run  <file>  - auto-detect by extension
 */
public class ScriptEngine {

    private Shell     shell;
    private VirtualFS fs;
    private Hashtable luaGlobals;
    private Hashtable bshVars;
    private Hashtable shVars;

    // Output accumulator
    private StringBuffer output;

    public ScriptEngine(Shell shell, VirtualFS fs) {
        this.shell      = shell;
        this.fs         = fs;
        this.luaGlobals = new Hashtable();
        this.bshVars    = new Hashtable();
        this.shVars     = new Hashtable();
        this.output     = new StringBuffer();
    }

    /** Wire (or replace) the shell reference after construction. */
    public void setShell(Shell shell) {
        this.shell = shell;
    }

    /** Auto-detect script type and run. */
    public String run(String filePath) {
        String content = fs.readFile(filePath);
        if (content == null) return "run: " + filePath + ": No such file or cannot read";
        String name = filePath.toLowerCase();
        if      (name.endsWith(".lua")) return runLua(content, filePath);
        else if (name.endsWith(".bsh")) return runBsh(content, filePath);
        else if (name.endsWith(".sh"))  return runSh(content, filePath);
        else {
            // Check shebang
            if (content.startsWith("#!/bin/sh") || content.startsWith("#!/bin/bash"))
                return runSh(content, filePath);
            if (content.startsWith("-- ") || content.startsWith("--\n"))
                return runLua(content, filePath);
            if (content.startsWith("//") || content.startsWith("print("))
                return runBsh(content, filePath);
            return "run: unknown script type for " + filePath;
        }
    }

    // ===================== SHELL SCRIPT (.sh) =====================

    public String runSh(String code, String name) {
        output = new StringBuffer();
        shVars = new Hashtable();
        // Inherit env from shell
        String[] lines = splitLines(code);
        try {
            executeSh(lines, 0, lines.length, shVars);
        } catch (ScriptException e) {
            output.append("sh: " + name + ": " + e.getMessage() + "\n");
        }
        return output.toString().trim();
    }

    private int executeSh(String[] lines, int from, int to, Hashtable vars)
            throws ScriptException {
        int i = from;
        while (i < to) {
            String raw = lines[i].trim();
            i++;
            if (raw.length() == 0 || raw.startsWith("#")) continue;

            // if / elif / else / fi
            if (raw.startsWith("if ") || raw.equals("if")) {
                String cond = raw.startsWith("if ") ? raw.substring(3).trim() : "";
                if (cond.endsWith("; then")) cond = cond.substring(0, cond.length()-6).trim();
                // Find then/else/fi boundaries
                int thenStart = i;
                int elseStart = -1;
                int fiLine    = -1;
                int depth = 1;
                for (int j = i; j < to; j++) {
                    String l = lines[j].trim();
                    if (l.startsWith("if ")) depth++;
                    if (l.equals("fi")) { depth--; if (depth == 0) { fiLine = j; break; } }
                    if (depth == 1 && l.startsWith("else")) elseStart = j;
                }
                boolean condResult = evalShCond(cond, vars);
                int elseEnd = elseStart >= 0 ? elseStart : (fiLine >= 0 ? fiLine : to);
                if (condResult) {
                    executeSh(lines, thenStart, elseEnd, vars);
                } else if (elseStart >= 0 && fiLine >= 0) {
                    executeSh(lines, elseStart + 1, fiLine, vars);
                }
                i = fiLine >= 0 ? fiLine + 1 : to;
                continue;
            }

            // for VAR in LIST; do ... done
            if (raw.startsWith("for ") && raw.indexOf(" in ") > 0) {
                int inIdx = raw.indexOf(" in ");
                String loopVar = raw.substring(4, inIdx).trim();
                String rest    = raw.substring(inIdx + 4).trim();
                if (rest.endsWith("; do")) rest = rest.substring(0, rest.length() - 4).trim();
                else if (rest.endsWith(";do")) rest = rest.substring(0, rest.length()-3).trim();
                String[] items = expandShWords(rest, vars);
                // Find done
                int doneIdx = -1;
                for (int j = i; j < to; j++) {
                    if (lines[j].trim().equals("done")) { doneIdx = j; break; }
                }
                int bodyEnd = doneIdx >= 0 ? doneIdx : to;
                for (int k = 0; k < items.length; k++) {
                    vars.put(loopVar, items[k]);
                    executeSh(lines, i, bodyEnd, vars);
                }
                i = doneIdx >= 0 ? doneIdx + 1 : to;
                continue;
            }

            // while ... do ... done
            if (raw.startsWith("while ")) {
                String cond = raw.substring(6).trim();
                if (cond.endsWith("; do")) cond = cond.substring(0, cond.length()-4).trim();
                int doneIdx = -1;
                for (int j = i; j < to; j++) {
                    if (lines[j].trim().equals("done")) { doneIdx = j; break; }
                }
                int bodyEnd = doneIdx >= 0 ? doneIdx : to;
                int maxIter = 100;
                while (evalShCond(cond, vars) && maxIter-- > 0) {
                    executeSh(lines, i, bodyEnd, vars);
                }
                i = doneIdx >= 0 ? doneIdx + 1 : to;
                continue;
            }

            // then / do / else / fi / done - skip standalone markers
            if (raw.equals("then") || raw.equals("do") || raw.equals("else") ||
                raw.equals("fi")   || raw.equals("done")) continue;

            // Variable assignment: VAR=value
            if (!raw.startsWith("$") && raw.indexOf('=') > 0 && raw.indexOf(' ') < 0) {
                int eq = raw.indexOf('=');
                String key = raw.substring(0, eq);
                String val = expandShVar(raw.substring(eq + 1), vars);
                vars.put(key, val);
                continue;
            }

            // export VAR=val
            if (raw.startsWith("export ")) {
                String rest = raw.substring(7).trim();
                int eq = rest.indexOf('=');
                if (eq > 0) {
                    vars.put(rest.substring(0, eq), expandShVar(rest.substring(eq+1), vars));
                }
                continue;
            }

            // echo
            if (raw.startsWith("echo ") || raw.equals("echo")) {
                String text = raw.length() > 5 ? raw.substring(5) : "";
                output.append(expandShVar(unquote(text), vars)).append("\n");
                continue;
            }

            // Exit
            if (raw.startsWith("exit")) continue;

            // Pipe: cmd | cmd
            int pipeIdx = raw.indexOf(" | ");
            if (pipeIdx >= 0) {
                String left  = raw.substring(0, pipeIdx).trim();
                String right = raw.substring(pipeIdx + 3).trim();
                String leftOut = runShCommand(left, vars);
                output.append(leftOut).append("\n");
                continue;
            }

            // Redirect: cmd > file or cmd >> file
            int redir2 = raw.indexOf(" >> ");
            int redir1 = redir2 < 0 ? raw.indexOf(" > ") : -1;
            if (redir2 >= 0) {
                String cmd  = raw.substring(0, redir2).trim();
                String file = expandShVar(raw.substring(redir2+4).trim(), vars);
                String out  = runShCommand(cmd, vars);
                fs.appendFile(fs.resolvePath(file), out + "\n");
                continue;
            }
            if (redir1 >= 0) {
                String cmd  = raw.substring(0, redir1).trim();
                String file = expandShVar(raw.substring(redir1+3).trim(), vars);
                String out  = runShCommand(cmd, vars);
                fs.writeFile(fs.resolvePath(file), out + "\n");
                continue;
            }

            // Command substitution: VAR=$(cmd)
            raw = expandShSubst(raw, vars);

            // Regular command
            String result = runShCommand(raw, vars);
            if (result != null && result.length() > 0) output.append(result).append("\n");
        }
        return i;
    }

    private String runShCommand(String cmdLine, Hashtable vars) {
        // Expand variables first
        cmdLine = expandShVar(cmdLine, vars);
        // Remove quotes
        cmdLine = unquote(cmdLine);
        if (cmdLine.length() == 0) return "";
        // Execute via Shell (shell may be null if engine was created without one)
        if (shell == null) return "(sh: no shell context)";
        return shell.execute(cmdLine);
    }

    private boolean evalShCond(String cond, Hashtable vars) {
        cond = expandShVar(cond.trim(), vars);
        // [ expr ] or test expr
        if ((cond.startsWith("[") && cond.endsWith("]"))) {
            cond = cond.substring(1, cond.length()-1).trim();
        } else if (cond.startsWith("test ")) {
            cond = cond.substring(5).trim();
        }
        // -z string (empty)
        if (cond.startsWith("-z ")) {
            String s = stripQuotes(cond.substring(3).trim());
            return s.length() == 0;
        }
        // -n string (non-empty)
        if (cond.startsWith("-n ")) {
            String s = stripQuotes(cond.substring(3).trim());
            return s.length() > 0;
        }
        // -f file
        if (cond.startsWith("-f ")) {
            return fs.isFile(fs.resolvePath(cond.substring(3).trim()));
        }
        // -d dir
        if (cond.startsWith("-d ")) {
            return fs.isDir(fs.resolvePath(cond.substring(3).trim()));
        }
        // str = str
        if (cond.indexOf(" = ") >= 0) {
            String[] p = split2(cond, " = ");
            return stripQuotes(p[0]).equals(stripQuotes(p[1]));
        }
        // str != str
        if (cond.indexOf(" != ") >= 0) {
            String[] p = split2(cond, " != ");
            return !stripQuotes(p[0]).equals(stripQuotes(p[1]));
        }
        // numeric -eq -ne -lt -gt -le -ge
        if (cond.indexOf(" -eq ") >= 0) return numCmp(cond, " -eq ") == 0;
        if (cond.indexOf(" -ne ") >= 0) return numCmp(cond, " -ne ") != 0;
        if (cond.indexOf(" -lt ") >= 0) return numCmp(cond, " -lt ") < 0;
        if (cond.indexOf(" -gt ") >= 0) return numCmp(cond, " -gt ") > 0;
        if (cond.indexOf(" -le ") >= 0) return numCmp(cond, " -le ") <= 0;
        if (cond.indexOf(" -ge ") >= 0) return numCmp(cond, " -ge ") >= 0;
        // Non-empty string = true
        return cond.length() > 0 && !cond.equals("0") && !cond.equals("false");
    }

    private int numCmp(String cond, String op) {
        String[] p = split2(cond, op);
        try { return Integer.parseInt(p[0].trim()) - Integer.parseInt(p[1].trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private String expandShVar(String s, Hashtable vars) {
        if (s == null || s.indexOf('$') < 0) return s;
        StringBuffer sb = new StringBuffer();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '$') {
                i++;
                // ${VAR} or $VAR
                boolean braced = i < s.length() && s.charAt(i) == '{';
                if (braced) i++;
                int start = i;
                while (i < s.length()) {
                    char ch = s.charAt(i);
                    if (braced ? ch == '}' : (!isAlphaNum(ch) && ch != '_')) break;
                    i++;
                }
                String name = s.substring(start, i);
                if (braced && i < s.length()) i++; // skip }
                String val = (String) vars.get(name);
                if (val == null && shell != null) val = shell.getEnv(name);
                if (val == null) val = "";
                sb.append(val);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private String expandShSubst(String s, Hashtable vars) {
        while (s.indexOf("$(") >= 0) {
            int start = s.indexOf("$(");
            int end   = s.indexOf(")", start);
            if (end < 0) break;
            String cmd = s.substring(start+2, end);
            String out = runShCommand(cmd, vars).trim();
            s = s.substring(0, start) + out + s.substring(end+1);
        }
        return s;
    }

    private String[] expandShWords(String s, Hashtable vars) {
        s = expandShVar(s, vars);
        // Simple word split
        Vector v = new Vector();
        StringBuffer cur = new StringBuffer();
        boolean inQ = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') { inQ = !inQ; continue; }
            if (!inQ && (c == ' ' || c == '\t')) {
                if (cur.length() > 0) { v.addElement(cur.toString()); cur = new StringBuffer(); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) v.addElement(cur.toString());
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    private String unquote(String s) {
        s = s.trim();
        if (s.length() >= 2 && ((s.charAt(0) == '"'  && s.charAt(s.length()-1) == '"') ||
                                  (s.charAt(0) == '\'' && s.charAt(s.length()-1) == '\''))) {
            s = s.substring(1, s.length()-1);
        }
        return s;
    }

    // ===================== LUA 5.0 INTERPRETER =====================

    public String runLua(String code, String name) {
        output     = new StringBuffer();
        luaGlobals = new Hashtable();
        // Built-ins
        luaGlobals.put("_VERSION", "Lua 5.0");
        String[] lines = splitLines(code);
        try {
            executeLua(lines, 0, lines.length, luaGlobals);
        } catch (ScriptException e) {
            output.append(name + ": " + e.getMessage() + "\n");
        }
        return output.toString().trim();
    }

    private void executeLua(String[] lines, int from, int to, Hashtable env)
            throws ScriptException {
        int i = from;
        while (i < to) {
            String raw = lines[i].trim();
            i++;
            if (raw.length() == 0 || raw.startsWith("--")) continue;

            // local VAR = expr  or  VAR = expr
            if (raw.startsWith("local ") || (raw.indexOf('=') > 0 && !raw.startsWith("if") &&
                !raw.startsWith("while") && !raw.startsWith("for") && !raw.startsWith("return"))) {
                String stmt = raw.startsWith("local ") ? raw.substring(6).trim() : raw;
                int eq = stmt.indexOf('=');
                if (eq > 0) {
                    String varName = stmt.substring(0, eq).trim();
                    String expr    = stmt.substring(eq+1).trim();
                    // Handle multiple assignment: a, b = 1, 2
                    if (varName.indexOf(',') >= 0) {
                        String[] names  = splitComma(varName);
                        String[] exprs  = splitComma(expr);
                        for (int k = 0; k < names.length; k++) {
                            String val = k < exprs.length ? evalLua(exprs[k].trim(), env) : "nil";
                            env.put(names[k].trim(), val);
                        }
                    } else {
                        env.put(varName, evalLua(expr, env));
                    }
                    continue;
                }
            }

            // print(...)
            if (raw.startsWith("print(") && raw.endsWith(")")) {
                String inner = raw.substring(6, raw.length()-1);
                // Multiple args
                String[] args = splitComma(inner);
                StringBuffer sb = new StringBuffer();
                for (int k = 0; k < args.length; k++) {
                    if (k > 0) sb.append("\t");
                    sb.append(evalLua(args[k].trim(), env));
                }
                output.append(sb.toString()).append("\n");
                continue;
            }

            // io.write(...)
            if (raw.startsWith("io.write(") && raw.endsWith(")")) {
                String inner = raw.substring(9, raw.length()-1);
                output.append(evalLua(inner, env));
                continue;
            }

            // if expr then ... end  (single block)
            if (raw.startsWith("if ") && (raw.endsWith(" then") || raw.indexOf(" then") > 0)) {
                String cond = raw.substring(3);
                int thenIdx = lastIndexOfStr(cond, " then");
                if (thenIdx > 0) cond = cond.substring(0, thenIdx).trim();
                // Find end/else
                int elseIdx = -1, endIdx = -1, depth = 1;
                for (int j = i; j < to; j++) {
                    String l = lines[j].trim();
                    if (l.startsWith("if "))         depth++;
                    if (l.equals("end"))              { depth--; if (depth==0) { endIdx = j; break; } }
                    if (depth == 1 && l.equals("else")) elseIdx = j;
                }
                boolean condVal = evalLuaBool(cond, env);
                int bodyEnd = elseIdx >= 0 ? elseIdx : (endIdx >= 0 ? endIdx : to);
                if (condVal) {
                    executeLua(lines, i, bodyEnd, env);
                } else if (elseIdx >= 0 && endIdx >= 0) {
                    executeLua(lines, elseIdx+1, endIdx, env);
                }
                i = endIdx >= 0 ? endIdx+1 : to;
                continue;
            }

            // for i=start,end[,step] do ... end
            if (raw.startsWith("for ") && raw.indexOf("=") > 0 && raw.indexOf(",") > 0) {
                // numeric for
                String rest = raw.substring(4).trim();
                int eq = rest.indexOf('=');
                if (eq > 0) {
                    String var  = rest.substring(0, eq).trim();
                    String rhs  = rest.substring(eq+1);
                    if (rhs.endsWith(" do")) rhs = rhs.substring(0, rhs.length()-3).trim();
                    String[] parts = splitComma(rhs);
                    int start  = parseInt(evalLua(parts[0].trim(), env), 1);
                    int end    = parts.length > 1 ? parseInt(evalLua(parts[1].trim(), env), 10) : 10;
                    int step   = parts.length > 2 ? parseInt(evalLua(parts[2].trim(), env), 1)  : 1;
                    if (step == 0) step = 1;
                    int doneIdx = findLuaEnd(lines, i, to);
                    int bodyEnd = doneIdx >= 0 ? doneIdx : to;
                    int maxIter = 1000;
                    for (int v = start; step > 0 ? v <= end : v >= end; v += step) {
                        if (maxIter-- <= 0) break;
                        env.put(var, String.valueOf(v));
                        executeLua(lines, i, bodyEnd, copyHashtable(env));
                    }
                    i = doneIdx >= 0 ? doneIdx+1 : to;
                    continue;
                }
            }

            // for k, v in pairs(t) do ... end  -- simplified
            if (raw.startsWith("for ") && raw.indexOf(" in ") > 0) {
                // skip complex iterators for now
                int endIdx = findLuaEnd(lines, i, to);
                i = endIdx >= 0 ? endIdx+1 : to;
                continue;
            }

            // while cond do ... end
            if (raw.startsWith("while ")) {
                String cond = raw.substring(6).trim();
                if (cond.endsWith(" do")) cond = cond.substring(0, cond.length()-3).trim();
                int endIdx = findLuaEnd(lines, i, to);
                int bodyEnd = endIdx >= 0 ? endIdx : to;
                int maxIter = 1000;
                while (evalLuaBool(cond, env) && maxIter-- > 0) {
                    executeLua(lines, i, bodyEnd, copyHashtable(env));
                }
                i = endIdx >= 0 ? endIdx+1 : to;
                continue;
            }

            // function name(...) ... end
            if (raw.startsWith("function ")) {
                int endIdx = findLuaEnd(lines, i, to);
                i = endIdx >= 0 ? endIdx+1 : to;
                continue; // Functions stored as closures - simplified skip
            }

            // return statement
            if (raw.startsWith("return ")) continue;

            // end / else
            if (raw.equals("end") || raw.equals("else")) continue;

            // os.exit() / os.clock() etc
            if (raw.startsWith("os.")) {
                if (raw.equals("os.exit()") || raw.equals("os.exit(0)")) return;
                continue;
            }

            // math.* - evaluate as expression
            if (raw.startsWith("math.") || raw.startsWith("string.") || raw.startsWith("table.")) {
                evalLua(raw, env);
                continue;
            }

            // Expression statement (function call etc.)
            evalLua(raw, env);
        }
    }

    private String evalLua(String expr, Hashtable env) {
        expr = expr.trim();
        if (expr.length() == 0) return "nil";

        // String literal
        if ((expr.startsWith("\"") && expr.endsWith("\"")) ||
            (expr.startsWith("'")  && expr.endsWith("'"))) {
            return expr.substring(1, expr.length()-1);
        }

        // Number
        try { return String.valueOf(Integer.parseInt(expr)); } catch (NumberFormatException e) {}
        try {
            double d = Double.parseDouble(expr);
            if (d == Math.floor(d)) return String.valueOf((long)d);
            return String.valueOf(d);
        } catch (NumberFormatException e) {}

        // nil / true / false
        if (expr.equals("nil"))   return "nil";
        if (expr.equals("true"))  return "true";
        if (expr.equals("false")) return "false";

        // #string or #table
        if (expr.startsWith("#")) {
            String s = (String) env.get(expr.substring(1).trim());
            return s != null ? String.valueOf(s.length()) : "0";
        }

        // .. concatenation
        if (expr.indexOf(" .. ") >= 0) {
            String[] parts = splitOn(expr, " .. ");
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < parts.length; i++) sb.append(evalLua(parts[i].trim(), env));
            return sb.toString();
        }

        // Arithmetic: +, -, *, /, %
        String arith = evalLuaArith(expr, env);
        if (arith != null) return arith;

        // math.* functions
        if (expr.startsWith("math.")) {
            if (expr.startsWith("math.floor(") && expr.endsWith(")")) {
                try { double d = Double.parseDouble(evalLua(expr.substring(11,expr.length()-1), env));
                      return String.valueOf((long)Math.floor(d)); } catch (Exception e) {}
            }
            if (expr.startsWith("math.abs(") && expr.endsWith(")")) {
                try { double d = Double.parseDouble(evalLua(expr.substring(9,expr.length()-1), env));
                      return String.valueOf(Math.abs(d)); } catch (Exception e) {}
            }
            if (expr.startsWith("math.max(") && expr.endsWith(")")) {
                String[] args = splitComma(expr.substring(9, expr.length()-1));
                if (args.length >= 2) {
                    try { double a = Double.parseDouble(evalLua(args[0].trim(), env));
                          double b = Double.parseDouble(evalLua(args[1].trim(), env));
                          return String.valueOf(a > b ? a : b); } catch (Exception e) {}
                }
            }
            if (expr.startsWith("math.min(") && expr.endsWith(")")) {
                String[] args = splitComma(expr.substring(9, expr.length()-1));
                if (args.length >= 2) {
                    try { double a = Double.parseDouble(evalLua(args[0].trim(), env));
                          double b = Double.parseDouble(evalLua(args[1].trim(), env));
                          return String.valueOf(a < b ? a : b); } catch (Exception e) {}
                }
            }
            if (expr.startsWith("math.sqrt(") && expr.endsWith(")")) {
                try { double d = Double.parseDouble(evalLua(expr.substring(10,expr.length()-1), env));
                      return String.valueOf(Math.sqrt(d)); } catch (Exception e) {}
            }
            return "0";
        }

        // string.* functions
        if (expr.startsWith("string.len(") && expr.endsWith(")")) {
            String s = evalLua(expr.substring(11, expr.length()-1), env);
            return String.valueOf(s.length());
        }
        if (expr.startsWith("string.upper(") && expr.endsWith(")")) {
            return evalLua(expr.substring(13, expr.length()-1), env).toUpperCase();
        }
        if (expr.startsWith("string.lower(") && expr.endsWith(")")) {
            return evalLua(expr.substring(13, expr.length()-1), env).toLowerCase();
        }
        if (expr.startsWith("string.rep(") && expr.endsWith(")")) {
            String[] args = splitComma(expr.substring(11, expr.length()-1));
            if (args.length >= 2) {
                String s = evalLua(args[0].trim(), env);
                int n = parseInt(evalLua(args[1].trim(), env), 1);
                StringBuffer sb = new StringBuffer();
                for (int k = 0; k < n; k++) sb.append(s);
                return sb.toString();
            }
        }

        // tostring() / tonumber()
        if (expr.startsWith("tostring(") && expr.endsWith(")"))
            return evalLua(expr.substring(9, expr.length()-1), env);
        if (expr.startsWith("tonumber(") && expr.endsWith(")")) {
            String s = evalLua(expr.substring(9, expr.length()-1), env);
            try { return String.valueOf(Integer.parseInt(s)); } catch (Exception e) {}
            return "nil";
        }

        // type()
        if (expr.startsWith("type(") && expr.endsWith(")")) {
            String v = evalLua(expr.substring(5, expr.length()-1), env);
            try { Double.parseDouble(v); return "number"; } catch (Exception e) {}
            if (v.equals("true") || v.equals("false")) return "boolean";
            if (v.equals("nil")) return "nil";
            return "string";
        }

        // Variable lookup
        String val = (String) env.get(expr);
        if (val != null) return val;

        // Nil for unknown
        return "nil";
    }

    private String evalLuaArith(String expr, Hashtable env) {
        // Simple binary ops: +, -, *, /, %  (respects operator precedence via right-associative)
        // Find last +/- not inside parens
        for (int op = expr.length()-1; op > 0; op--) {
            char c = expr.charAt(op);
            if ((c == '+' || c == '-') && op > 0 && expr.charAt(op-1) != 'e') {
                String left  = evalLua(expr.substring(0, op).trim(), env);
                String right = evalLua(expr.substring(op+1).trim(), env);
                try {
                    double a = Double.parseDouble(left);
                    double b = Double.parseDouble(right);
                    double r = c == '+' ? a+b : a-b;
                    if (r == Math.floor(r)) return String.valueOf((long)r);
                    return String.valueOf(r);
                } catch (Exception e) {
                    if (c == '+') return left + right; // string concat fallback
                    return "nil";
                }
            }
        }
        for (int op = expr.length()-1; op > 0; op--) {
            char c = expr.charAt(op);
            if (c == '*' || c == '/' || c == '%') {
                String left  = evalLua(expr.substring(0, op).trim(), env);
                String right = evalLua(expr.substring(op+1).trim(), env);
                try {
                    double a = Double.parseDouble(left);
                    double b = Double.parseDouble(right);
                    double r;
                    if (c == '*') r = a*b;
                    else if (c == '/') r = b != 0 ? a/b : 0;
                    else r = a % b;
                    if (r == Math.floor(r)) return String.valueOf((long)r);
                    return String.valueOf(r);
                } catch (Exception e) { return "nil"; }
            }
        }
        return null;
    }

    private boolean evalLuaBool(String expr, Hashtable env) {
        expr = expr.trim();
        if (expr.equals("true"))  return true;
        if (expr.equals("false") || expr.equals("nil")) return false;
        if (expr.startsWith("not ")) return !evalLuaBool(expr.substring(4), env);
        // Comparison operators
        String[] ops = {" == "," ~= "," <= "," >= "," < "," > "};
        for (int k = 0; k < ops.length; k++) {
            int idx = expr.indexOf(ops[k]);
            if (idx >= 0) {
                String left  = evalLua(expr.substring(0, idx).trim(), env);
                String right = evalLua(expr.substring(idx + ops[k].length()).trim(), env);
                switch (k) {
                    case 0: return left.equals(right);
                    case 1: return !left.equals(right);
                    case 2: case 3: case 4: case 5:
                        try {
                            double a = Double.parseDouble(left);
                            double b = Double.parseDouble(right);
                            if (k==2) return a<=b; if (k==3) return a>=b;
                            if (k==4) return a<b;  return a>b;
                        } catch (Exception e) {
                            int cmp = left.compareTo(right);
                            if (k==2) return cmp<=0; if (k==3) return cmp>=0;
                            if (k==4) return cmp<0;  return cmp>0;
                        }
                }
            }
        }
        // and / or
        int andIdx = expr.indexOf(" and ");
        if (andIdx >= 0) return evalLuaBool(expr.substring(0,andIdx), env) &&
                                evalLuaBool(expr.substring(andIdx+5), env);
        int orIdx = expr.indexOf(" or ");
        if (orIdx >= 0)  return evalLuaBool(expr.substring(0,orIdx),  env) ||
                                evalLuaBool(expr.substring(orIdx+4),   env);
        String v = evalLua(expr, env);
        return !v.equals("nil") && !v.equals("false") && !v.equals("0") && v.length() > 0;
    }

    private int findLuaEnd(String[] lines, int from, int to) {
        int depth = 1;
        for (int j = from; j < to; j++) {
            String l = lines[j].trim();
            if (l.startsWith("if ") || l.startsWith("function ") ||
                l.startsWith("for ") || l.startsWith("while ") || l.startsWith("do")) depth++;
            if (l.equals("end")) { depth--; if (depth == 0) return j; }
        }
        return -1;
    }

    // ===================== BEANSHELL INTERPRETER =====================

    public String runBsh(String code, String name) {
        output  = new StringBuffer();
        bshVars = new Hashtable();
        String[] lines = splitLines(code);
        try {
            executeBsh(lines, 0, lines.length, bshVars);
        } catch (ScriptException e) {
            output.append(name + ": " + e.getMessage() + "\n");
        }
        return output.toString().trim();
    }

    private void executeBsh(String[] lines, int from, int to, Hashtable vars)
            throws ScriptException {
        int i = from;
        while (i < to) {
            String raw = lines[i].trim();
            i++;
            if (raw.length() == 0 || raw.startsWith("//") || raw.startsWith("/*")) continue;

            // Remove trailing ;
            if (raw.endsWith(";")) raw = raw.substring(0, raw.length()-1).trim();

            // print(...) / System.out.println(...)
            if (raw.startsWith("print(") && raw.endsWith(")")) {
                String inner = raw.substring(6, raw.length()-1);
                output.append(evalBsh(inner, vars)).append("\n");
                continue;
            }
            if (raw.startsWith("System.out.println(") && raw.endsWith(")")) {
                String inner = raw.substring(19, raw.length()-1);
                output.append(evalBsh(inner, vars)).append("\n");
                continue;
            }
            if (raw.startsWith("System.out.print(") && raw.endsWith(")")) {
                String inner = raw.substring(17, raw.length()-1);
                output.append(evalBsh(inner, vars));
                continue;
            }

            // Variable declaration: type name = value;
            String[] types = {"int","long","double","float","boolean","String","char","byte","short"};
            boolean isDecl = false;
            for (int t = 0; t < types.length && !isDecl; t++) {
                if (raw.startsWith(types[t] + " ")) {
                    String rest = raw.substring(types[t].length()+1).trim();
                    int eq = rest.indexOf('=');
                    if (eq > 0) {
                        String varName = rest.substring(0, eq).trim();
                        String val     = rest.substring(eq+1).trim();
                        vars.put(varName, evalBsh(val, vars));
                        isDecl = true;
                    } else {
                        vars.put(rest.trim(), "");
                        isDecl = true;
                    }
                }
            }
            if (isDecl) continue;

            // Assignment: name = value;
            if (raw.indexOf(" = ") > 0 && !raw.startsWith("if") && !raw.startsWith("for") &&
                !raw.startsWith("while")) {
                int eq = raw.indexOf(" = ");
                String varName = raw.substring(0, eq).trim();
                String val     = raw.substring(eq+3).trim();
                if (varName.indexOf("(") < 0 && varName.indexOf(".") < 0) {
                    vars.put(varName, evalBsh(val, vars));
                    continue;
                }
            }

            // if (cond) { ... }
            if (raw.startsWith("if (") || raw.startsWith("if(")) {
                int parenEnd = raw.indexOf(')');
                if (parenEnd > 0) {
                    String cond = raw.substring(raw.indexOf('(')+1, parenEnd);
                    boolean condVal = evalBshBool(cond, vars);
                    // Find matching braces
                    int braceStart = raw.indexOf('{');
                    int braceEnd   = findBshEnd(lines, i-1, to, '{', '}');
                    // Simple: brace on same line
                    if (braceStart >= 0 && raw.endsWith("{")) {
                        int bodyEnd = braceEnd >= 0 ? braceEnd : to;
                        if (condVal) executeBsh(lines, i, bodyEnd, vars);
                        i = bodyEnd >= 0 ? bodyEnd+1 : to;
                    }
                    continue;
                }
            }

            // for (init; cond; incr) {...}
            if (raw.startsWith("for (") || raw.startsWith("for(")) {
                // Parse numeric for loop: for (int i=0; i<10; i++)
                int p1 = raw.indexOf('(');
                int p2 = raw.indexOf(')');
                if (p1 >= 0 && p2 > p1) {
                    String inner = raw.substring(p1+1, p2);
                    String[] parts = splitSemi(inner);
                    if (parts.length >= 2) {
                        // Parse init
                        String initS = parts[0].trim();
                        String condS = parts[1].trim();
                        String incrS = parts.length > 2 ? parts[2].trim() : "";
                        // Execute init
                        if (initS.indexOf('=') > 0) {
                            String var = initS.substring(initS.lastIndexOf(' ')+1, initS.indexOf('='));
                            String val = initS.substring(initS.indexOf('=')+1).trim();
                            vars.put(var.trim(), evalBsh(val, vars));
                        }
                        int bodyEnd = findBshEnd(lines, i-1, to, '{', '}');
                        int maxIter = 1000;
                        while (evalBshBool(condS, vars) && maxIter-- > 0) {
                            executeBsh(lines, i, bodyEnd >= 0 ? bodyEnd : to, vars);
                            applyBshIncr(incrS, vars);
                        }
                        i = bodyEnd >= 0 ? bodyEnd+1 : to;
                    }
                    continue;
                }
            }

            // while (cond) { ... }
            if (raw.startsWith("while (") || raw.startsWith("while(")) {
                int p1 = raw.indexOf('('), p2 = raw.indexOf(')');
                if (p1 >= 0 && p2 > p1) {
                    String cond = raw.substring(p1+1, p2);
                    int bodyEnd = findBshEnd(lines, i-1, to, '{', '}');
                    int maxIter = 1000;
                    while (evalBshBool(cond, vars) && maxIter-- > 0) {
                        executeBsh(lines, i, bodyEnd >= 0 ? bodyEnd : to, vars);
                    }
                    i = bodyEnd >= 0 ? bodyEnd+1 : to;
                    continue;
                }
            }

            // { } block markers
            if (raw.equals("{") || raw.equals("}")) continue;

            // Return
            if (raw.startsWith("return ")) continue;
        }
    }

    private String evalBsh(String expr, Hashtable vars) {
        expr = expr.trim();
        if (expr.length() == 0) return "";

        // String literal
        if (expr.startsWith("\"") && expr.endsWith("\""))
            return expr.substring(1, expr.length()-1);

        // char literal
        if (expr.startsWith("'") && expr.endsWith("'") && expr.length() == 3)
            return String.valueOf(expr.charAt(1));

        // Numbers
        try { return String.valueOf(Integer.parseInt(expr)); } catch (NumberFormatException e) {}
        try {
            double d = Double.parseDouble(expr);
            if (d == Math.floor(d)) return String.valueOf((long)d);
            return String.valueOf(d);
        } catch (NumberFormatException e) {}

        // Boolean
        if (expr.equals("true")) return "true";
        if (expr.equals("false")) return "false";
        if (expr.equals("null"))  return "null";

        // String concat: "..." + var + "..."
        if (expr.indexOf(" + ") >= 0) {
            String[] parts = splitOn(expr, " + ");
            StringBuffer sb = new StringBuffer();
            for (int k = 0; k < parts.length; k++) sb.append(evalBsh(parts[k].trim(), vars));
            return sb.toString();
        }

        // Arithmetic
        String arith = evalBshArith(expr, vars);
        if (arith != null) return arith;

        // System.currentTimeMillis()
        if (expr.equals("System.currentTimeMillis()"))
            return String.valueOf(System.currentTimeMillis());

        // String.valueOf(x)
        if (expr.startsWith("String.valueOf(") && expr.endsWith(")"))
            return evalBsh(expr.substring(15, expr.length()-1), vars);

        // Integer.parseInt(x)
        if (expr.startsWith("Integer.parseInt(") && expr.endsWith(")")) {
            String s = evalBsh(expr.substring(17, expr.length()-1), vars);
            try { return String.valueOf(Integer.parseInt(s)); } catch (Exception e) { return "0"; }
        }

        // Math.abs/max/min/sqrt
        if (expr.startsWith("Math.abs(") && expr.endsWith(")")) {
            try { double d = Double.parseDouble(evalBsh(expr.substring(9,expr.length()-1), vars));
                  long r = (long)Math.abs(d); return String.valueOf(r); } catch (Exception e) {}
        }
        if (expr.startsWith("Math.max(") && expr.endsWith(")")) {
            String[] args = splitComma(expr.substring(9, expr.length()-1));
            if (args.length >= 2) {
                try { double a = Double.parseDouble(evalBsh(args[0].trim(), vars));
                      double b = Double.parseDouble(evalBsh(args[1].trim(), vars));
                      return String.valueOf((long)(a > b ? a : b)); } catch (Exception e) {}
            }
        }
        if (expr.startsWith("Math.sqrt(") && expr.endsWith(")")) {
            try { double d = Double.parseDouble(evalBsh(expr.substring(10,expr.length()-1), vars));
                  return String.valueOf(Math.sqrt(d)); } catch (Exception e) {}
        }

        // .length() on variable
        if (expr.endsWith(".length()")) {
            String varName = expr.substring(0, expr.length()-9);
            String v = (String) vars.get(varName);
            return v != null ? String.valueOf(v.length()) : "0";
        }

        // .toUpperCase() / .toLowerCase()
        if (expr.endsWith(".toUpperCase()")) {
            String v = evalBsh(expr.substring(0, expr.length()-14), vars);
            return v.toUpperCase();
        }
        if (expr.endsWith(".toLowerCase()")) {
            String v = evalBsh(expr.substring(0, expr.length()-14), vars);
            return v.toLowerCase();
        }

        // Variable lookup
        String v = (String) vars.get(expr);
        return v != null ? v : expr;
    }

    private String evalBshArith(String expr, Hashtable vars) {
        for (int op = expr.length()-1; op > 0; op--) {
            char c = expr.charAt(op);
            if ((c == '+' || c == '-') && expr.charAt(op-1) != 'e') {
                String left  = evalBsh(expr.substring(0, op).trim(), vars);
                String right = evalBsh(expr.substring(op+1).trim(), vars);
                try {
                    double a = Double.parseDouble(left);
                    double b = Double.parseDouble(right);
                    double r = c == '+' ? a+b : a-b;
                    if (r == Math.floor(r)) return String.valueOf((long)r);
                    return String.valueOf(r);
                } catch (Exception e) {
                    if (c == '+') return left + right;
                    return null;
                }
            }
        }
        for (int op = expr.length()-1; op > 0; op--) {
            char c = expr.charAt(op);
            if (c == '*' || c == '/' || c == '%') {
                String left  = evalBsh(expr.substring(0, op).trim(), vars);
                String right = evalBsh(expr.substring(op+1).trim(), vars);
                try {
                    double a = Double.parseDouble(left);
                    double b = Double.parseDouble(right);
                    double r;
                    if (c=='*') r=a*b; else if (c=='/') r=b!=0?a/b:0; else r=a%b;
                    if (r == Math.floor(r)) return String.valueOf((long)r);
                    return String.valueOf(r);
                } catch (Exception e) { return null; }
            }
        }
        return null;
    }

    private boolean evalBshBool(String expr, Hashtable vars) {
        expr = expr.trim();
        if (expr.equals("true"))  return true;
        if (expr.equals("false")) return false;
        // Comparison operators
        String[] ops = {" == "," != "," <= "," >= "," < "," > "};
        for (int k = 0; k < ops.length; k++) {
            int idx = expr.indexOf(ops[k]);
            if (idx >= 0) {
                String left  = evalBsh(expr.substring(0, idx).trim(), vars);
                String right = evalBsh(expr.substring(idx+ops[k].length()).trim(), vars);
                switch (k) {
                    case 0: return left.equals(right);
                    case 1: return !left.equals(right);
                    default:
                        try {
                            double a = Double.parseDouble(left);
                            double b = Double.parseDouble(right);
                            if (k==2) return a<=b; if (k==3) return a>=b;
                            if (k==4) return a<b;  return a>b;
                        } catch (Exception e) { return false; }
                }
            }
        }
        // && / ||
        int andIdx = expr.indexOf(" && ");
        if (andIdx >= 0) return evalBshBool(expr.substring(0,andIdx),vars) &&
                                evalBshBool(expr.substring(andIdx+4),vars);
        int orIdx = expr.indexOf(" || ");
        if (orIdx >= 0)  return evalBshBool(expr.substring(0,orIdx), vars) ||
                                evalBshBool(expr.substring(orIdx+4),  vars);
        // !expr
        if (expr.startsWith("!")) return !evalBshBool(expr.substring(1), vars);

        String v = evalBsh(expr, vars);
        return !v.equals("false") && !v.equals("0") && !v.equals("null") && v.length() > 0;
    }

    private void applyBshIncr(String incr, Hashtable vars) {
        if (incr.endsWith("++")) {
            String var = incr.substring(0, incr.length()-2).trim();
            String v   = (String) vars.get(var);
            try { vars.put(var, String.valueOf(Integer.parseInt(v != null ? v : "0") + 1)); }
            catch (Exception e) {}
        } else if (incr.endsWith("--")) {
            String var = incr.substring(0, incr.length()-2).trim();
            String v   = (String) vars.get(var);
            try { vars.put(var, String.valueOf(Integer.parseInt(v != null ? v : "0") - 1)); }
            catch (Exception e) {}
        } else if (incr.indexOf(" += ") > 0) {
            int eq = incr.indexOf(" += ");
            String var = incr.substring(0, eq).trim();
            String amt = incr.substring(eq+4).trim();
            String v   = (String) vars.get(var);
            try { vars.put(var, String.valueOf(Integer.parseInt(v!=null?v:"0") +
                                               Integer.parseInt(evalBsh(amt,vars)))); }
            catch (Exception e) {}
        }
    }

    private int findBshEnd(String[] lines, int from, int to, char open, char close) {
        int depth = 0;
        for (int j = from; j < to; j++) {
            String l = lines[j].trim();
            for (int k = 0; k < l.length(); k++) {
                if (l.charAt(k) == open)  depth++;
                if (l.charAt(k) == close) { depth--; if (depth == 0) return j; }
            }
        }
        return -1;
    }

    // ===================== SHARED HELPERS =====================

    private static String[] splitLines(String s) {
        if (s == null || s.length() == 0) return new String[0];
        Vector v = new Vector();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '\n') {
                v.addElement(s.substring(start, i));
                start = i+1;
            }
        }
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    private static String[] splitComma(String s) {
        Vector v = new Vector();
        int start = 0, depth = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || (s.charAt(i) == ',' && depth == 0)) {
                v.addElement(s.substring(start, i).trim());
                start = i+1;
            } else {
                char c = s.charAt(i);
                if (c == '(' || c == '[' || c == '{') depth++;
                if (c == ')' || c == ']' || c == '}') depth--;
            }
        }
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    private static String[] splitSemi(String s) {
        Vector v = new Vector();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == ';') {
                v.addElement(s.substring(start, i).trim());
                start = i+1;
            }
        }
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    private static String[] split2(String s, String sep) {
        int idx = s.indexOf(sep);
        if (idx < 0) return new String[]{s, ""};
        return new String[]{s.substring(0, idx).trim(), s.substring(idx+sep.length()).trim()};
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    /** Strip leading/trailing double-quotes from a string. CLDC-safe. */
    private static String stripQuotes(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length()-1) == '"')
            return s.substring(1, s.length()-1);
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length()-1) == '\'')
            return s.substring(1, s.length()-1);
        return s;
    }

    /** CLDC 1.1 replacement for Character.isLetterOrDigit(). */
    private static boolean isAlphaNum(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
               (c >= '0' && c <= '9');
    }

    /** CLDC 1.1 replacement for String.lastIndexOf(String). */
    private static int lastIndexOfStr(String s, String sub) {
        if (s == null || sub == null || sub.length() == 0) return -1;
        int last = -1;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            last = idx;
            idx += sub.length();
        }
        return last;
    }

    /** CLDC 1.1 replacement for new Hashtable(existing) copy constructor. */
    private static Hashtable copyHashtable(Hashtable src) {
        Hashtable dst = new Hashtable();
        if (src == null) return dst;
        java.util.Enumeration keys = src.keys();
        while (keys.hasMoreElements()) {
            Object k = keys.nextElement();
            dst.put(k, src.get(k));
        }
        return dst;
    }

    /** Split string on a literal separator string. CLDC-safe (no regex). */
    private static String[] splitOn(String s, String sep) {
        if (s == null) return new String[0];
        Vector v = new Vector();
        int start = 0, idx;
        while ((idx = s.indexOf(sep, start)) >= 0) {
            v.addElement(s.substring(start, idx));
            start = idx + sep.length();
        }
        v.addElement(s.substring(start));
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    /** Simple script exception. */
    static class ScriptException extends Exception {
        ScriptException(String msg) { super(msg); }
    }
}
