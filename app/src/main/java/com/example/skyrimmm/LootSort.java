package com.example.skyrimmm;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Sorts a plugin load order with the rules of LOOT masterlists (groups and "load after" rules).
 * Pure Java with no Android dependencies. The Legendary Edition masterlist is the main source; the Special Edition one fills in
 * plugins the first list does not know, because the app converts Special Edition mods.
 * Not a full LOOT: it does not compare plugin records, so plugins without rules keep their current relative order.
 */
public final class LootSort {
    private LootSort() {}

    private static final String[] OFFICIAL = { "skyrim.esm", "update.esm", "dawnguard.esm", "hearthfires.esm", "dragonborn.esm" };

    // ------------------------------------------------------------------ masterlist model

    public static final class Rule { public final String target; public final String cond; Rule(String t, String c) { target = t; cond = c; } }

    public static final class Entry {
        public final String name; public final boolean regex; final Pattern pat;
        public String group; public final List<Rule> after = new ArrayList<>();
        Entry(String name) {
            this.name = name; this.regex = isRegex(name);
            Pattern p = null;
            if (regex) try { p = Pattern.compile(name, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); } catch (Exception ignored) {}
            pat = p;
        }
        boolean matches(String lower, String raw) { return regex ? pat != null && pat.matcher(raw).matches() : name.equalsIgnoreCase(raw); }
    }

    public static final class Master {
        public final LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>(); // group name -> groups it loads after
        public final List<Entry> entries = new ArrayList<>();
        final Map<String, List<Entry>> literal = new HashMap<>();
        final List<Entry> regexes = new ArrayList<>();
        private final Map<String, Set<String>> ancMemo = new HashMap<>();

        void index() {
            for (Entry e : entries) {
                if (e.regex) { if (e.pat != null) regexes.add(e); }
                else literal.computeIfAbsent(e.name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
            }
        }

        /** Entries that apply to a plugin: exact-name entries first, then pattern entries. */
        public List<Entry> find(String name) {
            List<Entry> out = new ArrayList<>();
            List<Entry> l = literal.get(name.toLowerCase(Locale.ROOT));
            if (l != null) out.addAll(l);
            for (Entry e : regexes) if (e.matches(null, name)) out.add(e);
            return out;
        }

        /** Every group that loads before `g`, directly or through other groups. */
        Set<String> ancestors(String g) {
            Set<String> got = ancMemo.get(g);
            if (got != null) return got;
            Set<String> out = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>(groups.getOrDefault(g, Collections.emptyList()));
            while (!todo.isEmpty()) {
                String a = todo.pop();
                if (a.equals(g) || !out.add(a)) continue;
                todo.addAll(groups.getOrDefault(a, Collections.emptyList()));
            }
            ancMemo.put(g, out);
            return out;
        }
    }

    static boolean isRegex(String s) {
        for (int i = 0; i < s.length(); i++) if (":\\*?|".indexOf(s.charAt(i)) >= 0) return true;
        return false;
    }

    // ------------------------------------------------------------------ YAML (only the parts the sorter needs)

    private static final class Sc { String v; int end; }

