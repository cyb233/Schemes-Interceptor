# Schemes Interceptor

[English](README.en.md) | 简体中文

一个 Android 空白应用，用于按配置拦截多种 URL Scheme。应用通过透明的 `BlankActivity` 接收 Scheme Intent，并立即关闭自身；用户可以在设置页按 Scheme 管理拦截开关。

## 功能

- 从 `schemes.json` 自动生成 URL Scheme 对应的 `activity-alias`。
- 同一 Scheme 自动去重，并合并多个描述，例如 `QQ / TIM`。
- 透明拦截 Activity 收到 Intent 后立即 `finish()`。
- 设置页使用 RecyclerView 展示所有 Scheme。
- 显示能够处理对应 Scheme 的其他已安装应用，并排除本应用。
- 按 Scheme 字母顺序排序。
- 按 Scheme 或描述搜索。
- 通过 `SwitchCompat` 启用或禁用单个 Scheme alias。
- 支持“只显示已安装应用”筛选。
- 支持基础 I18N：界面默认使用英文，中文系统使用中文；`desc` 支持按设备语言选择描述。
- GitHub Actions 自动构建 APK 并上传构建产物。

## 配置 Scheme

配置文件位于：

`app/src/main/assets/schemes.json`

文件格式为 JSON 数组，每项至少包含 `scheme`：

```json
[
  {
    "scheme": "mqq",
    "desc": {
      "zh-CN": "QQ",
      "en": "QQ"
    }
  },
  {
    "scheme": "mqq",
    "desc": {
      "zh-CN": "TIM",
      "en": "TIM"
    }
  },
  {
    "scheme": "weixin",
    "desc": "微信"
  }
]
```

### `desc` 的本地化规则

`desc` 支持字符串或语言映射对象：

```json
"desc": "固定描述"
```

字符串会直接使用。也可以使用语言映射：

```json
"desc": {
  "zh-CN": "微信",
  "zh": "微信",
  "en": "WeChat"
}
```

对象按以下顺序查找：

1. 当前完整语言标签，例如 `zh-CN`；
2. 下划线形式，例如 `zh_CN`；
3. 当前语言代码，例如 `zh`；
4. `en` 作为回退。

建议每个本地化对象都提供 `en`。如果最终没有可用描述，该项仍会保留，但副标题为空。

Scheme 必须符合 URL Scheme 格式：以英文字母开头，后续可使用字母、数字、`+`、`-` 和 `.`。

## 构建

### 环境要求

- Android Studio 或 Gradle 环境
- JDK 17
- Android SDK 37
- Android Gradle Plugin 9.3.0

项目当前未提交 Gradle Wrapper；如果本地已安装 Gradle，可执行：

```bash
gradle build
```

构建后的 APK 位于：

```text
app/build/outputs/apk/
```

安装 debug APK：

```bash
gradle installDebug
```

## Manifest 自动生成

[gradle/scheme-manifest.gradle](gradle/scheme-manifest.gradle) 会在处理主 Manifest 前执行，并将：

- `<!-- SCHEME_QUERIES -->` 替换为对应的 `<queries>` Intent；
- `<!-- SCHEME_ALIASES -->` 替换为对应的 `<activity-alias>`。

源模板为：

`app/src/main/AndroidManifest.xml`

每个 alias 都指向 `.BlankActivity`。alias 名称由 Scheme 和哈希值生成，`SchemeManager` 使用相同算法定位组件，因此修改 Scheme 后也能正确控制对应开关。

## 项目结构

```text
.
├── app/
│   └── src/main/
│       ├── assets/schemes.json
│       ├── java/moe/shuvi/schemesinterceptor/
│       │   ├── BlankActivity.java
│       │   ├── SchemeAdapter.java
│       │   ├── SchemeManager.java
│       │   └── SettingsActivity.java
│       └── res/
├── gradle/
│   └── scheme-manifest.gradle
└── .github/workflows/build.yml
```

## GitHub Actions

工作流文件为 `.github/workflows/build.yml`，在 push、pull request 或手动触发时：

1. checkout 源码；
2. 配置 JDK 17；
3. 配置 Gradle 9.5.0；
4. 执行 `gradle build`；
5. 上传 `app/build/outputs/apk/**/*.apk` 作为 artifact。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE)（GPL-3.0）授权。

你可以依据 GPLv3 的条款使用、研究、复制、修改和分发本项目。分发本项目或其衍生作品时，必须遵守 GPLv3 的适用要求，包括保留版权和许可证声明，并向接收者提供相应的源代码或获取源代码的方式。

本项目按“现状”提供，不提供任何明示或默示担保。完整的许可证条款请参阅 [LICENSE](LICENSE) 文件。
