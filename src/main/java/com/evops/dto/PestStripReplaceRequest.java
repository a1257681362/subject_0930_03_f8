package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 诱捕片安装/更换：点位上无在用片时为首次安装（STRIP_INSTALL）；
 * 已有在用片时为更换——旧片置 REPLACED 但不删除，旧片期间监测记录全部保留。
 */
@Data
public class PestStripReplaceRequest {

    @NotBlank(message = "新诱捕片编号不能为空")
    private String newStripNo;

    @NotBlank(message = "操作人不能为空")
    private String operator;

    /** 更换原因（首次安装可空，更换时必填由服务端校验） */
    private String replaceReason;

    /** 安装/更换时间，缺省取当前时间 */
    private LocalDateTime operatedAt;
}
