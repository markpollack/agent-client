#!/usr/bin/python3
# Synthetic Codex exec protocol fixture. It never calls a model.
# Fresh: `... exec ... -- -`. Resume: `... exec resume ... <thread_id> -- -` appends one turn
# to the same rollout file, as the real CLI 0.166.0 does.
import json, os, pathlib, sys, time, uuid
cwd = pathlib.Path.cwd()
(cwd/'pid').write_text(str(os.getpid()))
with (cwd/'launches.jsonl').open('a') as f: f.write(json.dumps(sys.argv)+'\n')
prompt = sys.stdin.buffer.read()
(cwd/'prompt.bin').write_bytes(prompt)
mode = prompt.decode('utf-8').split(':')[0]
sys.stderr.buffer.write(b'original-stderr-\xff-secret=fixture-token\n')
sys.stderr.buffer.flush()
if mode == 'wait':
    (cwd/'waiting').touch()
    time.sleep(60)
    sys.exit(0)
resume = 'resume' in sys.argv
n = sys.argv[sys.argv.index('--') - 1] if resume else str(uuid.uuid4())
def emit(obj): print(json.dumps(obj), flush=True)
if mode == 'wrong' and resume: emit({'type':'thread.started','thread_id':'wrong-'+n})
elif not (resume and mode == 'noid'): emit({'type':'thread.started','thread_id':n})
if mode == 'duplicate': emit({'type':'thread.started','thread_id':n})
emit({'type':'turn.started'})
emit({'type':'item.completed','item':{'id':'item_0','type':'agent_message','text': 'answer:'+prompt.decode('utf-8')}})
root = pathlib.Path(os.environ['FIXTURE_SESSIONS']); root.mkdir(parents=True,exist_ok=True)
source = root/('rollout-fixture-'+n+'.jsonl')
template = pathlib.Path(__file__).with_name('codex-native-template.jsonl').read_text()
t = str(uuid.uuid4())
template = template.replace('11111111-1111-4111-8111-111111111111', 'wrong' if (mode=='wrong' and not resume) else n).replace('22222222-2222-4222-8222-222222222222',t).replace('/synthetic/codex-workspace',str(cwd))
records = [json.loads(line) for line in template.splitlines()]
existing = source.read_text() if (resume and source.exists()) else ''
turn = existing.count('"task_started"') + 1
usage = {'input_tokens':11,'cached_input_tokens':3,'cache_write_input_tokens':0,'output_tokens':4,'reasoning_output_tokens':0}
cumulative = dict((k, v*turn) for k, v in usage.items())
for record in records:
    payload = record['payload']
    if record['type'] == 'response_item' and payload.get('role') == 'user': payload['content'][0]['text'] = prompt.decode('utf-8')
    if record['type'] == 'response_item' and payload.get('role') == 'assistant': payload['content'][0]['text'] = 'answer:'+prompt.decode('utf-8')
    if payload.get('type') == 'task_complete': payload['last_agent_message'] = 'answer:'+prompt.decode('utf-8')
    if payload.get('type') == 'token_count':
        payload['info']['total_token_usage'] = dict(cumulative,total_tokens=15*turn)
        payload['info']['last_token_usage'] = dict(usage,total_tokens=15)
    if record['type'] == 'token_usage_record':
        payload['usage'] = dict(usage,total_tokens=15); payload['turn_token_usage'] = dict(usage,total_tokens=15)
        payload['thread_token_usage'] = dict(cumulative,total_tokens=15*turn)
if resume:
    records = [r for r in records if r['type'] != 'session_meta']
    records.insert(0, {'ordinal': 0, 'payload': {'type': 'thread_settings_applied'}, 'timestamp': '2026-11-03T00:00:00Z', 'type': 'event_msg'})
native = existing + ''.join(json.dumps(record)+'\n' for record in records)
if mode != 'missing': source.write_text(native.rstrip('\n') if mode=='incomplete' else native)
if mode == 'eof': sys.exit(0)
if mode == 'malformed': print('{bad}',flush=True)
else: emit({'type':'turn.failed' if mode=='failed' else 'turn.completed','usage':usage})
