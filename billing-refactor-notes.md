# 计费模式整改说明（project_id + line_id 配额模式）

## 1. 改造目标

本次改造将系统计费模式从“金额/价格模板”切换为“项目线路配额（验证码个数）”。

核心规则：

- 计费维度：`user_id + project_id + line_id`
- 取号：先预扣 1 个配额
- 无验证码：退回 1 个配额
- 代理充值：必须扣代理同项目同线路配额，再加到下级
- 用户线路权限：仅当该线路配额 `> 0` 时有权限
- 用户余额查询：必须传 `project_id + line_id`，返回线路剩余配额

## 2. 新增内容

### 2.1 新增表模型

1. `user_project_quota`
- 作用：存储每个用户在每个项目线路下的可用配额
- 关键字段：`user_id`, `project_id`, `line_id`, `available_count`
- 关键约束：`(user_id, project_id, line_id)` 唯一

2. `user_project_quota_ledger`
- 作用：记录配额变动流水，支持业务幂等
- 关键字段：`biz_no`, `user_id`, `operator_id`, `project_id`, `line_id`, `change_count`, `ledger_type`, `count_before`, `count_after`
- 关键约束：`biz_no` 唯一

### 2.2 新增代码文件

1. `src/main/java/com/wzz/smscode/entity/UserProjectQuota.java`
2. `src/main/java/com/wzz/smscode/entity/UserProjectQuotaLedger.java`
3. `src/main/java/com/wzz/smscode/mapper/UserProjectQuotaMapper.java`
4. `src/main/java/com/wzz/smscode/mapper/UserProjectQuotaLedgerMapper.java`
5. `src/main/java/com/wzz/smscode/service/UserProjectQuotaService.java`
6. `src/main/java/com/wzz/smscode/service/impl/UserProjectQuotaServiceImpl.java`

### 2.3 新增服务能力

`UserProjectQuotaService` 新增能力：

1. 查询线路配额：`getAvailableCount`
2. 扣减配额（幂等）：`deductQuota`
3. 增加配额（幂等）：`addQuota`
4. 转移配额（代理扣自己给下级）：`transferQuota`
5. 查询有配额线路：`listAvailableLineIds`

## 3. 已改动内容

### 3.1 核心业务链路改动

1. `src/main/java/com/wzz/smscode/service/impl/NumberRecordServiceImpl.java`

改了什么：
- `getNumber`：去掉价格模板和余额判断，改为检查项目线路配额
- `createOrderTransaction`：改为写记录后按业务号 `NR_PRE_{recordId}` 预扣 1 个配额
- `updateRecordAfterRetrieval`：
  - 超时/失败：按 `NR_REFUND_{recordId}` 退回 1 个配额
  - 退款后获码成功：按 `NR_REDEDUCT_{recordId}` 再扣 1 个配额
- `batchRefundByQuery`：由金额退款改为批量退回配额

为什么改：
- 满足“先扣费、无码退费”的新配额模式
- 保证扣减与退款幂等，防重复扣退

改后作用：
- 取号/取码主流程完全脱离金额计费
- 以项目线路配额作为唯一计费依据

2. `src/main/java/com/wzz/smscode/service/impl/ProjectServiceImpl.java`

改了什么：
- `listLinesWithCamelCaseKeyFor` 不再依赖模板，改为按 `user_project_quota.available_count > 0` 返回线路

为什么改：
- 用户线路权限改为“是否有该线路配额”

改后作用：
- 无配额线路自然无权限

3. `src/main/java/com/wzz/smscode/service/impl/UserServiceImpl.java`

改了什么：
- `getBalance` 改为按 `project_id + line_id` 返回配额
- `chargeUser/rechargeUser/deductUser` 改为配额操作（后台）
- `rechargeUserFromAgentBalance/deductUserToAgentBalance` 改为代理与下级之间的同项目线路配额转移
- `createUser` 去除对模板和初始金额记账的强依赖
- `processRebates` 旧金额返点逻辑改为停用日志
- `batchFundOperation` 明确抛出“旧金额接口已弃用”

