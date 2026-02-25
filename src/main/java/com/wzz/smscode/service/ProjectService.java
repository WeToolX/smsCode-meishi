package com.wzz.smscode.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wzz.smscode.dto.project.SelectProjectDTO;
import com.wzz.smscode.dto.project.ProjectPriceDetailsDTO;
import com.wzz.smscode.dto.project.ProjectPriceSummaryDTO;
import com.wzz.smscode.entity.Project;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface ProjectService extends IService<Project> {


    @Transactional(rollbackFor = Exception.class) // 确保事务性
    boolean updateProject(Project projectDTO);

    Project getProject(String projectId, String lineId);

    List<String> listLines(String projectId);

    List<Map<String, Object>> listLinesWithCamelCaseKey(String projectId);


    List<Map<String, Object>> listLinesWithCamelCaseKeyFor(Long userId, String projectId);

    Map<String, ProjectPriceDetailsDTO> getAllProjectPrices();

    Map<String, BigDecimal> fillMissingPrices(Map<String, BigDecimal> inputPrices);

    Map<String, ProjectPriceSummaryDTO> getAllProjectPriceSummaries();

    Boolean deleteByID(long id);

    /**
     * 查询用户可用项目列表（仅返回有剩余配额的项目）
     *
     * @param userId 用户ID
     * @return 项目列表（包含 projectId / projectName）
     */
    List<SelectProjectDTO> listUserProjects(Long userId);

}
