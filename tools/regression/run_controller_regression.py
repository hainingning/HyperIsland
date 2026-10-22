"""Run the exact current controller body against a bounded UI scheduler model.
Android adapters model a still-attached invisible ancestor and callback delivery;
they are not an on-device visibility or rendering test.
"""
import hashlib, json, os, subprocess
from pathlib import Path
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT/'tmp/width-marquee-regression'
OUT.mkdir(parents=True, exist_ok=True)
CACHE=ROOT/'tmp/gradle-home/caches/modules-2/files-2.1'
WORK=OUT/'controller-probe'
WORK.mkdir(exist_ok=True)
(WORK/'classes').mkdir(exist_ok=True)
JAVA=next((ROOT/'tmp/jdk21').glob('*/bin/java.exe'))
def jar(group,name,version=None):
    folder=CACHE/group/name
    return next((folder/version if version else folder).rglob('*.jar'))
source=ROOT/'android/app/src/main/kotlin/io/github/hyperisland/xposed/hook/SystemUI/MarqueeHook.kt'
EVIDENCE=ROOT.parent/'HyperIsland-review-20261022/performance-fix/marquee'
EVIDENCE.mkdir(parents=True,exist_ok=True)
original=source.read_text('utf-8-sig')
body=original[original.index('    class MarqueeController('):]
sources={
'Resources.kt': '''
package android.content.res
class Metrics { var density=3.25f }
class Resources { val displayMetrics=Metrics(); fun getResourceEntryName(id: Int)="other" }
''',
'View.kt': '''
package android.view
open class View {
    companion object { const val VISIBLE=0; const val INVISIBLE=4; const val GONE=8; const val NO_ID=-1 }
    var parent: Any?=null
    var width=1000; var left=0; var scrollX=0
    var paddingLeft=0; var paddingRight=0
    var translationX=0f; var scaleX=1f; var pivotX=0f; var alpha=1f
    var windowVisibility=VISIBLE
    var id=NO_ID; var visibility=VISIBLE; var isAttachedToWindow=true; var layoutDirection=0
    val resources=android.content.res.Resources()
    var viewTreeObserver=ViewTreeObserver()
    fun scrollTo(x: Int,y: Int) { scrollX=x }
}
open class ViewGroup: View() {
    var clipChildren=true; var clipToPadding=true
    val children=mutableListOf<View>()
    val childCount get()=children.size
    fun getChildAt(index: Int)=children[index]
}
class ViewTreeObserver {
    fun interface OnPreDrawListener { fun onPreDraw(): Boolean }
    var isAlive=true
    val listeners=mutableListOf<OnPreDrawListener>()
    fun addOnPreDrawListener(listener: OnPreDrawListener) { listeners.add(listener) }
    fun removeOnPreDrawListener(listener: OnPreDrawListener) { listeners.removeAll { it === listener } }
    fun draw() { listeners.toList().forEach { it.onPreDraw() } }
}
class Choreographer {
    fun interface FrameCallback { fun doFrame(frameTimeNanos: Long) }
    data class Pending(val callback: FrameCallback,val due: Long,val delayed: Boolean)
    val pending=mutableListOf<Pending>()
    var now=0L
    var immediatePosts=0; var delayedPosts=0; var delivered=0
    fun postFrameCallback(callback: FrameCallback) { immediatePosts++; pending.add(Pending(callback,now,false)) }
    fun postFrameCallbackDelayed(callback: FrameCallback,delayMillis: Long) {
        delayedPosts++; pending.add(Pending(callback,now+delayMillis*1_000_000L,true))
    }
    fun removeFrameCallback(callback: FrameCallback) { pending.removeAll { it.callback === callback } }
    fun frame(time: Long,deliveredAt: Long=time) {
        now=deliveredAt
        val callbacks=pending.filter { it.due <= deliveredAt }
        pending.removeAll(callbacks.toSet())
        callbacks.forEach { delivered++; it.callback.doFrame(time) }
    }
    fun reset() { pending.clear(); now=0L; immediatePosts=0; delayedPosts=0; delivered=0 }
    companion object { private val instance=Choreographer(); fun getInstance()=instance }
}
object Gravity {
    const val RIGHT=5; const val CENTER_HORIZONTAL=1; const val HORIZONTAL_GRAVITY_MASK=7
    fun getAbsoluteGravity(gravity: Int,direction: Int)=gravity
}
''',
'Clock.kt': '''
package io.github.hyperisland.xposed.hook
// Android frame timestamps and System.nanoTime use the same monotonic clock.
internal object System { fun nanoTime()=android.view.Choreographer.getInstance().now }
''',
'Text.kt': '''
package android.widget
class Layout(var width: Int=1000,var drawnWidth: Float=1000f) {
    val lineCount=1
    fun getLineLeft(index: Int)=0f
    fun getLineRight(index: Int)=drawnWidth
    fun getParagraphDirection(index: Int)=1
}
open class TextView: android.view.View() {
    var text: CharSequence="A long notification"
    var layout: Layout?=Layout()
    var gravity=0
    val compoundPaddingLeft get()=paddingLeft
    val compoundPaddingRight get()=paddingRight
}
''',
'MarqueeController.kt': '''
package io.github.hyperisland.xposed.hook
import android.view.*
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
object MarqueeHook {
    val islandMarqueeState=mutableMapOf<ViewGroup,Boolean>()
    class Session(var dismissed: Boolean=false)
    val islandAutoHideSessions=mutableMapOf<ViewGroup,Session>()
    private fun normalizeText(text: String)=text.trim()
    private fun registerScrollingView(view: TextView) {}
    private fun unregisterScrollingView(view: TextView) {}
    var loopCalls=0
    var loopAction: (() -> Unit)?=null
    private fun onLoopCompleted(view: TextView,loops: Int) { loopCalls++; loopAction?.invoke() }
    private fun findBigIslandView(view: TextView): ViewGroup? {
        var parent=view.parent
        while (parent is ViewGroup) { if (islandMarqueeState.containsKey(parent)) return parent; parent=parent.parent }
        return null
    }
    private fun isMarqueeEnabledFor(view: TextView)=islandMarqueeState[findBigIslandView(view)]==true
'''+body,
'Probe.kt': (HERE/'fixtures/MarqueeControllerCases.kt').read_text('utf-8')
}
helpers=[source.with_name(name+'.kt') for name in ('MarqueeReadiness','MarqueeScrollRange','MarqueeViewport')]
inputs=[source,*helpers,HERE/'fixtures/MarqueeControllerCases.kt',Path(__file__).resolve()]
before={p:hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.10')
annotations=jar('org.jetbrains','annotations','13.0')
compiler_cp=os.pathsep.join(map(str,[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.10'),stdlib,
    jar('org.jetbrains.kotlin','kotlin-script-runtime','2.4.10'),jar('org.jetbrains.kotlin','kotlin-reflect'),
    jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0'),annotations]))

def execute(label, controller_body, args=()):
    folder=WORK/label; folder.mkdir(exist_ok=True)
    classes=folder/'classes'; classes.mkdir(exist_ok=True)
    generated=dict(sources)
    generated['MarqueeController.kt']=sources['MarqueeController.kt'].removesuffix(body)+controller_body
    for name,text in generated.items(): (folder/name).write_text(text.strip()+'\n','utf-8')
    generated_hashes={str(p.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in [folder/name for name in generated]}
    runtime_cp=os.pathsep.join(map(str,[classes,stdlib,annotations]))
    compiled=subprocess.run([str(JAVA),'-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
        '-nowarn','-classpath',runtime_cp,'-jvm-target','21','-d',str(classes),*map(str,helpers),
        *[str(folder/name) for name in generated]],capture_output=True,text=True)
    (EVIDENCE/(label+'-compile.txt')).write_text(compiled.stdout+compiled.stderr,'utf8')
    if compiled.returncode:
        print(compiled.stdout+compiled.stderr); raise SystemExit(compiled.returncode)
    run=subprocess.run([str(JAVA),'-cp',runtime_cp,'audit.ProbeKt',*args],capture_output=True,text=True)
    (EVIDENCE/(label+'-run.txt')).write_text(run.stdout+run.stderr,'utf8')
    results=[json.loads(line) for line in run.stdout.splitlines() if line.startswith('{')]
    return run,results,generated_hashes

baseline=EVIDENCE/'MarqueeHook.before.kt'
baseline_raw=baseline.read_bytes()
assert hashlib.sha256(baseline_raw).hexdigest()=='b1551bd39c3130ab108cc301e56a4d5f31471f40c88307a833314cce315a5a93', 'Negative control must remain original code2026102201 marquee source'
baseline_text=baseline_raw.decode('utf-8-sig')
baseline_body=baseline_text[baseline_text.index('    class MarqueeController('):]
negative_run,negative_results,negative_generated=execute('before',baseline_body,['--negative-control'])
negative_ok=negative_run.returncode==0 and len(negative_results)==2 and all(not x['passed'] for x in negative_results)
negative_report={'status':'EXPECTED_FAILURE_DETECTED' if negative_ok else 'FAIL',
 'baseline_source_sha256':hashlib.sha256(baseline_raw).hexdigest(),
 'controller_body_sha256':hashlib.sha256(baseline_body.encode()).hexdigest(),
 'results':negative_results,'fixture_sha256':negative_generated,
 'note':'Execute exact previous production controller; pause scheduling assertions must fail. Same helpers and scheduler adapters, no Android runtime claim.'}
(EVIDENCE/'negative-control.json').write_text(json.dumps(negative_report,indent=2),'utf8')
run,results,generated_hashes=execute('current',body)
unchanged=all(hashlib.sha256(p.read_bytes()).hexdigest()==v for p,v in before.items())
failed=[case for case in results if not case['passed']]
report={'status':'PASS' if run.returncode==0 and not failed and negative_ok and unchanged else 'FAIL',
 'exit_code':run.returncode,'tests_passed':len(results)-len(failed),'tests_total':len(results),
 'controller_body_sha256':hashlib.sha256(body.encode()).hexdigest(),
 'source_sha256':before[source],
 'production_sources':{str(p.relative_to(ROOT)).replace('\\','/'):before[p] for p in [source,*helpers]},
 'fixture_sha256':generated_hashes,
 'runner_sha256':before[Path(__file__).resolve()],
 'case_source_sha256':before[HERE/'fixtures/MarqueeControllerCases.kt'],
 'sources_unchanged_after_test':unchanged,
 'negative_control':negative_report,
 'adapters':'Exact controller body plus actual geometry/readiness sources; bounded UI scheduler with delayed-due times, monotonic System.nanoTime clock adapter, late dispatch, and passive native preDraw. No Android dispatch/rendering/power measurement.',
 'results':results}
(OUT/'controller-probe.json').write_text(json.dumps(report,indent=2),'utf-8')
(EVIDENCE/'controller-probe.json').write_text(json.dumps(report,indent=2),'utf-8')
print(json.dumps({'suite':'controller','status':report['status'],'tests':len(results),'failures':failed,'negative_control':negative_report['status']}))
if report['status']!='PASS': raise SystemExit(1)
