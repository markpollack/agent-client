#!/usr/bin/env python3
"""Minimal retained transport fixture. Never calls a model."""
import json, os, signal, sys, time
from pathlib import Path
root = Path.cwd()

def permission(payload):
    """Strict required PreToolUse subset; not a claim about a native CLI's policy."""
    assert isinstance(payload, dict), 'hook body must be an object'
    assert set(payload) <= {'hookSpecificOutput', 'continue', 'decision', 'reason'}, 'flattened/unknown fields'
    if 'continue' in payload:
        assert type(payload['continue']) is bool, 'continue must be boolean'
    if payload.get('continue') is False and payload.get('decision') == 'block':
        assert isinstance(payload.get('reason'), str), 'block reason required'
        return 'deny'
    # A continue-only workaround is not an explicit permission decision.
    assert 'continue' not in payload, 'absent optional control was manufactured'
    specific = payload['hookSpecificOutput']
    assert isinstance(specific, dict), 'hookSpecificOutput must be an object'
    assert set(specific) <= {'hookEventName', 'permissionDecision', 'permissionDecisionReason'}
    assert specific['hookEventName'] == 'PreToolUse', 'wrong hook event'
    assert specific['permissionDecision'] in ('allow', 'deny'), 'explicit permission required'
    if 'permissionDecisionReason' in specific:
        assert isinstance(specific['permissionDecisionReason'], str)
    return specific['permissionDecision']

if '--policy-self-test' in sys.argv:
    invalid = [None, {'continue': None}, {'continue': True},
               {'permission_decision': 'deny'}, {'permissionDecision': 'deny'},
               {'hookSpecificOutput': None},
               {'hookSpecificOutput': {'hookEventName': 'PostToolUse', 'permissionDecision': 'deny'}},
               {'hookSpecificOutput': {'hookEventName': 'PreToolUse', 'permissionDecision': None}}]
    for payload in invalid:
        try:
            permission(payload)
        except (AssertionError, KeyError, TypeError):
            continue
        raise AssertionError('Malformed policy accepted: ' + str(payload))
    print('POLICY_NEGATIVES_PASS=' + str(len(invalid)))
    sys.exit(0)

(root / ('pid-' + str(os.getpid()))).write_text(str(os.getpid()))
args = sys.argv[1:]
sid = args[args.index('--resume') + 1] if '--resume' in args else 'fixture-history'
with (root / 'launches.jsonl').open('a') as f:
    f.write(json.dumps({'pid': os.getpid(), 'args': args, 'token': os.getenv('TOKEN_FILE')}) + '\n')
callback = None
hook_mode = None
native_sequence = 0
native_parent = None
native_init = False

def send(value):
    print(json.dumps(value), flush=True)

def terminal(value, error=False):
    send({'type': 'result', 'subtype': 'error_during_execution' if error else 'success',
          'is_error': error, 'duration_ms': 1, 'duration_api_ms': 1, 'num_turns': 1,
          'result': value, 'session_id': sid, 'usage': {'input_tokens': 1, 'output_tokens': 1}})

for line in sys.stdin:
    with (root / 'wire.jsonl').open('a') as f: f.write(line)
    request = json.loads(line)
    if request.get('type') == 'control_request':
        callback = request['request']['hooks']['PreToolUse'][0]['hookCallbackIds'][0]
        send({'type': 'control_response', 'response': {'subtype': 'success',
              'request_id': request['request_id'], 'response': {}}})
        continue
    if request.get('type') == 'control_response' and hook_mode:
        response = request['response']
        try:
            assert response['request_id'] == 'required-tool'
            assert response['subtype'] == 'success'
            applied = permission(response['response'])
            if hook_mode in ('hook-reject', 'hook-eof', 'hook-reject-success'):
                raise AssertionError('deliberate peer hook rejection')
        except (AssertionError, KeyError, TypeError) as failure:
            (root / 'hook-observation.json').write_text(json.dumps({'valid': False, 'error': str(failure)}))
            print('hook response rejected: ' + str(failure), file=sys.stderr, flush=True)
            if hook_mode == 'hook-eof': sys.exit(0)
            if hook_mode == 'hook-reject-success':
                # Simulate the native failure mode: diagnostic alone, then tool work and success.
                (root / 'operation').write_text('executed despite hook rejection')
                terminal('PEER_REPORTED_SUCCESS_AFTER_REJECTION')
            else:
                terminal('HOOK_REJECTED', True)
            continue
        if applied == 'allow': (root / 'operation').write_text('allowed fixture operation')
        (root / 'hook-observation.json').write_text(json.dumps({'valid': True, 'applied': applied}))
        if hook_mode == 'hook-cancel':
            (root / 'waiting').touch()
            while True: time.sleep(.05)
        terminal(applied.upper())
        continue
    if request.get('type') != 'user':
        continue
    prompt = request['message']['content']
    with (root / 'prompts.jsonl').open('a') as f: f.write(json.dumps(prompt) + '\n')
    contract_fixture = (root / 'native-projects' / 'project').is_dir()
    if not contract_fixture or not native_init:
        init = {'type': 'system', 'subtype': 'init', 'session_id': sid, 'cwd': str(root), 'tools': []}
        if contract_fixture: init['claude_code_version'] = '2.1.316'
        print(json.dumps(init), flush=True)
        native_init = True
    if prompt.startswith('hook-'):
        hook_mode = prompt
        assert callback is not None, 'required hook was not registered'
        send({'type': 'control_request', 'request_id': 'required-tool',
              'request': {'subtype': 'hook_callback', 'callback_id': callback,
                          'tool_use_id': 'fixture-tool', 'input': {'hook_event_name': 'PreToolUse',
                          'session_id': sid, 'transcript_path': str(root / 'transcript.jsonl'),
                          'cwd': str(root), 'tool_name': 'Write', 'tool_use_id': 'fixture-tool',
                          'tool_input': {'file_path': str(root / 'operation'), 'content': 'fixture'}}}})
        continue
    if prompt.startswith('cwd-marker:'):
        (root / prompt.split(':', 1)[1]).write_text(str(root))
    if prompt == 'wait':
        (root / 'waiting').touch()
        while True: time.sleep(.05)
    if prompt == 'eof':
        sys.exit(0)
    if prompt == 'wrong-history': sid = 'different-history'
    projects = root / 'native-projects' / 'project'
    if projects.is_dir():
        native_sequence += 1
        user_id = 'u' + str(native_sequence)
        assistant_id = 'a' + str(native_sequence)
        with (projects / (sid + '.jsonl')).open('a') as native:
            native.write(json.dumps({'type': 'user', 'sessionId': sid, 'uuid': user_id,
                'parentUuid': native_parent, 'version': '2.1.316',
                'message': {'role': 'user', 'content': prompt}}) + '\n')
            native.write(json.dumps({'type': 'assistant', 'sessionId': sid, 'uuid': assistant_id,
                'parentUuid': user_id, 'version': '2.1.316', 'message': {'role': 'assistant',
                'id': 'm' + str(native_sequence), 'content': [{'type': 'text', 'text': prompt}]}}) + '\n')
        native_parent = assistant_id
    print(json.dumps({'type':'result','subtype':'success','is_error':False,'duration_ms':1,
        'duration_api_ms':1,'num_turns':1,'result':prompt,'session_id':sid,
        'usage':{'input_tokens':1,'output_tokens':1}}), flush=True)
