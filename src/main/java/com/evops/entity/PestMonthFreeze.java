package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 监测结果月度冻结：(period_month, scope_type, scope_id) 唯一，幂等。
 * 冻结后该月该范围现场监测记录不可补录/修改；冻结动作只追加，不提供解冻。
 * scope_type: ALL 全馆（scope_id=null）/ HALL 单展厅 / STORAGE 单库区。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_month_freeze")
public class PestMonthFreeze extends BaseEntity {

    /** yyyy-MM */
    private String periodMonth;

    /** ALL / HALL / STORAGE */
    private String scopeType;

    /** ALL 时为 null */
    private Long scopeId;

    private String frozenBy;
    private LocalDateTime frozenAt;

    /** 冻结时纳入的监测记录条数快照 */
    private Integer checkCount;

    private String remark;
}
