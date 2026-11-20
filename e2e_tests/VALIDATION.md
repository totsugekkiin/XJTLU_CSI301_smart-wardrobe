# Smart Wardrobe 洗护与换季验证记录

验证日期：2026-11-19。设备：`emulator-5572`（WardrobeFlow），Android 13 / API 33，720 × 1440。应用：0.6.15（versionCode 22），SQLite schema v4；Maestro 2.12.1。

APK SHA-256：`5AA25459BBA3D1C72DC16DBEC0AEB06A19FC30B182F1AC5944ACF4D70557F3A5`。根目录 `README.md` 的删除状态在任务开始前已存在，本次未操作该文件。

## 最终变更范围

- 按用户后续要求，撤回三大洗护分组方案，保留具体洗标分组、原排序及原整组洗涤条件。
- 当季已收起衣物分为洁净且无护理提醒、待洗或存在护理提醒两组，分别一键取出、一键取出并标待洗；数据库事务批量更新。
- 记录洗涤清除待洗状态时保持存放状态；待洗与收起叠加时显示组合状态。
- 护理提醒及完成检查入口不再被待洗、收起状态遮蔽。
- 修复手动建档仍强制照片的问题，与名称、类别必填的规则一致。
- 复用并验证现有日历默认勾选「同时标为待洗」及取消选择逻辑。

## 已完成的验收

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 修改前环境预检 | 两份 ONNX 权重存在，assembleDebug 通过，两台模拟器在线 | garment.onnx 10,994,299 字节；care-yolo11m.onnx 80,417,826 字节 |
| 无照片手动建档 → 详情 → 画布返回 → 停止进程并恢复草稿 → 保存 → 日历 → 使用统计 | Maestro 退出码 0，3 分 39 秒；SQLite 断言通过 | results/phase1-retry.xml、baseline-detail.png、baseline-after 数据库副本 |
| 保留原洗标组，换季两类批量取出，再登记最高 30°C 组洗涤 | Maestro 退出码 0，44 秒 | results/phase2-original-groups.xml |
| 批量状态转换及计数 | 四个中间状态与每件衣物记录均符合预期 | results/care-timeline.json |
| 原最高 60°C、柔洗、手洗、未标洗标及不可水洗的单件登记 | 五条路径通过；均增加 1 条洗涤记录并清除待洗 | single-wash-* 数据库副本、care-after.png |
| 20 层上限和同单品去重 | 重复候选被排除，第 21 件被拒绝，持久化草稿和保存后均为 20 个不同图层 | canvas-limit-draft-*、canvas-limit-saved-* 数据库副本 |
| 编辑未保存直接系统返回 | 取消退出保留表单；确认放弃不写入数据库 | chaos-discard-* 数据库副本 |
| 待洗与收起叠加后批量取出、批量洗涤 | 取出不增加洗涤记录，后续洗涤增加 1 次并清除待洗 | chaos-stacked-*、chaos-taken-out-*、chaos-washed-* 数据库副本 |
| 已收起衣物直接批洗 | 洗涤增加 1 次，仍保持收起；之后洁净批量取出不再增加次数 | chaos-stored-before-wash-*、chaos-stored-after-wash-*、chaos-clean-taken-out-* 数据库副本 |
| 洗涤次数与闲置天数两类护理提醒 | 详情完成检查后恢复可穿；检查记录的 wash_checkpoint 等于当前洗涤总数 | chaos-check-E2E-CareStored-*、chaos-check-E2E-IdleStored-* 数据库副本及截图 |
| 日历取消待洗及同日同套去重 | 取消勾选后 20 件均保持洁净；再次默认勾选后均待洗，仍只有 1 条历史及 20 个衣物关联 | chaos-calendar-opt-out-*、chaos-calendar-dedup-* 数据库副本 |
| 穿过衣物归档及未穿衣物删除 | 穿过衣物无永久删除入口，归档后穿着与穿搭关联保留；未穿衣物删除后清理对应图层 | chaos-archive-*、chaos-delete-cascade-*、final-regression-state-* 数据库副本 |
| 收起状态下的护理检查 | 收起状态主操作为取出时，详情记录区仍可完成护理；重置基线后保持收起，随后取出恢复可穿，界面洗涤次数正确 | care-overlap-* 数据库副本、care-overlap.png |
| 最终 APK 完整基线复跑 | Maestro 退出码 0，3 分 21 秒；详情状态、统计、日历、草稿清理及 SQLite 检查再次通过 | results/final-human.xml、results/final-human/、baseline-detail.png |
| 原模拟器数据恢复 | 所有原数据表逐行一致，124 个照片文件的 SHA-256 全部一致；最终安装包可正常打开原衣橱，日志检查通过 | restore_data.py、original-expected.sqlite、restored-* 数据库副本、restored-original-closet.png |

换季与批洗观察到的 `(待洗数, 收起数, 洗涤记录数)` 为：

`(8,4,1) → (8,3,1) → (10,0,1) → (5,0,6)`

首次取出只减少收起数；第二次取出把两件已触发护理提醒的洁净衣物加入待洗；原 30°C 组洗涤为五件衣物各增加一次记录。随后五条单件路径使所有测试待洗衣物洁净。

## 执行说明

混沌边界已逐项执行通过，长列表归档定位修正后从 `--resume-archive` 续跑，退出码为 0；`care_overlap.py`、`verify_final_state.py` 均退出为 0。最终 APK 的完整基线复跑与 SQLite 核对亦通过。`restore_data.py` 已退出为 0，原数据表和全部照片已校验恢复。

## 验证边界

每组检查包含 SQLite `integrity_check`、`foreign_key_check`、图层上限/去重及同日同套穿搭去重检查，并保存应用崩溃与 SQLite 错误日志扫描结果。本轮使用手动建档和预设历史触发提醒，不覆盖实体手机相机、洗标识别准确率或外网商品页。
