package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 点位迁移：旧址置 MOVED 并另立新点位行；
 * 前后坐标与新旧点位都落 t_pest_point_move 永久保留。
 */
@Data
public class PestPointMoveRequest {

    @NotBlank(message = "新点位编号不能为空")
    private String newPointNo;

    @NotNull(message = "新点位 X 坐标不能为空")
    @DecimalMin(value = "0", message = "新点位 X 坐标不能为负")
    private BigDecimal toCoordX;

    @NotNull(message = "新点位 Y 坐标不能为空")
    @DecimalMin(value = "0", message = "新点位 Y 坐标不能为负")
    private BigDecimal toCoordY;

    private String coordDesc;

    private String reason;

    @NotBlank(message = "迁移操作人不能为空")
    private String movedBy;

    /** 迁移时间，缺省取当前时间 */
    private LocalDateTime movedAt;
}
