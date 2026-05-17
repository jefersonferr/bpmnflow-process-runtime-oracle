package org.bpmnflow.runtime.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.bpmnflow.runtime.dto.*;
import org.bpmnflow.runtime.dto.WorkflowSummaryResponse;
import org.bpmnflow.runtime.duality.doc.WfProcessInstanceDoc;
import org.bpmnflow.runtime.duality.service.ProcessInstanceDualityService;
import org.bpmnflow.runtime.service.ProcessInstanceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "Workflow",
        description = "Workflow execution — start, advance and inspect running process instances"
)
@RestController
@RequestMapping("/workflow")
@SuppressWarnings("unused")
public class ProcessController {

    private final ProcessInstanceService instanceService;
    private final ProcessInstanceDualityService dualityService;

    /**
     * Feature flag: bpmnflow.duality.enabled=true routes all operations through
     * the Duality View service, including ETag-based OCC.
     * Default: false (safe — uses existing JPA service).
     */
    @Value("${bpmnflow.duality.enabled:false}")
    private boolean dualityEnabled;

    public ProcessController(ProcessInstanceService instanceService,
                             ProcessInstanceDualityService dualityService) {
        this.instanceService = instanceService;
        this.dualityService  = dualityService;
    }

    @Operation(
            summary = "List workflow instances",
            description = "Returns a paginated summary list of workflow instances ordered by creation date (newest first). " +
                    "Optionally filter by **status** (ACTIVE, COMPLETED, CANCELLED) and/or **processKey**. " +
                    "Pagination is controlled by **page** (0-based) and **size** (default 50, max 200). " +
                    "Response headers include X-Page, X-Page-Size and X-Result-Count. " +
                    "For the full state of a specific instance — activity history, variables and conclusions — use GET /{instanceId}."
    )
    @ApiResponse(responseCode = "200", description = "Instance list returned successfully")
    @GetMapping
    public ResponseEntity<List<WorkflowSummaryResponse>> listInstances(
            @Parameter(description = "Filter by instance status: ACTIVE, COMPLETED or CANCELLED")
            @RequestParam(required = false) String status,
            @Parameter(description = "Filter by process key (ex: PIZZA_DELIVERY)")
            @RequestParam(required = false) String processKey,
            @Parameter(description = "Page number (0-based, default 0)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (default 50, max 200)")
            @RequestParam(defaultValue = "50") int size) {

        size = Math.min(size, 200);

        List<WorkflowSummaryResponse> result = dualityEnabled
                ? dualityService.listInstances(status, processKey, page, size)
                : instanceService.listInstances(status, processKey, page, size);

        return ResponseEntity.ok()
                .header("X-Page",         String.valueOf(page))
                .header("X-Page-Size",    String.valueOf(size))
                .header("X-Result-Count", String.valueOf(result.size()))
                .body(result);
    }

