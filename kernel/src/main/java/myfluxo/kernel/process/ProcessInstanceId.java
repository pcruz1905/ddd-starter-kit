package myfluxo.kernel.process;

import myfluxo.kernel.id.Identifier;
import myfluxo.kernel.id.UuidV7;

import java.util.UUID;

/**
 * Identifier for a long-running process instance. UUID v7 for the same
 * reasons aggregate ids use it: time-ordered, B-tree-friendly inserts.
 */
public record ProcessInstanceId(UUID value) implements Identifier<UUID> {

    public ProcessInstanceId {
        if (value == null) {
            throw new IllegalArgumentException("ProcessInstanceId value cannot be null");
        }
    }

    public static ProcessInstanceId newId() {
        return new ProcessInstanceId(UuidV7.generate());
    }
}
