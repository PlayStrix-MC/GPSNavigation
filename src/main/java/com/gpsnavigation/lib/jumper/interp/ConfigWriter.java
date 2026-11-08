package com.gpsnavigation.lib.jumper.interp;

import com.gpsnavigation.lib.jumper.runtime.JTable;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes Java values as .jmc text, the inverse of {@link Config#parse}; {@link Config#format} and
 * {@link Config#write} are its public face. One pass over the value, straight into one StringBuilder:
 * no tokens, no tree, no copies of the tables.
 *
 * <p>The text reads back to the same values: strings, numbers ({@code Integer}, {@code Long},
 * {@code Double}; {@code Short}, {@code Byte} and {@code Float} come back as the wider type), booleans,
 * {@code null} inside arrays, tables and arrays. A table entry whose value is {@code null} is left out, as a
 * table cannot hold {@code null} anyway. Keys are written as strings. A record or a class with public fields
 * is written as a table of its components or fields (see {@link ConfigBinder}); a {@link Config.Comment} on a
 * component becomes a {@code //} comment above its declaration, one on the type the header of the file.
 *
 * <p>The top level of a table is written as declarations, in its order, when every key is a plain name the
 * language accepts as a variable ({@code port}, not {@code class} or {@code my key}); the declared type is
 * the one of the value ({@code int}, {@code long}, {@code double}, {@code boolean}, {@code String}, otherwise
 * {@code dyn}). Otherwise, and for a top level that is not a table, the file is {@code return <value>;}.
 * A table or array that holds another non-empty one is broken over lines; anything else is written on one
 * line when it fits in {@link #WIDTH} columns.
 */
final class ConfigWriter {
    static final int WIDTH = 100;
    private static final String INDENT = "    ";

    /** The words the lexer reads as keywords or literals: a key with one of these names is quoted. */
    private static final Set<String> RESERVED = Set.of(
            "dyn", "int", "long", "double", "boolean", "String", "void", "class", "new", "return", "if", "else",
            "while", "for", "do", "break", "continue", "true", "false", "null", "import", "this", "super",
            "extends", "static", "try", "switch", "case", "default", "catch", "finally", "throw");

    private final StringBuilder sb;
    /** Where the line being written starts in sb, so that the width check is a subtraction. */
    private int lineStart;
    private int depth;
    /** The containers being written, kept only once the value is suspiciously deep: a value that contains itself. */
    private Set<Object> open;

    private ConfigWriter(int capacity) {
        sb = new StringBuilder(capacity);
    }

    /** The text of a whole file. */
    static String format(Object value, String header) {
        ConfigWriter w = new ConfigWriter(256);
        if (header == null && ConfigBinder.isObject(value)) header = ConfigBinder.schema(value.getClass()).comment;
        if (header != null && !header.isEmpty()) {
            w.comment(header, "");
            w.nl();
        }
        w.file(value);
        return w.sb.toString();
    }

    /** The text of one value, as it goes after {@code name = } in a declaration: no newline, no semicolon. */
    static String formatValue(Object value) {
        ConfigWriter w = new ConfigWriter(64);
        w.value(value, 0);
        return w.sb.toString();
    }

    /** {@code type name = value;} with its comment, for a declaration appended to a file. */
    static String declaration(String name, Object value, String comment) {
        ConfigWriter w = new ConfigWriter(64);
        if (comment != null && !comment.isEmpty()) w.comment(comment, "");
        w.declaration(name, value);
        return w.sb.toString();
    }

    private void file(Object value) {
        Entries e = isTable(value) ? entries(value) : null;
        if (e == null || e.n == 0 || !e.allBare()) {
            sb.append("return ");
            value(value, 0);
            sb.append(";\n");
            return;
        }
        boolean blank = false;
        for (int i = 0; i < e.n; i++) {
            String c = e.comments == null ? null : e.comments[i];
            if (c != null && !c.isEmpty()) {
                if (i > 0) nl();
                comment(c, "");
                blank = true;
            } else if (blank) {
                nl();
                blank = false;
            }
            declaration(e.keys[i], e.vals[i]);
        }
    }

    private void declaration(String name, Object value) {
        sb.append(declaredType(value)).append(' ').append(name).append(" = ");
        value(value, 0);
        sb.append(';');
        nl();
    }

    private void nl() {
        sb.append('\n');
        lineStart = sb.length();
    }

    private void comment(String text, String pad) {
        int from = 0;
        while (true) {
            int nl = text.indexOf('\n', from);
            String line = (nl < 0 ? text.substring(from) : text.substring(from, nl)).stripTrailing();
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            sb.append(pad).append("//");
            if (!line.isEmpty()) sb.append(' ').append(line);
            nl();
            if (nl < 0) return;
            from = nl + 1;
        }
    }

    // ------------------------------------------------------------------ values

    private void value(Object v, int indent) {
        if (scalar(v)) return;
        Entries e = null;
        List<?> items = null;
        Object[] arr = null;
        int n;
        if (isTable(v)) {
            e = entries(v);
            n = e.n;
        } else {
            items = itemsOf(v);
            if (items == null) {
                arr = arrayOf(v);
                n = arr.length;
            } else {
                n = items.size();
            }
        }
        if (n == 0) {
            sb.append(e != null ? "{}" : "[]");
            return;
        }
        enter(v);
        try {
            boolean nested = false;
            for (int i = 0; i < n; i++) {
                Object c = e != null ? e.vals[i] : items != null ? items.get(i) : arr[i];
                if (isContainer(c) && !isEmpty(c)) { nested = true; break; }
            }
            if (!nested) {
                int mark = sb.length();
                flat(e, items, arr, n);   // never a newline: so the rollback keeps lineStart right
                if (sb.length() - lineStart <= WIDTH) return;
                sb.setLength(mark);
            }
            block(e, items, arr, n, indent);
        } finally {
            leave(v);
        }
    }

    /** One line: {@code { a: 1, b: "x" }} or {@code [1, "x"]}. */
    private void flat(Entries e, List<?> items, Object[] arr, int n) {
        if (e != null) {
            sb.append("{ ");
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(", ");
                key(e.keys[i]);
                sb.append(": ");
                value(e.vals[i], 0);
            }
            sb.append(" }");
        } else {
            sb.append('[');
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(", ");
                value(items != null ? items.get(i) : arr[i], 0);
            }
            sb.append(']');
        }
    }

    /** One entry per line, indented one level more than the opening bracket. */
    private void block(Entries e, List<?> items, Object[] arr, int n, int indent) {
        sb.append(e != null ? '{' : '[');
        nl();
        for (int i = 0; i < n; i++) {
            pad(indent + 1);
            if (e != null) {
                key(e.keys[i]);
                sb.append(": ");
                value(e.vals[i], indent + 1);
            } else {
                value(items != null ? items.get(i) : arr[i], indent + 1);
            }
            if (i + 1 < n) sb.append(',');
            nl();
        }
        pad(indent);
        sb.append(e != null ? '}' : ']');
    }

    private void pad(int indent) {
        for (int i = 0; i < indent; i++) sb.append(INDENT);
    }

    /** Writes a scalar and says so; false for anything else, with nothing written. The common kinds first. */
    private boolean scalar(Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) quote(s);
        else if (v instanceof Integer i) sb.append(i.intValue());
        else if (v instanceof Boolean b) sb.append(b ? "true" : "false");
        else if (v instanceof Double d) number(d);
        else if (v instanceof Long l) sb.append(l.longValue()).append('L');   // -9223372036854775808L is the lexer's special case
        else if (v instanceof Float f) number(Double.parseDouble(f.toString()));
        else if (v instanceof Short || v instanceof Byte) sb.append(((Number) v).intValue());
        else if (v instanceof Character c) quote(String.valueOf(c));
        else if (v instanceof Enum<?> e) quote(e.name());
        else return false;
        return true;
    }

    private void number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d))
            throw new IllegalArgumentException("NaN and infinity cannot be written to a config: " + d);
        sb.append(d);   // Double.toString: always a '.' or an exponent, so the lexer reads a double
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** A string literal: the escapes the lexer reads back; other characters as they are (the file is UTF-8). */
    private void quote(String s) {
        sb.append('"');
        int n = s.length(), from = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != '"' && c != '\\' && c != 0x7f && !Character.isSurrogate(c)) continue;
            sb.append(s, from, i);
            from = i + 1;
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\0' -> sb.append("\\0");
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(c).append(s.charAt(i + 1));   // a pair: a valid character in UTF-8
                        i++;
                        from = i + 1;
                    } else {   // a control character or a lone surrogate
                        sb.append("\\u").append(HEX[c >> 12 & 15]).append(HEX[c >> 8 & 15]).append(HEX[c >> 4 & 15]).append(HEX[c & 15]);
                    }
                }
            }
        }
        sb.append(s, from, n).append('"');
    }

    private void key(String k) {
        if (bare(k)) sb.append(k);
        else quote(k);
    }

    /** A plain name: usable as a key without quotes and as a declared variable. */
    static boolean bare(String k) {
        int n = k.length();
        if (n == 0) return false;
        for (int i = 0; i < n; i++) {
            char c = k.charAt(i);
            boolean ok = c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (i > 0 && c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return n > 8 || !RESERVED.contains(k);   // the longest reserved word has 8 letters
    }

    /** The type a top-level declaration gets, so that the value reads back as the same Java type. */
    static String declaredType(Object v) {
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return "int";
        if (v instanceof Long) return "long";
        if (v instanceof Double || v instanceof Float) return "double";
        if (v instanceof String || v instanceof Character || v instanceof Enum<?>) return "String";
        return "dyn";
    }

    // ------------------------------------------------------------------ what a value is

    static boolean isScalar(Object v) {
        return v == null || v instanceof String || v instanceof Boolean || v instanceof Integer
                || v instanceof Long || v instanceof Double || v instanceof Float || v instanceof Short
                || v instanceof Byte || v instanceof Character || v instanceof Enum<?>;
    }

    /** Not a scalar: a table, an array, an object - or something that cannot be written, which is found out when it is reached. */
    private static boolean isContainer(Object v) {
        return !(v == null || v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof Enum<?>);
    }

    private static boolean isTable(Object v) {
        return v instanceof Map<?, ?> || v instanceof JTable || ConfigBinder.isObject(v);
    }

    private static boolean isEmpty(Object v) {
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        if (v instanceof Collection<?> c) return c.isEmpty();
        if (v instanceof JTable t) return t.size() == 0;
        if (v.getClass().isArray()) return Array.getLength(v) == 0;
        return false;
    }

    /** The entries of a table, in order, with String keys; null values are left out. */
    static final class Entries {
        String[] keys;
        Object[] vals;
        String[] comments;
        int n;

        Entries(int capacity) {
            keys = new String[capacity];
            vals = new Object[capacity];
        }

        void add(String k, Object v) {
            if (n == keys.length) {
                keys = java.util.Arrays.copyOf(keys, n * 2 + 1);
                vals = java.util.Arrays.copyOf(vals, n * 2 + 1);
            }
            keys[n] = k;
            vals[n++] = v;
        }

        boolean allBare() {
            for (int i = 0; i < n; i++) if (!bare(keys[i])) return false;
            return true;
        }
    }

    private static Entries entries(Object v) {
        if (v instanceof Map<?, ?> m) {
            Entries e = new Entries(m.size());
            Set<String> seen = null;   // only when a key is not a String: then two keys may print the same
            for (Map.Entry<?, ?> en : m.entrySet()) {
                Object k = en.getKey();
                if (k == null) throw new IllegalArgumentException("a table key must not be null");
                if (en.getValue() == null) continue;
                String key;
                if (k instanceof String s) key = s;
                else {
                    key = String.valueOf(k);
                    if (seen == null) {
                        seen = new HashSet<>();
                        for (int i = 0; i < e.n; i++) seen.add(e.keys[i]);
                    }
                }
                if (seen != null && !seen.add(key)) throw new IllegalArgumentException("two keys are written as \"" + key + "\"");
                e.add(key, en.getValue());
            }
            return e;
        }
        if (v instanceof JTable t) {
            if (t.cls() != null) throw new IllegalArgumentException("an instance of a class cannot be written to a config");
            Entries e = new Entries(t.size());
            for (Object k : t.keys()) {
                Object x = t.get(k);
                if (x != null) e.add(k instanceof String s ? s : String.valueOf(k), x);
            }
            return e;
        }
        return ConfigBinder.entries(v);
    }

    /** A List (JArray is one) or another Collection; null for a Java array. */
    private static List<?> itemsOf(Object v) {
        if (v instanceof List<?> l) return l;
        if (v instanceof Collection<?> c) return new java.util.ArrayList<>(c);   // not List.copyOf: it rejects nulls
        if (v.getClass().isArray()) return null;
        throw new IllegalArgumentException("a " + v.getClass().getName() + " cannot be written to a config");
    }

    private static Object[] arrayOf(Object v) {
        if (v instanceof Object[] os) return os;
        int n = Array.getLength(v);
        Object[] items = new Object[n];
        for (int i = 0; i < n; i++) items[i] = Array.get(v, i);
        return items;
    }

    private void enter(Object v) {
        if (++depth < 32) return;
        if (open == null) open = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (!open.add(v)) throw new IllegalArgumentException("a value that contains itself cannot be written to a config");
    }

    private void leave(Object v) {
        if (depth-- >= 32 && open != null) open.remove(v);
    }
}
