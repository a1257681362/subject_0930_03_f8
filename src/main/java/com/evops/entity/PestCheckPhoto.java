package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 监测现场照片：一行一张，内容寻址复用影像归档存储。
 * 随现场监测提交，只追加、不删除；记录提交岗位快照（含外包人员上传场景）。
 */
@Data
@TableName("t_pest_check_photo")
public class PestCheckPhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long checkId;
    private Long fileId;
    private String contentHash;
    private String photoName;
    private String uploadedBy;
    private String uploadedRole;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
