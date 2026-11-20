# Smart Wardrobe 设备端 E2E

## 版本说明

最终引导验收及边界验证见 [ONBOARDING-VALIDATION.md](ONBOARDING-VALIDATION.md)。补充执行入口为 `python e2e_tests/onboarding_edges.py`。

聚光灯引导专项：安装当前构建后运行 `python e2e_tests/onboarding_ux.py`。脚本仅接受模拟器，先备份再测试空数据库的六步流程、遮罩触摸拦截、跳过与完成的持久化、筛选面板重播、已有衣物、横屏、大字体和进程恢复；最后恢复系统显示设置、原数据库与照片。截图和检查结果保存在忽略的 `results/onboarding-*/`。首次引导会遮住旧脚本的操作；运行历史脚本前可先完成或跳过引导。

本目录保留多个版本阶段的回归脚本。2026-11-21 已精简全局 UI 文案并调整统一衣物页面，旧脚本中的按钮名、标题和部分布局断言可能需要按当前界面更新。下文是对应阶段的执行记录，不表示全部脚本已在最终文案版本重新通过。最新更新范围及已完成验证见仓库根目录的「更新说明.md」。`results/` 和日志仅保留本地，不提交个人数据或照片。

本套件通过 Maestro 和 ADB/UIAutomator 点击真实界面，并读取 SQLite 主库与 WAL 检查结果。目标是专用 Android 模拟器，默认 `emulator-5572`；`device.py` 拒绝以手机序列号运行。使用 `WARDROBE_SERIAL`、`ADB` 可选择其他模拟器及 ADB 路径。

## 本次范围

按用户最新要求，保留具体洗标分组及原有整组洗涤条件，不合并为三个大组。验证换季两类批量取出、日历默认勾选待洗及取消选择、护理检查、编辑放弃确认和原有数据约束。

## 环境与备份

先确认 `app/src/main/assets/garment.onnx`、`care-yolo11m.onnx` 均存在，编译成功且模拟器在线。Windows 当前环境使用：

```powershell
$env:ANDROID_HOME='E:\androidstudio'
.\gradlew.bat assembleDebug --offline --console=plain '-Pandroid.overridePathCheck=true'
& E:/androidstudio/platform-tools/adb.exe devices -l
& E:/androidstudio/platform-tools/adb.exe -s emulator-5572 install -r app/build/outputs/apk/debug/app-debug.apk
```

在运行任何测试前，将模拟器应用私有目录用 `adb exec-out run-as com.smartwardrobe.local tar -cf - .` 以**二进制**写入 `results/pre-task-data.tar`。先 force-stop 应用；不要用可能转码的 PowerShell 文本重定向保存 TAR、SQLite 或 PNG。`seed_regression.py` 会替换该模拟器的数据库，仅在保留此备份后运行。

Maestro 2.12.1 已安装于 `E:/CodexTools/maestro/maestro/bin/maestro.bat`，依据[官方安装说明](https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli)使用 Windows 发行包。执行前设置 Java 17 以上的 `JAVA_HOME`、`ANDROID_HOME`，并把 `platform-tools` 加到本次进程 PATH。

## 执行顺序

删除与多选专项：编译并安装后运行 `python -u e2e_tests/delete_ux.py`。脚本备份并恢复模拟器数据，覆盖单件两种动态确认、取消、有关联衣物彻底删除、照片及子表清理、空／非空穿搭和日历记录、数据库及内存草稿恢复、长按多选、当前筛选全选、批量删除和归档。证据在 `results/delete-ux-*/`。

本次洗涤交互专项：编译并安装后运行 `python e2e_tests/wash_ux.py`。脚本单独备份及恢复模拟器数据，验证列表、详情、整组直接今日已洗及撤销、保留收起状态、补记仅限过去日期，以及洁净／待洗状态不受补记影响。证据存入 `results/wash-ux-*/`。

1. `maestro --device emulator-5572 test e2e_tests/human_flow.yaml`，然后 `python e2e_tests/verify_baseline.py`。使用无照片手动建档，不依赖相机或网络；覆盖系统返回、停止并重启进程恢复草稿、保存、日历登记及使用统计。固定测试名称需要在无同名数据的测试库运行。
2. `python e2e_tests/seed_regression.py` 初始化可重复的换季及边界数据。洗涤和闲置护理提醒分别由预设历史和建档日期触发，后续操作均走 UI。
3. 先启动 `python e2e_tests/observe_care.py`，同时运行 `maestro --device emulator-5572 test e2e_tests/care_flow.yaml`。观察器逐步断言 `(待洗数, 收起数, 洗涤记录数)` 为 `(8,4,1) → (8,3,1) → (10,0,1) → (5,0,6)`。
4. 依次运行 `python e2e_tests/wash_remaining.py`、`python e2e_tests/verify_care.py`、`python e2e_tests/canvas_bounds.py`、`python e2e_tests/chaos_flow.py`、`python e2e_tests/care_overlap.py`、`python e2e_tests/verify_final_state.py`。
5. 测试结束后运行 `python e2e_tests/restore_data.py`，逐表、逐照片核对原模拟器数据，并保留最终安装包。

不要同时运行两个会点击界面的脚本。SQLite 观察器只读，可与 Maestro 并行。所有截图、XML、数据库副本、日志、JUnit 报告和备份保存在被 Git 忽略的 `results/`。

`chaos_flow.py --resume-archive` 仅用于前面断言已经完成、需从归档与删除段继续的同一轮测试；完整重测应重新初始化测试库并按上述顺序运行。

## 验收依据

- Maestro 必须退出为 0，不能以仅编译成功代替流程通过。
- 每件衣物分别检查清洁状态、存放状态及洗涤/穿着记录；批量取出不增加洗涤次数，记录洗涤不自动取出已收起衣物。
- 护理检查写入当前洗涤总数作为计数基线，并更新检查日期；待洗或收起状态不遮蔽护理入口。
- 检查 SQLite 完整性、外键、画布 20 层上限、图层去重、同日同套记录唯一，以及日志中的应用崩溃和 SQLite 错误。
- 本套件不验证实体手机相机、洗标模型准确率和外网商品页。
