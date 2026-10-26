#!/usr/bin/env python3
"""Genera docs/manuale/SirJoe-MTB-Manuale.pdf dal contenuto HTML (Chromium via Playwright, due passate per i numeri di pagina dell'indice)."""
import html, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
APP_VER = re.search(r'versionName = "([^"]+)"', open(os.path.join(ROOT, 'app/build.gradle.kts')).read()).group(1)
PC_VER = json.load(open(os.path.join(ROOT, 'desktop/package.json')))['version']
OUT = os.path.join(HERE, 'SirJoe-MTB-Manuale.pdf')

body = open(os.path.join(HERE, 'contenuto.html'), encoding='utf-8').read()

# avvertenze: stesso testo mostrato dall'app
av = open(os.path.join(ROOT, 'app/src/main/assets/avvertenze.txt'), encoding='utf-8').read().strip().split('\n')
out, in_list = [], False
for line in av:
    t = line.strip()
    if not t:
        if in_list: out.append('</ul>'); in_list = False
        continue
    if t.startswith('•'):
        if not in_list: out.append('<ul>'); in_list = True
        out.append('<li>' + html.escape(t[1:].strip()) + '</li>')
    elif re.match(r'^\d+\. [A-ZÀ-Ù ]+', t):
        if in_list: out.append('</ul>'); in_list = False
        out.append('<h4>' + html.escape(t) + '</h4>')
    else:
        out.append('<p>' + html.escape(t) + '</p>')
if in_list: out.append('</ul>')
body = body.replace('%%AVVERTENZE%%', '\n'.join(out))

# voci dell'indice: titoli di parte e capitoli
entries = []
for m in re.finditer(r'<(h1|h2) [^>]*id="([^"]+)"[^>]*>(.*?)</\1>', body, re.S):
    entries.append((m.group(1), m.group(2), re.sub('<[^>]+>', '', m.group(3))))
part_of = {}
for m in re.finditer(r'<div class="part-kicker">(.*?)</div>\s*<h1 class="part-title" id="([^"]+)"', body):
    part_of[m.group(2)] = m.group(1)

logo = open(os.path.join(ROOT, 'docs/logo-by-sirjoe.svg'), encoding='utf-8').read()
logo = re.sub(r'<\?xml[^>]*>', '', logo)

