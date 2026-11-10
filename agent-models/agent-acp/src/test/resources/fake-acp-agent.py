#!/usr/bin/python3
# Offline ACP agent fixture. It never calls a model.
# argv: <record-file> <http-advertised: true|false>
# Answers initialize, records each session/new params line to <record-file>, and ends each prompt.
import json, sys
record, http = sys.argv[1], sys.argv[2] == 'true'
def send(message):
    sys.stdout.write(json.dumps(message) + '\n'); sys.stdout.flush()
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    message = json.loads(line)
    method, ident = message.get('method'), message.get('id')
    if ident is None:
        continue
    if method == 'initialize':
        send({'jsonrpc': '2.0', 'id': ident, 'result': {'protocolVersion': 1, 'authMethods': [],
              'agentCapabilities': {'loadSession': False, 'mcpCapabilities': {'http': http, 'sse': False}}}})
    elif method == 'session/new':
        with open(record, 'a') as f:
            f.write(json.dumps(message.get('params')) + '\n')
        send({'jsonrpc': '2.0', 'id': ident, 'result': {'sessionId': 'fake-session'}})
    elif method == 'session/prompt':
        send({'jsonrpc': '2.0', 'id': ident, 'result': {'stopReason': 'end_turn'}})
    else:
        send({'jsonrpc': '2.0', 'id': ident, 'error': {'code': -32601, 'message': 'unsupported ' + str(method)}})
