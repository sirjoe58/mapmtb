#!/usr/bin/env python3
"""Piani di allenamento pronti: un'unica fonte per il Planner (desktop/app/plans.js)
e per l'app Android (training/src/main/assets/piani.json).  Uso: python3 tools/piani/genera.py"""
import json, os

def st(label, minutes, zlo, zhi, clo=0, chi=0, tip=''):
    return {"label": label, "sec": minutes * 60, "zLo": zlo, "zHi": zhi, "cLo": clo, "cHi": chi, "tip": tip}

RISC = st('Riscaldamento', 10, 1, 2, 80, 90, 'Rapporto leggero, gambe sciolte, parti piano.')
DEF = lambda m=5: st('Defaticamento', m, 1, 1, 75, 95, 'Rallenta e lascia scendere il battito.')
FONDO_TIP = 'Ritmo da chiacchierata: devi riuscire a parlare. In salita scala marcia presto per restare in zona 2.'
RITMO_TIP = 'Sostenuto ma controllato: riesci a dire frasi brevi. Seduto, rapporto che non ti fa piantare.'
REC_TIP = 'Scendi di rapporto e respira.'

def rep(times, *steps):
    out = []
    for _ in range(times):
        out += [dict(s) for s in steps]
    return out

DIM = [
    ("dim-1", "Dimagrire 1 · Fondo 45'", "Settimane 1-2 e 5. Base del piano: quasi tutto in zona 2.",
     [RISC, st('Fondo', 30, 2, 2, 80, 95, FONDO_TIP), DEF()]),
    ("dim-2", "Dimagrire 2 · Lungo facile 75'", "Settimane 1-3 e 5, nel fine settimana. Lungo e tranquillo.",
     [RISC, st('Fondo lungo', 60, 2, 2, 80, 95, FONDO_TIP + ' Se il battito sale troppo anche col rapporto più leggero, scendi e spingi la bici.'), DEF()]),
    ("dim-3", "Dimagrire 3 · Fondo 60'", "Settimane 3-5 e 8.",
     [RISC, st('Fondo', 45, 2, 2, 80, 95, FONDO_TIP), DEF()]),
    ("dim-4", "Dimagrire 4 · Ritmo 3×6' (55')", "Settimane 3-4. Primi tratti in zona 3: solo se le uscite in zona 2 vanno bene.",
     [st('Riscaldamento', 12, 1, 2, 80, 90, 'Rapporto leggero, gambe sciolte, parti piano.')]
     + rep(3, st('Ritmo', 6, 3, 3, 75, 90, RITMO_TIP), st('Recupero', 4, 1, 2, 80, 95, REC_TIP))
     + [st('Fondo', 8, 2, 2, 80, 95), DEF()]),
    ("dim-5", "Dimagrire 5 · Lungo 90'", "Settimane 4 e 6. Porta acqua e qualcosa da mangiare.",
     [RISC, st('Fondo lungo', 75, 2, 2, 80, 95, FONDO_TIP + ' Bevi un sorso ogni 15-20 minuti.'), DEF()]),
    ("dim-6", "Dimagrire 6 · Fondo agile 70'", "Settimane 6-7. Zona 2 con brevi tratti a cadenza alta.",
     [RISC]
     + rep(3, st('Fondo', 12, 2, 2, 80, 90, FONDO_TIP), st('Agile', 3, 2, 2, 95, 105, 'Rapporto leggero, pedala veloce senza saltare sulla sella.'))
     + [st('Fondo', 10, 2, 2, 80, 95), DEF()]),
    ("dim-7", "Dimagrire 7 · Ritmo 4×8' (70')", "Settimane 6-8.",
     [st('Riscaldamento', 12, 1, 2, 80, 90, 'Rapporto leggero, gambe sciolte, parti piano.')]
     + rep(4, st('Ritmo', 8, 3, 3, 75, 90, RITMO_TIP), st('Recupero', 4, 1, 2, 80, 95, REC_TIP))
     + [DEF(10)]),
    ("dim-8", "Dimagrire 8 · Lungo 2 ore", "Settimane 7-8. Porta acqua e qualcosa da mangiare.",
     [RISC, st('Fondo lungo', 50, 2, 2, 80, 95, FONDO_TIP + ' Bevi ogni 15-20 minuti.'),
      st('Ritmo leggero', 10, 2, 3, 75, 90, 'Solo se ti senti bene, altrimenti resta in zona 2.'),
      st('Fondo lungo', 40, 2, 2, 80, 95, 'Mangia qualcosa e continua tranquillo.'), DEF(10)]),
]

CALENDARIO = [
    "Sett. 1: mar Dimagrire 1 · gio Dimagrire 1 · sab/dom Dimagrire 2",
    "Sett. 2: mar Dimagrire 1 · gio Dimagrire 1 · sab/dom Dimagrire 2",
    "Sett. 3: mar Dimagrire 3 · gio Dimagrire 4 · sab/dom Dimagrire 2",
    "Sett. 4: mar Dimagrire 3 · gio Dimagrire 4 · sab/dom Dimagrire 5",
    "Sett. 5 (più leggera): mar Dimagrire 1 · gio Dimagrire 3 · sab/dom Dimagrire 2",
    "Sett. 6: mar Dimagrire 6 · gio Dimagrire 7 · sab/dom Dimagrire 5",
    "Sett. 7: mar Dimagrire 6 · gio Dimagrire 7 · sab/dom Dimagrire 8",
    "Sett. 8: mar Dimagrire 3 · gio Dimagrire 7 · sab/dom Dimagrire 8",
]

PIANI = [{
    "id": "dimagrire-8",
    "name": "Piano dimagrimento 8 settimane",
    "note": "3 uscite a settimana in giorni non consecutivi, quasi tutte in zona 2 (la più adatta a bruciare grassi "
            "e a migliorare la resistenza), con tratti in zona 3 dalla terza settimana. Un quarto giorno facoltativo: "
            "Recupero attivo 30'. Se sei stanco o il battito a riposo è più alto del solito, fai l'uscita più corta o riposa.",
    "calendar": CALENDARIO,
    "workouts": [{"id": i, "name": n, "note": note, "steps": s} for i, n, note, s in DIM],
}]

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
for p in PIANI:
    for w in p["workouts"]:
        tot = sum(s["sec"] for s in w["steps"]) // 60
        print(f'{w["name"]:38s} {tot:4d} min  {len(w["steps"])} fasi')
txt = json.dumps(PIANI, ensure_ascii=False, indent=1)
with open(os.path.join(root, 'training/src/main/assets/piani.json'), 'w', encoding='utf-8') as f:
    f.write(txt + '\n')
with open(os.path.join(root, 'desktop/app/plans.js'), 'w', encoding='utf-8') as f:
    f.write('/* Piani di allenamento pronti (generato da tools/piani/genera.py: non modificare a mano) */\n'
            "'use strict';\nconst TR_PLANS = " + txt + ';\n')
