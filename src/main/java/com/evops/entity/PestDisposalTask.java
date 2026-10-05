package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 虫害处置任务：监测虫种数量达到处置阈值时自动开立，按 (点位, 虫种) 归并连续超阈。
 *
 * <ul>
 *   <li>OPEN：待处置，连续超阈检查并入本任务（更新 last/peak/count）；</li>
 *   <li>HANDLING：已由馆内责任岗位登记处置，仍接受连续超阈并入；</li>
 *   <li>CLOSED：馆内责任岗位复核关闭并封存；外包人员不能关闭。</li>
 * </ul>
 *
 * 任务关联点位所在责任区域（HALL: hall_id / STORAGE: storage_area_id），
 * 并固化责任区域编号、名称与责任保管员快照。
 * mergeKey = point|species 仅 OPEN/HANDLING 持有非空且唯一；CLOSED 置 null
 * （H2 唯一约束允许多个 NULL），再次超阈另开新任务。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_disposal_task")
public class PestDisposalTask extends BaseEntity {

    private String taskNo;

    private Long pointId;
    private String pointNo;

    private String pestSpecies;

    /** 连续超阈归并锚点 point|species，仅在途任务非空 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String mergeKey;

    /** HALL / STORAGE */
    private String locationType;
    private Long hallId;
    private Long storageAreaId;

    private String areaNoSnapshot;
    private String areaNameSnapshot;
    private String custodian;

    /** 首警时生效规则版本与处置阈值快照 */
    private Long ruleVersionId;
    private String ruleVersionNo;
    private Integer alarmThreshold;

    private Integer firstCount;
    private Integer lastCount;
    private Integer peakCount;
    private Integer checkCount;

    private Long firstCheckId;
    private Long lastCheckId;
    private LocalDateTime firstAt;
    private LocalDateTime lastAt;

    /** 处置登记（馆内岗位） */
    private String handler;
    private String handleRemark;
    private LocalDateTime handledAt;

    /** 关闭（馆内岗位复核） */
    private String closedBy;
    private String closeRemark;
    private LocalDateTime closedAt;

    /** OPEN / HANDLING / CLOSED */
    private String status;
}
