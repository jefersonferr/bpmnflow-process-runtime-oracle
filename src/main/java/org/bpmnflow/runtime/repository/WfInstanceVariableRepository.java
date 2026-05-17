package org.bpmnflow.runtime.repository;

import org.bpmnflow.runtime.model.entity.WfInstanceVariableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WfInstanceVariableRepository
        extends JpaRepository<WfInstanceVariableEntity, Long> {

    List<WfInstanceVariableEntity> findByInstance_InstanceId(Long instanceId);

    Optional<WfInstanceVariableEntity> findByInstance_InstanceIdAndVariableKey(
            Long instanceId, String variableKey);

    /**
     * Atomic upsert: UPDATE if exists, INSERT if not.
     *
     * Uses two separate statements instead of MERGE because Oracle MERGE
     * is not immune to concurrent INSERT races — two sessions can both enter
     * WHEN NOT MATCHED before either commits, causing ORA-00001.
     *
     * Strategy: attempt UPDATE first (no race condition — UPDATE acquires a
     * row lock). If 0 rows updated (row doesn't exist yet), attempt INSERT.
     * If INSERT also fails with ORA-00001 (another session beat us), do a
     * final UPDATE. This is the standard Oracle "upsert" pattern.
     */
    @Modifying
    @Query(value = """
            UPDATE wf_instance_variable
               SET variable_type  = :variableType,
                   variable_value = :variableValue,
                   updated_at     = SYSTIMESTAMP
             WHERE instance_id  = :instanceId
               AND variable_key = :variableKey
            """, nativeQuery = true)
    int updateVariable(@Param("instanceId")    Long   instanceId,
                       @Param("variableKey")   String variableKey,
                       @Param("variableType")  String variableType,
                       @Param("variableValue") String variableValue);

    @Modifying
    @Query(value = """
            INSERT INTO wf_instance_variable
                (instance_id, variable_key, variable_type, variable_value, created_at, updated_at)
            VALUES (:instanceId, :variableKey, :variableType, :variableValue, SYSTIMESTAMP, SYSTIMESTAMP)
            """, nativeQuery = true)
    void insertVariable(@Param("instanceId")    Long   instanceId,
                        @Param("variableKey")   String variableKey,
                        @Param("variableType")  String variableType,
                        @Param("variableValue") String variableValue);
}