package com.evops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 处置任务现场照片：一行一张、只追加。
 * 外包人员可随现场数据上传；任务关闭前至少需有一张。
 */
@Data
@TableName("t_pest_task_photo")
public class PestTaskPhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private Long fileId;
    private String contentHash;
    private String photoName;
    private String uploadedBy;
    private String uploadedRole;
    private String remark;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createTime;
}
