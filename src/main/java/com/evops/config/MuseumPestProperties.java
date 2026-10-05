package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 虫害监测配置（museum.pest.*）。
 *
 * <ul>
 *   <li>{@code freeze-on-startup}：应用就绪后是否补冻结上月（重启/跨月场景）；</li>
 *   <li>{@code freeze-scan-interval-ms}：月度冻结定时扫描间隔（毫秒），
 *       扫描只冻结"已结束的上月及更早"未冻结月份，当月永不冻结；</li>
 *   <li>{@code default-custodian}：点位未指定责任保管员时处置任务上的兜底责任人。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.pest")
public class MuseumPestProperties {

    /** 启动就绪后是否补冻结历史月份 */
    private boolean freezeOnStartup = true;

    /** 月度冻结定时扫描间隔（毫秒） */
    private long freezeScanIntervalMs = 3600000;

    /** 点位未指定保管员时的兜底责任人 */
    private String defaultCustodian = "馆方保管部";
}
