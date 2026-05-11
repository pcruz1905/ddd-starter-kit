package myfluxo.kernel.aggregate;

/**
 * Strategy for turning an {@link ArchivedSnapshot} back into a domain
 * aggregate.
 *
 * <p>One implementation per aggregate type. The implementation knows:
 * <ul>
 *     <li>which {@link #entityType()} string identifies its snapshots
 *         in the archive (must match what was written by the sink),</li>
 *     <li>how to parse the {@code payloadJson} into typed components,</li>
 *     <li>which aggregate factory ({@code Xxx.rehydrate(...)}) to call
 *         with the parsed values.</li>
 * </ul>
 *
 * <p>The restorer returns the rehydrated aggregate; the caller decides
 * what to do with it (save it back to the primary table to "restore",
 * inspect it for forensics, emit a {@code Restored} event, etc.). This
 * keeps the side effect — re-insertion — out of the recovery primitive.
 */
public interface AggregateRestorer<A> {

    /** The {@code entity_archive.entity_type} value this restorer handles. */
    String entityType();

    /** Rebuild the aggregate from a snapshot read out of the archive. */
    A rehydrate(ArchivedSnapshot snapshot);
}
