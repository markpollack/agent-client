#!/usr/bin/env python3
import os, signal, time
from pathlib import Path
root = Path(__file__).resolve().parent
pid = os.fork()
if pid == 0:
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    for fd in (0, 1, 2):
        try: os.close(fd)
        except OSError: pass
    (root / 'descendant.pid').write_text(str(os.getpid()))
    time.sleep(30)
    os._exit(0)
(root / 'root.pid').write_text(str(os.getpid()))
while True:
    time.sleep(1)
