#!/usr/bin/python3
# Offline ACP agent fixture. It never calls a model.
# argv: <record-file> <http-advertised: true|false> [converse|hang]
# Answers initialize, records each session/new params line to <record-file>, and ends each prompt.
# With 'converse', a prompt first streams session updates (one of a kind no SDK models), asks
# permission, records the client's answer to <record-file>, and only then ends the prompt.
# With 'hang', a prompt never ends; a '$/cancel_request' is recorded to <record-file>.
import json, sys
record, http = sys.argv[1], sys.argv[2] == 'true'
converse = len(sys.argv) > 3 and sys.argv[3] == 'converse'
hang = len(sys.argv) > 3 and sys.argv[3] == 'hang'
pending_prompt = None
def update(session, body):
    send({'jsonrpc': '2.0', 'method': 'session/update', 'params': {'sessionId': session, 'update': body}})
def send(message):
    sys.stdout.write(json.dumps(message) + '\n'); sys.stdout.flush()
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    message = json.loads(line)
    method, ident = message.get('method'), message.get('id')
    if method == '$/cancel_request':
        with open(record, 'a') as f:
            f.write(json.dumps({'cancelled': message.get('params')}) + '\n')
    if ident is None:
        continue
    if method is None and ident == 'perm-1':
        with open(record, 'a') as f:
            f.write(json.dumps({'permissionAnswer': message.get('result')}) + '\n')
        send({'jsonrpc': '2.0', 'id': pending_prompt, 'result': {'stopReason': 'end_turn'}})
        continue
    if method == 'initialize':
        send({'jsonrpc': '2.0', 'id': ident, 'result': {'protocolVersion': 1, 'authMethods': [],
              'agentCapabilities': {'loadSession': False, 'mcpCapabilities': {'http': http, 'sse': False}}}})
    elif method == 'session/new':
        with open(record, 'a') as f:
            f.write(json.dumps(message.get('params')) + '\n')
        send({'jsonrpc': '2.0', 'id': ident, 'result': {'sessionId': 'fake-session'}})
    elif method == 'session/prompt' and converse:
        session = message['params']['sessionId']
        update(session, {'sessionUpdate': 'agent_thought_chunk', 'content': {'type': 'text', 'text': 'thinking'}})
        update(session, {'sessionUpdate': 'tool_call', 'toolCallId': 'call-1', 'title': 'Read pom.xml',
                         'kind': 'read', 'status': 'pending', 'rawInput': {'path': 'pom.xml'},
                         '_meta': {'tool': 'read_file'}})
        update(session, {'sessionUpdate': 'future_kind_no_sdk_models', 'detail': 1})
        update(session, {'sessionUpdate': 'agent_message_chunk', 'content': {'type': 'text', 'text': 'Hello '}})
        update(session, {'sessionUpdate': 'agent_message_chunk', 'content': {'type': 'text', 'text': 'world'}})
        pending_prompt = ident
        send({'jsonrpc': '2.0', 'id': 'perm-1', 'method': 'session/request_permission', 'params': {
            'sessionId': session, 'toolCall': {'toolCallId': 'call-1'},
            'options': [{'optionId': 'no', 'name': 'Reject', 'kind': 'reject_once'},
                        {'optionId': 'yes', 'name': 'Allow', 'kind': 'allow_once'}]}})
    elif method == 'session/prompt' and hang:
        pass
    elif method == 'session/prompt':
        send({'jsonrpc': '2.0', 'id': ident, 'result': {'stopReason': 'end_turn'}})
    else:
        send({'jsonrpc': '2.0', 'id': ident, 'error': {'code': -32601, 'message': 'unsupported ' + str(method)}})
