"""Exercise the real ConfigManager and width policy against in-memory preferences.
Only the platform preferences/module interface and JSON wrapper are adapters.
This does not replace an Android integration test and writes no phone settings.
"""
import hashlib, json, os, subprocess
from pathlib import Path
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT/'tmp/width-marquee-regression'
OUT.mkdir(parents=True, exist_ok=True)
CACHE = ROOT/'tmp/gradle-home/caches/modules-2/files-2.1'
WORK = OUT/'config-probe'
WORK.mkdir(exist_ok=True)
(WORK/'classes').mkdir(exist_ok=True)
JAVA = next((ROOT/'tmp/jdk21').glob('*/bin/java.exe'))
def jar(group, name, version=None):
    folder = CACHE/group/name
    return next((folder/version if version else folder).rglob('*.jar'))
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def relative(path): return path.relative_to(ROOT).as_posix()

actual = [ROOT/'android/app/src/main/kotlin/io/github/hyperisland/xposed/ConfigManager.kt',
    ROOT/'android/app/src/main/kotlin/io/github/hyperisland/xposed/hook/SystemUI/geometry/IslandWidthPolicy.kt']
case_source = HERE/'fixtures/WidthConfigCases.kt'
runner = Path(__file__).resolve()
# Bind source inputs before reading the cases or compiling, never only after execution.
before = {path: sha(path) for path in [*actual, case_source, runner]}
sources = {
'Preferences.kt': '''
package android.content
interface SharedPreferences {
    fun interface OnSharedPreferenceChangeListener { fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) }
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getLong(key: String, default: Long): Long
    fun getInt(key: String, default: Int): Int
    fun getFloat(key: String, default: Float): Float
    fun contains(key: String): Boolean
}
class MemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    fun put(key: String, value: Any) { values[key] = value; listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) } }
    fun remove(key: String) { values.remove(key); listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) } }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
    override fun getString(key: String, default: String?) = values[key] as? String ?: default
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
    override fun contains(key: String) = values.containsKey(key)
}
''',
'Module.kt': '''
package io.github.libxposed.api
class XposedModule(val prefs: android.content.SharedPreferences) {
    fun getRemotePreferences(name: String) = prefs
    fun log(message: () -> String) {}
}
''',
'Logging.kt': '''
package io.github.hyperisland.xposed
fun io.github.libxposed.api.XposedModule.logWarn(message: String) {}
fun io.github.libxposed.api.XposedModule.logError(message: String) {}
''',
'Json.kt': '''
package org.json
import com.google.gson.*
class JSONObject(private val obj: JsonObject) {
    constructor(text: String): this(JsonParser.parseString(text).asJsonObject)
    companion object { val NULL = Any() }
    fun optJSONObject(key: String): JSONObject? = obj.get(key)?.takeIf { it.isJsonObject }?.let { JSONObject(it.asJsonObject) }
    fun optJSONArray(key: String): JSONArray? = obj.get(key)?.takeIf { it.isJsonArray }?.let { JSONArray(it.asJsonArray) }
    fun opt(key: String): Any? {
        val value = obj.get(key) ?: return null
        if (value.isJsonNull) return NULL
        if (value.isJsonObject) return JSONObject(value.asJsonObject)
        if (value.isJsonArray) return JSONArray(value.asJsonArray)
        val primitive = value.asJsonPrimitive
        return when { primitive.isString -> primitive.asString; primitive.isBoolean -> primitive.asBoolean; else -> primitive.asNumber }
    }
}
class JSONArray(private val array: JsonArray) {
    fun length() = array.size()
    fun optString(index: Int) = array.get(index).asString
}
''',
'Probe.kt': case_source.read_text('utf-8')
}
for name, source in sources.items(): (WORK/name).write_text(source.strip()+'\n','utf-8')
generated = {WORK/name: sha(WORK/name) for name in sources}
stdlib = jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.10')
annotations = jar('org.jetbrains','annotations','13.0')
gson = jar('com.google.code.gson','gson','2.11.0')
compiler_cp = os.pathsep.join(map(str,[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.10'),
    stdlib,jar('org.jetbrains.kotlin','kotlin-script-runtime','2.4.10'),jar('org.jetbrains.kotlin','kotlin-reflect'),
    jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0'),annotations]))
runtime_cp = os.pathsep.join(map(str,[WORK/'classes',stdlib,annotations,gson]))
command = [str(JAVA),'-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
    '-classpath',runtime_cp,'-jvm-target','21','-d',str(WORK/'classes'), *map(str,actual),
    *[str(WORK/name) for name in sources]]
compiled = subprocess.run(command, capture_output=True, text=True)
(OUT/'config-compile.txt').write_text(compiled.stdout+compiled.stderr, 'utf-8')
results = []
parse_error = None
run = None
if compiled.returncode == 0:
    run = subprocess.run([str(JAVA),'-cp',runtime_cp,'audit.ProbeKt'], capture_output=True, text=True)
    (OUT/'config-run.txt').write_text(run.stdout+run.stderr, 'utf-8')
    try:
        results = [json.loads(line) for line in run.stdout.splitlines()]
        if not all(isinstance(case, dict) and isinstance(case.get('passed'), bool) for case in results):
            raise ValueError('Unexpected test result shape')
    except (ValueError, TypeError) as error:
        parse_error = type(error).__name__
        results = []
else:
    # Replace a previous successful runtime log when this compilation failed.
    (OUT/'config-run.txt').write_text('Not run: compilation failed.\n', 'utf-8')
unchanged = all(path.is_file() and sha(path) == digest for path, digest in {**before, **generated}.items())
failed = [case for case in results if not case['passed']]
exit_code = compiled.returncode if run is None else run.returncode
passed = exit_code == 0 and bool(results) and not failed and parse_error is None and unchanged
report = {
    'status': 'PASS' if passed else 'FAIL',
    'exit_code': exit_code,
    'compile_exit_code': compiled.returncode,
    'tests_total': len(results),
    'tests_passed': len(results)-len(failed),
    'real_source_sha256': {relative(path): before[path] for path in actual},
    'production_sources': {relative(path): before[path] for path in actual},
    'fixture_sha256': {relative(path): digest for path, digest in generated.items()},
    'runner_sha256': before[runner],
    'case_source_sha256': before[case_source],
    'sources_unchanged_after_test': unchanged,
    'platform_adapters': 'In-memory SharedPreferences and Gson-backed JSONObject; complete real ConfigManager and width policy sources. No Android injection or power measurement.',
    'parse_error': parse_error,
    'results': results,
}
(OUT/'config-probe.json').write_text(json.dumps(report,indent=2), 'utf-8')
print(json.dumps({'suite': 'config', 'status': report['status'], 'tests': len(results),
    'failures': failed, 'sources_unchanged_after_test': unchanged}))
if not passed: raise SystemExit(exit_code or 1)
