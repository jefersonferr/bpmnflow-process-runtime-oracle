package org.bpmnflow.runtime;

/**
 * Thrown when the Oracle Duality View rejects a write because the ETag
 * in the request is stale — i.e. the document was modified by another
 * session between the client's last GET and this PUT/POST.
 *
 * <p>Oracle raises ORA-40896 (formerly ORA-42699 in 23c) for this
 * condition. The {@link org.bpmnflow.runtime.GlobalExceptionHandler}
 * maps this exception to HTTP 412 Precondition Failed.</p>
 *
 * <p>The correct client recovery is to re-fetch the document (GET),
 * obtain the current ETag from the response header, apply the
 * desired modification, and retry the PUT with the fresh ETag in
 * the {@code If-Match} header.</p>
 */
public class ETagConflictException extends RuntimeException {

    private final Long instanceId;

    public ETagConflictException(Long instanceId) {
        super("ETag mismatch for instance " + instanceId +
                ": document was modified by another session. Re-fetch and retry.");
        this.instanceId = instanceId;
    }

    public ETagConflictException(Long instanceId, Throwable cause) {
        super("ETag mismatch for instance " + instanceId +
                ": document was modified by another session. Re-fetch and retry.", cause);
        this.instanceId = instanceId;
    }

    public Long getInstanceId() {
        return instanceId;
    }
}