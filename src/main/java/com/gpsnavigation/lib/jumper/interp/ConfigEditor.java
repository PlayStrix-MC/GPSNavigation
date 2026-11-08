package com.gpsnavigation.lib.jumper.interp;

import com.gpsnavigation.lib.jumper.lexer.Lexer;
import com.gpsnavigation.lib.jumper.lexer.Token;
import com.gpsnavigation.lib.jumper.lexer.TokenType;
import com.gpsnavigation.lib.jumper.runtime.JTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gpsnavigation.lib.jumper.lexer.TokenType.*;

/**
 * Changes the values of top-level variables in the text of a config and leaves everything else - comments,
 * blank lines, the other variables and their expressions - exactly as it was. {@link Config#update} and
 * {@link Config#loadOrCreate} use it.
 *
 * <p>The text is tokenized (not parsed): at brace depth 0, {@code [type] name = <expression>;} at the start
 * of a statement is a declaration. A change replaces the expression of the first declaration of the name
 * (and the type keyword when the new value needs another one); a name the file does not declare is appended
 * at the end, with its comment; a {@code null} removes the declaration. The result is then read as a
 * config and every changed name checked: a file that assigns the name again lower down ({@code if (dev)
 * { port = 1; }}) does not get the new value from its declaration, so the change is refused rather than
 * written. A file that is {@code return <value>;} has no declarations to change and is refused too.
 */
final class ConfigEditor {
    private final String src;
    private final String nl;
    private final List<Token> toks;
    private final int[] lineStart;
    /** Name -> its first top-level declaration. */
    private final Map<String, Decl> decls = new LinkedHashMap<>();
    private boolean hasReturn;

    /** Offsets into src: the statement, the type keyword (typeEnd == typeStart when there is none), the expression. */
    private record Decl(int start, int typeStart, int typeEnd, String type, int exprStart, int exprEnd, int end) {}

