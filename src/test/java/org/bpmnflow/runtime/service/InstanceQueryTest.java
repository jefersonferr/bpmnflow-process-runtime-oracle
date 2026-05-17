package org.bpmnflow.runtime.service;

import org.bpmnflow.runtime.dto.*;
import org.bpmnflow.runtime.ResourceNotFoundException;
import org.bpmnflow.runtime.model.entity.*;
import org.bpmnflow.runtime.model.entity.ActivityStepStatus;
import org.bpmnflow.runtime.model.entity.InstanceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ProcessInstanceService — instance queries")
class InstanceQueryTest extends ProcessInstanceServiceTestBase {

    // ---------------------------------------------------------------
    // getInstance
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("getInstance")
    class GetInstance {

        @Test
        @DisplayName("returns full instance state with available conclusions")
        void returnsFullState() {
            WfProcessInstanceEntity instance = instance(INSTANCE_ID, "ACTIVE", "IN_PREPARATION");
            WfInstanceActivityEntity step = step(instance, actBAK, 4, "ACTIVE");
            instance.getInstanceActivities().add(step);

            when(instanceRepo.findById(INSTANCE_ID)).thenReturn(Optional.of(instance));
            when(instActivityRepo.findByInstance_InstanceIdAndStatus(INSTANCE_ID, ActivityStepStatus.ACTIVE))
                    .thenReturn(Optional.of(step));
            when(variableRepo.findByInstance_InstanceId(INSTANCE_ID)).thenReturn(List.of());

            ProcessInstanceResponse resp = service.getInstance(INSTANCE_ID);

            assertThat(resp.getInstanceId()).isEqualTo(INSTANCE_ID);
            assertThat(resp.getInstanceStatus()).isEqualTo("ACTIVE");
            assertThat(resp.getProcessStatus()).isEqualTo("IN_PREPARATION");
            assertThat(resp.getCurrentActivity().getAbbreviation()).isEqualTo("CH-BAK");
            assertThat(resp.getCurrentActivity().getAvailableConclusions())
                    .extracting("code")
                    .containsExactlyInAnyOrder("READY_FOR_DELIVERY", "NOT_READY");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when instance does not exist")
        void throwsWhenNotFound() {
            when(instanceRepo.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getInstance(99L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Instance not found");
        }
    }

    // ---------------------------------------------------------------
    // listInstances — uses projection queries with Pageable
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("listInstances")
    class ListInstances {

        // Helper: build a WorkflowSummaryProjection anonymous instance
        private WorkflowSummaryProjection projection(Long instanceId,
                                                     String instanceStatus,
                                                     String processStatus,
                                                     String processKey,
                                                     String activityAbbreviation) {
            return new WorkflowSummaryProjection() {
                public Long getInstanceId()                    { return instanceId; }
                public String getExternalId()                  { return null; }
                public String getInstanceStatus()              { return instanceStatus; }
                public String getProcessStatus()               { return processStatus; }
                public Long getVersionId()                     { return VERSION_ID; }
                public Integer getVersionNumber()              { return 1; }
                public String getProcessKey()                  { return processKey; }
                public String getProcessName()                 { return "Pizza Delivery"; }
                public String getCurrentActivityAbbreviation() { return activityAbbreviation; }
                public String getCurrentActivityName()         { return null; }
                public LocalDateTime getCreatedAt()            { return LocalDateTime.now(); }
                public LocalDateTime getUpdatedAt()            { return LocalDateTime.now(); }
                public LocalDateTime getCompletedAt()          { return null; }
            };
        }

        @Test
        @DisplayName("returns all instances when no filter is provided")
        void returnsAllWithNoFilter() {
            var proj = projection(INSTANCE_ID, "ACTIVE", "NEW", "PIZZA_DELIVERY", "CS-SEL");

            when(instanceRepo.findAllSummary(any(Pageable.class))).thenReturn(List.of(proj));

            List<WorkflowSummaryResponse> result = service.listInstances(null, null, 0, 50);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getInstanceId()).isEqualTo(INSTANCE_ID);
            assertThat(result.getFirst().getCurrentActivityAbbreviation()).isEqualTo("CS-SEL");
            verify(instanceRepo).findAllSummary(PageRequest.of(0, 50));
        }

        @Test
        @DisplayName("filters by status")
        void filtersByStatus() {
            var proj = projection(INSTANCE_ID, "ACTIVE", "NEW", "PIZZA_DELIVERY", "CS-SEL");

            when(instanceRepo.findSummaryByStatus(eq(InstanceStatus.ACTIVE), any(Pageable.class)))
                    .thenReturn(List.of(proj));

            List<WorkflowSummaryResponse> result = service.listInstances("active", null, 0, 50);

            assertThat(result).hasSize(1);
            verify(instanceRepo).findSummaryByStatus(InstanceStatus.ACTIVE, PageRequest.of(0, 50));
        }

        @Test
        @DisplayName("filters by processKey")
        void filtersByProcessKey() {
            var proj = projection(INSTANCE_ID, "ACTIVE", "NEW", "PIZZA_DELIVERY", "CS-SEL");

            when(instanceRepo.findSummaryByProcessKey(eq("PIZZA_DELIVERY"), any(Pageable.class)))
                    .thenReturn(List.of(proj));

            List<WorkflowSummaryResponse> result = service.listInstances(null, "PIZZA_DELIVERY", 0, 50);

            assertThat(result).hasSize(1);
            verify(instanceRepo).findSummaryByProcessKey("PIZZA_DELIVERY", PageRequest.of(0, 50));
        }

        @Test
        @DisplayName("filters by both processKey and status")
        void filtersByProcessKeyAndStatus() {
            var proj = projection(INSTANCE_ID, "COMPLETED", "CLOSED", "PIZZA_DELIVERY", null);

            when(instanceRepo.findSummaryByProcessKeyAndStatus(
                    eq("PIZZA_DELIVERY"), eq(InstanceStatus.COMPLETED), any(Pageable.class)))
                    .thenReturn(List.of(proj));

            List<WorkflowSummaryResponse> result = service.listInstances("completed", "PIZZA_DELIVERY", 0, 50);

            assertThat(result).hasSize(1);
            verify(instanceRepo).findSummaryByProcessKeyAndStatus(
                    "PIZZA_DELIVERY", InstanceStatus.COMPLETED, PageRequest.of(0, 50));
        }

        @Test
        @DisplayName("returns empty list when no instances match")
        void returnsEmptyList() {
            when(instanceRepo.findAllSummary(any(Pageable.class))).thenReturn(List.of());

            assertThat(service.listInstances(null, null, 0, 50)).isEmpty();
        }

        @Test
        @DisplayName("currentActivityAbbreviation is null for COMPLETED instance")
        void completedInstanceHasNoCurrentActivity() {
            var proj = projection(INSTANCE_ID, "COMPLETED", "CLOSED", "PIZZA_DELIVERY", null);

            when(instanceRepo.findAllSummary(any(Pageable.class))).thenReturn(List.of(proj));

            List<WorkflowSummaryResponse> result = service.listInstances(null, null, 0, 50);

            assertThat(result.getFirst().getCurrentActivityAbbreviation()).isNull();
            assertThat(result.getFirst().getInstanceStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("respects page and size parameters")
        void respectsPagination() {
            when(instanceRepo.findAllSummary(any(Pageable.class))).thenReturn(List.of());

            service.listInstances(null, null, 2, 25);

            verify(instanceRepo).findAllSummary(PageRequest.of(2, 25));
        }
    }
}