CSS = """
@page { size: A4; margin: 18mm 17mm 20mm 17mm; }
@page :first { margin: 0; }
:root { --or:#FF6D00; --dk:#1B2A33; --mut:#5d6b73; --line:#e3e7ea; --soft:#fff4ec; }
* { box-sizing: border-box; }
body { font-feature-settings: 'calt' 0, 'case' 0, 'liga' 0; font-family: 'Inter', 'DejaVu Sans', sans-serif; font-size: 10.3pt; line-height: 1.5; color: var(--dk); margin: 0; }
.cover { height: 297mm; width: 210mm; padding: 28mm 22mm; display: flex; flex-direction: column; justify-content: space-between;
  background: linear-gradient(160deg, #1B2A33 0%, #22343f 60%, #2d1a10 100%); color: #fff; page-break-after: always; }
.cover .logo svg { width: 120mm; height: auto; }
.cover h1 { font-size: 34pt; line-height: 1.1; margin: 0 0 4mm; font-weight: 800; letter-spacing: -0.5px; }
.cover .sub { font-size: 14pt; color: #ffcfae; margin: 0 0 10mm; }
.cover .apps { display: flex; gap: 8mm; }
.cover .app { flex: 1; border: 1px solid rgba(255,255,255,.25); border-radius: 4mm; padding: 5mm 6mm; }
.cover .app b { display: block; font-size: 13pt; margin-bottom: 1mm; }
.cover .app span { color: #cfd8dc; font-size: 9.5pt; }
.cover .foot { font-size: 9pt; color: #b0bec5; border-top: 1px solid rgba(255,255,255,.2); padding-top: 4mm; }
.toc { page-break-after: always; }
.toc h1 { font-size: 22pt; margin: 0 0 6mm; }
.toc .row { display: flex; align-items: baseline; gap: 2mm; padding: 1.2mm 0; }
.toc .row a { color: inherit; text-decoration: none; }
.toc .row .dots { flex: 1; border-bottom: 1px dotted #b0bec5; transform: translateY(-1mm); }
.toc .part { font-weight: 800; color: var(--or); margin-top: 5mm; font-size: 11.5pt; text-transform: uppercase; letter-spacing: .5px; }
.toc .ch { padding-left: 5mm; }
section.part { page-break-before: always; padding-top: 70mm; text-align: left; }
.part-kicker { color: var(--or); font-weight: 800; letter-spacing: 2px; text-transform: uppercase; font-size: 11pt; }
.part-title { font-size: 30pt; line-height: 1.1; margin: 2mm 0 6mm; border-bottom: 3px solid var(--or); padding-bottom: 4mm; }
.part-lead { font-size: 12.5pt; color: var(--mut); max-width: 150mm; }
h2 { font-size: 16pt; margin: 9mm 0 3mm; padding-bottom: 1.5mm; border-bottom: 1px solid var(--line); page-break-after: avoid; }
section.part + h2 { page-break-before: always; margin-top: 0; }
h3 { font-size: 11.5pt; margin: 5mm 0 1.5mm; color: #263238; page-break-after: avoid; }
h4 { font-size: 10.5pt; margin: 4mm 0 1mm; color: var(--or); }
p { margin: 0 0 2.5mm; }
ul, ol { margin: 0 0 3mm; padding-left: 6mm; }
li { margin-bottom: 1.2mm; }
code { font-family: 'DejaVu Sans Mono', monospace; font-size: 8.8pt; background: #eef1f3; padding: .3mm 1.2mm; border-radius: 1mm; }
table { width: 100%; border-collapse: collapse; margin: 2mm 0 4mm; font-size: 9.5pt; page-break-inside: auto; }
tr { page-break-inside: avoid; }
th { text-align: left; background: #263238; color: #fff; padding: 1.8mm 2.5mm; font-weight: 600; }
td { padding: 1.6mm 2.5mm; border-bottom: 1px solid var(--line); vertical-align: top; }
tr:nth-child(even) td { background: #f7f9fa; }
.faq td:first-child { font-weight: 600; width: 36%; }
.box { border-left: 4px solid var(--or); background: var(--soft); padding: 3mm 4mm; margin: 3mm 0 4mm; border-radius: 0 2mm 2mm 0; page-break-inside: avoid; }
.box.warn { border-color: #C62828; background: #fdecea; }
figure { margin: 3mm 0 5mm; text-align: center; page-break-inside: avoid; }
figcaption { font-size: 8.8pt; color: var(--mut); margin-top: 1mm; }
.diagram { width: 100%; max-height: 82mm; }
.diagram .phone { fill: #263238; }
.diagram .screen { fill: #dfe9d8; }
.diagram .side { fill: #eceff1; }
.diagram .tool { fill: #cfd8dc; }
.diagram .bar { fill: #1B2A33; }
.diagram .route { fill: none; stroke: #1565C0; stroke-width: 4; stroke-linecap: round; }
.diagram .track { fill: none; stroke: #FF6D00; stroke-width: 4; stroke-linecap: round; }
.diagram .prof { fill: none; stroke: #2E7D32; stroke-width: 2; }
.diagram .pt { fill: #fff; stroke: #1565C0; stroke-width: 2; }
.diagram .cursor { fill: #FF6D00; stroke: #fff; stroke-width: 1.5; }
.diagram .banner { fill: #2E7D32; }
.diagram .btn { fill: #fff; stroke: #90a4ae; }
.diagram .rec { fill: #FF6D00; }
.diagram .qr { fill: #fff; stroke: #263238; stroke-width: 6; stroke-dasharray: 6 4; }
.diagram .lead { stroke: #90a4ae; stroke-width: 1; }
.diagram .arrow { stroke: #FF6D00; stroke-width: 3; marker-end: url(#ah); fill: none; }
.diagram text { font-family: 'Inter', sans-serif; fill: #263238; }
.diagram .lbl { font-size: 11px; font-weight: 600; }
.diagram .small { font-size: 10px; font-weight: 600; }
.diagram .tiny { font-size: 7.5px; font-weight: 600; }
.diagram .w { fill: #fff; }
.avvertenze { font-size: 9.6pt; }
.avvertenze h4 { page-break-after: avoid; }
"""