    private static Sc scalar(String s, int from, boolean flow, Map<String, String> anchors) {
        int i = from;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        Sc r = new Sc();
        if (i < s.length() && s.charAt(i) == '&') { // anchor: skip its name
            while (i < s.length() && s.charAt(i) != ' ') i++;
            while (i < s.length() && s.charAt(i) == ' ') i++;
        }
        if (i >= s.length()) { r.v = ""; r.end = i; return r; }
        char c = s.charAt(i);
        if (c == '*') {
            int j = i + 1;
            while (j < s.length() && " ,]}".indexOf(s.charAt(j)) < 0) j++;
            String id = s.substring(i + 1, j);
            r.v = anchors.getOrDefault(id, id); r.end = j; return r;
        }
        if (c == '\'') {
            StringBuilder b = new StringBuilder(); int j = i + 1;
            while (j < s.length()) {
                char d = s.charAt(j);
                if (d == '\'') { if (j + 1 < s.length() && s.charAt(j + 1) == '\'') { b.append('\''); j += 2; continue; } break; }
                b.append(d); j++;
            }
            r.v = b.toString(); r.end = Math.min(j + 1, s.length()); return r;
        }
        if (c == '"') {
            StringBuilder b = new StringBuilder(); int j = i + 1;
            while (j < s.length()) {
                char d = s.charAt(j);
                if (d == '\\' && j + 1 < s.length()) {
                    char e = s.charAt(j + 1);
                    b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e); j += 2; continue;
                }
                if (d == '"') break;
                b.append(d); j++;
            }
            r.v = b.toString(); r.end = Math.min(j + 1, s.length()); return r;
        }
        int j = i;
        while (j < s.length()) {
            char d = s.charAt(j);
            if (flow && (d == ',' || d == ']' || d == '}')) break;
            if (d == '#' && j > i && s.charAt(j - 1) == ' ') break;
            j++;
        }
        r.v = s.substring(i, j).trim(); r.end = j; return r;
    }

