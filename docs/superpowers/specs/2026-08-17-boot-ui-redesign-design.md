# 启动界面 UI 全面重构设计

> 日期:2026-08-17
> 状态:已批准(用户确认设计)

## 背景与目标

现状问题:

- 引导页(GuideWizard.kt)功能齐全(步骤卡、状态摘要、操作区、顶部细条、WebView 错误拦截),但视觉为默认 Material 白底 + 平台控件,缺乏设计感;仅浅色模式
- 顶部细条在失败态无法持久呈现、不可达(I-26 教训:错误页卡死时无任何出口)
- 日志只能复制到剪贴板,无查看/分享能力;无 PC/无 adb 设备上无法导出日志

目标:在保持既有功能与接口契约的前提下,全面重构启动界面(引导页 + 顶部细条)的视觉与交互:

- 简洁、优雅、现代(Linear/Vercel 式克制感:中性底 + 单一渐变强调)
- 支持深色模式(跟随系统)
- 失败态细条常驻 + 入口可达;应用内日志查看器(复制/分享);版本信息(应用/ABI/快照 dsh 版本);引擎状态摘要卡
- 克制微动效(仅 transform/alpha,零新依赖)

## 设计

### 1. 视觉语言

**色板**(浅/深两套,全部文字对比度 ≥ WCAG AA 4.5:1):

| 角色 | 浅色 | 深色 |
|---|---|---|
| 页面底 | `#FAFAFC` | `#0E1116` |
| 卡片 | `#FFFFFF` | `#161B22` |
| 内嵌面 | `#F2F4F8` | `#1C222C` |
| 描边(发丝线) | `#E7EAF0` | `#272E3A` |
| 主文 | `#171A21` | `#ECEEF3` |
| 次文 | `#6E7684`(≈4.6:1) | `#98A1B0`(≈7:1) |
| 品牌渐变 | `#4D6BFE → #8B5CF6` | `#5B7CFF → #9D6BFF` |
| 成功 | `#17A26A` | `#2FBF85` |
| 错误 | `#D64545` | `#E85D5D` |

**渐变策略**:仅三处使用渐变 — 品牌徽标、主按钮、页面顶部光晕(径向,约 14% 透明度)。状态一律纯色,防渐变泛滥。

**字体**:平台字体(Roboto/Noto),不引入字体资产。层级:标题 22sp Bold / 卡片标题 15sp Medium / 次级文本 13sp Regular / 日志与版本号 12sp Monospace。

**卡片**:双层嵌套 — 外壳 1dp 发丝描边 + 16dp 圆角;内芯内嵌面 + 12dp 圆角,内容落内芯。不引入沉重阴影(克制路线)。

**按钮**:主按钮 = 渐变药丸 + 尾随独立圆形图标位(圆内 chevron),按压缩放 0.98;次按钮 = 幽灵式(发丝描边,透明底)。

**动效**:统一 `PathInterpolator(0.16, 1, 0.3, 1)`;入场错落(淡入 + 上移 12dp,stagger 80ms,时长 ~400ms);仅 transform/alpha;`ViewPropertyAnimator` 自动遵循系统动画缩放(减动效)。无无限循环动效(仅状态点呼吸)。

### 2. 引导页结构(纵向)

- 品牌块:程序化渐变徽标 + 应用名 + 副标语,紧凑不占屏
- 步骤卡 ×3(纵向列表):引擎启动 → 应用运行 → 就绪;状态用图标 + 状态色点/勾;激活卡有呼吸点,完成卡有勾 + 缩放进入
- 引擎状态摘要卡:状态文字 + 引擎 URL + "查看日志"入口
- 操作区:主按钮(渐变药丸)+ 2×2 幽灵按钮(检查更新/重新加载/后台运行/复制日志)
- 版本行:底部,次级文字,等宽字体(应用版本 + ABI + 快照 dsh 版本)
- 整体包 ScrollView,防小屏溢出;用留白与卡片层级代替分隔线

### 3. 顶部细条(改为悬浮药丸)

- 屏幕顶部居中悬浮(margin 12dp),圆角 22dp,半透明底 + 发丝描边,呼吸点 + 状态文字 + 尾部 `›`
- 三态:`启动中`(靛蓝点呼吸)/ `失败`(红点,"连接失败 · 点按查看")/ `成功`(绿点)
- **失败态常驻不自动隐藏**(I-26 教训);成功态 6s 淡出(保留现有行为);启动中持续呼吸
- 滑入动画 translateY -32→0;点按进引导页(现有行为)

### 4. 日志查看器

- 引导页内全屏覆盖面板(叠加层,非新页面/Activity):顶部栏(标题"诊断日志" + 复制 / 分享 / 关闭),主体等宽 12sp 滚动日志(`AppLog.dump()` 全文),打开自动滚到底部
- 分享走 `ACTION_SEND`(text/plain,无需存储权限)— 解决无 PC 设备导出日志的痛点

### 5. 主流程/接口变更

- `GuideWizard` 公开 API:`showTopBar(state: BarState)`(统一三态入口)、`openLogPanel()`;保留 `showGuideStatus/showGuideError/showLaunchReady/showWeb/showGuide` 与全部现有回调(onPrimaryAction/onCheckUpdate/onBackToHarness/onKeepAlive/onCopyLog)
- `MainActivity`:新增 `onOpenLog` 接线(引导页"查看日志"按钮 + 摘要卡入口);`onEngineError`(WebView 主框架失败)→ 引导页 + 失败态细条常驻;`showWeb` 成功 → 成功态细条 6s 淡出
- 新增 `applyTheme()`:`onCreate` 与 `onConfigurationChanged(uiMode)` 时应用窗口/状态栏/导航栏色(深浅自动跟随)

### 6. 工程

- 纯代码构建延续(无 XML 布局);新增 `values-night/colors.xml`(同名资源自动跟随系统)
- 新抽 `StepState` 步骤状态机 + `GuidePalette`(颜色解析) — 可单测;日志面板数据装配可测
- 徽标/光晕/药丸底用 `GradientDrawable` 程序化绘制;strings 新增文案进 `values/` + `values-zh/`
- 零新依赖(无 appcompat);零新资产(无字体/图标文件)
- 版本行 dsh 版本:读取快照内 `package.json` 并缓存(不影响引导速度)

## 验证策略

- 本机:仅 `./tests/run-local.sh`(JS/C/wrapper);无 JDK/SDK,gradle 门禁在 CI
- CI Quality gate:`assembleDebug lintDebug ktlintCheck testDebugUnitTest` + run-local.sh,全绿
- 新增单元测试:`StepState` 状态迁移、`GuidePalette` 深浅解析、日志面板文本装配
- 真机(用户):华为禁令设备走完整首启流程;kill 后重开验证失败态常驻与出口;切深色模式验证

## 范围外(明确不做)

- launcher 图标/应用图标重设计(用户未选)
- 新依赖/新字体/新图标资产
- 引导页以外页面(WebView 内页)的视觉重构
- 设置页、更新检查 UI 等既有功能的行为变更