为什么改：
- 后台与代理侧充值/扣款必须统一到配额体系
- 完全停止旧金额模式副作用

改后作用：
- 管理端、代理端、用户端全链路统一配额口径

### 3.2 接口层改动

1. 用户接口：`src/main/java/com/wzz/smscode/controller/user/UserController.java`

- `GET/POST /api/user/getNumber`
  - 变更：`lineId` 参数改为 `String`
  - 作用：统一线路ID字符串化

- `GET/POST /api/user/getBalance`
  - 旧参数：`userName`, `password`
  - 新参数：`userName`, `password`, `projectId`, `lineId`
  - 新返回：线路配额数量（`Long`）
  - 作用：用户余额查询改为“项目线路配额查询”

2. 代理接口：`src/main/java/com/wzz/smscode/controller/agent/AgentController.java`

- `POST /api/agent/rechargeUser`
  - 旧参数：`targetUserId`, `amount`
  - 新参数：`targetUserId`, `projectId`, `lineId`, `count`

- `POST /api/agent/deductUser`
  - 旧参数：`targetUserId`, `amount`
  - 新参数：`targetUserId`, `projectId`, `lineId`, `count`

作用：
- 强制代理按“同项目同线路配额”对下级进行充值/扣减

3. 管理员接口：`src/main/java/com/wzz/smscode/controller/admin/AdminController.java`

- `POST /api/admin/rechargeUser`
  - 旧参数：`targetUserId`, `amount`
  - 新参数：`targetUserId`, `projectId`, `lineId`, `count`

- `POST /api/admin/deductUser`
  - 旧参数：`targetUserId`, `amount`
  - 新参数：`targetUserId`, `projectId`, `lineId`, `count`

作用：
- 后台资金调整改为后台配额调整

### 3.3 线路ID类型统一改动

改了什么：
- `NumberRecord.lineId` 改为 `String`
- 查询/统计 DTO 的 `lineId` 统一改为 `String`
- `ProjectService.getProject`、缓存组件 `NumberRecordCacheManager` 的线路参数改为 `String`

涉及文件：

1. `src/main/java/com/wzz/smscode/entity/NumberRecord.java`
2. `src/main/java/com/wzz/smscode/dto/number/NumberDTO.java`
3. `src/main/java/com/wzz/smscode/dto/StatisticsQueryDTO.java`
4. `src/main/java/com/wzz/smscode/dto/SubordinateNumberRecordQueryDTO.java`
5. `src/main/java/com/wzz/smscode/dto/UserLineStatsDTO.java`
6. `src/main/java/com/wzz/smscode/dto/LineStatisticsDTO.java`
7. `src/main/java/com/wzz/smscode/service/ProjectService.java`
8. `src/main/java/com/wzz/smscode/service/impl/ProjectServiceImpl.java`
9. `src/main/java/com/wzz/smscode/cacheManager/NumberRecordCacheManager.java`
10. `src/main/java/com/wzz/smscode/moduleService/SmsApiService.java`
11. `src/main/java/com/wzz/smscode/service/NumberRecordService.java`

## 4. 弃用内容（接口与逻辑）

以下为“已明确弃用”的旧金额能力：

1. 价格模板能力（后端与前端）已整体移除
- 价格模板实体/服务/Mapper/DTO/接口已删除，不再存在任何模板管理链路
- 用户创建、用户编辑、用户列表不再包含模板相关字段与逻辑

2. 旧金额返点流程
- `UserServiceImpl.processRebates` 已停用（仅记录日志）

3. 旧金额批量资金接口
- `UserServiceImpl.batchFundOperation` 已明确抛出“已弃用”异常

4. 用户创建时模板/初始金额入账逻辑
- 创建用户不再依赖模板和金额充值逻辑

