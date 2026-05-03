package com.uriroute.engine

import com.uriroute.model.ExecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.FunctionObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * JavaScript execution engine powered by Rhino.
 *
 * The `uriRoute` object (getValue/add/httpGet/httpPost) is implemented as
 * pure JavaScript + FunctionObject-injected native functions to avoid the
 * Rhino auto-wrap crash. All runtime errors are caught and logged to the
 * JS console instead of crashing.
 *
 * Execution order (fixed):
 * 1. Inject console polyfill
 * 2. Inject native HTTP functions (via FunctionObject)
 * 3. Load imported JS packages
 * 4. Create `uriRoute` object (JS with native HTTP backing)
 * 5. Load environment variables as globals
 * 6. Load user script
 * 7. Execute run()
 * 8. Collect logs and results
 */
class JsEngine {

    companion object {
        init {
            try {
                val factory = object : ContextFactory() {
                    override fun makeContext(): Context {
                        return super.makeContext().apply {
                            optimizationLevel = -1
                            languageVersion = Context.VERSION_ES6
                        }
                    }
                }
                ContextFactory.initGlobal(factory)
            } catch (e: Exception) {
                // Already initialized — proceed
            }
        }

        /**
         * Native HTTP GET — called from JS via FunctionObject (not auto-wrap).
         * @param headersJson JSON string like {"Authorization":"Bearer x","Accept":"*!/!*"}
         */
        @JvmStatic
        fun nativeHttpGet(url: String, headersJson: String): String {
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true

                val headers = JSONObject(headersJson)
                headers.keys().forEach { key ->
                    conn.setRequestProperty(key, headers.optString(key, ""))
                }

                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            } catch (e: Exception) {
                """{"error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}","url":"${url.replace("\"", "\\\"")}"}"""
            }
        }

