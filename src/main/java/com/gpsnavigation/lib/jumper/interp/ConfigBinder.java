package com.gpsnavigation.lib.jumper.interp;

import com.gpsnavigation.lib.jumper.runtime.JArray;
import com.gpsnavigation.lib.jumper.runtime.JTable;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java objects as config tables, both ways. A record is a table of its components, in declaration order; a
 * class with public instance fields is a table of those fields. {@link Config#load(java.nio.file.Path, Class)}
 * builds the object from a config value, {@link Config#write} writes one out.
 *
 * <p>Binding a value to a type: {@code int}, {@code long}, {@code double}, {@code float}, {@code short},
 * {@code byte}, {@code boolean}, {@code char} and their boxes; {@code String}; enums (by name); {@code List},
 * {@code Set}, {@code Collection} and arrays of any of these; {@code Map<String, V>}; records and classes with
 * public fields; {@code Object} (the config value as it is), {@code JTable}, {@code JArray}. Numbers widen
 * as in Java ({@code int} fits a {@code long} or a {@code double}) and never narrow. A key the config does not
 * have takes the value of the same component of the defaults object, if one was given; otherwise it is
 * {@code null} for a reference type and an error for a primitive (a public field keeps what its initializer
 * gave it). Keys of the config that the type does not have are ignored: a config may hold helper variables.
 */
final class ConfigBinder {
    private ConfigBinder() {}

    /** A component or field of a bound type. */
    record Prop(String name, Type type, Class<?> raw, String comment, Method accessor, Field field) {
        Object get(Object o) {
            try {
                return accessor != null ? accessor.invoke(o) : field.get(o);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalArgumentException("cannot read " + name + ": " + cause(e).getMessage(), cause(e));
            }
        }
    }

    /** How a type is read and built. */
    static final class Schema {
        final Class<?> type;
        final Prop[] props;
        final Constructor<?> ctor;        // a record's canonical constructor, or a class's no-arg constructor
        final String comment;
        final boolean record;

        Schema(Class<?> type, Prop[] props, Constructor<?> ctor, String comment, boolean record) {
            this.type = type;
            this.props = props;
            this.ctor = ctor;
            this.comment = comment;
            this.record = record;
        }
    }

    private static final ClassValue<Schema> SCHEMAS = new ClassValue<>() {   // not a lambda: see Config.warmUp
        @Override
        protected Schema computeValue(Class<?> c) {
            return build(c);
        }
    };

    static Schema schema(Class<?> c) {
        return SCHEMAS.get(c);
    }

    private static Schema build(Class<?> c) {
        Config.Comment tc = c.getAnnotation(Config.Comment.class);
        String comment = tc == null ? null : tc.value();
        if (c.isRecord()) {
            RecordComponent[] rcs = c.getRecordComponents();
            Prop[] props = new Prop[rcs.length];
            Class<?>[] types = new Class<?>[rcs.length];
            for (int i = 0; i < rcs.length; i++) {
                RecordComponent rc = rcs[i];
                Method acc = rc.getAccessor();
                acc.trySetAccessible();
                Config.Comment pc = rc.getAnnotation(Config.Comment.class);
                props[i] = new Prop(rc.getName(), rc.getGenericType(), rc.getType(), pc == null ? null : pc.value(), acc, null);
                types[i] = rc.getType();
            }
            Constructor<?> ctor;
            try {
                ctor = c.getDeclaredConstructor(types);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException(c.getName() + " has no canonical constructor", e);
            }
            ctor.trySetAccessible();
            return new Schema(c, props, ctor, comment, true);
        }
        List<Prop> props = new ArrayList<>();
        for (Field f : c.getFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isTransient(m)) continue;
            Config.Comment pc = f.getAnnotation(Config.Comment.class);
            props.add(new Prop(f.getName(), f.getGenericType(), f.getType(), pc == null ? null : pc.value(), null, f));
        }
        if (props.isEmpty())
            throw new IllegalArgumentException("a " + c.getName() + " cannot be written to a config: not a record and no public fields");
        Constructor<?> ctor = null;
        try {
            ctor = c.getDeclaredConstructor();
            ctor.trySetAccessible();
        } catch (NoSuchMethodException ignored) {
            // write-only: binding needs a no-arg constructor and says so when asked
        }
        return new Schema(c, props.toArray(new Prop[0]), ctor, comment, false);
    }

    /** A record or an object with public fields (anything that is not a scalar, a collection, a map, an array or a runtime value). */
    static boolean isObject(Object v) {
        if (v == null || ConfigWriter.isScalar(v) || v instanceof Map<?, ?> || v instanceof Collection<?>
                || v instanceof JTable || v.getClass().isArray()) return false;
        Class<?> c = v.getClass();
        if (c.isRecord()) return true;
        String n = c.getName();
        return !n.startsWith("java.") && !n.startsWith("javax.") && !n.startsWith("jdk.");
    }

    /** The table of an object, for the writer: components or fields in order, with their comments. */
    static ConfigWriter.Entries entries(Object o) {
        Schema s = schema(o.getClass());
        ConfigWriter.Entries e = new ConfigWriter.Entries(s.props.length);
        e.comments = new String[s.props.length];
        for (Prop p : s.props) {
            Object v = p.get(o);
            if (v == null) continue;
            e.comments[e.n] = p.comment;
            e.add(p.name, v);
        }
        return e;
    }

    // ------------------------------------------------------------------ config value -> Java

    /** Bind a config value to a type; {@code defaults} fills the keys the value lacks (it may be null). */
    @SuppressWarnings("unchecked")
    static <T> T bind(Object value, Class<T> type, T defaults) {
        return (T) bind(value, type, defaults, "");
    }

    private static Object bind(Object v, Type t, Object def, String path) {
        Class<?> raw = rawOf(t);
        if (v == null) {
            if (def != null) return def;
            if (raw.isPrimitive()) throw missing(path, raw);
            return null;
        }
        if (raw == Object.class) return v;
        if (raw == String.class) {
            if (v instanceof String) return v;
            throw mismatch(path, "a String", v);
        }
        if (raw == int.class || raw == Integer.class) {
            if (v instanceof Integer) return v;
            throw mismatch(path, "an int", v);
        }
        if (raw == long.class || raw == Long.class) {
            if (v instanceof Long) return v;
            if (v instanceof Integer i) return (long) i;
            throw mismatch(path, "a long", v);
        }
        if (raw == double.class || raw == Double.class) {
            if (v instanceof Double) return v;
            if (v instanceof Integer || v instanceof Long) return ((Number) v).doubleValue();
            throw mismatch(path, "a double", v);
        }
        if (raw == float.class || raw == Float.class) {
            if (v instanceof Double || v instanceof Integer || v instanceof Long) return ((Number) v).floatValue();
            throw mismatch(path, "a float", v);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            if (v instanceof Boolean) return v;
            throw mismatch(path, "a boolean", v);
        }
        if (raw == short.class || raw == Short.class) {
            if (v instanceof Integer i && i == (short) (int) i) return (short) (int) i;
            throw mismatch(path, "a short", v);
        }
        if (raw == byte.class || raw == Byte.class) {
            if (v instanceof Integer i && i == (byte) (int) i) return (byte) (int) i;
            throw mismatch(path, "a byte", v);
        }
        if (raw == char.class || raw == Character.class) {
            if (v instanceof String s && s.length() == 1) return s.charAt(0);
            throw mismatch(path, "a one-character String", v);
        }
        if (raw.isEnum()) {
            if (!(v instanceof String s)) throw mismatch(path, "a String naming a " + raw.getSimpleName(), v);
            for (Object k : raw.getEnumConstants()) if (((Enum<?>) k).name().equals(s)) return k;
            StringBuilder names = new StringBuilder();
            for (Object k : raw.getEnumConstants()) names.append(names.isEmpty() ? "" : ", ").append(((Enum<?>) k).name());
            throw new IllegalArgumentException(where(path) + "\"" + s + "\" is not one of " + names);
        }
        if (raw == JTable.class) {
            if (v instanceof JTable) return v;
            throw mismatch(path, "a table", v);
        }
        if (raw == JArray.class) {
            if (v instanceof JArray) return v;
            throw mismatch(path, "an array", v);
        }
        if (raw.isArray()) {
            if (!(v instanceof List<?> l)) throw mismatch(path, "an array", v);
            Type et = t instanceof GenericArrayType g ? g.getGenericComponentType() : raw.getComponentType();
            Object out = Array.newInstance(raw.getComponentType(), l.size());
            for (int i = 0; i < l.size(); i++) Array.set(out, i, bind(l.get(i), et, null, path + "[" + i + "]"));
            return out;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            if (!(v instanceof List<?> l)) throw mismatch(path, "an array", v);
            Type et = typeArg(t, 0);
            Collection<Object> out;
            if (raw.isAssignableFrom(ArrayList.class)) out = new ArrayList<>(l.size());
            else if (raw.isAssignableFrom(LinkedHashSet.class)) out = new LinkedHashSet<>();
            else throw new IllegalArgumentException(where(path) + raw.getName() + " is not a collection a config can build (List, Set or Collection)");
            for (int i = 0; i < l.size(); i++) out.add(bind(l.get(i), et, null, path + "[" + i + "]"));
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (!(v instanceof JTable tb)) throw mismatch(path, "a table", v);
            if (!raw.isAssignableFrom(LinkedHashMap.class))
                throw new IllegalArgumentException(where(path) + raw.getName() + " is not a map a config can build (Map)");
            Class<?> kt = rawOf(typeArg(t, 0));
            if (kt != String.class && kt != Object.class)
                throw new IllegalArgumentException(where(path) + "a config table has String keys, not " + kt.getSimpleName());
            Type vt = typeArg(t, 1);
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Object k : tb.keys()) out.put(k, bind(tb.get(k), vt, null, path.isEmpty() ? String.valueOf(k) : path + "." + k));
            return out;
        }
        if (raw.isPrimitive() || raw.getName().startsWith("java.") || raw.getName().startsWith("javax."))
            throw new IllegalArgumentException(where(path) + "a config cannot be bound to " + raw.getName());
        return object(v, raw, def, path);
    }

    private static Object object(Object v, Class<?> raw, Object def, String path) {
        if (!(v instanceof JTable tb)) throw mismatch(path, "a table", v);
        Schema s = schema(raw);
        if (s.ctor == null)
            throw new IllegalArgumentException(where(path) + raw.getName() + " needs a no-arg constructor to be read from a config");
        if (s.record) {
            Object[] args = new Object[s.props.length];
            for (int i = 0; i < args.length; i++) {
                Prop p = s.props[i];
                args[i] = bind(tb.get(p.name), p.type, def == null ? null : p.get(def), path.isEmpty() ? p.name : path + "." + p.name);
            }
            try {
                return s.ctor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(where(path) + "cannot create " + raw.getName() + ": " + cause(e).getMessage(), cause(e));
            }
        }
        Object o;
        try {
            o = s.ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(where(path) + "cannot create " + raw.getName() + ": " + cause(e).getMessage(), cause(e));
        }
        for (Prop p : s.props) {
            Object in = tb.get(p.name);
            Object d = def == null ? null : p.get(def);
            if (in == null && d == null) continue;   // the field keeps the value its initializer gave it
            Object x = bind(in, p.type, d, path.isEmpty() ? p.name : path + "." + p.name);
            try {
                p.field.set(o, x);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException(where(path) + "cannot set " + p.name + " (is it final?)", e);
            }
        }
        return o;
    }

    // ------------------------------------------------------------------ types and messages

    static Class<?> rawOf(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        if (t instanceof GenericArrayType g) return Array.newInstance(rawOf(g.getGenericComponentType()), 0).getClass();
        if (t instanceof WildcardType w) return rawOf(w.getUpperBounds()[0]);
        return Object.class;   // a type variable
    }

    private static Type typeArg(Type t, int i) {
        if (t instanceof ParameterizedType p) return p.getActualTypeArguments()[i];
        return Object.class;
    }

    private static String where(String path) {
        return path.isEmpty() ? "" : path + ": ";
    }

    private static IllegalArgumentException missing(String path, Class<?> raw) {
        return new IllegalArgumentException(where(path) + "the config has no value for this " + raw.getSimpleName());
    }

    private static IllegalArgumentException mismatch(String path, String expected, Object got) {
        String g = got instanceof String s ? "the String \"" + s + "\""
                : got instanceof JTable ? "a table"
                : got instanceof List<?> ? "an array"
                : got.getClass() == Integer.class ? "the int " + got
                : got.getClass() == Long.class ? "the long " + got
                : got.getClass() == Double.class ? "the double " + got
                : got instanceof Boolean ? "the boolean " + got
                : "a " + got.getClass().getSimpleName();
        return new IllegalArgumentException(where(path) + "expected " + expected + ", got " + g);
    }

    private static Throwable cause(Throwable e) {
        return e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
    }

    /** Top-level names of a type, for Config.loadOrCreate to know which keys a file lacks. */
    static Set<String> names(Class<?> c) {
        Set<String> out = new LinkedHashSet<>();
        for (Prop p : schema(c).props) out.add(p.name);
        return out;
    }
}
