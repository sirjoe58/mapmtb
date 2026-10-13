"""Verifica i file FIT generati dai test con la libreria indipendente fitparse."""
import glob
import sys

import fitparse

files = glob.glob("app/build/test-fit/*.fit")
if not files:
    sys.exit("Nessun file FIT da verificare")
for path in files:
    fit = fitparse.FitFile(path, check_crc=True)
    fit.parse()
    records = list(fit.get_messages("record"))
    course = list(fit.get_messages("course"))
    assert len(records) == 60, (path, len(records))
    assert course and course[0].get_value("name"), path
    print(f"OK {path}: {len(records)} punti, nome '{course[0].get_value('name')}'")
