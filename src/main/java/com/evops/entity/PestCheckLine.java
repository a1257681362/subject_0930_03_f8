package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 监测虫种明细行：一次检查每虫种一行，固化当时阈值与判定结论。
 * 规则后续调整只能发新版本，永不改写本行保存的历史结论。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_check_line")
public class PestCheckLine extends BaseEntity {

    private Long checkId;

    private String pestSpecies;

    private Integer count;

    private Integer warnThreshold;
    private Integer alarmThreshold;

    /** NORMAL / WARN / ALARM */
    private String level;
}
