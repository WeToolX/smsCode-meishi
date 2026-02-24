package com.wzz.smscode.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wzz.smscode.dto.EntityDTO.ProjectDTO;
import com.wzz.smscode.dto.project.ProjectPriceDetailsDTO;
import com.wzz.smscode.dto.project.ProjectPriceSummaryDTO;
import com.wzz.smscode.entity.Project;
import com.wzz.smscode.entity.UserProjectLine;
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

    List<UserProjectLine>listUserProjects(Long userId);

}
