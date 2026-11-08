package com.gpsnavigation.lib.jumper.interp;

import com.gpsnavigation.lib.jumper.ast.FunctionNode;
import com.gpsnavigation.lib.jumper.parser.Parser;
import com.gpsnavigation.lib.jumper.runtime.Access;
import com.gpsnavigation.lib.jumper.runtime.JArray;
import com.gpsnavigation.lib.jumper.runtime.JTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Config files (*.jmc, JuMper Config). The language of scripts, cut down to what a config needs:
 * values, variables, expressions, table and array literals, if/else, switch, ternaries, return
 * (see Parser.config). No loops, functions, lambdas, classes, import or try - and no Java at all:
 * the file runs under {@link Access#none()}.
 *
 * <pre>
 * // server.jmc - the top-level variables are the config
 * int port = 25565;
 * boolean isDev = false;
 * int permLevel = isDev ? 10 : 0;
 * dyn motd = {"Welcome", "to", "the server"};     // {a, b} is an array, as a Java initializer
 * dyn database = { url: "jdbc:mysql://localhost/mc", pool: { min: 2, max: 10 } };
 * </pre>
 * gives the table {@code {port: 25565, isDev: false, permLevel: 0, motd: [...], database: {...}}}, in
 * declaration order. A file may instead end with {@code return <value>;}, and then that value is the config.
 *
 * <p>Configs like the one above - declarations, assignments, if/else, operators over literals and the
 * variables above - are read by {@link ConfigReader} in one pass, without the parser and the interpreter;
 * anything else takes the full path. Both give the same values (tested against each other).
 *
 * <p>Three kinds of files, three extensions: {@code .jmp} scripts, {@code .jma} access policies,
 * {@code .jmc} configs.
 */
public final class Config {
    private Config() {}

    /** Read and evaluate a *.jmc file. */
    public static Object load(Path file) throws IOException {
        warmUp();
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ the first load in a process

    /**
     * The first config a process loads spends most of its time in the JVM, not in reading: loading,
     * verifying and linking the reader's classes and resolving their call sites, all interpreted. Reading
     * the file (the first file read in a process loads its own share of JDK classes) does not need any of
     * it - so the first load has another thread load and initialize the reader's classes while this one
     * reads the file. When the text is there they are ready or half-way. Once per process, with 4+ cores
     * (-Djmp.configwarmup=1 forces it, =0 turns it off).
     */
    private static void warmUp() {
        if (warmed) return;
        warmed = true;
        String w = System.getProperty("jmp.configwarmup");
        // on two cores the second thread competes with this one and the JIT: measured slower there
        if (!FAST || "0".equals(w) || w == null && Runtime.getRuntime().availableProcessors() < 4) return;
        Thread t = new Thread(null, new Runnable() {   // not a lambda: an invokedynamic bootstrap costs more than it saves
            @Override
            public void run() {
                ClassLoader l = Config.class.getClassLoader();
                for (String c : WARM_CLASSES) {
                    try { Class.forName(c, true, l); } catch (Throwable ignored) { return; }
                }
            }
        }, "jmc-warmup", 1 << 20);
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean warmed;

    private static final String[] WARM_CLASSES = {"com.gpsnavigation.lib.jumper.interp.ConfigReader", "com.gpsnavigation.lib.jumper.ast.VarType",
            "com.gpsnavigation.lib.jumper.runtime.Shape", "com.gpsnavigation.lib.jumper.runtime.JTable", "com.gpsnavigation.lib.jumper.runtime.JTable$Plain",
            "com.gpsnavigation.lib.jumper.runtime.JArray"};


    /** Evaluate config text. ParseError for what a config may not contain, JmpError for runtime errors. */
    public static Object parse(String source) {
        if (FAST) {
            int slot = TEMPLATE ? templateSlot(source) : 0;
            Template t = TEMPLATE ? TEMPLATES[slot] : null;
            boolean again = t != null && t.text.equals(source);
            if (again && t.value != null) return copy(t.value);
            Object v = ConfigReader.read(source);
            if (v != ConfigReader.FALLBACK) {
                // the first time a text is seen only the text is kept; read a second time, it gets a prototype
                // (a file edited on every read never pays for the copy)
                if (TEMPLATE) TEMPLATES[slot] = new Template(source, again ? copy(v) : null);
                return v;
            }
        }
        return parseFull(source);
    }

    // ------------------------------------------------------------------ templates of unchanged text

    /** As Opts.offValue - repeated here so that the cold config path does not load Opts. */
    private static boolean off(String v) {
        if (v == null) return false;
        String t = v.trim();
        return t.equals("0") || t.equalsIgnoreCase("off") || t.equalsIgnoreCase("false");
    }

    /**
     * A config read again with exactly the same text - a /reload with nothing edited, a plugin reading
     * its config on every event - is not read at all: its value is a pure function of the text (a
     * fast-path config has no calls, no Java, no globals), so the value built the first time is kept as
     * a prototype and every read of the same text gets a fresh copy of it - new tables and arrays the
     * caller owns and may change, the immutable strings and numbers shared. One comparison of the text
     * (vectorized) and the copying: no scanning at all. A different text - any edit - is read as usual;
     * it becomes the prototype when it is read a second time unchanged. Only fast-path results: a config that needs the interpreter may one day
     * call something that is not a pure function. -Djmp.configtemplate=0 turns it off.
     */
    private static final boolean TEMPLATE = !off(System.getProperty("jmp.configtemplate"));

    private record Template(String text, Object value) {}

    private static final Template[] TEMPLATES = new Template[64];

    private static int templateSlot(String text) {
        int n = text.length();
        int h = n * 31 + (n > 0 ? text.charAt(n - 1) + text.charAt(n >> 1) * 7 : 0);
        return (h ^ (h >>> 6)) & (TEMPLATES.length - 1);
    }

    /** A deep copy of the tables and arrays of a config value; strings, numbers and booleans are shared. */
    private static Object copy(Object v) {
        if (v instanceof JTable.Plain t) {
            JTable.Plain c = t.copyStorage();
            java.util.LinkedHashMap<Object, Object> d = c.dictionary();
            if (d != null) {
                for (java.util.Map.Entry<Object, Object> e : d.entrySet()) {
                    Object x = e.getValue();
                    if (x instanceof JTable || x instanceof JArray) e.setValue(copy(x));
                }
            } else {
                Object[] vs = c.values;
                for (int i = 0; i < vs.length; i++) {
                    Object x = vs[i];
                    if (x instanceof JTable || x instanceof JArray) vs[i] = copy(x);
                }
            }
            return c;
        }
        if (v instanceof JArray a) {
            JArray c = a.copyStorage();
            if (c.objs != null) {
                Object[] os = c.objs;
                for (int i = 0; i < c.size; i++) {
                    Object x = os[i];
                    if (x instanceof JTable || x instanceof JArray) os[i] = copy(x);
                }
            }
            return c;
        }
        return v;
    }

    /** Pure-data configs skip the language entirely (ConfigReader); -Djmp.fastconfig=0 sends everything the full way. */
    // Not in runtime.Opts: those are Tier 1 optimizations the bench gate flips on every benchmark,
    // and no benchmark loads a config.
    private static final boolean FAST = !off(System.getProperty("jmp.fastconfig"));

    /** The full path: parse in config mode, run under Access.none(). Also what the fast path is tested against. */
    public static Object parseFull(String source) {
        Interpreter jj = new Interpreter().access(Access.none());
        FunctionNode main = jj.parse(() -> new Parser(source, jj.globals()).source(null, jj.modules()).config().parseProgram());
        Script s = new Script(main);
        Object r = s.run();
        if (r != null) return r;
        JTable t = JTable.plain(s.names().size());
        for (String name : s.names()) t.put(name, s.get(name));
        return t;
    }

    // ------------------------------------------------------------------ Java objects

    /**
     * A comment written above a component or field in the config file, or, on the type, at the top of the
     * file. Several lines are fine.
     * <pre>
     * &#64;Config.Comment("Server settings. Edit and /reload.")
     * record ServerConfig(
     *         &#64;Config.Comment("The port players connect to") int port,
     *         &#64;Config.Comment("Shown in the server list") List&lt;String&gt; motd) {}
     * </pre>
     */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({java.lang.annotation.ElementType.RECORD_COMPONENT, java.lang.annotation.ElementType.FIELD,
            java.lang.annotation.ElementType.TYPE})
    public @interface Comment {
        String value();
    }

    /**
     * Read a *.jmc file into an object: a record (one component per variable, in any order) or a class with a
     * no-arg constructor and public fields. Components may be {@code int}, {@code long}, {@code double},
     * {@code float}, {@code short}, {@code byte}, {@code boolean}, {@code char} and their boxes, {@code String},
     * enums (by name), {@code List}, {@code Set} and arrays of these, {@code Map<String, V>}, other records or
     * classes, or {@code Object}, {@link JTable}, {@link JArray} for the raw value. Numbers widen as in Java and
     * never narrow. Variables of the config that the type does not have are ignored (helper variables are
     * fine); a component the config lacks is {@code null}, or an error for a primitive.
     *
     * <pre>
     * record ServerConfig(int port, boolean dev, List&lt;String&gt; motd, Db db) {}
     * record Db(String url, int poolMax) {}
     *
     * ServerConfig cfg = Config.load(Path.of("server.jmc"), ServerConfig.class);
     * </pre>
     *
     * @throws IllegalArgumentException when a value does not fit its component: {@code db.poolMax: expected an int, got the String "ten"}
     */
    public static <T> T load(Path file, Class<T> type) throws IOException {
        return bind(load(file), type, file);
    }

    /**
     * Read a *.jmc file into an object of the same type as {@code defaults}, which supplies every variable the
     * file lacks (at the top level and inside nested records). A file that does not exist gives {@code defaults}
     * itself; nothing is written - see {@link #loadOrCreate} for that.
     */
    public static <T> T load(Path file, T defaults) throws IOException {
        if (!Files.exists(file)) return defaults;
        @SuppressWarnings("unchecked") Class<T> type = (Class<T>) defaults.getClass();
        try {
            return ConfigBinder.bind(load(file), type, defaults);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }

    /**
     * The config a plugin keeps next to its defaults: read the file into an object like {@code defaults}, and
     * keep the file complete. A file that does not exist is written from {@code defaults}, with the comments of
     * its type ({@link Comment}). A file that exists but lacks some top-level variables gets them appended,
     * each with its comment, while everything already in the file stays as it is. The object returned has the
     * file's values, with the defaults for whatever the file lacked.
     *
     * <pre>
     * static final ServerConfig DEFAULTS = new ServerConfig(25565, false, List.of("Welcome"), new Db("jdbc:h2:mem:mc", 10));
     * ServerConfig cfg = Config.loadOrCreate(Path.of("server.jmc"), DEFAULTS);
     * </pre>
     */
    public static <T> T loadOrCreate(Path file, T defaults) throws IOException {
        @SuppressWarnings("unchecked") Class<T> type = (Class<T>) defaults.getClass();
        if (!Files.exists(file)) {
            write(file, defaults);
            return defaults;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Object value = parse(text);
        if (value instanceof JTable t) {
            Map<String, Object> missing = new LinkedHashMap<>();
            Map<String, String> comments = new LinkedHashMap<>();
            for (ConfigBinder.Prop p : ConfigBinder.schema(type).props) {
                if (t.has(p.name())) continue;
                Object v = p.get(defaults);
                if (v == null) continue;
                missing.put(p.name(), v);
                if (p.comment() != null) comments.put(p.name(), p.comment());
            }
            if (!missing.isEmpty()) {
                try {
                    writeText(file, new ConfigEditor(text).apply(missing, comments));
                } catch (IllegalStateException e) {
                    // a config with a `return`, or one that sets a name again: leave the file alone, the defaults still apply
                }
            }
        }
        try {
            return ConfigBinder.bind(value, type, defaults);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }

    /** {@link #load(Path, Class)} for config text. */
    public static <T> T parse(String source, Class<T> type) {
        return ConfigBinder.bind(parse(source), type, null);
    }

    /** A config value ({@link #load}, {@link #parse}) as an object; see {@link #load(Path, Class)} for the types. */
    public static <T> T bind(Object value, Class<T> type) {
        return ConfigBinder.bind(value, type, null);
    }

    private static <T> T bind(Object value, Class<T> type, Path file) {
        try {
            return ConfigBinder.bind(value, type, null);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ writing from Java

    /**
     * The .jmc text of a value: what {@link #parse} reads back as the same value. Accepted: {@code String},
     * {@code Integer}, {@code Long}, {@code Double} ({@code Short} and {@code Byte} come back as
     * {@code Integer}, {@code Float} as {@code Double}), {@code Boolean}, {@code null}, {@link Character}
     * and enums (as strings), {@link JTable} and {@link Map} (as tables; null values are left out),
     * {@link JArray}, {@link List}, other {@link java.util.Collection}s and Java arrays (as arrays), records
     * and classes with public fields (as tables, with their {@link Comment}s). Anything else throws
     * {@link IllegalArgumentException}, as do NaN, infinite numbers and a value that contains itself.
     *
     * <pre>
     * Map&lt;String, Object&gt; cfg = Config.table(
     *         "port", 25565,
     *         "dev", false,
     *         "motd", List.of("Welcome", "to the server"),
     *         "db", Config.table("url", "jdbc:mysql://localhost/mc", "pool", Config.table("min", 2, "max", 10)));
     * System.out.println(Config.format(cfg));
     * </pre>
     * prints
     * <pre>
     * int port = 25565;
     * boolean dev = false;
     * dyn motd = ["Welcome", "to the server"];
     * dyn db = {
     *     url: "jdbc:mysql://localhost/mc",
     *     pool: { min: 2, max: 10 }
     * };
     * </pre>
     * The top level of a table is written as variable declarations when its keys are plain names (see
     * {@link #table}); otherwise as {@code return <value>;}. Either way the file reads back to the same
     * values, in the same order.
     */
    public static String format(Object value) {
        return ConfigWriter.format(value, null);
    }

    /** {@link #format(Object)} with a comment at the top of the file (several lines are fine). */
    public static String format(Object value, String header) {
        return ConfigWriter.format(value, header);
    }

    /**
     * Write {@link #format(Object) format(value)} to a file, UTF-8, replacing the file. The text is made
     * before the file is touched, so a value that cannot be written leaves the old file as it was; the new
     * text goes to a temporary file in the same folder and is moved over the old one. Missing folders are
     * created.
     */
    public static void write(Path file, Object value) throws IOException {
        writeText(file, format(value));
    }

    /** {@link #write(Path, Object)} with a comment at the top of the file. */
    public static void write(Path file, Object value, String header) throws IOException {
        writeText(file, format(value, header));
    }

    /**
     * Change the values of top-level variables in a config file and leave the rest of the file - comments,
     * blank lines, other variables and their expressions - exactly as it is. The expression of each named
     * variable is replaced by the value (and the declared type adjusted if the value needs another one); a
     * name the file does not declare is appended at the end; a {@code null} removes the declaration, with the
     * comment lines right above it. A file that does not exist is created from the changes.
     *
     * <pre>
     * Config.update(file, Map.of("port", 25566, "dev", true));
     * Config.update(file, "motd", List.of("Back online"));
     * </pre>
     *
     * The file is read back before it is written and every changed variable checked. A config that sets the
     * variable again lower down ({@code if (dev) { port = 1; }}) would not take the new value from its
     * declaration, and a {@code return} config has no top-level variables: these are refused with an
     * {@link IllegalStateException} and the file is not touched. Use {@link #write} for such files.
     */
    public static void update(Path file, Map<String, ?> changes) throws IOException {
        if (!Files.exists(file)) {
            Map<String, Object> t = new LinkedHashMap<>();
            for (Map.Entry<String, ?> e : changes.entrySet()) if (e.getValue() != null) t.put(e.getKey(), e.getValue());
            write(file, t);
            return;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String out = new ConfigEditor(text).apply(changes, null);
        if (!out.equals(text)) writeText(file, out);
    }

    /** {@link #update(Path, Map)} for one variable. */
    public static void update(Path file, String name, Object value) throws IOException {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put(name, value);
        update(file, one);
    }

    /** {@link #update(Path, Map)} on config text: the changed text. */
    public static String update(String source, Map<String, ?> changes) {
        return new ConfigEditor(source).apply(changes, null);
    }

    private static void writeText(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A table for {@link #format}, with its keys in the order given: {@code table("port", 25565, "dev", false)}.
     * The keys are Strings, the values anything {@link #format} accepts. A key given twice, or a key without
     * a value, throws {@link IllegalArgumentException}. A table of plain names at the top of a config is
     * written as declarations; a key that is a keyword, or has a space or a dash in it, is written quoted.
     */
    public static LinkedHashMap<String, Object> table(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) throw new IllegalArgumentException("table(...) takes key and value pairs");
        LinkedHashMap<String, Object> t = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (!(keysAndValues[i] instanceof String k))
                throw new IllegalArgumentException("table key must be a String: " + keysAndValues[i]);
            if (t.containsKey(k)) throw new IllegalArgumentException("table key given twice: " + k);
            t.put(k, keysAndValues[i + 1]);
        }
        return t;
    }
}