    ConfigEditor(String src) {
        this.src = src;
        this.nl = src.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
        this.toks = Lexer.tokenize(src);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < src.length(); i++) if (src.charAt(i) == '\n') starts.add(i + 1);
        lineStart = new int[starts.size()];
        for (int i = 0; i < lineStart.length; i++) lineStart[i] = starts.get(i);
        scan();
    }

    private int offset(Token t) { return lineStart[t.line() - 1] + t.col() - 1; }
    private int endOffset(Token t) { return lineStart[t.line() - 1] + t.endCol() - 1; }

    private static boolean isType(TokenType t) {
        return t == DYN || t == KW_INT || t == KW_LONG || t == KW_DOUBLE || t == KW_BOOLEAN || t == KW_STRING;
    }

    private void scan() {
        int depth = 0;
        boolean start = true;   // at the start of a top-level statement
        for (int i = 0; i < toks.size(); i++) {
            Token t = toks.get(i);
            TokenType k = t.type();
            if (k == EOF) break;
            if (depth == 0 && start) {
                if (k == RETURN) hasReturn = true;
                int j = i;
                if (isType(k)) j++;
                if (j + 1 < toks.size() && toks.get(j).type() == IDENT && toks.get(j + 1).type() == EQ) {
                    int semi = j + 2, d = 0;
                    for (; semi < toks.size(); semi++) {
                        TokenType s = toks.get(semi).type();
                        if (s == LPAREN || s == LBRACKET || s == LBRACE) d++;
                        else if (s == RPAREN || s == RBRACKET || s == RBRACE) d--;
                        else if (s == SEMI && d == 0) break;
                        else if (s == EOF) break;
                    }
                    if (semi < toks.size() && toks.get(semi).type() == SEMI) {
                        String name = toks.get(j).text();
                        Token typeTok = j > i ? t : null;
                        decls.putIfAbsent(name, new Decl(offset(t),
                                offset(t), typeTok == null ? offset(t) : endOffset(typeTok), typeTok == null ? null : typeTok.text(),
                                offset(toks.get(j + 2)), offset(toks.get(semi)), endOffset(toks.get(semi))));
                        i = semi;
                        start = true;
                        continue;
                    }
                }
            }
            if (k == LPAREN || k == LBRACKET || k == LBRACE) { depth++; start = false; }
            else if (k == RPAREN || k == RBRACKET || k == RBRACE) { depth--; start = depth == 0 && k == RBRACE; }
            else start = k == SEMI && depth == 0;
        }
    }

    /**
     * The text with the changes applied and checked. {@code comments} may carry a comment for a name that
     * gets appended; it is ignored for names the file already declares.
     */
    String apply(Map<String, ?> changes, Map<String, String> comments) {
        if (hasReturn)
            throw new IllegalStateException("the config is a 'return' and has no top-level variables to change; write it whole instead");
        // edits from the end of the text backwards, so that earlier offsets stay valid
        List<Map.Entry<String, ?>> present = new ArrayList<>();
        List<Map.Entry<String, ?>> absent = new ArrayList<>();
        for (Map.Entry<String, ?> e : changes.entrySet()) {
            if (!ConfigWriter.bare(e.getKey()))
                throw new IllegalArgumentException("'" + e.getKey() + "' is not a name a config variable can have");
            (decls.containsKey(e.getKey()) ? present : absent).add(e);
        }
        present.sort((a, b) -> Integer.compare(decls.get(b.getKey()).start, decls.get(a.getKey()).start));
        StringBuilder sb = new StringBuilder(src);
        for (Map.Entry<String, ?> e : present) {
            Decl d = decls.get(e.getKey());
            Object v = e.getValue();
            if (v == null) {
                remove(sb, d);
                continue;
            }
            sb.replace(d.exprStart, d.exprEnd, ConfigWriter.formatValue(v));
            String want = ConfigWriter.declaredType(v);
            if (d.type != null && !d.type.equals("dyn") && !d.type.equals(want) && !widens(d.type, want))
                sb.replace(d.typeStart, d.typeEnd, want);
        }
        if (!absent.isEmpty()) {
            // after the last line that has anything on it, so that a trailing blank line stays trailing
            int end = sb.length();
            while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) end--;
            StringBuilder tail = new StringBuilder();
            if (end > 0) tail.append(nl);
            // as the writer lays out a file: a blank line around a declaration that has a comment
            boolean first = true, blank = false;
            for (Map.Entry<String, ?> e : absent) {
                if (e.getValue() == null) continue;
                String c = comments == null ? null : comments.get(e.getKey());
                boolean commented = c != null && !c.isEmpty();
                if (end > 0 && first || commented && !first || blank) tail.append(nl);
                first = false;
                blank = commented;
                tail.append(ConfigWriter.declaration(e.getKey(), e.getValue(), c).replace("\n", nl));
            }
            sb.setLength(end);
            sb.append(tail);
        }
        String out = sb.toString();
        verify(out, changes);
        return out;
    }

    /** A typed declaration may keep its type for a narrower value: {@code long a = 5;} reads as the long 5. */
    private static boolean widens(String declared, String value) {
        return declared.equals("long") && value.equals("int")
                || declared.equals("double") && (value.equals("int") || value.equals("long"));
    }

    /** Removes the statement; if nothing else is on its line(s), the line(s) go too, with the comment right above. */
    private void remove(StringBuilder sb, Decl d) {
        int from = d.start, to = d.end;
        int ls = from;
        while (ls > 0 && sb.charAt(ls - 1) != '\n') ls--;
        int le = to;
        while (le < sb.length() && sb.charAt(le) != '\n') le++;
        boolean alone = sb.substring(ls, from).isBlank() && sb.substring(to, le).isBlank();
        if (alone) {
            from = ls;
            to = Math.min(le + 1, sb.length());
            // the comment lines directly above belong to the declaration
            while (from > 0) {
                int pls = from - 1;
                while (pls > 0 && sb.charAt(pls - 1) != '\n') pls--;
                String prev = sb.substring(pls, from);
                if (prev.stripLeading().startsWith("//")) from = pls;
                else break;
            }
        }
        sb.delete(from, to);
    }

    private static void verify(String text, Map<String, ?> changes) {
        Object v;
        try {
            v = Config.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalStateException("the changed config does not read: " + e.getMessage(), e);
        }
        if (!(v instanceof JTable t)) throw new IllegalStateException("the changed config is not a table of variables");
        for (Map.Entry<String, ?> e : changes.entrySet()) {
            Object got = t.get(e.getKey());
            if (e.getValue() == null) {
                if (got != null) throw refused(e.getKey());
            } else if (!same(Config.parse("return " + ConfigWriter.formatValue(e.getValue()) + ";"), got)) {
                throw refused(e.getKey());
            }
        }
    }

    /** The value written and the value read back; a typed declaration may have widened a number (long big = 6). */
    private static boolean same(Object want, Object got) {
        if (want == null || got == null) return want == got;
        if (want instanceof JTable a) {
            if (!(got instanceof JTable b) || a.size() != b.size()) return false;
            for (Object k : a.keys()) if (!same(a.get(k), b.get(k))) return false;
            return true;
        }
        if (want instanceof List<?> a) {
            if (!(got instanceof List<?> b) || a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
            return true;
        }
        if (want instanceof Integer i) return got.equals(i) || got instanceof Long l && l == i.longValue() || got instanceof Double d && d == i.doubleValue();
        if (want instanceof Long l) return got.equals(l) || got instanceof Double d && d == l.doubleValue();
        return want.equals(got);
    }

    private static IllegalStateException refused(String name) {
        return new IllegalStateException("'" + name + "' is set again further down the config, so changing its declaration would not change the config; edit the file or write it whole");
    }

    /** The names the text declares at the top level, in order. */
    java.util.Set<String> names() {
        return decls.keySet();
    }
}
