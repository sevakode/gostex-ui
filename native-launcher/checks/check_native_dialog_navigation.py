"""Exercise the production TV dialog focus bridge with explicit Android view boundaries.

Verifies key routing, preferred button fallback, row retention and canceled/repeated
keys. Android window/IME focus delivery remains a separate on-device check.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'native-launcher/app/src/main/java/tv/orbit/shield/NativeDialogNavigation.kt'
CACHE = ROOT / '.tools/native/gradle-cache/caches/modules-2/files-2.1'
JAVA = ROOT / '.tools/native/jdk/jdk-21.0.13+9/Contents/Home/bin/java'
VIEW = '''package android.view
class KeyEvent(val action: Int, val isCanceled: Boolean = false) {
    companion object { const val ACTION_DOWN = 0; const val ACTION_UP = 1; const val KEYCODE_DPAD_RIGHT = 22; const val KEYCODE_DPAD_LEFT = 21 }
}
class FocusGroup { var focused: View? = null }
open class View(val group: FocusGroup) {
    var isShown = true
    var isEnabled = true
    fun hasFocus() = group.focused === this
    fun requestFocus(): Boolean { if (!isShown || !isEnabled) return false; group.focused = this; return true }
}
'''
WIDGET = '''package android.widget
import android.view.View
import android.view.FocusGroup
class Button(group: FocusGroup): View(group)
class ListView(group: FocusGroup, var count: Int): View(group) {
    var selectedItemPosition = -1
    var firstVisiblePosition = 0
    fun setSelection(position: Int) { check(position in 0 until count); selectedItemPosition = position }
    fun post(task: () -> Unit) { task() }
}
'''
DIALOG = '''package android.app
import android.view.KeyEvent
import android.view.FocusGroup
import android.widget.ListView
import android.widget.Button
class AlertDialog(val listView: ListView?, val group: FocusGroup) {
    companion object { const val BUTTON_POSITIVE = -1; const val BUTTON_NEUTRAL = -3; const val BUTTON_NEGATIVE = -2 }
    var isShowing = true
    val buttons = mutableMapOf<Int, Button>()
    var listener: ((AlertDialog, Int, KeyEvent) -> Boolean)? = null
    fun getButton(id: Int): Button? = buttons[id]
    fun setOnKeyListener(listener: (AlertDialog, Int, KeyEvent) -> Boolean) { this.listener = listener }
    fun send(key: Int, action: Int = 0, canceled: Boolean = false) = listener?.invoke(this,key,KeyEvent(action,canceled)) ?: false
}
'''
CHECKS = '''package tv.orbit.shield
import android.app.AlertDialog
import android.view.FocusGroup
import android.widget.ListView
import android.widget.Button
fun main() {
    var checks=0
    fun pass(name:String) { println("PASS|$name"); checks++ }
    fun dialog(count:Int=107):AlertDialog {
        val group=FocusGroup()
        return AlertDialog(ListView(group,count),group).apply {
            buttons[-1]=Button(group); buttons[-2]=Button(group); buttons[-3]=Button(group)
            enableListButtonNavigation()
        }
    }
    val full=dialog()
    check(full.listView!!.hasFocus() && full.listView.selectedItemPosition==0)
    full.listView.setSelection(77)
    check(full.send(22) && full.buttons[-1]!!.hasFocus())
    repeat(10) { check(full.send(22) && full.buttons[-1]!!.hasFocus()) }
    check(full.send(22,1))
    check(full.send(21) && full.listView.hasFocus() && full.listView.selectedItemPosition==77)
    check(full.send(21,1))
    pass("Any checklist row reaches Apply directly and Left restores the selected row")
    val fallback=dialog()
    fallback.buttons[-1]!!.isEnabled=false
    check(fallback.send(22) && fallback.buttons[-3]!!.hasFocus()); check(fallback.send(22,1))
    check(fallback.send(21)); check(fallback.send(21,1))
    fallback.buttons[-3]!!.isShown=false
    check(fallback.send(22) && fallback.buttons[-2]!!.hasFocus()); check(fallback.send(22,1))
    check(fallback.send(21)); check(fallback.send(21,1))
    fallback.buttons[-2]!!.isShown=false
    check(!fallback.send(22) && fallback.listView!!.hasFocus())
    pass("Missing or disabled Apply falls back to available actions without losing focus")
    for (key in listOf(4,19,20,23,66,96,97,102,103)) {
        check(!full.send(key)); check(!full.send(key,1))
    }
    val name=AlertDialog(null,FocusGroup()); name.enableListButtonNavigation()
    check(name.listener==null)
    pass("Back, confirm, vertical movement and name dialog cursor/IME remain native")
    val changed=dialog()
    changed.listView!!.setSelection(99)
    check(changed.send(22)); check(changed.send(22,1))
    changed.listView.count=10
    check(changed.send(21) && changed.listView.selectedItemPosition==9)
    pass("Returning to a shortened list clamps the remembered row safely")
    val canceled=dialog()
    check(!canceled.send(22,canceled=true) && canceled.listView!!.hasFocus())
    check(!canceled.send(22,1,canceled=true))
    check(canceled.send(22)); check(canceled.send(22,1,canceled=true))
    check(!canceled.send(22,1))
    val empty=dialog(0)
    empty.buttons[-1]!!.requestFocus()
    check(!empty.send(21))
    pass("Canceled and repeated gestures or empty lists do not make spurious focus moves")
    println("TOTAL|$checks")
}
'''
def jar(group,artifact,version):
    return next((CACHE/group/artifact/version).glob('*/*.jar'))
compiler=[jar('org.jetbrains.kotlin',artifact,version) for artifact,version in [
    ('kotlin-compiler-embeddable','2.4.21'),('kotlin-stdlib','2.4.21'),
    ('kotlin-script-runtime','2.4.21'),('kotlin-reflect','1.6.10')
]]+[jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0'),jar('org.jetbrains','annotations','13.0')]
classpath=str(compiler[1])
report={'timestampUtc':datetime.now(timezone.utc).isoformat(),'sourceSha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        'notCovered':['Android dialog window and IME delivery','Physical TV remote/gamepad'], 'passed':False}
with tempfile.TemporaryDirectory(prefix='gostex-dialog-navigation-') as folder:
    temporary=Path(folder)
    for name,content in [('View.kt',VIEW),('Widget.kt',WIDGET),('Dialog.kt',DIALOG),('Checks.kt',CHECKS)]:
        (temporary/name).write_text(content)
    classes=temporary/'classes'
    compiled=subprocess.run([str(JAVA),'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                             '-no-stdlib','-no-reflect','-classpath',classpath,'-d',str(classes),str(SOURCE),
                             *[str(temporary/name) for name in ['View.kt','Widget.kt','Dialog.kt','Checks.kt']]],capture_output=True,text=True)
    report['compile']={'returncode':compiled.returncode,'stderr':compiled.stderr}
    if compiled.returncode==0:
        run=subprocess.run([str(JAVA),'-cp',f'{classes}:{classpath}','tv.orbit.shield.ChecksKt'],capture_output=True,text=True)
        report['run']={'returncode':run.returncode,'stdout':run.stdout,'stderr':run.stderr}
        report['checks']=[line.removeprefix('PASS|') for line in run.stdout.splitlines() if line.startswith('PASS|')]
        report['passed']=run.returncode==0 and len(report['checks'])==5
output=ROOT/'.reports/native-dialog-navigation-checks.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':report['passed'],'checks':len(report.get('checks',[])),'report':str(output)}))
if not report['passed']:
    print(json.dumps(report,ensure_ascii=False,indent=2))
    raise SystemExit(1)
