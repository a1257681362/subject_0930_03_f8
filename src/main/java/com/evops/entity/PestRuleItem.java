package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 虫害规则条目：同一版本内每虫种一行，固化单次检查的预警/处置数量阈值。
 * 阈值随监测明细行再次快照，历史监测结论不随后续版本调整而变化。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_rule_item")
public class PestRuleItem extends BaseEntity {

    private Long ruleVersionId;

    private String pestSpecies;

    /** 预警阈值（达到即 WARN） */
    private Integer warnThreshold;

    /** 处置阈值（达到即 ALARM，自动开立处置任务） */
    private Integer alarmThreshold;
}
