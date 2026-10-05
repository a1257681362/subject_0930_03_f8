package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 诱捕点位布放：展厅（HALL+hallId）或库房库区（STORAGE+storageAreaId）二选一。
 * 记录点位编号、布放日期与坐标，责任保管员用于处置任务关联责任区域。
 */
@Data
public class PestPointCreateRequest {

    @NotBlank(message = "点位编号不能为空")
    private String pointNo;

    /** HALL / STORAGE */
    @NotBlank(message = "点位区域类型不能为空")
    private String locationType;

    private Long hallId;
    private Long storageAreaId;

    @NotNull(message = "点位 X 坐标不能为空")
    @DecimalMin(value = "0", message = "点位 X 坐标不能为负")
    private BigDecimal coordX;

    @NotNull(message = "点位 Y 坐标不能为空")
    @DecimalMin(value = "0", message = "点位 Y 坐标不能为负")
    private BigDecimal coordY;

    private String coordDesc;

    /** 布放日期，缺省取当前时间 */
    private LocalDateTime laidAt;

    private String trapType;

    private String custodian;
}
