package eu.neydev.saver.core.storage;

import java.util.List;

/**
 * Versioned schema migration: a set of SQL statements applied atomically
 * and once. The migration registry lives in {@link JdbcStorage#migrations()}.
 *
 * <p>The statements travel as an immutable list, not a varargs array: an array
 * component would give the record array-identity equals and hashCode, and at
 * the call site a varargs spread of text blocks reads like three parameters
 * of a constructor that only has two.
 */
public record Migration(int version, String description, List<String> sql) {

    public Migration {

        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1");
        }

        sql = List.copyOf(sql);

        if (sql.isEmpty()) {
            throw new IllegalArgumentException("Migration without SQL: " + version);
        }

    }

}

