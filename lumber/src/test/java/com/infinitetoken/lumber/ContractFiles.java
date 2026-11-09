package com.infinitetoken.lumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Lumber-Kit's contract (src/test/resources/contract, synced by scripts/sync-contract.sh): the same schema and known
 * answers Lumber-Node, Lumber-Python and Lumber-Go are tested against.
 */
final class ContractFiles {
    static final Map<String, Object> VECTORS = read("vectors.json");
    static final Map<String, Object> SCHEMA = read("schema.json");

    private ContractFiles() {}

    private static Map<String, Object> read(String name) {
        try (InputStream in = ContractFiles.class.getResourceAsStream("/contract/" + name)) {
            if (in == null) {
                throw new IllegalStateException("no contract/" + name + ": run scripts/sync-contract.sh");
            }
            return FakeLumber.asObject(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> object(Object value) {
        return FakeLumber.asObject(value);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    /** A value as it would arrive: through JSON and back, so a Java Integer and a JSON number compare equal. */
    static Object normalize(Object value) {
        return Json.parse(Json.stringify(value));
    }

    static void assertJson(Object want, Object got) {
        assertEquals(normalize(want), normalize(got));
    }

    /**
     * Checks a value against one of the kit's schema definitions: the subset of JSON Schema the generated schema uses
     * ($ref, type, enum, properties, required, additionalProperties), so the tests need no dependency for it.
     */
    static void conforms(String definition, Object value) {
        String problem = check(definition(definition), normalize(value), definition);
        if (problem != null) {
            fail("not a " + definition + ": " + problem);
        }
    }

    private static Map<String, Object> definition(String name) {
        return object(object(SCHEMA.get("definitions")).get(name));
    }

    private static String check(Map<String, Object> rule, Object value, String path) {
        Object ref = rule.get("$ref");
        if (ref instanceof String) {
            return check(definition(((String) ref).substring("#/definitions/".length())), value, path);
        }
        Object allowed = rule.get("enum");
        if (allowed instanceof List && !((List<?>) allowed).contains(value)) {
            return path + " is " + value + ", not one of " + allowed;
        }
        Object type = rule.get("type");
        if ("string".equals(type) && !(value instanceof String)) {
            return path + " isn't a string";
        }
        if ("number".equals(type) && !(value instanceof Number)) {
            return path + " isn't a number";
        }
        if ("object".equals(type)) {
            if (!(value instanceof Map)) {
                return path + " isn't an object";
            }
            Map<String, Object> object = object(value);
            Object properties = rule.get("properties");
            Map<String, Object> known = properties instanceof Map ? object(properties) : Map.of();
            Object required = rule.get("required");
            if (required instanceof List) {
                for (Object key : (List<?>) required) {
                    if (!object.containsKey((String) key)) {
                        return path + " has no " + key;
                    }
                }
            }
            for (String key : new TreeSet<>(object.keySet())) {
                if (known.containsKey(key)) {
                    String problem = check(object(known.get(key)), object.get(key), path + "." + key);
                    if (problem != null) {
                        return problem;
                    }
                } else if (Boolean.FALSE.equals(rule.get("additionalProperties"))) {
                    return path + " has " + key + ", which it doesn't allow";
                }
            }
        }
        return null;
    }
}
