#!/usr/bin/python3
# Deterministic Antigravity stream fixture. Never invokes a model.
import json,os,pathlib,sys,time,uuid
cwd=pathlib.Path.cwd();(cwd/'pid').write_text(str(os.getpid()))
with (cwd/'launches.jsonl').open('a') as f:f.write(json.dumps(sys.argv)+'\n')
prompt=sys.argv[sys.argv.index('--print')+1]
(cwd/'prompt.bin').write_bytes(prompt.encode());(cwd/'stdin.bin').write_bytes(sys.stdin.buffer.read())
(cwd/'environment').write_text(os.environ.get('FIXTURE_MARKER',''))
mode=prompt.split(':')[0]
sys.stderr.buffer.write(b'original-stderr-\xff-secret=fixture-token\n');sys.stderr.buffer.flush()
if mode=='wait':
 (cwd/'waiting').touch();time.sleep(60);sys.exit(0)
n=str(uuid.uuid4())
def emit(x):print(json.dumps(x),flush=True)
emit({'event':'init','conversation_id':n})
if mode=='overflow': print(' '* (4*1024*1024+10),flush=True)
if mode=='stderr-overflow':sys.stderr.write(' '* (4*1024*1024+10));sys.stderr.flush()
if mode in ('recover','failed'):
 emit({'event':'step_update','step_update':{'step_type':'tool','state':'ERROR'}})
if mode=='recover':emit({'event':'step_update','step_update':{'step_type':'tool','state':'DONE'}})
if mode=='denied':sys.stderr.write('permission denied\n');sys.stderr.flush()
if mode=='eof':sys.exit(0)
if mode=='malformed':print('{bad}',flush=True);sys.exit(0)
result={'event':'result','result':{'conversation_id':'' if mode=='missing' else ('wrong' if mode=='wrong' else n),'status':'ERROR' if mode in ('recover','failed') else 'SUCCESS','response':'answer:'+prompt,'num_turns':1,'usage':{'input_tokens':11,'output_tokens':4,'thinking_tokens':1,'cache_read_tokens':3,'total_tokens':15}}}
if mode=='incomplete':sys.stdout.write(json.dumps(result));sys.stdout.flush()
else:emit(result)
if mode=='duplicate':emit(result)
