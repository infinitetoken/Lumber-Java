package com.infinitetoken.lumber;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compact JSON, so the library needs no JSON dependency. Writing never fails: anything JSON has no type for is sent as
 * text (a date as ISO 8601, an exception as its class and message), a NaN or an infinity as null, and a structure that
 * contains itself as "[Circular]", rather than a log being lost. Reading is for the API's answers and the tests.
 */
final class Json {
    private static final int MAX_DEPTH = 64;

    private Json() {}

    static byte[] encode(Object value) {
        return stringify(value).getBytes(StandardCharsets.UTF_8);
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(out, value, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, Set<Object> seen, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            string(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number) {
            number(out, (Number) value);
        } else if (value instanceof Map || value instanceof Iterable || value.getClass().isArray()) {
            if (depth >= MAX_DEPTH) {
                string(out, "[Too deep]");
            } else if (!seen.add(value)) {
                string(out, "[Circular]");
            } else {
                try {
                    container(out, value, seen, depth);
                } finally {
                    seen.remove(value);
                }
            }
        } else if (value instanceof Optional) {
            write(out, ((Optional<?>) value).orElse(null), seen, depth);
        } else if (value instanceof Enum) {
            string(out, ((Enum<?>) value).name());
        } else if (value instanceof Date) {
            string(out, ((Date) value).toInstant().toString());
        } else if (value instanceof Class) {
            string(out, ((Class<?>) value).getName());
        } else {
            // Character, CharSequence, UUID, URI, java.time's types (ISO 8601), a Throwable (its class and message),
            // and anything else: its text
            string(out, text(value));
        }
    }

    private static void container(StringBuilder out, Object value, Set<Object> seen, int depth) {
        if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue(), seen, depth + 1);
            }
            out.append('}');
        } else if (value instanceof byte[]) {
            string(out, Base64.getEncoder().encodeToString((byte[]) value));
        } else {
            out.append('[');
            boolean first = true;
            if (value instanceof Iterable) {
                for (Object item : (Iterable<?>) value) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(out, item, seen, depth + 1);
                }
            } else {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(out, Array.get(value, i), seen, depth + 1);
                }
            }
            out.append(']');
        }
    }

    private static void number(StringBuilder out, Number value) {
        if (value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Byte
                || value instanceof BigInteger
                || value instanceof AtomicInteger
                || value instanceof AtomicLong) {
            out.append(value.toString());
        } else if (value instanceof BigDecimal) {
            out.append(((BigDecimal) value).toString());
        } else if (value instanceof Float) {
            float f = value.floatValue();
            out.append(Float.isFinite(f) ? Float.toString(f) : "null");
        } else {
            double d = value.doubleValue();
            out.append(Double.isFinite(d) ? Double.toString(d) : "null");
        }
    }

    private static String text(Object value) {
        if (value instanceof TemporalAccessor || value instanceof CharSequence || value instanceof Character) {
            return value.toString();
        }
        try {
            return String.valueOf(value);
        } catch (RuntimeException e) {
            return value.getClass().getName();
        }
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20 || isLoneSurrogate(value, i)) {
                        // A lone surrogate isn't valid UTF-8: escaped, it stays valid JSON, as JavaScript sends it
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static boolean isLoneSurrogate(String value, int i) {
        char c = value.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i == 0 || !Character.isHighSurrogate(value.charAt(i - 1));
        }
        return false;
    }

    /**
     * Parses JSON into maps (in order), lists, strings, booleans, null, and numbers: a Long for a whole number that
     * fits, otherwise a Double.
     *
     * @throws IllegalArgumentException when the text isn't JSON
     */
    static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.space();
        Object value = parser.value(0);
        parser.space();
        if (parser.at < text.length()) {
            throw parser.error("trailing characters");
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Object value(int depth) {
            if (depth > 512) {
                throw error("nested too deeply");
            }
            if (at >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(at);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("unexpected '" + c + "'");
            }
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            space();
            if (peek('}')) {
                at++;
                return map;
            }
            while (true) {
                space();
                if (!peek('"')) {
                    throw error("expected a key");
                }
                String key = string();
                space();
                expect(':');
                space();
                map.put(key, value(depth + 1));
                space();
                if (peek(',')) {
                    at++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            at++;
            space();
            if (peek(']')) {
                at++;
                return list;
            }
            while (true) {
                space();
                list.add(value(depth + 1));
                space();
                if (peek(',')) {
                    at++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            at++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("control character in a string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    throw error("unterminated escape");
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(escaped);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (at + 4 > text.length()) {
                            throw error("short \\u escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("bad \\u escape");
                        }
                        at += 4;
                        break;
                    default:
                        throw error("bad escape \\" + escaped);
                }
            }
        }

        private Number number() {
            int start = at;
            if (peek('-')) {
                at++;
            }
            boolean whole = true;
            int digits = digits();
            if (digits == 0) {
                throw error("expected a digit");
            }
            if (peek('.')) {
                at++;
                whole = false;
                if (digits() == 0) {
                    throw error("expected a digit after '.'");
                }
            }
            if (peek('e') || peek('E')) {
                at++;
                whole = false;
                if (peek('+') || peek('-')) {
                    at++;
                }
                if (digits() == 0) {
                    throw error("expected an exponent");
                }
            }
            String number = text.substring(start, at);
            if (whole) {
                try {
                    return Long.parseLong(number);
                } catch (NumberFormatException e) {
                    // Too big for a long: a double, as JavaScript reads it
                }
            }
            return Double.parseDouble(number);
        }

        private int digits() {
            int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
                at++;
            }
            return at - start;
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw error("unexpected token");
            }
            at += word.length();
            return value;
        }

        void space() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                at++;
            }
        }

        private boolean peek(char c) {
            return at < text.length() && text.charAt(at) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            at++;
        }

        IllegalArgumentException error(String problem) {
            return new IllegalArgumentException("Invalid JSON at " + at + ": " + problem);
        }
    }
}
