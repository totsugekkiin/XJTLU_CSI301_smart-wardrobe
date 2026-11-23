# 体感搭配验收

日期：2026-11-23。入口位于「穿搭」页右上角「体感搭配」。

## 实现范围

- 使用现有衣橱与穿搭数据库，不修改表结构。只读取未归档且未收起的衣物。两件模式为上装（含外套）和下装；三件模式分为外套、内搭上装、下装，外套不会重复进入内搭栏。连衣裙、鞋与配饰不参加。
- 纯白画布、黑色操作重点、1 px 图标与按钮边线。画布移除标题、类别计数、单品名称与教学文案。中央衣物始终完全不透明；邻项缩放 0.80、透明度 0.22。衣物透明留边只在显示时裁切，不修改原文件。触控切换采用 220 ms EaseOutCubic。
- 两件模式统一 230 × 230 dp 正方形画框，三件模式统一 165 × 165 dp，栏间距 12 dp，图片在框内留 12 dp 边距完整居中缩放。小屏空间不足时，所有栏位使用同一个缩小后的正方形边长；衣物长宽比和切换单品均不影响容器大小。图钉为 20 dp，图标视口距正方形上边缘、右边缘均为 12 dp，点击区域仍为 48 dp。
- 焦点仅绘制四个纯黑 L 形角标，臂长 12 dp、线宽 1.5 dp。跨栏切换只平移 Y 坐标，持续 220 ms EaseOutCubic，宽高不变化且无缩放脉冲。两件与三件模式通过 220 ms 布局过渡切换。
- 穿搭主页数量为动态的「N 套穿搭」，13 sp 并启用 `tnum`，与衣橱数量样式一致。右侧体感入口为 14 sp Medium 无图标、无边框、无背景文字；与「+ 新建」间距 16 dp，新建为 34 dp 高、4 dp 圆角、黑底白字，水平内边距 14 dp。穿搭组合预览在视图背景与实际绘制中均显式设为纯白，预览及卡片边线为 1 px `#EFEFEF`，缺图占位不使用灰块。
- 顶栏为返回、20 dp 筛选滑块图标、20 dp 校准准星和高度 34 dp 的保存按钮。校准图标旋转 90°。筛选弹窗按现有季节位标记与颜色名称共同过滤三栏，支持重置；应用筛选后保留仍符合条件的当前单品及锁定状态。
- 底部居中 52 dp 白底胶囊，边线为 1 px `#DCDCDC`，包含层数、体感开关和 38 dp 黑底白线骰子。骰子点击与摇动共用 240 ms 旋转动效和随机流程。体感关闭时图标为 `#AAAAAA` 描边，并立即注销传感器，触控仍可操作；恢复时重新校准和监听。
- 重力传感器识别相对 Pitch / Roll；缺少重力传感器时从加速度低通估算。线性加速度传感器识别摇动，缺失时使用原始加速度减重力估计。每次进入或返回前台重新校准，按钮可手动校准。
- Roll 死区 12°、触发阈值 15°，保持 400 ms 后每 280 ms 切换，超过 30° 后每 160 ms 切换。动画支持中途接续，避免 220 ms 动画吞掉 160 ms 连续输入。Pitch 阈值 18°、冷却 500 ms；摇动阈值严格大于 13 m/s²、冷却 800 ms。
- 图钉锁定约束随机搭配；手动和姿态切换仍可调整单品。筛选框和命名框打开时暂停姿态操作，确认保存当前可见的两件或三件衣物，返回列表即可查看和编辑。三件模式上下倾斜依次移动到前一栏或后一栏，沿用 500 ms 冷却。
- 页面暂停和退出均注销全部传感器，停止随机定时器与轮盘动画。选择、锁定、激活栏保存在 Activity 状态中。空栏可打开预选类别的真实录入页，返回后重新载入衣橱。

## 已执行

1. `assembleDebug` 通过。
2. `OutfitMotionTest` 通过：相对零点、方向、死区、400 / 280 / 160 ms 时序、Pitch 冷却、摇动阈值与冷却、重新校准、随机和命名后保留零点、角度跨 ±180°。
3. 回归入口为 `python e2e_tests/motion_outfit.py`，覆盖入口、分类及归档/收起过滤、主画布只保留保存与返回文字、上下装像素色值均不被调暗、滑动、焦点、季节加颜色组合筛选与重置、三件模式无重复单品、体感开关的 sensorservice 注销、锁定随机、命名保存两层及三层、后台清理、空栏录入往返、SQLite 完整性与外键。使用透明测试衣物轮廓，不代表真实照片。新版证据目录为 `results/motion-outfit-1795425047188327100/`。
4. 已查看两件和三件模式的测试画面，确认无教学文字、无单品名、两栏全亮、邻项露边、单一活动框和底部胶囊。`--visual-only` 可运行边缘轮廓像素检查。
5. 每轮测试先备份应用私有目录，结束后恢复数据库、照片和偏好设置，并核对原表记录数及数据库完整性。证据在忽略的 `e2e_tests/results/motion-outfit-*/`。
6. 新版空栏专项 `python e2e_tests/motion_outfit.py --empty-only` 退出码 0：上装、下装和外套均能打开真实衣物录入页并返回。证据为 `results/motion-outfit-1795425231448950200/`。修正了轮盘结束动画方法与 Activity 退出方法重名导致空栏入口未跳转的问题。
7. 等大画框和主页专项为 `python e2e_tests/motion_outfit.py --frames-only`：测量两件与三件模式栏高及栏间距，切换照片后确认尺寸不变，核对图钉横坐标、四角黑线与无整框像素，保存两套穿搭后核对动态数量、文字入口与新建按钮居中对齐、34 dp 按钮高度及 16 dp 间距、预览纯白底与 1 px 浅色边线，并点击验证新建入口。
8. 顶栏精简专项 `python e2e_tests/motion_outfit.py --header-only` 已通过：无图标无框文字入口、16 dp 间距、34 dp 新建按钮、预览空白区域纯白像素、两个入口均能正常进入目标页。证据为 `results/motion-outfit-1795427394128217400/`。

## 仍需实机验证

已覆盖安装到实体手机并核对原数据不变。真实握持下的倾斜方向与舒适度、摇动阈值以及震动效果尚未实测；模拟器触控验收和确定性时序检查不能代替这些实机检查。

全项目 `lintDebug` 已通过。后续天气刷新更新显式处理了定位权限拒绝异常，消除了此前两处 `MissingPermission` 错误，没有屏蔽检查。

## 复现命令

```powershell
$env:ANDROID_HOME='E:\androidstudio'
.\gradlew.bat assembleDebug --offline --console=plain '-Pandroid.overridePathCheck=true'
javac -d app/build/motion-policy-tests app/src/main/java/com/smartwardrobe/local/OutfitMotion.java e2e_tests/OutfitMotionTest.java
java -cp app/build/motion-policy-tests com.smartwardrobe.local.OutfitMotionTest
python e2e_tests/motion_outfit.py
python e2e_tests/motion_outfit.py --visual-only
python e2e_tests/motion_outfit.py --empty-only
python e2e_tests/motion_outfit.py --frames-only
python e2e_tests/motion_outfit.py --header-only
```
