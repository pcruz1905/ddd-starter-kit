package myfluxo.kernel.job;

import myfluxo.kernel.id.Identifier;
import myfluxo.kernel.id.UuidV7;

import java.util.UUID;

/** Branded id for a single scheduled job row. UUID v7 for B-tree friendliness. */
public record JobInstanceId(UUID value) implements Identifier<UUID> {

    public JobInstanceId {
        if (value == null) {
            throw new IllegalArgumentException("JobInstanceId value cannot be null");
        }
    }

    public static JobInstanceId newId() {
        return new JobInstanceId(UuidV7.generate());
    }
}
