package com.wzz.smscode.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wzz.smscode.cacheManager.NumberRecordCacheManager;
import com.wzz.smscode.dto.project.ProjectPriceDetailsDTO;
import com.wzz.smscode.dto.project.ProjectPriceSummaryDTO;
import com.wzz.smscode.entity.*;
import com.wzz.smscode.exception.BusinessException;
import com.wzz.smscode.mapper.ProjectMapper;
import com.wzz.smscode.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProjectServiceImpl extends ServiceImpl<ProjectMapper, Project> implements ProjectService {

    @Autowired
    @Lazy
    private UserService userService;

    @Autowired
    private UserProjectLineService userProjectLineService;

    // 【新增注入】用于操作模板和模板项
    @Autowired
    @Lazy
    private PriceTemplateService priceTemplateService;

    @Autowired
    @Lazy
    private PriceTemplateItemService priceTemplateItemService;

    @Autowired
    private UserProjectQuotaService userProjectQuotaService;

    @Autowired
    private NumberRecordCacheManager cacheManager; // 注入缓存管理器

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean updateProject(Project projectDTO) {
        if (projectDTO.getId() == null) {
            throw new BusinessException("更新项目失败：必须提供项目的主键ID。");
        }
        Project existingProject = this.getById(projectDTO.getId());
        if (existingProject == null) {
            log.warn("尝试更新一个不存在的项目，ID: {}", projectDTO.getId());
            return false;
        }
        String oldProjectId = existingProject.getProjectId();
        String oldLineId = existingProject.getLineId();
        Project projectToUpdate = new Project();
        BeanUtils.copyProperties(projectDTO, projectToUpdate);
        boolean projectUpdated = this.updateById(projectToUpdate);
        if (!projectUpdated) {
            log.error("更新项目基础信息失败, Project ID: {}", projectDTO.getId());
            throw new BusinessException("更新项目基础信息失败，操作已回滚。");
        }
        cacheManager.evictProject(oldProjectId, oldLineId);
        cacheManager.evictProject(projectDTO.getProjectId(), projectDTO.getLineId());
        log.info("项目基础信息已更新并清理缓存, ID: {}", projectDTO.getId());
        log.info("开始同步更新关联的价格模板项配置...");
        LambdaUpdateWrapper<PriceTemplateItem> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(PriceTemplateItem::getProjectTableId, existingProject.getId());
        try {
            if (projectDTO.getProjectId() != null) {
                updateWrapper.set(PriceTemplateItem::getProjectId, Long.valueOf(projectDTO.getProjectId()));
            }
            if (projectDTO.getLineId() != null) {
                updateWrapper.set(PriceTemplateItem::getLineId, Long.valueOf(projectDTO.getLineId()));
            }
        } catch (NumberFormatException e) {
            log.error("项目ID或线路ID格式转换错误，无法同步模板", e);
        }

        updateWrapper.set(PriceTemplateItem::getProjectName, projectDTO.getProjectName());
        updateWrapper.set(PriceTemplateItem::getCostPrice, projectDTO.getCostPrice());
        updateWrapper.set(PriceTemplateItem::getMinPrice, projectDTO.getPriceMin());
        updateWrapper.set(PriceTemplateItem::getMaxPrice, projectDTO.getPriceMax());

        boolean itemsUpdated = priceTemplateItemService.update(updateWrapper);
        log.info("关联的价格模板项同步完成: {}, Project ID: {}", itemsUpdated, projectDTO.getId());

        return true;
    }

    @Override
    public Project getProject(String projectId, String lineId) {

        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getProjectId, projectId)
                .eq(Project::getLineId, lineId);
        return this.getOne(wrapper);
    }

    @Override
    public List<String> listLines(String projectId) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getProjectId, projectId)
                .select(Project::getLineId);
        List<Project> projects = this.list(wrapper);
        return projects.stream()
                .map(Project::getLineId)
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> listLinesWithCamelCaseKey(String projectId) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getProjectId, projectId)
                .select(Project::getLineId, Project::getLineName);
        List<Project> projects = this.list(wrapper);
        return projects.stream()
                .map(project -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("lineId", project.getLineId());
                    map.put("lineName", project.getLineName());
                    return map;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> listLinesWithCamelCaseKeyFor(Long userId, String projectId) {
        // 1. 基础参数校验
        if (userId == null || projectId == null || projectId.isEmpty()) {
            throw new BusinessException("用户ID和项目ID不能为空");
        }

        // 2. 获取用户信息
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 3. 根据配额过滤线路：只有配额>0的线路才有权限
        List<UserProjectQuota> quotaList = userProjectQuotaService.list(new LambdaQueryWrapper<UserProjectQuota>()
                .eq(UserProjectQuota::getUserId, userId)
                .eq(UserProjectQuota::getProjectId, projectId)
                .gt(UserProjectQuota::getAvailableCount, 0)
                .select(UserProjectQuota::getLineId));
        if (CollectionUtils.isEmpty(quotaList)) {
            return Collections.emptyList();
        }
        List<String> lineIds = quotaList.stream()
                .map(UserProjectQuota::getLineId)
                .distinct()
                .collect(Collectors.toList());

        // 4. 查询项目线路元数据，仅返回系统中启用的线路
        List<Project> projects = this.list(new LambdaQueryWrapper<Project>()
                .eq(Project::getProjectId, projectId)
                .in(Project::getLineId, lineIds)
                .eq(Project::isStatus, true)
                .select(Project::getLineId, Project::getLineName));

        if (CollectionUtils.isEmpty(projects)) {
            return Collections.emptyList();
        }

        // 5. 组装返回结构
        return projects.stream().map(project -> {
            Map<String, Object> map = new HashMap<>();
            map.put("lineId", project.getLineId());
            map.put("lineName", project.getLineName());
            return map;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, ProjectPriceDetailsDTO> getAllProjectPrices() {
        List<Project> allLines = this.list();
        if (allLines.isEmpty()) {
            return Collections.emptyMap();
        }
        return allLines.stream()
                .collect(Collectors.toMap(
                        p -> p.getProjectId() + "-" + p.getLineId(),
                        p -> new ProjectPriceDetailsDTO(p.getPriceMin(),p.getPriceMax(), p.getCostPrice())
                ));
    }

    @Override
    public Map<String, BigDecimal> fillMissingPrices(Map<String, BigDecimal> inputPrices) {
        Map<String, ProjectPriceSummaryDTO> priceSummaries = getAllProjectPriceSummaries();
        List<Project> allProjectLines = this.list();

        for (Project line : allProjectLines) {
            String priceKey = line.getProjectId() + "-" + line.getLineId();
            inputPrices.computeIfAbsent(priceKey, k -> {
                ProjectPriceSummaryDTO summary = priceSummaries.get(line.getProjectId());
                if (summary != null && summary.getMaxPrice() != null) {
                    return summary.getMaxPrice();
                } else {
                    return line.getCostPrice();
                }
            });
        }
        return inputPrices;
    }

    @Override
    public Map<String, ProjectPriceSummaryDTO> getAllProjectPriceSummaries() {
        List<ProjectPriceSummaryDTO> summaries = this.baseMapper.selectProjectPriceSummaries();
        return summaries.stream()
                .collect(Collectors.toMap(ProjectPriceSummaryDTO::getProjectId, Function.identity(), (a, b) -> a));
    }

    /**
     * 重写 save 方法
     * 【修改】: 在创建新项目后，不再为用户直接生成线路，而是同步更新到所有已有的“价格模板”中。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Project project) {
        if(project.getLineId() == null || project.getProjectId() == null || project.getProjectName() == null) {
            throw new BusinessException(0,"项目id，项目名称，线路id不能为空");
        }
        // 1. 保存项目自身
        boolean projectSaved = super.save(project);
        if (!projectSaved) {
            log.error("创建项目基础信息失败: {}", project.getProjectName());
            return false;
        }
        log.info("项目 '{}' 已成功保存，ID为: {}", project.getProjectName(), project.getId());

        List<PriceTemplate> allTemplates = priceTemplateService.list();
        if (CollectionUtils.isEmpty(allTemplates)) {
            log.warn("项目 '{}' 创建成功，但系统中没有找到任何价格模板，无需同步。", project.getProjectName());
            return true;
        }
        List<PriceTemplateItem> itemsToInsert = new ArrayList<>();

        BigDecimal initialPrice = project.getPriceMax() != null && project.getPriceMax().compareTo(BigDecimal.ZERO) > 0
                ? project.getPriceMax()
                : project.getCostPrice();

        for (PriceTemplate template : allTemplates) {
            PriceTemplateItem item = new PriceTemplateItem();
            item.setTemplateId(template.getId());
            item.setProjectTableId(project.getId()); // 关联项目主键ID

            try {
                item.setProjectId(Long.valueOf(project.getProjectId()));
                item.setLineId(Long.valueOf(project.getLineId()));
            } catch (NumberFormatException e) {
                log.error("项目ID或线路ID非数字，跳过该项模板同步: {}", project.getProjectName());
                continue;
            }

            item.setProjectName(project.getProjectName());

            // 设置价格相关
            item.setCostPrice(project.getCostPrice());
            item.setPrice(initialPrice); // 模板项的默认售价
            item.setMinPrice(project.getPriceMin());
            item.setMaxPrice(project.getPriceMax());

            itemsToInsert.add(item);
        }

        // 4. 批量保存模板项
        if (!itemsToInsert.isEmpty()) {
            log.info("准备为 {} 个模板批量插入项目 '{}' 的配置项...", itemsToInsert.size(), project.getProjectName());
            boolean itemsSaved = priceTemplateItemService.saveBatch(itemsToInsert);
            if (!itemsSaved) {
                throw new BusinessException("为模板批量创建项目配置失败，操作已回滚。");
            }
        }

        return true;
    }

    /**
     * 重写 deleteByID 方法
     * 【修改】: 删除项目时，同步删除关联的“价格模板项”，不再删除用户线路配置(UserProjectLine)。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByID(long id) {
        Project projectToDelete = this.getById(id);
        if (projectToDelete == null) {
            log.warn("尝试删除一个不存在的项目，ID: {}", id);
            throw new BusinessException("删除失败：找不到ID为 " + id + " 的项目。");
        }

        // 1. 【修改】删除所有模板中关联该项目的配置项
        log.info("正在删除项目 '{}' (ID: {})，将同步删除所有关联的价格模板项...", projectToDelete.getProjectName(), id);

        LambdaQueryWrapper<PriceTemplateItem> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(PriceTemplateItem::getProjectTableId, id);

        // 执行删除模板项
        priceTemplateItemService.remove(deleteWrapper);
        log.info("项目 '{}' 关联的模板项配置已清理完毕。", projectToDelete.getProjectName());

        // 2. 删除项目本身
        boolean projectRemoved = this.removeById(id);
        if (!projectRemoved) {
            throw new BusinessException("删除项目主体记录失败，操作已回滚。");
        }

        log.info("项目 '{}' (ID: {}) 已被成功删除。", projectToDelete.getProjectName(), id);
        return true;
    }

    @Override
    public List<UserProjectLine> listUserProjects(Long userId) {
        return userProjectLineService.getLinesByUserId(userId);
    }
}
