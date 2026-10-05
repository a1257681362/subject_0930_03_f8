package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 虫害规则条目：单个虫种的预警阈值与处置阈值（单次检查捕获数量）。
 */
@Data
public class PestRuleItemRequest {

    @NotBlank(message = "虫种不能为空")
    private String pestSpecies;

    @NotNull(message = "预警阈值不能为空")
    @Min(value = 0, message = "预警阈值不能为负")
    private Integer warnThreshold;

    @NotNull(message = "处置阈值不能为空")
    @Min(value = 0, message = "处置阈值不能为负")
    private Integer alarmThreshold;
}
