package com.wzz.smscode.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class UserLineStatsDTO {
    private Long userId;
    private String userName;
    private String projectId;
    private String lineId;

    private Long totalNumbers; // 取号总数
    private Long totalCodes;   // 取码总数 (status=2)
    private String successRate; // 来码率 (百分比字符串)

    // 辅助字段，用于后端计算百分比，不一定非要返回给前端
    public void calculateRate() {
        if (totalNumbers == null || totalNumbers == 0) {
            this.successRate = "0.00%";
        } else {
            BigDecimal codes = new BigDecimal(totalCodes);
            BigDecimal total = new BigDecimal(totalNumbers);
            this.successRate = codes.divide(total, 4, BigDecimal.ROUND_HALF_UP)
                    .multiply(new BigDecimal(100))
                    .setScale(2, BigDecimal.ROUND_HALF_UP) + "%";
        }
    }
}
