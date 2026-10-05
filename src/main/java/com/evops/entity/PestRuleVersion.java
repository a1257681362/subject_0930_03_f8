package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 虫害阈值规则版本：只追加。
 * ACTIVE 当前生效；发布新版本时旧 ACTIVE -> SUPERSEDED，版本与条目内容永不改写。
 * 监测记录固化提交时的规则版本快照，规则新版本不改写历史监测结论。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pest_rule_version")
public class PestRuleVersion extends BaseEntity {

    private String versionNo;
    private String title;
    private String remark;

    /** ACTIVE / SUPERSEDED */
    private String status;

    private String publishedBy;
    private LocalDateTime publishedAt;
}
