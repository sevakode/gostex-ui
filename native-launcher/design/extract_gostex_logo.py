"""Extract the supplied PDF's original wordmark paths as Android vectors (PyMuPDF)."""
from pathlib import Path
import sys
import fitz
page = fitz.open(sys.argv[1])[3]
paths = [d for d in page.get_drawings() if d['fill'] and abs(d['fill'][0] - .8) < .001 and d['fill'][1] == 1 and d['fill'][2] == 0 and d['rect'].intersects(fitz.Rect(110,170,215,201))]
assert len(paths) == 7
origin = fitz.Point(114,170)
def point(p): return f'{p.x-origin.x:.5f},{p.y-origin.y:.5f}'
def vector_path(d):
    parts=[]; end=None
    for item in d['items']:
        kind=item[0]; start=item[1]
        if kind not in ('l','c'): raise ValueError(kind)
        if end != start:
            if end is not None: parts.append('Z')
            parts.append('M'+point(start))
        if kind=='l': parts.append('L'+point(item[2]));end=item[2]
        else: parts.append('C'+ ' '.join(point(p) for p in item[2:]));end=item[-1]
    parts.append('Z')
    return '<path android:fillColor="#CCFF00" android:fillType="'+('evenOdd' if d['even_odd'] else 'nonZero')+'" android:pathData="'+' '.join(parts)+'" />'
body='\n'.join(vector_path(d) for d in paths)
root=Path('native-launcher/app/src/main/res/drawable')
def save(name,w,h,inner):
    (root/name).write_text(f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{w}dp" android:height="{h}dp" android:viewportWidth="{w}" android:viewportHeight="{h}">\n{inner}\n</vector>\n')
save('gostex_logo.xml',100,34,body)
save('orbit_icon.xml',108,108,'<path android:fillColor="#0A0A0A" android:pathData="M0,0h108v108h-108z" />\n<group android:translateX="14" android:translateY="40" android:scaleX="0.8" android:scaleY="0.8">'+body+'</group>')
save('orbit_banner.xml',320,180,'<path android:fillColor="#0A0A0A" android:pathData="M0,0h320v180h-320z" />\n<group android:translateX="40" android:translateY="50" android:scaleX="2.4" android:scaleY="2.4">'+body+'</group>')
