package com.priya.ai;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader/writer (plain Java, no android / org.json), so AgentStore can also be checked on a
 * plain JVM (tests/AgentStoreCheck.java). Objects = LinkedHashMap<String,Object>, arrays = ArrayList<Object>,
 * numbers = Long or Double, plus String / Boolean / null.
 */
final class MiniJson {

    private MiniJson() {}

    // ------------------------------------------------------------------ parse

    static Object parse(String s) {
        if (s == null) throw new IllegalArgumentException("null json");
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw new IllegalArgumentException("extra text at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        return (Map<String, Object>) o;
    }

    private static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { if (i >= s.length()) throw new IllegalArgumentException("unexpected end"); return s.charAt(i); }
        Object value() {
            char c = peek();
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return num();
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new IllegalArgumentException("key expected at " + i);
                String k = str();
                ws();
                if (peek() != ':') throw new IllegalArgumentException(": expected at " + i);
                i++; ws();
                m.put(k, value());
                ws();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException(", expected at " + (i - 1));
            }
        }
        List<Object> arr() {
            List<Object> a = new ArrayList<>();
            i++; ws();
            if (peek() == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                char c = peek();
                i++;
                if (c == ']') return a;
                if (c != ',') throw new IllegalArgumentException(", expected at " + (i - 1));
            }
        }
        String str() {
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = peek();
                    i++;
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("bad \\u");
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
        }
        Object num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty()) throw new IllegalArgumentException("bad value at " + st);
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
            try { return Long.parseLong(t); } catch (NumberFormatException e) { return Double.parseDouble(t); }
        }
    }

    // ------------------------------------------------------------------ write

    static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, -1, 0);
        return b.toString();
    }

    static String pretty(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, 1, 0);
        return b.toString();
    }

    private static void nl(StringBuilder b, int ind, int lvl) {
        if (ind < 0) return;
        b.append('\n');
        for (int k = 0; k < ind * lvl; k++) b.append(' ');
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object o, int ind, int lvl) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String) { quote(b, (String) o); return; }
        if (o instanceof Boolean || o instanceof Long || o instanceof Integer) { b.append(o); return; }
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d);
            return;
        }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            b.append('{');
            Iterator<Map.Entry<String, Object>> it = m.entrySet().iterator();
            boolean first = true;
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                if (!first) b.append(',');
                first = false;
                nl(b, ind, lvl + 1);
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue(), ind, lvl + 1);
            }
            if (!first) nl(b, ind, lvl);
            b.append('}');
            return;
        }
        if (o instanceof List) {
            List<Object> a = (List<Object>) o;
            b.append('[');
            for (int k = 0; k < a.size(); k++) {
                if (k > 0) b.append(',');
                nl(b, ind, lvl + 1);
                write(b, a.get(k), ind, lvl + 1);
            }
            if (!a.isEmpty()) nl(b, ind, lvl);
            b.append(']');
            return;
        }
        quote(b, String.valueOf(o));
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------------ helpers

    static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    static long lng(Map<String, Object> m, String k, long def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) return ((Number) v).longValue();
        try { return v == null ? def : Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return def; }
    }

    static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return def;
        String t = String.valueOf(v).trim().toLowerCase();
        return t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("haan") || t.equals("on");
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
    }

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) if (o != null && !String.valueOf(o).trim().isEmpty()) out.add(String.valueOf(o).trim());
        } else if (v != null) {
            for (String t : String.valueOf(v).split("[,;\\n]+")) if (!t.trim().isEmpty()) out.add(t.trim());
        }
        return out;
    }
}
