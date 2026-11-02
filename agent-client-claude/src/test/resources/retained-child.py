#!/usr/bin/env python3
"""Minimal retained transport fixture. Never calls a model."""
import json, os, signal, sys, time
from pathlib import Path
root = Path.cwd()
(root / ('pid-' + str(os.getpid()))).write_text(str(os.getpid()))
args = sys.argv[1:]
sid = args[args.index('--resume') + 1] if '--resume' in args else 'fixture-history'
with (root / 'launches.jsonl').open('a') as f:
    f.write(json.dumps({'pid': os.getpid(), 'args': args, 'token': os.getenv('TOKEN_FILE')}) + '\n')
for line in sys.stdin:
    request = json.loads(line)
    if request.get('type') != 'user':
        continue
    prompt = request['message']['content']
    with (root / 'prompts.jsonl').open('a') as f: f.write(json.dumps(prompt) + '\n')
    print(json.dumps({'type': 'system', 'subtype': 'init', 'session_id': sid, 'cwd': str(root), 'tools': []}), flush=True)
    if prompt == 'wait':
        (root / 'waiting').touch()
        while True: time.sleep(.05)
    if prompt == 'eof':
        sys.exit(0)
    if prompt == 'wrong-history': sid = 'different-history'
    print(json.dumps({'type':'result','subtype':'success','is_error':False,'duration_ms':1,
        'duration_api_ms':1,'num_turns':1,'result':prompt,'session_id':sid,
        'usage':{'input_tokens':1,'output_tokens':1}}), flush=True)
