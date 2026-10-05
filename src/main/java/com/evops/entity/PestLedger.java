package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 虫害台账：只追加、不修改、不删除。
 * 规则发布/布点/停用/迁移/换片/检查/任务开闭/月度冻结全部留痕，
 * 每条尽量固化规则版本号与责任区域，虫害监测全程可追溯。
 */
@Data
@TableName("t_pest_ledger")
public class PestLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pointId;
    private Long checkId;
    private Long taskId;
    private Long hallId;
    private Long storageAreaId;
    private Long ruleVersionId;
    private String ruleVersionNo;

    /** PEST_RULE_PUBLISH / POINT_LAY / POINT_CLOSE / POINT_MOVE / STRIP_INSTALL
     *  / STRIP_REPLACE / CHECK_OK / CHECK_ALARM / TASK_OPEN / TASK_MERGE
     *  / TASK_HANDLE / TASK_CLOSE / MONTH_FREEZE */
    private String action;

    private String bizNo;
    private String summary;
    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