    @Operation(
            summary = "Start a workflow instance",
            description = "Creates a new workflow instance from a deployed process version. " +
                    "Resolves the first activity via the START_TO_TASK rule and returns it " +
                    "with its available conclusions. " +
                    "Optionally accepts an external correlation ID and initial typed variables."
    )
    @ApiResponse(responseCode = "200", description = "Instance started successfully")
    @ApiResponse(responseCode = "404", description = "Version not found",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 404,
                              "error": "Not Found",
                              "message": "Version not found: 99",
                              "path": "/workflow/start"
                            }
                            """)))
    @ApiResponse(responseCode = "409", description = "No START_TO_TASK rule defined for this version",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 409,
                              "error": "Conflict",
                              "message": "No START_TO_TASK rule found for version 1",
                              "path": "/workflow/start"
                            }
                            """)))
    @PostMapping("/start")
    public ResponseEntity<ProcessInstanceResponse> startProcess(
            @Parameter(description = "Version ID of the deployed BPMN process")
            @RequestParam Long versionId,
            @RequestBody(required = false) StartProcessRequest request) {

        ProcessInstanceResponse body = dualityEnabled
                ? dualityService.startProcess(versionId, request)
                : instanceService.startProcess(versionId, request);

        // When duality is enabled, emit the ETag of the newly created document
        // so clients can use it immediately for the first complete/variable update.
        if (dualityEnabled) {
            String etag = dualityService.getETagForInstance(body.getInstanceId());
            if (etag != null) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.ETAG, quote(etag))
                        .body(body);
            }
        }
        return ResponseEntity.ok(body);
    }

    @Operation(
            summary = "Complete current activity and advance",
            description = "Completes the active activity step with a conclusion code, resolves " +
                    "the next activity from the process rules, and advances the workflow. " +
                    "If the conclusion leads to an end event, the instance is marked as COMPLETED. " +
                    "Optionally sets or updates typed variables.\n\n" +
                    "**Optimistic Concurrency Control (Duality View mode):** When `bpmnflow.duality.enabled=true`, " +
                    "this endpoint enforces ETag-based OCC. Include the `If-Match` header with the ETag " +
                    "obtained from the last `GET /{instanceId}` response. If another session has advanced " +
                    "the instance since your last read, the request is rejected with HTTP 412. " +
                    "Re-fetch the current state and retry."
    )
    @ApiResponse(responseCode = "200", description = "Activity completed and instance advanced")
    @ApiResponse(responseCode = "400", description = "Invalid or missing conclusion code, or malformed request body",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = {
                            @ExampleObject(name = "Invalid conclusion", value = """
                                    {
                                      "timestamp": "2026-04-12T10:00:00",
                                      "status": 400,
                                      "error": "Bad Request",
                                      "message": "Invalid conclusion 'WRONG_CODE' for activity 'CS-ORD'. Available: [ORDER_CONFIRMED, NEEDS_ATTENTION]",
                                      "path": "/workflow/1/complete"
                                    }
                                    """),
                            @ExampleObject(name = "Malformed JSON", value = """
                                    {
                                      "timestamp": "2026-04-12T10:00:00",
                                      "status": 400,
                                      "error": "Bad Request",
                                      "message": "Request body is missing or contains invalid JSON",
                                      "path": "/workflow/1/complete"
                                    }
                                    """)
                    }))
    @ApiResponse(responseCode = "404", description = "Instance not found",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 404,
                              "error": "Not Found",
                              "message": "Instance not found: 99",
                              "path": "/workflow/99/complete"
                            }
                            """)))
    @ApiResponse(responseCode = "409", description = "Instance already completed or no active activity",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 409,
                              "error": "Conflict",
                              "message": "Process instance is already completed",
                              "path": "/workflow/1/complete"
                            }
                            """)))
    @ApiResponse(responseCode = "412", description = "ETag mismatch — document modified by another session (Duality View mode only)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 412,
                              "error": "Precondition Failed",
                              "message": "ETag mismatch for instance 42: document was modified by another session. Re-fetch and retry.",
                              "path": "/workflow/42/complete"
                            }
                            """)))
    @PostMapping("/{instanceId}/complete")
    public ResponseEntity<ProcessInstanceResponse> completeActivity(
            @Parameter(description = "Workflow instance ID")
            @PathVariable Long instanceId,
            @Parameter(description = "ETag from last GET response (required in Duality View mode for OCC)")
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody CompleteActivityRequest request) {

        ProcessInstanceResponse body;
        if (dualityEnabled) {
            // Strip surrounding quotes from HTTP ETag value (RFC 9110 §8.8.3)
            String etag = unquote(ifMatch);
            body = dualityService.completeActivity(instanceId, etag, request);

            // Emit updated ETag so clients can chain their next call without a GET
            String newEtag = dualityService.getETagForInstance(instanceId);
            if (newEtag != null) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.ETAG, quote(newEtag))
                        .body(body);
            }
        } else {
            body = instanceService.completeActivity(instanceId, request);
        }
        return ResponseEntity.ok(body);
    }

    @Operation(
            summary = "Get workflow instance state",
            description = "Returns the full state of a workflow instance: current activity with " +
                    "available conclusions, activity history, typed variables, and business process status.\n\n" +
                    "**ETag header:** When `bpmnflow.duality.enabled=true`, the response includes an " +
                    "`ETag` header containing the current document version hash. Pass this value in the " +
                    "`If-Match` header of subsequent `POST /{instanceId}/complete` or " +
                    "`PUT /{instanceId}/variables` calls to enable optimistic concurrency control."
    )
    @ApiResponse(responseCode = "200", description = "Instance state returned successfully")
    @ApiResponse(responseCode = "404", description = "Instance not found",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 404,
                              "error": "Not Found",
                              "message": "Instance not found: 99",
                              "path": "/workflow/99"
                            }
                            """)))
    @GetMapping("/{instanceId}")
    public ResponseEntity<ProcessInstanceResponse> getInstance(
            @Parameter(description = "Workflow instance ID")
            @PathVariable Long instanceId) {

        if (dualityEnabled) {
            // Fetch document directly to access the ETag before converting to response DTO
            WfProcessInstanceDoc doc = dualityService.getInstanceDoc(instanceId);
            ProcessInstanceResponse body = dualityService.buildResponseFromDoc(doc);
            String etag = doc.etagValue();
            if (etag != null) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.ETAG, quote(etag))
                        .body(body);
            }
            return ResponseEntity.ok(body);
        }

        return ResponseEntity.ok(instanceService.getInstance(instanceId));
    }

    @Operation(
            summary = "Set or update instance variables",
            description = "Adds or updates typed variables for a workflow instance. " +
                    "Each variable must declare its type: STRING, INTEGER, FLOAT, BOOLEAN, DATE or JSON. " +
                    "The value is validated against the declared type before persisting — " +
                    "mismatches (e.g. type INTEGER with value 'abc') return HTTP 400. " +
                    "Existing keys are overwritten; new keys are created. Type can be changed on update.\n\n" +
                    "**OCC:** When `bpmnflow.duality.enabled=true`, pass `If-Match` with the current ETag " +
                    "to prevent concurrent variable overwrites. Returns HTTP 412 if stale."
    )
    @ApiResponse(responseCode = "200", description = "Variables set successfully")
    @ApiResponse(responseCode = "400", description = "Invalid variable type or value",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 400,
                              "error": "Bad Request",
                              "message": "Invalid value for type INTEGER: \\"abc\\"",
                              "path": "/workflow/1/variables"
                            }
                            """)))
    @ApiResponse(responseCode = "404", description = "Instance not found",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 404,
                              "error": "Not Found",
                              "message": "Instance not found: 99",
                              "path": "/workflow/99/variables"
                            }
                            """)))
    @ApiResponse(responseCode = "412", description = "ETag mismatch (Duality View mode only)",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 412,
                              "error": "Precondition Failed",
                              "message": "ETag mismatch for instance 42: document was modified by another session. Re-fetch and retry.",
                              "path": "/workflow/42/variables"
                            }
                            """)))
    @PutMapping("/{instanceId}/variables")
    public ResponseEntity<List<VariableResponse>> setVariables(
            @Parameter(description = "Workflow instance ID")
            @PathVariable Long instanceId,
            @Parameter(description = "ETag from last GET response (optional, enables OCC in Duality View mode)")
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody List<VariableRequest> variables) {

        if (dualityEnabled) {
            String etag = unquote(ifMatch);
            List<VariableResponse> body = dualityService.setVariables(instanceId, etag, variables);
            String newEtag = dualityService.getETagForInstance(instanceId);
            if (newEtag != null) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.ETAG, quote(newEtag))
                        .body(body);
            }
            return ResponseEntity.ok(body);
        }

        return ResponseEntity.ok(instanceService.setVariables(instanceId, variables));
    }

    @Operation(
            summary = "Get instance variables",
            description = "Returns all typed variables stored for this workflow instance, " +
                    "including the raw stored value and the converted Java type value."
    )
    @ApiResponse(responseCode = "200", description = "Variables returned successfully")
    @ApiResponse(responseCode = "404", description = "Instance not found",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-04-12T10:00:00",
                              "status": 404,
                              "error": "Not Found",
                              "message": "Instance not found: 99",
                              "path": "/workflow/99/variables"
                            }
                            """)))
    @GetMapping("/{instanceId}/variables")
    public ResponseEntity<List<VariableResponse>> getVariables(
            @Parameter(description = "Workflow instance ID")
            @PathVariable Long instanceId) {

        return ResponseEntity.ok(dualityEnabled
                ? dualityService.getVariables(instanceId)
                : instanceService.getVariables(instanceId));
    }

    // ── ETag header helpers ────────────────────────────────────────────────

    /** Wraps a raw ETag hash in RFC 9110 double-quotes: {@code A3F...} → {@code "A3F..."}  */
    private static String quote(String etag) {
        if (etag == null) return null;
        return etag.startsWith("\"") ? etag : "\"" + etag + "\"";
    }

    /** Strips surrounding double-quotes from an HTTP ETag header value. */
    private static String unquote(String etag) {
        if (etag == null) return null;
        return etag.startsWith("\"") && etag.endsWith("\"")
                ? etag.substring(1, etag.length() - 1)
                : etag;
    }
}