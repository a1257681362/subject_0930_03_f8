package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 点位迁移记录：每次迁移一行、只追加。
 * 旧点位坐标（from_coord_*）与新点位坐标（to_coord_*）、新旧点位 id 都永久保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_point_move")
public class PestPointMove extends BaseEntity {

    /** 旧点位 id */
    private Long pointId;

    /** 新点位 id */
    private Long newPointId;

    private BigDecimal fromCoordX;
    private BigDecimal fromCoordY;
    private BigDecimal toCoordX;
    private BigDecimal toCoordY;

    private String reason;
    private String movedBy;
    private LocalDateTime movedAt;
}
