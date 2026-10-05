package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 诱捕片更换记录：一片一行、只追加。
 * IN_USE 使用中；更换时旧片置 REPLACED（不删除旧片行，也不删除旧片期间的监测记录）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_trap_strip")
public class PestTrapStrip extends BaseEntity {

    private Long pointId;

    private String stripNo;

    private LocalDateTime installedAt;
    private String installedBy;

    private LocalDateTime replacedAt;
    private String replacedBy;
    private String replaceReason;

    /** IN_USE / REPLACED */
    private String status;
}