5. 旧金额账本相关接口已下线（保留路由，统一返回“已下线”）
- 管理端：
  - `GET/POST /api/admin/viewUserLedger`
  - `GET/POST /api/admin/viewAllLedger`
  - `POST /api/admin/ledger/clear-physical`
  - `GET /api/admin/get/user-id/leader/`
- 代理端：
  - `GET /api/agent/viewUserLedger`
  - `GET /api/agent/subordinate-ledgers`
  - `GET /api/agent/my-ledger`
  - `POST /api/agent/ledger/clear`
  - `GET /api/agent/by-user/totalProfit`
- 用户端：
  - `GET/POST /api/user/ledger/list`
  - `GET/POST /api/user/ledger/clear`

为什么改：
- 这些接口全部依赖旧金额账本模型，不符合“按项目线路配额计费”的新规则。

改后作用：
- 从接口层彻底阻断旧金额逻辑继续被调用，避免新旧计费混用。

## 5. 本轮物理删除的旧金额代码文件

以下文件已从代码库删除：

1. `src/main/java/com/wzz/smscode/service/UserLedgerService.java`
2. `src/main/java/com/wzz/smscode/service/impl/UserLedgerServiceImpl.java`
3. `src/main/java/com/wzz/smscode/mapper/UserLedgerMapper.java`
4. `src/main/java/com/wzz/smscode/entity/UserLedger.java`
5. `src/main/java/com/wzz/smscode/dto/CreatDTO/LedgerCreationDTO.java`
6. `src/main/java/com/wzz/smscode/dto/EntityDTO/LedgerDTO.java`
7. `src/main/java/com/wzz/smscode/enums/FundType.java`
8. `src/main/java/com/wzz/smscode/util/BalanceUtil.java`

为什么改：
- 这些文件属于旧金额账本核心模型与工具，继续保留会引入误用风险。

改后作用：
- 编译期即可阻断任何新代码继续依赖旧金额体系。

## 6. 兼容性说明

1. 旧金额账本服务、实体、Mapper、DTO、工具类已物理删除。
2. 对外账本接口已下线并返回明确错误提示，防止旧前端误调用。
3. 价格模板模块已物理删除，不再提供任何模板相关接口。
4. `User.balance` 等历史金额字段尚未做数据库结构删除（如需删库字段，建议单独执行DDL变更窗口）。

## 7. 本轮新增：价格模板代码移除清单

### 7.1 后端删除文件

1. `src/main/java/com/wzz/smscode/dto/PriceTemplateCreateDTO.java`
2. `src/main/java/com/wzz/smscode/dto/PriceTemplateItemDTO.java`
3. `src/main/java/com/wzz/smscode/dto/PriceTemplateResponseDTO.java`
4. `src/main/java/com/wzz/smscode/entity/PriceTemplate.java`
5. `src/main/java/com/wzz/smscode/entity/PriceTemplateItem.java`
6. `src/main/java/com/wzz/smscode/mapper/PriceTemplateMapper.java`
7. `src/main/java/com/wzz/smscode/mapper/PriceTemplateItemMapper.java`
8. `src/main/java/com/wzz/smscode/service/PriceTemplateService.java`
9. `src/main/java/com/wzz/smscode/service/PriceTemplateItemService.java`
10. `src/main/java/com/wzz/smscode/service/impl/PriceTemplateServiceImpl.java`
11. `src/main/java/com/wzz/smscode/service/impl/PriceTemplateItemServiceImpl.java`

### 7.2 后端代码改动

1. `src/main/java/com/wzz/smscode/controller/admin/AdminController.java`
- 移除全部价格模板接口与模板字段回填逻辑。

2. `src/main/java/com/wzz/smscode/controller/agent/AgentController.java`
- 移除全部价格模板接口与模板字段查询逻辑。

3. `src/main/java/com/wzz/smscode/service/impl/UserServiceImpl.java`
- 移除用户更新、用户分页中的模板关联逻辑。