ARROW_DEF = '<svg width="0" height="0" style="position:absolute"><defs><marker id="ah" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#FF6D00"/></marker></defs></svg>'


def build_html(pages):
    rows = []
    for tag, id_, title in entries:
        n = pages.get(id_, '')
        if tag == 'h1':
            rows.append(f'<div class="row part"><a href="#{id_}">{part_of.get(id_, "")} · {title}</a><span class="dots"></span><span>{n}</span></div>')
        else:
            rows.append(f'<div class="row ch"><a href="#{id_}">{title}</a><span class="dots"></span><span>{n}</span></div>')
    cover = f"""
<div class="cover">
  <div class="logo">{logo}</div>
  <div>
    <h1>Manuale d'uso</h1>
    <p class="sub">Ciclocomputer, navigatore e planner per mountain bike</p>
    <div class="apps">
      <div class="app"><b>SirJoe MTB</b><span>App per Android · versione {APP_VER}</span></div>
      <div class="app"><b>SirJoe MTB Planner</b><span>Programma per Windows e Mac · versione {PC_VER}</span></div>
    </div>
  </div>
  <div class="foot">Progetto personale gratuito e open source, non destinato alla vendita · github.com/sirjoe58/mapmtb · Ottobre 2026<br>
  Prima di usare le app leggi le Avvertenze d'uso (capitolo 22).</div>
</div>"""
    toc = '<div class="toc"><h1>Indice</h1>' + '\n'.join(rows) + '</div>'
    # marcatori invisibili per trovare la pagina di ogni voce
    marked = body
    for tag, id_, title in entries:
        marked = re.sub(rf'(<{tag} [^>]*id="{id_}"[^>]*>)', rf'\1<span style="font-size:1px;color:#fff">@@{id_}@@</span>', marked, count=1)
    return f'<!doctype html><html lang="it"><head><meta charset="utf-8"><title>Manuale SirJoe MTB</title><style>{CSS}</style></head><body>{ARROW_DEF}{cover}{toc}{marked}</body></html>'


def render(pages):
    path = os.path.join(HERE, '_manuale.html')
    open(path, 'w', encoding='utf-8').write(build_html(pages))
    js = f"""
const {{ chromium }} = require('playwright');
(async () => {{
  const b = await chromium.launch({{ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--headless=new'] }});
  const p = await b.newPage();
  await p.goto('file://{path}');
  await p.evaluate(() => document.fonts.ready);
  await p.pdf({{ path: '{OUT}', format: 'A4', printBackground: true, preferCSSPageSize: true,
    displayHeaderFooter: true, headerTemplate: '<span></span>',
    footerTemplate: '<div style="width:100%;font-size:8px;color:#78909c;padding:0 17mm;display:flex;justify-content:space-between;font-family:Inter,sans-serif"><span>SirJoe MTB · Manuale d\\'uso · BY SIRJOE</span><span class="pageNumber"></span></div>' }});
  await b.close();
}})();
"""
    env = dict(os.environ, NODE_PATH='/opt/npm-tools/node_modules')
    subprocess.run(['node', '-e', js], check=True, env=env)


def find_pages():
    from pypdf import PdfReader
    r = PdfReader(OUT)
    found = {}
    for i, pg in enumerate(r.pages):
        for m in re.finditer(r'@@((?:t-p|[abcd])\d+)', pg.extract_text() or ''):
            found.setdefault(m.group(1), i + 1)
    return found, len(r.pages)


render({})
pages, _ = find_pages()
render(pages)
pages2, total = find_pages()
missing = [e[1] for e in entries if e[1] not in pages2]
print('pagine:', total, 'indice ok' if pages == pages2 and not missing else f'differenze/mancanti {missing}')
os.remove(os.path.join(HERE, '_manuale.html'))
