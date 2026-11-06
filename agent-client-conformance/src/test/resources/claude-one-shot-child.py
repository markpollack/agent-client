#!/usr/bin/env python3
"""One-shot Claude stream-json fixture for the recording conformance suite. Never calls a model.

It reports the same usage as the Codex fixture (input 11, cache read 3, output 4) and no cost,
and writes its native transcript under FIXTURE_CLAUDE_PROJECTS, as the CLI does under
~/.claude/projects."""
import json, os, sys, uuid
from pathlib import Path
root = Path.cwd()
projects = Path(os.environ['FIXTURE_CLAUDE_PROJECTS'])
sid = str(uuid.uuid4())
with (root / 'launches.jsonl').open('a') as f:
    f.write(json.dumps(sys.argv[1:]) + '\n')
usage = {'input_tokens': 11, 'cache_creation_input_tokens': 0, 'cache_read_input_tokens': 3, 'output_tokens': 4}

def send(value):
    print(json.dumps(value), flush=True)

for line in sys.stdin:
    request = json.loads(line)
    if request.get('type') == 'control_request':
        send({'type': 'control_response', 'response': {'subtype': 'success',
              'request_id': request['request_id'], 'response': {}}})
        continue
    if request.get('type') != 'user':
        continue
    prompt = request['message']['content']
    send({'type': 'system', 'subtype': 'init', 'session_id': sid, 'cwd': str(root), 'tools': [],
          'claude_code_version': '2.1.316'})
    project = projects / ''.join(c if c.isalnum() else '-' for c in str(root))
    project.mkdir(parents=True, exist_ok=True)
    with (project / (sid + '.jsonl')).open('w') as native:
        native.write(json.dumps({'type': 'user', 'sessionId': sid, 'uuid': 'u1', 'parentUuid': None,
            'version': '2.1.316', 'cwd': str(root), 'message': {'role': 'user', 'content': prompt}}) + '\n')
        native.write(json.dumps({'type': 'assistant', 'sessionId': sid, 'uuid': 'a1', 'parentUuid': 'u1',
            'version': '2.1.316', 'cwd': str(root), 'message': {'role': 'assistant', 'id': 'm1',
            'type': 'message', 'model': 'synthetic-model', 'stop_reason': 'end_turn', 'usage': usage,
            'content': [{'type': 'text', 'text': 'answer:' + prompt}]}}) + '\n')
    send({'type': 'result', 'subtype': 'success', 'is_error': False, 'duration_ms': 1, 'duration_api_ms': 1,
          'num_turns': 1, 'result': 'answer:' + prompt, 'session_id': sid, 'usage': usage})