        /**
         * Native HTTP POST — called from JS via FunctionObject.
         * @param headersJson JSON string like {"Content-Type":"application/json",...}
         * @param body POST body string
         */
        @JvmStatic
        fun nativeHttpPost(url: String, headersJson: String, body: String): String {
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                conn.doOutput = true

                val headers = JSONObject(headersJson)
                headers.keys().forEach { key ->
                    conn.setRequestProperty(key, headers.optString(key, ""))
                }

                if (body.isNotBlank()) {
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                        writer.write(body)
                        writer.flush()
                    }
                }

                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            } catch (e: Exception) {
                """{"error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}","url":"${url.replace("\"", "\\\"")}"}"""
            }
        }

        /**
         * Native shell execution via the configured permission source (Root or Shizuku).
         * Returns command output, or error message if the permission source is unavailable.
         */
        @JvmStatic
        fun nativeShell(command: String): String {
            return ShellManager.execute(command)
        }

        private var envBasePath: String? = null

        /**
         * Initialize the base directory for env.conf persistence.
         * Must be called once before any script uses saveEnv().
         * @param basePath absolute path to the "js" directory (e.g. context.filesDir + "/js")
         */
        @JvmStatic
        fun initEnvBase(basePath: String) {
            envBasePath = basePath
        }

        /**
         * Native saveEnv — called from JS via FunctionObject.
         * Writes key-value to env.conf in the script's directory.
         * If env.conf already contains the key, its value is updated.
         */
        @JvmStatic
        fun nativeSaveEnv(group: String, name: String, key: String, value: String): String {
            val base = envBasePath ?: return "ENV_DIR_NOT_INITIALIZED"
            return try {
                val dir = File(File(base, group), name)
                dir.mkdirs()
                val envFile = File(dir, "env.conf")
                val existing = if (envFile.exists()) JSONObject(envFile.readText()) else JSONObject()
                existing.put(key, value)
                envFile.writeText(existing.toString())
                "OK"
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }
    }

    data class ExecuteRequest(
        val scriptContent: String,
        val imports: List<String> = emptyList(),
        val envVars: Map<String, String> = emptyMap(),
        val customParams: Map<String, String> = emptyMap(),
        val group: String = "",
        val name: String = ""
    )

    fun execute(request: ExecuteRequest): ExecResult {
        val logs = mutableListOf<String>()

        val rhinoContext = Context.enter().apply {
            optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
        }

        try {
            val scope: Scriptable = rhinoContext.initStandardObjects()

            // Block all Java class access from JS (java.net.URL, java.io.*, etc.)
            // Rhino's Java interop crashes on Android ART — ClassShutter converts
            // the crash into a catchable RhinoException so we can log it gracefully.
            rhinoContext.setClassShutter(ClassShutter { false })

            // 1. Inject console polyfill
            injectConsolePolyfill(rhinoContext, scope)

            // 2. Inject native HTTP functions into scope (hidden, prefixed with __)
            injectNativeHttpFunctions(rhinoContext, scope)

            // 3. Load imported JS packages
            for ((index, importScript) in request.imports.withIndex()) {
                if (importScript.isBlank()) continue
                try {
                    rhinoContext.evaluateString(scope, importScript, "import_$index", 1, null)
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError) throw e
                    evaluateConsoleError(rhinoContext, scope, formatJsError("导入包$index", e))
                }
            }

            // 4. Create the uriRoute object (pure JS, no Java interop)
            val paramsJson = JSONObject(request.customParams).toString()
            val envJson = JSONObject(request.envVars).toString()
            val uriRouteScript = buildUriRouteScript(paramsJson, envJson, request.group, request.name)
            rhinoContext.evaluateString(scope, uriRouteScript, "uriRouteInit", 1, null)

            // 5. Set env vars as global JS variables
            val jsBuiltIns = setOf(
                "Object", "Function", "Array", "String", "Number", "Boolean",
                "Symbol", "Error", "Date", "RegExp", "Map", "Set", "Promise",
                "JSON", "Math", "console", "uriRoute", "run", "undefined", "null",
                "true", "false", "NaN", "Infinity", "isNaN", "parseInt", "parseFloat",
                "eval", "decodeURI", "encodeURI", "setTimeout", "setInterval"
            )
            for ((key, value) in request.envVars) {
                if (key.isBlank()) continue
                if (key in jsBuiltIns) {
                    evaluateConsoleError(rhinoContext, scope, "环境变量: 无法覆盖内置变量 '$key'，已跳过")
                    continue
                }
                ScriptableObject.putProperty(scope, key, value)
            }

            // 6. Load user script
            if (request.scriptContent.isBlank()) {
                return ExecResult(emptyMap(), logs, "脚本内容为空")
            }
            try {
                rhinoContext.evaluateString(scope, request.scriptContent, "userScript", 1, null)
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) throw e
                return ExecResult(emptyMap(), logs, formatJsError("用户脚本", e))
            }

            // 7. Execute run() function
            val runFn = scope.get("run", scope)
            if (runFn is Function) {
                try {
                    runFn.call(rhinoContext, scope, scope, emptyArray())
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError) throw e
                    evaluateConsoleError(rhinoContext, scope, formatJsError("run()", e))
                    collectConsoleLogs(scope, logs)
                    val partialResult = collectUriRouteResult(rhinoContext, scope)
                    return ExecResult(partialResult, logs, formatJsError("run()", e))
                }
            } else if (runFn is Undefined) {
                return ExecResult(emptyMap(), logs, "脚本错误: 未定义 run() 入口函数")
            } else {
                return ExecResult(emptyMap(), logs, "脚本错误: run 不是函数，请确保定义了 function run()")
            }

            // 8. Collect console logs
            collectConsoleLogs(scope, logs)

            // 9. Collect results from the JS uriRoute object
            val resultData = collectUriRouteResult(rhinoContext, scope)

            return ExecResult(resultData, logs)
        } catch (e: java.lang.StackOverflowError) {
            return ExecResult(emptyMap(), logs, "脚本执行超时或出现无限循环")
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            return ExecResult(emptyMap(), logs, "引擎内部错误: ${e.message}")
        } finally {
            Context.exit()
        }
    }

    suspend fun executeAsync(request: ExecuteRequest): ExecResult = withContext(Dispatchers.IO) {
        execute(request)
    }

    // ── Inject native HTTP functions via FunctionObject ────

    private fun injectNativeHttpFunctions(cx: Context, scope: Scriptable) {
        try {
            val getMethod = JsEngine::class.java.getDeclaredMethod(
                "nativeHttpGet", String::class.java, String::class.java
            )
            val postMethod = JsEngine::class.java.getDeclaredMethod(
                "nativeHttpPost", String::class.java, String::class.java, String::class.java
            )
            val shellMethod = JsEngine::class.java.getDeclaredMethod(
                "nativeShell", String::class.java
            )
            val saveEnvMethod = JsEngine::class.java.getDeclaredMethod(
                "nativeSaveEnv", String::class.java, String::class.java, String::class.java, String::class.java
            )

            val getFunc = FunctionObject("__nativeHttpGet", getMethod, scope)
            val postFunc = FunctionObject("__nativeHttpPost", postMethod, scope)
            val shellFunc = FunctionObject("__nativeShell", shellMethod, scope)
            val saveEnvFunc = FunctionObject("__nativeSaveEnv", saveEnvMethod, scope)

            ScriptableObject.putProperty(scope, "__nativeHttpGet", getFunc)
            ScriptableObject.putProperty(scope, "__nativeHttpPost", postFunc)
            ScriptableObject.putProperty(scope, "__nativeShell", shellFunc)
            ScriptableObject.putProperty(scope, "__nativeSaveEnv", saveEnvFunc)
        } catch (e: Exception) {
            // Inject fallback JS stubs so uriRoute.httpGet/httpPost/shell don't crash
            val fallback = """
                (function() {
                    if (typeof __nativeHttpGet === 'undefined') {
                        __nativeHttpGet = function(url, headers) { throw new Error('HTTP引擎未初始化'); };
                        __nativeHttpPost = function(url, headers, body) { throw new Error('HTTP引擎未初始化'); };
                        __nativeShell = function(cmd) { return 'Shell权限不可用'; };
                        __nativeSaveEnv = function(group, name, key, value) { return 'ENV_NOT_INITIALIZED'; };
                    }
                })();
            """.trimIndent()
            cx.evaluateString(scope, fallback, "httpStub", 1, null)
        }
    }

    // ── Build the uriRoute JS script ────────────────────

    private fun buildUriRouteScript(paramsJson: String, envJson: String, group: String = "", name: String = ""): String = """
        (function() {
            if (typeof uriRoute !== 'undefined') return;
            var _result = {};
            var _params = $paramsJson;
            var _env = $envJson;
            var _group = ${if (group.isNotBlank()) "\"$group\"" else "null"};
            var _name = ${if (name.isNotBlank()) "\"$name\"" else "null"};
            uriRoute = {
                getValue: function(key) {
                    if (key == null) return "";
                    var k = String(key).trim();
                    if (_params.hasOwnProperty(k) && _params[k] !== undefined) return String(_params[k]);
                    if (_env.hasOwnProperty(k) && _env[k] !== undefined) return String(_env[k]);
                    return "";
                },
                add: function(key, value) {
                    if (key == null || String(key).trim() === "") return;
                    _result[String(key).trim()] = (value != null) ? String(value) : "";
                },
                saveEnv: function(key, value) {
                    if (key == null || String(key).trim() === "") return;
                    if (_group === null || _name === null) {
                        console.error('[saveEnv] 无法保存: 缺少脚本标识(group/name)');
                        return;
                    }
                    var k = String(key).trim();
                    var v = (value != null) ? String(value) : "";
                    _env[k] = v;
                    if (_params.hasOwnProperty(k)) {
                        _params[k] = v;
                    }
                    var ret = __nativeSaveEnv(_group, _name, k, v);
                    if (ret !== 'OK') {
                        console.error('[saveEnv] 保存失败: ' + ret);
                    }
                },
                httpGet: function(url, headers) {
                    try {
                        if (url == null) { console.error('[httpGet] url 不能为空'); return JSON.stringify({error:'url is required'}); }
                        var h = (headers && typeof headers === 'object') ? JSON.stringify(headers) : '{}';
                        var resp = __nativeHttpGet(String(url), h);
                        return resp;
                    } catch(e) {
                        console.error('[httpGet] 执行失败: ' + (e.message || e));
                        return JSON.stringify({error: 'httpGet failed: ' + (e.message || 'unknown'), url: String(url)});
                    }
                },
                httpPost: function(url, headers, body) {
                    try {
                        if (url == null) { console.error('[httpPost] url 不能为空'); return JSON.stringify({error:'url is required'}); }
                        var h = (headers && typeof headers === 'object') ? JSON.stringify(headers) : '{}';
                        var b = (body != null) ? String(body) : '';
                        var resp = __nativeHttpPost(String(url), h, b);
                        return resp;
                    } catch(e) {
                        console.error('[httpPost] 执行失败: ' + (e.message || e));
                        return JSON.stringify({error: 'httpPost failed: ' + (e.message || 'unknown'), url: String(url)});
                    }
                },
                shell: function(command) {
                    try {
                        if (command == null) { console.error('[shell] command 不能为空'); return ''; }
                        var resp = __nativeShell(String(command));
                        return resp;
                    } catch(e) {
                        console.error('[shell] 执行失败: ' + (e.message || e));
                        return 'Shell权限不可用';
                    }
                }
            };
            this.__getUriRouteResult = function() {
                return JSON.stringify(_result);
            };
        })();
    """.trimIndent()

    // ── Console helpers ───────────────────────────────────

    private fun injectConsolePolyfill(cx: Context, scope: Scriptable) {
        val script = """
            (function() {
                var __logs = [];
                if (typeof console === 'undefined') { console = {}; }
                var safeJoin = function(args) {
                    var parts = [];
                    for (var i = 0; i < args.length; i++) {
                        try { parts.push(String(args[i])); } catch(e) { parts.push('[unprintable]'); }
                    }
                    return parts.join(' ');
                };
                console.log = function() { __logs.push(safeJoin(arguments)); };
                console.info = function() { __logs.push('[INFO] ' + safeJoin(arguments)); };
                console.warn = function() { __logs.push('[WARN] ' + safeJoin(arguments)); };
                console.error = function() { __logs.push('[ERROR] ' + safeJoin(arguments)); };
                this.__logs = __logs;
            })();
        """.trimIndent()
        cx.evaluateString(scope, script, "consolePolyfill", 1, null)
    }

    /** Format a JS error into a user-friendly Chinese message. */
    private fun formatJsError(context: String, error: Throwable): String {
        val msg = error.message ?: "未知错误"

        // ClassShutter blocked Java class access
        if (msg.contains("not visible to scripts", ignoreCase = true) ||
            msg.contains("access to java", ignoreCase = true) ||
            msg.contains("ClassShutter", ignoreCase = true)
        ) {
            // Extract class name from "Java class 'xxx' is not visible to scripts"
            val className = msg.replace(Regex(".*?['\"]([\\w.]+)['\"].*"), "$1")
            return "$context: Java包\"$className\"不可用，引擎不支持Java互操作"
        }

        // ReferenceError: X is not defined → likely an external package/API
        if (msg.contains("not defined", ignoreCase = true)) {
            val refMatch = Regex("[\"']([^\"']+)[\"']").find(msg)
            if (refMatch != null) {
                val identifier = refMatch.groupValues[1]
                return "$context: \"$identifier\"不可用，请确认是否已导入该包"
            }
            return "$context: 引用了未定义的变量，可能缺少导入包或不支持该功能"
        }

        // TypeError: Cannot read property X from undefined → data format
        if (msg.contains("Cannot read property", ignoreCase = true)) {
            val propMatch = Regex("[\"']([^\"']+)[\"']").find(msg)
            if (propMatch != null) {
                val prop = propMatch.groupValues[1]
                return "$context: 无法读取属性\"$prop\"，返回数据格式不正确或API返回异常"
            }
            return "$context: 数据格式错误，无法读取属性"
        }

        // TypeError: X is not a function
        if (msg.contains("not a function", ignoreCase = true)) {
            val funcMatch = Regex("[\"']([^\"']+)[\"']").find(msg)
            if (funcMatch != null) {
                val name = funcMatch.groupValues[1]
                return "$context: \"$name\"不是可用的函数，可能缺少导入包或不支持该功能"
            }
            return "$context: 调用了不可用的函数，可能缺少导入包"
        }

        // Generic ReferenceError
        if (error.javaClass.simpleName == "ReferenceError") {
            return "$context: 引用了一个未定义的变量，请检查代码"
        }

        // Generic TypeError
        if (error.javaClass.simpleName == "TypeError") {
            return "$context: 类型错误，数据格式或函数调用不正确"
        }

        // Fallback
        return "$context: ${msg}"
    }

    /** Evaluate a console.error() call at scope level. */
    private fun evaluateConsoleError(cx: Context, scope: Scriptable, msg: String) {
        try {
            val safe = msg.replace("'", "\\'").replace("\n", "\\n")
            cx.evaluateString(scope, "console.error('$safe');", "engineLog", 1, null)
        } catch (_: Exception) { }
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectConsoleLogs(scope: Scriptable, logs: MutableList<String>) {
        try {
            val jsLogs = scope.get("__logs", scope)
            if (jsLogs is Scriptable) {
                val length = jsLogs.get("length", jsLogs)
                if (length is Number) {
                    for (i in 0 until length.toInt()) {
                        val entry = jsLogs.get(i, jsLogs)
                        if (entry is String) logs.add(entry)
                        else if (entry !is Undefined && entry != null) logs.add(Context.toString(entry))
                    }
                }
            }
        } catch (e: Exception) {
            logs.add("[Console] Failed to collect logs: ${e.message}")
        }
    }

    private fun collectUriRouteResult(cx: Context, scope: Scriptable): Map<String, String> {
        return try {
            val resultStr = Context.toString(
                cx.evaluateString(scope, "__getUriRouteResult()", "collectResult", 1, null)
            )
            if (resultStr.isBlank()) return emptyMap()
            val json = JSONObject(resultStr)
            json.keys().asSequence().associateWith { json.optString(it, "") }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