    /** Splits the inside of a flow collection at top-level commas, respecting quotes and nesting. */
    private static List<String> splitFlow(String s) {
        List<String> out = new ArrayList<>(); StringBuilder b = new StringBuilder(); int depth = 0; char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) {
                b.append(c);
                if (c == q) { if (q == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') { b.append('\''); i++; } else q = 0; }
                else if (q == '"' && c == '\\' && i + 1 < s.length()) { b.append(s.charAt(++i)); }
                continue;
            }
            if (c == '\'' || c == '"') { q = c; b.append(c); }
            else if (c == '[' || c == '{') { depth++; b.append(c); }
            else if (c == ']' || c == '}') { depth--; b.append(c); }
            else if (c == ',' && depth == 0) { out.add(b.toString()); b.setLength(0); }
            else b.append(c);
        }
        if (b.toString().trim().length() > 0) out.add(b.toString());
        return out;
    }

    private static Rule dictRule(String inner, Map<String, String> anchors) {
        String name = null, cond = null;
        for (String part : splitFlow(inner)) {
            int k = part.indexOf(':'); if (k < 0) continue;
            String key = part.substring(0, k).trim(); String val = scalar(part, k + 1, true, anchors).v;
            if (key.equals("name")) name = val; else if (key.equals("condition")) cond = val;
        }
        return name == null ? null : new Rule(name, cond);
    }

    private static void flowList(String rest, List<Rule> out, Map<String, String> anchors) {
        int a = rest.indexOf('['), b = rest.lastIndexOf(']');
        if (a < 0 || b < a) return;
        for (String item : splitFlow(rest.substring(a + 1, b))) {
            String t = item.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("{")) { Rule r = dictRule(t.substring(1, t.lastIndexOf('}') < 0 ? t.length() : t.lastIndexOf('}')), anchors); if (r != null) out.add(r); }
            else out.add(new Rule(scalar(t, 0, true, anchors).v, null));
        }
    }

    /** True when a single-quoted value on this line is not closed yet. */
    private static boolean openQuote(String l) {
        int c = l.indexOf(':'); if (c < 0) return false;
        int i = c + 1;
        while (i < l.length() && l.charAt(i) == ' ') i++;
        if (i >= l.length() || l.charAt(i) != '\'') return false;
        for (int j = i + 1; j < l.length(); j++) {
            if (l.charAt(j) == '\'') { if (j + 1 < l.length() && l.charAt(j + 1) == '\'') { j++; continue; } return false; }
        }
        return true;
    }

    private static int indent(String l) { int i = 0; while (i < l.length() && l.charAt(i) == ' ') i++; return i; }
    private static boolean blank(String l) { String t = l.trim(); return t.isEmpty() || t.startsWith("#"); }

    /** Reads the `groups:` and `plugins:` sections of a LOOT masterlist. */
    public static Master parse(String text) {
        String[] L = text.replace("\r", "").split("\n", -1);
        Map<String, String> anchors = new HashMap<>();
        Pattern an = Pattern.compile("^\\s*(?:- )?[A-Za-z_]+:\\s+&([A-Za-z0-9_\\-]+)\\s+(\\S.*)$");
        for (String l : L) {
            java.util.regex.Matcher m = an.matcher(l);
            if (m.matches()) anchors.put(m.group(1), scalar(m.group(2), 0, false, anchors).v);
        }
        Master ms = new Master();
        String sec = "";
        for (int i = 0; i < L.length; i++) {
            String l = L[i];
            if (blank(l)) continue;
            if (indent(l) == 0) { sec = l.trim(); int c = sec.indexOf(':'); sec = c < 0 ? sec : sec.substring(0, c); continue; }
            boolean groups = sec.equals("groups"), plugins = sec.equals("plugins");
            if (!groups && !plugins) continue;
            if (!l.startsWith("  - name:") || indent(l) != 2) continue;
            String head = l;
            while (openQuote(head) && i + 1 < L.length) { head = head + " " + L[++i].trim(); } // a quoted name can continue on the next lines
            l = head;
            String name = scalar(l, "  - name:".length(), false, anchors).v;
            String group = null; List<Rule> after = new ArrayList<>();
            int j = i + 1;
            for (; j < L.length; j++) {
                String m = L[j];
                if (blank(m)) continue;
                if (indent(m) <= 2) break;
                if (indent(m) != 4) continue;
                if (m.startsWith("    group:")) group = scalar(m, "    group:".length(), false, anchors).v;
                else if (m.startsWith("    after:")) {
                    String rest = m.substring("    after:".length()).trim();
                    if (rest.startsWith("[")) flowList(rest, after, anchors);
                    else if (rest.isEmpty() || rest.startsWith("#")) {
                        int k = j + 1;
                        while (k < L.length) {
                            String n = L[k];
                            if (blank(n)) { k++; continue; }
                            if (indent(n) < 6) break;
                            if (indent(n) == 6 && n.startsWith("      - ")) {
                                String it = n.substring(8).trim();
                                if (it.startsWith("{")) { Rule r = dictRule(it.substring(1, it.lastIndexOf('}') < 0 ? it.length() : it.lastIndexOf('}')), anchors); if (r != null) after.add(r); k++; }
                                else if (it.startsWith("name:")) {
                                    String tn = scalar(it, 5, false, anchors).v, cond = null; k++;
                                    while (k < L.length && (blank(L[k]) || indent(L[k]) >= 8)) {
                                        String p = L[k].trim();
                                        if (p.startsWith("condition:")) cond = scalar(p, 10, false, anchors).v;
                                        k++;
                                    }
                                    after.add(new Rule(tn, cond));
                                } else { after.add(new Rule(scalar(it, 0, false, anchors).v, null)); k++; }
                            } else k++;
                        }
                        j = k - 1;
                    }
                }
            }
            if (groups) { List<String> a = new ArrayList<>(); for (Rule r : after) a.add(r.target); ms.groups.put(name, a); }
            else { Entry e = new Entry(name); e.group = group; e.after.addAll(after); ms.entries.add(e); }
            i = j - 1;
        }
        ms.index();
        return ms;
    }

    // ------------------------------------------------------------------ conditions

    /** What the conditions in "load after" rules are checked against. */
    public static final class Env {
        public final Set<String> files;                 // lower-case names present in the Data folder
        public final Set<String> active;                // lower-case names of active plugins
        public final Map<String, Boolean> master;       // lower-case plugin name -> master flag
        public final Map<String, List<String>> masters; // lower-case plugin name -> its masters
        public Env(Set<String> files, Set<String> active, Map<String, Boolean> master, Map<String, List<String>> masters) {
            this.files = files; this.active = active; this.master = master; this.masters = masters;
        }
    }

    private static final class Unsupported extends RuntimeException { Unsupported(String m) { super(m, null, false, false); } }

    private static final class Cond {
        final String s; int p = 0; final Env env;
        Cond(String s, Env env) { this.s = s; this.env = env; }
        void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }
        boolean word(String w) {
            ws();
            if (s.startsWith(w, p) && (p + w.length() >= s.length() || !Character.isLetterOrDigit(s.charAt(p + w.length())) && s.charAt(p + w.length()) != '_')) { p += w.length(); return true; }
            return false;
        }
        boolean or() { boolean v = and(); while (word("or")) { boolean r = and(); v = v || r; } return v; }
        boolean and() { boolean v = not(); while (word("and")) { boolean r = not(); v = v && r; } return v; }
        boolean not() { if (word("not")) return !not(); return prim(); }
        boolean prim() {
            ws();
            if (p < s.length() && s.charAt(p) == '(') { p++; boolean v = or(); ws(); if (p >= s.length() || s.charAt(p) != ')') throw new Unsupported("paren"); p++; return v; }
            int a = p;
            while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_')) p++;
            String fn = s.substring(a, p); ws();
            if (fn.isEmpty() || p >= s.length() || s.charAt(p) != '(') throw new Unsupported("syntax");
            p++; List<String> args = new ArrayList<>();
            while (true) {
                ws();
                if (p >= s.length()) throw new Unsupported("end");
                char c = s.charAt(p);
                if (c == ')') { p++; break; }
                if (c == ',') { p++; continue; }
                if (c == '"') { int e = s.indexOf('"', p + 1); if (e < 0) throw new Unsupported("quote"); args.add(s.substring(p + 1, e)); p = e + 1; }
                else { int e = p; while (e < s.length() && ",)".indexOf(s.charAt(e)) < 0) e++; args.add(s.substring(p, e).trim()); p = e; }
            }
            switch (fn) {
                case "file": return file(args.get(0));
                case "active": return active(args.get(0));
                case "is_master": { Boolean m = env.master.get(args.get(0).toLowerCase(Locale.ROOT)); return m != null && m; }
                default: throw new Unsupported(fn);
            }
        }
        boolean file(String path) {
            if (isRegex(path)) {
                if (path.indexOf('/') >= 0 || path.contains("\\/")) throw new Unsupported("path regex");
                Pattern pt = Pattern.compile(path, Pattern.CASE_INSENSITIVE);
                for (String f : env.files) if (pt.matcher(f).matches()) return true;
                return false;
            }
            if (path.indexOf('/') >= 0 || path.indexOf('\\') >= 0) throw new Unsupported("path");
            return env.files.contains(path.toLowerCase(Locale.ROOT));
        }
        boolean active(String name) {
            if (isRegex(name)) {
                Pattern pt = Pattern.compile(name, Pattern.CASE_INSENSITIVE);
                for (String f : env.active) if (pt.matcher(f).matches()) return true;
                return false;
            }
            return env.active.contains(name.toLowerCase(Locale.ROOT));
        }
    }

    /** True/false when the condition could be worked out, null when it uses something this app cannot check. */
    static Boolean eval(String cond, Env env) {
        try {
            Cond c = new Cond(cond, env);
            boolean v = c.or(); c.ws();
            return c.p == cond.length() ? v : null;
        } catch (RuntimeException e) { return null; }
    }

    // ------------------------------------------------------------------ the sort

    public static final class Move {
        public final String name; public final int from, to; public final String why;
        Move(String n, int f, int t, String w) { name = n; from = f; to = t; why = w; }
    }

    public static final class Result {
        public final List<String> order; public final List<Move> moved;
        public final int known;        // plugins the masterlists have a group or a rule for
        public final int rulesUsed;    // "load after" rules that applied to this load order
        public final int rulesSkipped; // rules left out because their condition could not be checked or was false/unsupported
        public final int conflicts;    // order constraints that contradicted each other and were dropped
        Result(List<String> o, List<Move> m, int k, int ru, int rs, int c) { order = o; moved = m; known = k; rulesUsed = ru; rulesSkipped = rs; conflicts = c; }
    }

    private static boolean officialName(String lower) { for (String o : OFFICIAL) if (o.equals(lower)) return true; return false; }

    /**
     * Returns the load order sorted by the masterlist rules. `current` is the order today; `se` may be null.
     * Official files stay first in their fixed order, master-flagged plugins stay before regular ones and every plugin stays after its masters.
     */
    public static Result sort(List<String> current, Master le, Master se, Env env) {
        final int n = current.size();
        final String[] name = current.toArray(new String[0]);
        final String[] low = new String[n];
        final Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < n; i++) { low[i] = name[i].toLowerCase(Locale.ROOT); at.putIfAbsent(low[i], i); }
        final boolean[] mast = new boolean[n]; final int[] off = new int[n];
        for (int i = 0; i < n; i++) {
            Boolean m = env.master.get(low[i]);
            mast[i] = m != null ? m : low[i].endsWith(".esm");
            off[i] = OFFICIAL.length;
            for (int k = 0; k < OFFICIAL.length; k++) if (OFFICIAL[k].equals(low[i])) off[i] = k;
        }
        // group of every plugin, and the "load after" rules that apply
        final String[] group = new String[n];
        final BitSet[] hard = new BitSet[n], soft = new BitSet[n], grp = new BitSet[n];
        for (int i = 0; i < n; i++) { hard[i] = new BitSet(n); soft[i] = new BitSet(n); grp[i] = new BitSet(n); }
        final Map<Long, String> ruleWhy = new HashMap<>();
        int known = 0, used = 0, skipped = 0;
        Set<String> seBeforeDefault = se == null ? Collections.<String>emptySet() : se.ancestors("default");
        for (int i = 0; i < n; i++) {
            group[i] = "default";
            if (off[i] < OFFICIAL.length) continue;
            List<Entry> a = le.find(name[i]), b = se == null ? Collections.<Entry>emptyList() : se.find(name[i]);
            String g = null; boolean any = false;
            for (Entry e : a) if (e.group != null) { g = e.group; break; }
            if (g == null) {
                for (Entry e : b) if (e.group != null) {
                    String sg = e.group;
                    g = le.groups.containsKey(sg) ? sg : seBeforeDefault.contains(sg) ? "Underrides" : "default";
                    break;
                }
            }
            if (g != null && le.groups.containsKey(g)) { group[i] = g; any = true; }
            for (List<Entry> src : Arrays.asList(a, b)) for (Entry e : src) for (Rule r : e.after) {
                Integer t = at.get(r.target.toLowerCase(Locale.ROOT));
                if (t == null || t == i) continue;
                if (r.cond != null) {
                    Boolean ok = eval(r.cond, env);
                    if (ok == null || !ok) { skipped++; continue; }
                }
                if (!mast[t] && mast[i]) { skipped++; continue; } // a regular plugin cannot go before a master
                if (!soft[t].get(i)) { soft[t].set(i); used++; ruleWhy.put(((long) t << 32) | i, "loads after " + name[t]); }
                any = true;
            }
            if (any) known++;
        }
        // hard edges: masters before the plugins that need them
        for (int i = 0; i < n; i++) {
            List<String> ms = env.masters.get(low[i]);
            if (ms == null) continue;
            for (String m : ms) { Integer t = at.get(m.toLowerCase(Locale.ROOT)); if (t != null && t != i) hard[t].set(i); }
        }
        // group edges: every plugin of a group loads after every plugin of the groups that come before it
        for (int i = 0; i < n; i++) {
            if (off[i] < OFFICIAL.length || !le.groups.containsKey(group[i])) continue;
            Set<String> anc = le.ancestors(group[i]);
            if (anc.isEmpty()) continue;
            for (int q = 0; q < n; q++) {
                if (q == i || group[q].equals(group[i]) || !anc.contains(group[q])) continue;
                if (mast[q] == false && mast[i] == true) continue; // regular plugins never go before masters
                if (!hard[q].get(i) && !soft[q].get(i)) grp[q].set(i);
            }
        }
        // stable topological sort: ready plugins are taken in (official, master, current position) order
        // Explicit constraints (masters, "load after" rules) always win; group order is dropped first when the two contradict.
        final int[] ix = new int[n], ig = new int[n];
        for (int a = 0; a < n; a++) {
            for (int b = hard[a].nextSetBit(0); b >= 0; b = hard[a].nextSetBit(b + 1)) ix[b]++;
            for (int b = soft[a].nextSetBit(0); b >= 0; b = soft[a].nextSetBit(b + 1)) if (!hard[a].get(b)) ix[b]++;
            for (int b = grp[a].nextSetBit(0); b >= 0; b = grp[a].nextSetBit(b + 1)) ig[b]++;
        }
        final long N = n + 1;
        PriorityQueue<Long> heap = new PriorityQueue<>();
        for (int i = 0; i < n; i++) if (ix[i] == 0 && ig[i] == 0) heap.add(key(off[i], mast[i], i, N));
        boolean[] done = new boolean[n]; int[] order = new int[n]; int cnt = 0, conflicts = 0;
        while (cnt < n) {
            int cur;
            if (heap.isEmpty()) { // contradicting rules: take the first plugin only group order holds back (else the first one left)
                int best = -1; long bk = Long.MAX_VALUE;
                for (int pass = 0; pass < 2 && best < 0; pass++)
                    for (int i = 0; i < n; i++)
                        if (!done[i] && (pass == 1 || ix[i] == 0) && key(off[i], mast[i], i, N) < bk) { bk = key(off[i], mast[i], i, N); best = i; }
                cur = best; conflicts++;
            } else {
                cur = (int) (heap.poll() % N);
                if (done[cur]) continue;
            }
            done[cur] = true; order[cnt++] = cur;
            for (int b = hard[cur].nextSetBit(0); b >= 0; b = hard[cur].nextSetBit(b + 1)) if (!done[b]) { ix[b]--; if (ix[b] == 0 && ig[b] == 0) heap.add(key(off[b], mast[b], b, N)); }
            for (int b = soft[cur].nextSetBit(0); b >= 0; b = soft[cur].nextSetBit(b + 1)) if (!done[b] && !hard[cur].get(b)) { ix[b]--; if (ix[b] == 0 && ig[b] == 0) heap.add(key(off[b], mast[b], b, N)); }
            for (int b = grp[cur].nextSetBit(0); b >= 0; b = grp[cur].nextSetBit(b + 1)) if (!done[b]) { ig[b]--; if (ix[b] == 0 && ig[b] == 0) heap.add(key(off[b], mast[b], b, N)); }
        }
        final int[] pos = new int[n];
        for (int k = 0; k < n; k++) pos[order[k]] = k;
        List<String> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) out.add(name[order[k]]);
        List<Move> moved = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int i = order[k];
            if (pos[i] == i) continue;
            moved.add(new Move(name[i], i, pos[i], why(i, n, name, group, hard, soft, grp, ruleWhy)));
        }
        return new Result(out, moved, known, used, skipped, conflicts);
    }

    private static long key(int official, boolean master, int idx, long N) { return ((long) official * 2 + (master ? 0 : 1)) * N + idx; }

    /** The first constraint that was broken before the sort and explains why this plugin moved. */
    private static String why(int i, int n, String[] name, String[] group, BitSet[] hard, BitSet[] soft, BitSet[] grp, Map<Long, String> ruleWhy) {
        for (int q = 0; q < n; q++) {
            if (q == i || q < i) continue; // q was after i originally; the rule says it must be before
            if (hard[q].get(i)) return "needs " + name[q] + " (its master) before it";
            if (soft[q].get(i) || grp[q].get(i)) {
                String r = ruleWhy.get(((long) q << 32) | i);
                return r != null ? r : "group \"" + group[i] + "\" loads after group \"" + group[q] + "\" (" + name[q] + ")";
            }
        }
        for (int r = 0; r < n; r++) {
            if (r == i || r > i) continue; // r was before i originally; the rule says it must be after
            if (hard[i].get(r)) return "must load before " + name[r] + ", which needs it";
            if (soft[i].get(r) || grp[i].get(r)) {
                String w = ruleWhy.get(((long) i << 32) | r);
                return w != null ? name[r] + " " + w.replace("loads after " + name[i], "must load after it") : "group \"" + group[i] + "\" loads before group \"" + group[r] + "\" (" + name[r] + ")";
            }
        }
        return "moved to make room for other plugins that had to move";
    }
}
