#!/usr/bin/python3
# Offline Junie ACP fixture. It never calls a model.
# argv: <sessions-dir> <mode> --acp true --project <dir> [more Junie options]
# It answers initialize as Junie 3813.7 does, names its session directory with the session/new id,
# and on session/prompt writes that directory's summary.json and events.jsonl, then ends the turn.
# Modes change one fact: no-version, no-summary, no-events, other-id, other-project, truncated,
# two-tasks, extra-session, preexisting, env (records its launch environment), replay (writes the sanitized
# live 3813.7 session beside this script, junie-acp-3813.7-live/, as its own), error, hang (ends only on session/cancel, stop reason
# cancelled) and zero (zero cost, no token counts).
import json, os, sys, time
sessions, mode = sys.argv[1], sys.argv[2]
project = sys.argv[sys.argv.index('--project') + 1]
session_id = 'session-preexisting' if mode == 'preexisting' else 'session-261116-120000-' + format(os.getpid() % 0xffff, '04x')
hanging = None

def send(message):
    sys.stdout.write(json.dumps(message) + '\n'); sys.stdout.flush()

def answer(message):
    blocks = message.get('params', {}).get('prompt', [])
    return 'answer:' + ''.join(b.get('text', '') for b in blocks)

LIVE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'junie-acp-3813.7-live')

def replay_session(directory):
    # A real sanitized 3813.7 session: only its session id and project directory change.
    for name in ('summary.json', 'events.jsonl'):
        with open(os.path.join(LIVE, name)) as f:
            text = f.read()
        text = text.replace('session-261116-181916-vwh1', session_id)
        text = text.replace('"/work"', json.dumps(project)).replace('"/work/', json.dumps(project + '/')[:-1])
        with open(os.path.join(directory, name), 'w') as f:
            f.write(text)

def write_session():
    directory = os.path.join(sessions, session_id)
    os.makedirs(directory, exist_ok=True)
    if mode == 'replay':
        replay_session(directory)
        return
    now = int(time.time() * 1000)
    task = 'task-261116-120000-abcd'
    events = [{'kind': 'TaskStartedEvent', 'taskId': task, 'timestampMs': now}]
    if mode == 'two-tasks':
        events.append({'kind': 'TaskStartedEvent', 'taskId': 'task-2', 'timestampMs': now})
    if mode == 'env':
        # As Junie 3813.7 does: the environment the process was launched with, whole.
        events.append({'kind': 'SessionA2uxEvent', 'timestampMs': now, 'event': {'state': 'IN_PROGRESS', 'agentEvent': {
            'kind': 'EnvironmentVariablesUpdatedEvent', 'agent': {'kind': 'MainAgent', 'id': 'main'},
            'env': [{'key': k, 'value': v} for k, v in sorted(os.environ.items())]}}})
    events.append({'kind': 'SessionA2uxEvent', 'timestampMs': now + 1, 'event': {'state': 'IN_PROGRESS', 'agentEvent': {
        'kind': 'ResultBlockUpdatedEvent', 'agent': {'kind': 'MainAgent', 'id': 'main'}, 'cancelled': False,
        'result': 'answer'}}})
    if mode != 'no-events':
        text = ''.join(json.dumps(e) + '\n' for e in events)
        if mode == 'truncated':
            text = text[:-1]
        with open(os.path.join(directory, 'events.jsonl'), 'w') as f:
            f.write(text)
    usage = {'model': 'qwen3:4b', 'cost': 0} if mode == 'zero' else {
        'model': 'fixture-model', 'cost': 0.001, 'inputTokens': 7, 'cacheInputTokens': 0, 'cacheCreateTokens': 0,
        'outputTokens': 3}
    summary = {'sessionId': 'session-other' if mode == 'other-id' else session_id,
               'createdAt': now, 'updatedAt': now + 2,
               'projectDir': '/somewhere/else' if mode == 'other-project' else project,
               'taskName': 'fixture', 'lifecycle': 'COMPLETED', 'mainAgent': {'modelUsage': [usage]}}
    if mode != 'no-summary':
        with open(os.path.join(directory, 'summary.json'), 'w') as f:
            json.dump(summary, f)
    if mode == 'extra-session':
        os.makedirs(os.path.join(sessions, 'session-unrelated'), exist_ok=True)

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    message = json.loads(line)
    method, ident = message.get('method'), message.get('id')
    if method == 'session/cancel' and hanging is not None:
        write_session()
        send({'jsonrpc': '2.0', 'id': hanging, 'result': {'stopReason': 'cancelled'}})
        hanging = None
    if ident is None:
        continue
    if method == 'initialize':
        result = {'protocolVersion': 1, 'authMethods': [],
                  'agentCapabilities': {'loadSession': True, 'mcpCapabilities': {'http': True, 'sse': True}}}
        if mode != 'no-version':
            result['agentInfo'] = {'name': '@jetbrains/junie', 'version': '26.10.20 (3813.7)', 'title': 'Junie'}
        send({'jsonrpc': '2.0', 'id': ident, 'result': result})
    elif method == 'session/new':
        send({'jsonrpc': '2.0', 'id': ident, 'result': {'sessionId': session_id}})
    elif method == 'session/prompt' and mode == 'hang':
        hanging = ident
        open(os.path.join(os.path.dirname(sessions), 'prompted'), 'w').close()
    elif method == 'session/prompt' and mode == 'error':
        send({'jsonrpc': '2.0', 'id': ident, 'error': {'code': -32603, 'message': 'fixture failure'}})
    elif method == 'session/prompt':
        write_session()
        send({'jsonrpc': '2.0', 'method': 'session/update', 'params': {'sessionId': session_id, 'update': {
            'sessionUpdate': 'agent_message_chunk', 'content': {'type': 'text', 'text': answer(message)}}}})
        send({'jsonrpc': '2.0', 'id': ident, 'result': {'stopReason': 'end_turn'}})
    else:
        send({'jsonrpc': '2.0', 'id': ident, 'error': {'code': -32601, 'message': 'unsupported ' + str(method)}})