4. `src/main/java/com/wzz/smscode/service/impl/ProjectServiceImpl.java`
- 移除项目与价格模板项的同步创建/更新/删除逻辑。

5. `src/main/java/com/wzz/smscode/entity/User.java`
- 移除 `templateId` 与 `templateName` 字段。

6. `src/main/java/com/wzz/smscode/dto/CreatDTO/UserCreateDTO.java`
7. `src/main/java/com/wzz/smscode/dto/update/UserUpdateDtoByUser.java`
- 移除模板字段，仅保留与用户本身和黑名单相关字段。

### 7.3 前端删除文件

1. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/pages/UserProjectPriceTemplate.vue`
2. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/pages/BillManage.vue`
3. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/components/RecordDialog.vue`
4. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/components/RechargeDialog.vue`

### 7.4 前端代码改动

1. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/pages/UserManage.vue`
- 移除模板选择与模板预览逻辑，改为纯用户管理与项目线路配额充值/扣减。

2. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/components/EditDialog.vue`
- 重写为不含模板逻辑的编辑弹窗。

3. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/router/index.js`
4. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/pages/Dashboard.vue`
- 移除模板页面路由和首页入口。

5. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/api/admin.js`
- 移除所有模板相关 API 方法。

## 8. 验证结果

已执行：

- `sh mvnw -q -DskipTests compile`
- `npm run build`（前端）

结果：

- 编译通过。

## 9. 本轮新增：移除“余额封控下限”配置与后端逻辑

### 9.1 后端改动

1. `src/main/java/com/wzz/smscode/entity/SystemConfig.java`
- 移除字段：`balanceThreshold`（`balance_threshold`）

2. `src/main/java/com/wzz/smscode/service/SystemConfigService.java`
- 移除接口方法：`getBalanceThreshold()`

3. `src/main/java/com/wzz/smscode/service/impl/SystemConfigServiceImpl.java`
- `updateConfig` 中移除 `balanceThreshold` 参数校验
- 移除 `getBalanceThreshold()` 实现

4. `src/main/java/com/wzz/smscode/enums/Status.java`
- `-4` 状态描述从“余额不足（或受到余额封控限制）”改为“项目线路配额不足”

5. `src/main/java/com/wzz/smscode/common/Constants.java`
6. `src/main/java/com/wzz/smscode/common/CommonResultDTO.java`
- `-4` 相关注释统一改为“项目线路配额不足”

为什么改：
- 新计费模型为项目线路配额，不再存在余额封控场景，保留该配置会造成误导和误用风险。

改后作用：
- 系统配置与业务含义保持一致，后端不再暴露或处理余额封控下限逻辑。

### 9.2 前端改动

1. `/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi/src/pages/SystemConfig.vue`
- 移除“余额封控下限 (元)”表单项
- 移除前端配置模型字段 `balanceThreshold`
- 自动封禁模式提示文案改为仅基于回码率

为什么改：
- 前端配置项需与后端字段保持一致，避免提交无效参数。

改后作用：
- 管理台系统配置页不再出现旧余额封控配置，减少误操作。

## 10. 本轮新增：代理端前端同步配额化改造

### 10.1 新增后端接口（供代理端查询本人配额）

1. `src/main/java/com/wzz/smscode/controller/agent/AgentController.java`
- 新增接口：`GET /api/agent/my/quotas`
- 支持可选参数：`projectId`、`lineId`
- 返回字段：`projectId`、`projectName`、`lineId`、`lineName`、`availableCount`
- 口径：按代理本人可用项目线路返回，未充值线路默认配额为 `0`

2. `src/main/java/com/wzz/smscode/dto/agent/AgentMyQuotaDTO.java`
- 新增 DTO：代理本人项目线路配额展示对象

为什么改：
- 代理端需要“方便查询自己各项目各线路剩余配额”的独立页面，旧接口无法直接提供该数据。

改后作用：
- 代理登录态下可直接查询本人项目线路配额，无需走旧余额接口。

### 10.2 代理端前端改动（`/Volumes/SSD_1/DevelopmentProject/vue/sms-agent-meishi`）

1. `src/api/agent.js`
- 重构为配额模式最小 API 集：
  - 保留：登录、下级管理、配额充值/扣减、项目列表、记录/统计、号码清理
  - 新增：`getAgentMyQuotas`
  - 移除：旧金额账本、价格模板、利润相关 API
- 充值/扣减改为参数：`targetUserId + projectId + lineId + count`

2. `src/components/UserRecharge.vue`
- 从“金额充值/扣款”改为“项目线路配额充值/扣减”
- 新增项目下拉（显示项目ID+项目名）
- 新增线路下拉（显示线路ID+线路名，按项目联动）
- 数量字段改为配额数量（整数）

3. `src/components/UserEditDialog.vue`
- 移除模板与价格配置编辑逻辑
- 保留并简化为：用户名、密码、代理开关、状态（编辑时）、项目线路黑名单
- 黑名单改为项目线路多选（`projectId-lineId`）

4. `src/pages/SubUsers.vue`
- 移除模板、余额、账单入口等旧金额展示
- 保留下级管理核心能力与配额充值/扣减入口

5. `src/pages/Dashboard.vue`
- 首页卡片改为配额口径展示（移除金额符号与利润口径展示）
- 快捷入口移除模板/账单入口，新增“我的项目配额”

6. `src/pages/ReportPage.vue`
- 移除“总收入/总成本/总盈利”列
- 仅保留取号、取码、回码率统计

7. `src/pages/MyQuota.vue`（新增）
- 新增代理本人项目线路剩余配额查询页面
- 支持按项目、线路筛选
- 展示线路数与总剩余配额汇总

8. `src/router/index.js`
- 移除旧页面路由（模板、价格配置、账单）
- 新增路由：`/reseller/my-quotas`

9. 已删除的前端文件
- `src/pages/AgentPriceTemplate.vue`
- `src/pages/PriceConfig.vue`
- `src/pages/UserBill.vue`
- `src/components/RecordDialog.vue`
- `src/components/EditDialog.vue`
- `src/components/PriceEditor.vue`
- `src/components/UserTable.vue`
- `src/components/PaginationBar.vue`
- `src/api/agent.projectPrice.js`

### 10.3 本轮验证

已执行：

- 后端：`sh mvnw -q -DskipTests compile`
- 代理端前端：`npm run build`

结果：

- 编译通过（前端仅保留体积告警，无功能性报错）。

## 11. 本轮新增：用户配额明细与项目线路流水查询（管理端+代理端）

### 11.1 后端新增接口

1. 代理端
- `GET /api/agent/user/quotas`
  - 参数：`targetUserId`（必填），`projectId`（可选），`lineId`（可选）
  - 作用：查询指定下级用户的项目线路配额明细（支持筛选）
- `GET /api/agent/user/quota-ledgers`
  - 参数：`targetUserId`（必填），`projectId`、`lineId`、`ledgerType`、`startTime`、`endTime`、`page`、`size`
  - 作用：查询指定下级用户在项目线路维度的配额流水

2. 管理端
- `GET /api/admin/user/quotas`
  - 参数：`targetUserId`（必填），`projectId`（可选），`lineId`（可选）
  - 作用：查询任意用户的项目线路配额明细
- `GET /api/admin/user/quota-ledgers`
  - 参数：`targetUserId`（必填），`projectId`、`lineId`、`ledgerType`、`startTime`、`endTime`、`page`、`size`
  - 作用：查询任意用户在项目线路维度的配额流水

3. 新增 DTO
- `src/main/java/com/wzz/smscode/dto/quota/UserQuotaItemDTO.java`
- `src/main/java/com/wzz/smscode/dto/quota/UserQuotaLedgerItemDTO.java`

为什么改：
- 需要在管理端与代理端用户管理中，按项目线路直接查看配额明细，并可定位某个项目线路的流水。

改后作用：
- 用户“配额明细 + 流水定位 + 充扣操作”形成闭环，排查和运营效率提升。

### 11.2 代理端新增/改动

路径：`/Volumes/SSD_1/DevelopmentProject/vue/sms-agent-meishi`

1. `src/components/UserRecharge.vue`
- 充值/扣减弹窗的项目线路来源改为“代理本人配额接口”；
- 下拉仅展示代理本人可用配额线路（`availableCount > 0`）；
- 修复“下级充配额没有项目列表”的问题。

2. `src/components/UserQuotaDialog.vue`（新增）
- 新增下级用户配额详情弹窗：
  - 查看该用户所有项目线路配额；
  - 支持在明细里直接充配额/扣配额；
  - 支持按项目+线路查询该用户配额流水。

3. `src/pages/SubUsers.vue`
- 新增“配额详情”按钮并接入 `UserQuotaDialog`。

4. `src/api/agent.js`
- 新增：
  - `getAgentUserQuotas`
  - `getAgentUserQuotaLedgers`

### 11.3 管理端新增/改动

路径：`/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi`

1. `src/components/UserQuotaDialog.vue`（新增）
- 新增用户配额详情弹窗：
  - 查看用户项目线路配额；
  - 支持在明细里直接充配额/扣配额；
  - 支持按项目+线路查询配额流水。

2. `src/pages/UserManage.vue`
- 新增“配额详情”按钮并接入 `UserQuotaDialog`。

3. `src/api/admin.js`
- 新增：
  - `getUserQuotaList`
  - `getUserQuotaLedgers`

### 11.4 弃用/替代关系

1. 旧金额账本查询能力继续保持下线状态，不再恢复。
2. 用户配额流水统一由 `user_project_quota_ledger` 提供，不再使用任何金额流水接口。

### 11.5 本轮验证

已执行：

- 后端：`sh mvnw -q -DskipTests compile`
- 代理端前端：`npm run build`
- 管理端前端：`npm run build`

结果：

- 均编译通过（前端仅存在资源体积告警）。

## 12. 本轮新增：配额详情承载充配额 + 配额明细搜索分页

### 12.1 需求对齐

1. 充配额入口统一收敛到“配额详情”弹窗，不再在用户管理列表单独弹窗操作。  
2. 配额详情中的“项目线路配额”支持按项目、线路筛选。  
3. 配额详情中的“项目线路配额”支持分页。  
4. 配额详情顶部新增“新增配额”，可选择项目ID和线路ID执行充值。

### 12.2 管理端改动

路径：`/Volumes/SSD_1/DevelopmentProject/vue/sms-admin-meishi`

1. `src/pages/UserManage.vue`
- 移除用户管理列表中的“充配额/扣配额”按钮；
- 移除旧的充扣配额弹窗与对应脚本逻辑；
- 保留“配额详情”作为唯一配额操作入口。

2. `src/components/UserQuotaDialog.vue`
- 在“项目线路配额”区域新增筛选栏：项目、线路、查询、重置；
- 增加本地分页（页码+页大小）；
- 顶部新增“新增配额”按钮与弹窗；
- 新增配额弹窗支持选择 `projectId + lineId + count` 并调用 `rechargeUser`；
- 保留行内“充配额/扣配额”操作，确保同页可快速调整。

### 12.3 代理端改动

路径：`/Volumes/SSD_1/DevelopmentProject/vue/sms-agent-meishi`

1. `src/pages/SubUsers.vue`
- 移除用户列表中的“充配额/扣配额”按钮；
- 移除旧的 `UserRecharge` 弹窗接入；
- 保留“配额详情”作为唯一配额操作入口。

2. `src/components/UserQuotaDialog.vue`
- 在“项目线路配额”区域新增筛选栏：项目、线路、查询、重置；
- 增加本地分页（页码+页大小）；
- 顶部新增“新增配额”按钮与弹窗；
- 新增配额弹窗的项目线路选项来源改为代理本人可用配额（`availableCount > 0`）；
- 新增配额提交调用 `rechargeAgentUser`，保持“同项目同线路配额扣减”约束。

3. 删除废弃文件
- `src/components/UserRecharge.vue`（已无引用，旧入口下线）。

### 12.4 为什么这样改

1. 将入口集中到配额详情，避免列表页多入口造成状态不一致和维护成本上升。  
2. 配额明细通常数据量更大，增加筛选和分页后可直接定位项目线路，提升运营效率。  
3. 新增配额弹窗改为明确选择项目+线路，符合当前计费主键（`project_id + line_id`）和接口约束。

### 12.5 本轮验证

已执行：

- 管理端前端：`npm run build`
- 代理端前端：`npm run build`

结果：

- 构建通过（仅资源体积告警，无功能性编译错误）。

## 13. 本轮新增：用户端 Flutter（HuiKe）配额模式同步

### 13.1 后端接口补齐（用户侧）

文件：`src/main/java/com/wzz/smscode/controller/user/UserController.java`

新增接口：

1. `GET/POST /api/user/quotas`
- 参数：`userName`、`password`、`projectId(可选)`、`lineId(可选)`
- 作用：查询当前登录用户的项目线路配额明细（支持按项目/线路筛选）

2. `GET/POST /api/user/quota-ledgers`
- 参数：`userName`、`password`、`projectId(可选)`、`lineId(可选)`、`ledgerType(可选)`、`startTime(可选)`、`endTime(可选)`、`page`、`size`
- 作用：查询当前登录用户的项目线路配额流水（分页）

说明：
- 旧接口 `GET/POST /api/user/ledger/list` 仍保持下线提示；
- 用户端流水查询统一切到配额流水接口。

### 13.2 用户端 Flutter 改动

路径：`/Volumes/SSD_1/DevelopmentProject/flutterProject/HuiKe`

1. `lib/services/huike_api_client.dart`
- `getBalance()` 改为 `getQuota(projectId, lineId)`，调用 `/api/user/getBalance`（按项目+线路）
- 新增 `listAllQuotas()`，调用 `/api/user/quotas`
- 新增 `listQuotaLedgers()`，调用 `/api/user/quota-ledgers`
- 移除旧 `viewAgentUserLedger()` 和旧 `clearLedgerHistory()` 逻辑

2. `lib/features/dashboard/dashboard_controller.dart`
- 取号前校验从“余额校验”改为“项目线路配额校验”
- 新增 `fetchQuota()` / `fetchAllQuotas()`
- 状态文案统一改为“配额不足/配额查询失败”

3. `lib/features/dashboard/dashboard_page.dart`
- 顶部按钮“查询余额”改为“查询当前配额”
- 新增“查询全部配额”按钮
- “查询流水”改为“查询配额流水”

4. `lib/features/dashboard/dialogs/ledger_dialog.dart`
- 流水弹窗改为查询 `/api/user/quota-ledgers`
- 支持项目ID、线路ID、流水类型筛选
- 表格字段改为配额流水字段（变动数量、变动前后、操作人、备注、时间）

5. `lib/features/dashboard/dialogs/quota_overview_dialog.dart`（新增）
- 新增“全部项目线路配额”弹窗
- 支持项目ID、线路ID筛选
- 支持本地分页展示

6. `lib/models/ledger_record.dart`
- 模型字段从旧金额流水改为配额流水结构

### 13.3 本轮验证

已执行：

- 后端：`sh mvnw -q -DskipTests compile`
- 用户端 Flutter：`flutter analyze`

结果：

- 后端编译通过；
- Flutter 静态检查通过（No issues found）。
