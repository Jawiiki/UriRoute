# UriRoute — JS 引擎驱动的 Content 路由动态管理器

通过 `content://uriroute` 动态执行 JavaScript 脚本并返回 JSON 结果，为其他 App 提供灵活的数据源。

## 快速开始

从 [Releases](https://github.com/Jawiiki/UriRoute/releases) 下载最新 APK 安装即可。

1. 打开 App → **JS编辑器** → 新建脚本
2. 编写 JS，`function run() {}` 自动生成
3. 在其他 App 中用 `content://` 调用

## UriRoute-For-Xiaomi-17-Rear-Screen

[UriRoute-For-Xiaomi-17-Rear-Screen仓库地址](https://github.com/Jawiiki/UriRoute-For-Xiaomi-17-Rear-Screen)

本项目能为小米 17 背屏模块提供数据支持，现有三个模块：
- **设备资讯** — 每秒刷新，展示 CPU 占用率、电压、内存、电流、存储、功率 (Shizuku权限下无法显示电压、电流、功率)
- **鸣潮资讯** — 每 600 秒刷新，展示游戏角色名、活跃度、结晶波片、电台等级等账号状态
- **通用数据模板** — 动态键值对渲染，修改 JS 即可适配任意数据源，无需改动背屏布局

可通过`通用数据模板`编写自己的模块


## 核心理念

UriRoute 在 Android 本地运行 JS 脚本（基于 Rhino 引擎），通过 **ContentProvider** 将执行结果以 URI 方式暴露给其他 App，相当于一个搭载了 JS 运行时的小型数据中间件。

```
第三方 App
     │  content://uriroute/data?group=xxx&name=xxx
     ▼
UriRoute ──→ 加载 JS ──→ 执行 run() ──→ 返回 JSON
```

## 使用方式

在 App 内创建脚本，`run()` 为入口函数：

```javascript
function run() {
    var data = uriRoute.httpGet("https://api.example.com/data", {"Accept": "application/json"});
    var json = JSON.parse(data);
    uriRoute.add("title", json.title);
}
```

外部通过 URI 调用：

```java
Uri uri = Uri.parse("content://uriroute/data?group=mygroup&name=myscript&city=beijing");
Cursor cursor = getContentResolver().query(uri, null, null, null, null);
```

| 路径 | 行为 |
|------|------|
| `/data` | 有缓存读缓存，否则执行脚本并返回结果 |
| `/update` | 清除该脚本的缓存 |

自定义参数在 JS 中通过 `uriRoute.getValue("city")` 获取。

## 内置 API

| API | 说明 |
|-----|------|
| `uriRoute.getValue(key)` | 读取 URI 参数或环境变量 |
| `uriRoute.add(key, value)` | 设置返回值 |
| `uriRoute.saveEnv(key, value)` | 保存到环境变量（写入 `env.conf`，同一脚本中可立即读取） |
| `uriRoute.httpGet(url, headers)` | HTTP GET 请求 |
| `uriRoute.httpPost(url, headers, body)` | HTTP POST 请求 |
| `uriRoute.shell(command)` | 执行 Shell 命令（需 Root/Shizuku） |


## 关于

本项目由Jawiiki开发。项目全程通过ClaudeCode生成，本人仅提供了项目的设计方案。

[UriRoute-For-Xiaomi-17-Rear-Screen](https://github.com/Jawiiki/UriRoute-For-Xiaomi-17-Rear-Screen)中的三个模块用作示范UriRoute的功能，显示的功能比较简单。希望有开发者能制作出更优秀的背屏模块。


## 致谢

- [Mozilla Rhino](https://github.com/mozilla/rhino) — JavaScript 引擎
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — Android UI 工具包
